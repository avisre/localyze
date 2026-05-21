using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Net.Http;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Threading.Tasks;
using System.Web;

namespace Localyze.Research;

public delegate Task<JsonObject> ToolFunc(JsonObject args);

public sealed record Tool(string Name, ToolFunc Call);

public sealed class Tools
{
    public List<Tool> Build(bool includeWeb)
    {
        var list = new List<Tool>
        {
            new("memory.search", MemorySearchAsync),
            new("files.search",  FilesSearchAsync),
            new("calc",          CalcAsync),
            new("run",           RunAsync),
            new("system.info",   SystemInfoAsync),
        };
        if (includeWeb) list.Add(new("web.search", WebSearchAsync));
        return list;
    }

    public Task<JsonObject> CalcAsync(JsonObject args)
    {
        var expr = (args["expr"]?.GetValue<string>() ?? "")
            .Replace("pi", System.Math.PI.ToString(System.Globalization.CultureInfo.InvariantCulture));
        // Use NCalc-style evaluation by leaning on DataTable.Compute — a built-in,
        // dep-free arithmetic evaluator that handles +-*/ () and basic functions.
        try
        {
            using var dt = new System.Data.DataTable();
            var result = dt.Compute(expr, "");
            var d = System.Convert.ToDouble(result, System.Globalization.CultureInfo.InvariantCulture);
            return Task.FromResult(new JsonObject { ["value"] = d });
        }
        catch (System.Exception ex)
        {
            return Task.FromResult(new JsonObject { ["error"] = $"could not evaluate: {ex.Message}" });
        }
    }

    public Task<JsonObject> SystemInfoAsync(JsonObject args)
    {
        var o = new JsonObject
        {
            ["os"]      = System.Runtime.InteropServices.RuntimeInformation.OSDescription,
            ["ram_gb"]  = (long)(System.GC.GetGCMemoryInfo().TotalAvailableMemoryBytes / 1_000_000_000),
            ["cores"]   = System.Environment.ProcessorCount,
            ["arch"]    = System.Runtime.InteropServices.RuntimeInformation.OSArchitecture.ToString(),
        };
        return Task.FromResult(o);
    }

    public Task<JsonObject> MemorySearchAsync(JsonObject args)
    {
        var query = args["query"]?.GetValue<string>() ?? "";
        var limit = args["limit"]?.GetValue<int>() ?? 5;
        var root  = Path.Combine(Inference.ModelPath.AppDataRoot, "memory");
        Directory.CreateDirectory(root);
        var hits = new JsonArray();
        foreach (var file in Directory.EnumerateFiles(root))
        {
            if (hits.Count >= limit) break;
            var body = File.ReadAllText(file);
            var idx = body.IndexOf(query, System.StringComparison.OrdinalIgnoreCase);
            if (idx < 0) continue;
            var start = System.Math.Max(0, idx - 80);
            var len   = System.Math.Min(body.Length - start, 200);
            hits.Add(new JsonObject
            {
                ["id"]      = Path.GetFileName(file),
                ["snippet"] = body.Substring(start, len),
            });
        }
        return Task.FromResult(new JsonObject { ["results"] = hits });
    }

    public Task<JsonObject> FilesSearchAsync(JsonObject args)
    {
        var query = args["query"]?.GetValue<string>() ?? "";
        var limit = args["limit"]?.GetValue<int>() ?? 5;
        var root  = Path.Combine(System.Environment.GetFolderPath(System.Environment.SpecialFolder.UserProfile),
                                 "Localyze", "files");
        if (!Directory.Exists(root)) return Task.FromResult(new JsonObject { ["results"] = new JsonArray() });
        var hits = new JsonArray();
        foreach (var file in Directory.EnumerateFiles(root))
        {
            if (hits.Count >= limit) break;
            try
            {
                using var fs = File.OpenRead(file);
                var buf = new byte[64 * 1024];
                var n = fs.Read(buf, 0, buf.Length);
                var body = System.Text.Encoding.UTF8.GetString(buf, 0, n);
                var idx = body.IndexOf(query, System.StringComparison.OrdinalIgnoreCase);
                if (idx < 0) continue;
                var start = System.Math.Max(0, idx - 80);
                var len   = System.Math.Min(body.Length - start, 200);
                hits.Add(new JsonObject
                {
                    ["path"]    = file,
                    ["snippet"] = body.Substring(start, len),
                });
            }
            catch { /* skip unreadable */ }
        }
        return Task.FromResult(new JsonObject { ["results"] = hits });
    }

    // Hard caps for the sandboxed code-exec tool.
    private const int RunTimeoutMs   = 10_000;       // 10 s wall clock
    private const int RunOutputCapB  = 64 * 1024;    // 64 KB combined stdout+stderr

    public async Task<JsonObject> RunAsync(JsonObject args)
    {
        // 1. Off-by-default. The agent cannot exec code unless the user has
        //    explicitly opted in via Settings → Code execution.
        if (!SettingsStore.Instance.CodeExecEnabled)
            return new JsonObject { ["error"] = "code execution is disabled in settings" };

        var lang = (args["lang"]?.GetValue<string>() ?? "").Trim().ToLowerInvariant();
        var code = args["code"]?.GetValue<string>() ?? "";

        // 2. Language allowlist. No `shell`, no `bash`, no `powershell`.
        string exe;
        string ext;
        switch (lang)
        {
            case "python":
            case "python3":
                exe = "python3"; ext = ".py"; break;
            case "javascript":
            case "node":
                exe = "node";    ext = ".js"; break;
            default:
                return new JsonObject { ["error"] = $"unsupported lang: {lang} (allowed: python, python3, javascript, node)" };
        }

        // 3. Empty tempdir as cwd; code written to a tempfile so it's never
        //    passed through a shell or as a -c argument.
        var workDir  = Path.Combine(Path.GetTempPath(), "localyze-run-" + System.Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(workDir);
        var codePath = Path.Combine(workDir, "snippet" + ext);
        await File.WriteAllTextAsync(codePath, code);

        var psi = new ProcessStartInfo
        {
            FileName               = exe,
            WorkingDirectory       = workDir,
            RedirectStandardOutput = true,
            RedirectStandardError  = true,
            UseShellExecute        = false,
            CreateNoWindow         = true,
        };
        psi.ArgumentList.Add(codePath);

        // 4. PATH lockdown. Drop the inherited environment and reset PATH to a
        //    minimal value. On Windows ProcessStartInfo doesn't auto-inherit
        //    once we touch Environment, but we clear explicitly to be safe.
        psi.Environment.Clear();
        psi.Environment["PATH"] = @"C:\Windows\System32;C:\Windows;/usr/bin";
        psi.Environment["SystemRoot"] = @"C:\Windows";

        Process? p = null;
        try
        {
            p = Process.Start(psi)!;

            // 5. Hard 10s timeout; kill the whole process tree.
            var stdoutTask = p.StandardOutput.ReadToEndAsync();
            var stderrTask = p.StandardError.ReadToEndAsync();
            if (!p.WaitForExit(RunTimeoutMs))
            {
                try { p.Kill(entireProcessTree: true); } catch { /* best-effort */ }
                return new JsonObject { ["error"] = "execution timeout (10s)" };
            }

            // 6. Output cap.
            var combined = (await stdoutTask) + (await stderrTask);
            var truncated = false;
            if (combined.Length > RunOutputCapB)
            {
                combined  = combined.Substring(0, RunOutputCapB);
                truncated = true;
            }

            var result = new JsonObject
            {
                ["stdout"]    = combined,
                ["exit_code"] = p.ExitCode,
            };
            if (truncated) result["truncated"] = true;
            return result;
        }
        catch (System.Exception ex)
        {
            return new JsonObject { ["error"] = ex.Message };
        }
        finally
        {
            try { p?.Dispose(); } catch { /* ignored */ }
            try { Directory.Delete(workDir, recursive: true); } catch { /* ignored */ }
        }
    }

    private static readonly HttpClient s_http = new();

    public async Task<JsonObject> WebSearchAsync(JsonObject args)
    {
        if (!SettingsStore.Instance.WebSearchEnabled)
            return new JsonObject { ["error"] = "web.search disabled" };
        var query = args["query"]?.GetValue<string>() ?? "";
        var n     = args["n"]?.GetValue<int>() ?? 5;
        var url   = $"{SettingsStore.Instance.SearxngUrl}?q={HttpUtility.UrlEncode(query)}&format=json";
        try
        {
            var json = await s_http.GetStringAsync(url);
            var root = JsonNode.Parse(json)!;
            var hits = new JsonArray();
            foreach (var item in root["results"]?.AsArray() ?? new JsonArray())
            {
                if (hits.Count >= n) break;
                hits.Add(new JsonObject
                {
                    ["title"]   = item?["title"]?.GetValue<string>() ?? "",
                    ["url"]     = item?["url"]?.GetValue<string>()   ?? "",
                    ["snippet"] = item?["content"]?.GetValue<string>() ?? "",
                });
            }
            return new JsonObject { ["results"] = hits };
        }
        catch (System.Exception ex) { return new JsonObject { ["error"] = ex.Message }; }
    }
}
