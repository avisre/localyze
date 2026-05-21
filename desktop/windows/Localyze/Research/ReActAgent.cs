using System.Collections.Generic;
using System.Runtime.CompilerServices;
using System.Text;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;
using System.Threading;
using Localyze.Inference;

namespace Localyze.Research;

public sealed record ResearchStep(string Kind, string Text);

/// <summary>
/// Deep research mode loop. Reason → Act → Observe → repeat.
/// Web search is a single binary flag exposed via SettingsStore.
/// Tool-call wire format is documented in desktop/shared/research-agent.md.
/// </summary>
public sealed class ReActAgent
{
    private readonly OnnxBackend _backend;
    private readonly Tools _tools = new();
    private const int MaxSteps = 12;

    private static readonly Regex FinalRx =
        new(@"<final>([\s\S]*?)</final>", RegexOptions.Compiled);
    private static readonly Regex ToolRx =
        new(@"<tool\s+name=""([^""]+)""\s*>([\s\S]*?)</tool>", RegexOptions.Compiled);

    public bool WebSearchEnabled
    {
        get => SettingsStore.Instance.WebSearchEnabled;
        set => SettingsStore.Instance.WebSearchEnabled = value;
    }

    public ReActAgent(OnnxBackend backend) => _backend = backend;

    public async IAsyncEnumerable<ResearchStep> RunAsync(
        string userPrompt,
        [EnumeratorCancellation] CancellationToken ct = default)
    {
        var webOn = WebSearchEnabled;
        var available = _tools.Build(webOn);
        var dispatch = new Dictionary<string, ToolFunc>();
        foreach (var t in available) dispatch[t.Name] = t.Call;

        var conv = new StringBuilder()
            .Append("<start_of_turn>system\n").Append(SystemPrompt(webOn)).Append("<end_of_turn>\n")
            .Append("<start_of_turn>user\n").Append(userPrompt).Append("<end_of_turn>\n")
            .Append("<start_of_turn>model\n");

        for (var step = 0; step < MaxSteps; step++)
        {
            yield return new ResearchStep("reason", $"step {step + 1}");
            var sb = new StringBuilder();
            await foreach (var tok in _backend.GenerateAsync(conv.ToString(), 512, ct))
            {
                sb.Append(tok);
            }
            var stepText = sb.ToString();

            if (FinalRx.Match(stepText) is { Success: true } fm)
            {
                yield return new ResearchStep("final", fm.Groups[1].Value.Trim());
                yield break;
            }

            var tm = ToolRx.Match(stepText);
            if (!tm.Success)
            {
                yield return new ResearchStep("final", stepText.Trim());
                yield break;
            }

            var toolName = tm.Groups[1].Value;
            var argsJson = tm.Groups[2].Value.Trim();
            var argsObj  = (JsonObject?)JsonNode.Parse(string.IsNullOrEmpty(argsJson) ? "{}" : argsJson)
                           ?? new JsonObject();
            yield return new ResearchStep("act", $"{toolName} {argsJson}");

            JsonObject result;
            if (dispatch.TryGetValue(toolName, out var fn))
            {
                try { result = await fn(argsObj); }
                catch (System.Exception ex) { result = new JsonObject { ["error"] = ex.Message }; }
            }
            else
            {
                result = new JsonObject { ["error"] = $"tool '{toolName}' is not available" };
            }
            var resultJson = result.ToJsonString();
            yield return new ResearchStep("observe", resultJson);

            conv.Append(stepText)
                .Append("\n<tool_result name=\"").Append(toolName).Append("\">\n")
                .Append(resultJson)
                .Append("\n</tool_result>\n");
        }

        yield return new ResearchStep("final", "[hit max steps]");
    }

    private static string SystemPrompt(bool webEnabled)
    {
        var sb = new StringBuilder()
            .Append("You are Localyze, a private on-device assistant.\n\n")
            .Append("Tools (call by emitting <tool name=\"NAME\">{json args}</tool>):\n")
            .Append("  memory.search(query, limit=5)\n")
            .Append("  files.search(query, limit=5)\n")
            .Append("  calc(expr)\n")
            .Append("  run(lang, code)\n")
            .Append("  system.info()\n");
        if (webEnabled) sb.Append("  web.search(query, n=5)\n");
        sb.Append("\nFinalize with: <final>...answer...</final>\n")
          .Append("Cite tool results. Prefer 1-3 tool calls. Be concise.\n");
        return sb.ToString();
    }
}
