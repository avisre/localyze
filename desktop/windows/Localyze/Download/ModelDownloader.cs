using System.Net.Http;
using System.Net.Http.Headers;
using System.Security.Cryptography;
using System.Text.Json;

namespace Localyze.Download;

public sealed record ManifestTier(
    string Id,
    Artifact? Runtime,
    Artifact Model);

public sealed record Artifact(string Url, string Sha256, long SizeBytes);

public sealed record DownloadProgress(string Artifact, long Done, long Total);

/// <summary>
/// Fetches the manifest, resolves the tier matching our selection, and downloads
/// only the runtime + model artifacts that aren't already on disk + verified.
/// Resume support: partial files (.part) are kept and the next attempt sends
/// a Range: bytes=N- header so big model downloads survive flaky networks.
/// </summary>
public sealed class ModelDownloader
{
    private const string ManifestUrl = "https://cdn.localyze.app/desktop/manifest.json";
    private readonly HttpClient _http = new();
    private readonly string _cacheRoot;

    public ModelDownloader(string cacheRoot) => _cacheRoot = cacheRoot;

    public async Task<ManifestTier> ResolveTierAsync(string tierId, CancellationToken ct)
    {
        var json = await _http.GetStringAsync(ManifestUrl, ct);
        using var doc = JsonDocument.Parse(json);
        foreach (var tier in doc.RootElement.GetProperty("tiers").EnumerateArray())
        {
            if (tier.GetProperty("id").GetString() != tierId) continue;
            return new ManifestTier(
                Id: tierId,
                Runtime: TryReadArtifact(tier, "runtime"),
                Model:   TryReadArtifact(tier, "model")!);
        }
        throw new System.InvalidOperationException($"manifest has no tier {tierId}");
    }

    public async IAsyncEnumerable<DownloadProgress> FetchAsync(
        ManifestTier tier,
        [System.Runtime.CompilerServices.EnumeratorCancellation] CancellationToken ct = default)
    {
        if (tier.Runtime is not null)
            await foreach (var p in FetchOneAsync(tier.Runtime, "runtime", ct)) yield return p;
        await foreach (var p in FetchOneAsync(tier.Model, "model", ct)) yield return p;
    }

    private async IAsyncEnumerable<DownloadProgress> FetchOneAsync(
        Artifact a, string label,
        [System.Runtime.CompilerServices.EnumeratorCancellation] CancellationToken ct = default)
    {
        Directory.CreateDirectory(_cacheRoot);
        var finalPath = Path.Combine(_cacheRoot, Path.GetFileName(new System.Uri(a.Url).LocalPath));

        // Already complete + verified.
        if (File.Exists(finalPath) && await VerifySha256(finalPath, a.Sha256, ct))
        {
            yield return new DownloadProgress(label, a.SizeBytes, a.SizeBytes);
            yield break;
        }

        var partPath = finalPath + ".part";
        long startAt = File.Exists(partPath) ? new FileInfo(partPath).Length : 0;

        using var req = new HttpRequestMessage(HttpMethod.Get, a.Url);
        if (startAt > 0) req.Headers.Range = new RangeHeaderValue(startAt, null);

        using var resp = await _http.SendAsync(req, HttpCompletionOption.ResponseHeadersRead, ct);
        resp.EnsureSuccessStatusCode();

        // If we asked for a range but the server returned 200 (no range support), the response body
        // is the full file from byte 0 — appending to our existing .part would corrupt the prefix
        // with a duplicated copy of the head of the file. Reset startAt and truncate the .part.
        // Only HTTP 206 (PartialContent) means "the body starts at byte startAt"; in that case we append.
        bool serverResumed = resp.StatusCode == System.Net.HttpStatusCode.PartialContent;
        if (startAt > 0 && !serverResumed)
        {
            startAt = 0;
            // Truncate any stale .part so the FileMode.OpenOrCreate write below starts clean.
            if (File.Exists(partPath)) File.Delete(partPath);
        }

        var total = (resp.Content.Headers.ContentLength ?? 0) + startAt;
        if (total <= 0) total = a.SizeBytes;

        using var src = await resp.Content.ReadAsStreamAsync(ct);
        // FileMode.Append would force-position at end of file even after we truncated above; use
        // OpenOrCreate and seek to startAt so the 200 (truncate) and 206 (append) paths share code.
        await using var dst = new FileStream(partPath, FileMode.OpenOrCreate, FileAccess.Write, FileShare.None);
        dst.Seek(startAt, SeekOrigin.Begin);
        if (startAt == 0) dst.SetLength(0);
        var buf = new byte[1 << 20];
        long done = startAt;
        int n;
        while ((n = await src.ReadAsync(buf, ct)) > 0)
        {
            await dst.WriteAsync(buf.AsMemory(0, n), ct);
            done += n;
            yield return new DownloadProgress(label, done, total);
        }
        await dst.FlushAsync(ct);
        dst.Close();

        if (!await VerifySha256(partPath, a.Sha256, ct))
        {
            File.Delete(partPath);
            throw new System.IO.InvalidDataException($"sha256 mismatch on {label}");
        }
        if (File.Exists(finalPath)) File.Delete(finalPath);
        File.Move(partPath, finalPath);
        yield return new DownloadProgress(label, total, total);
    }

    private static async Task<bool> VerifySha256(string path, string expected, CancellationToken ct)
    {
        await using var s = File.OpenRead(path);
        var hash = await SHA256.HashDataAsync(s, ct);
        return System.Convert.ToHexString(hash).Equals(expected, System.StringComparison.OrdinalIgnoreCase);
    }

    private static Artifact? TryReadArtifact(JsonElement tier, string key)
    {
        if (!tier.TryGetProperty(key, out var el) || el.ValueKind == JsonValueKind.Null) return null;
        return new Artifact(
            Url: el.GetProperty("url").GetString()!,
            Sha256: el.GetProperty("sha256").GetString()!,
            SizeBytes: el.TryGetProperty("size_mb", out var sm) ? (long)(sm.GetDouble() * 1_000_000) : 0);
    }
}
