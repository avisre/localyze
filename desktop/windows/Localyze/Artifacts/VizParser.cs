using System.Text.RegularExpressions;

namespace Localyze.Artifacts;

public enum VizKind { Chart, Table, Map, Run, Code, Form, Image, Pdf }

public sealed record VizBlock(VizKind Kind, IReadOnlyDictionary<string, string> Attrs, string? Inner);

/// <summary>
/// Streaming parser for &lt;viz type="..." .../&gt; blocks. The parser is intentionally
/// permissive: it tolerates partial JSON in attributes so charts/tables can render
/// incrementally as the model writes data. Spec: desktop/shared/viz-schema.md
/// </summary>
public static class VizParser
{
    private static readonly Regex BlockRx = new(
        @"<viz\s+(?<attrs>[^>]+?)(?:/>|>(?<inner>.*?)</viz>)",
        RegexOptions.Compiled | RegexOptions.Singleline);

    public static IEnumerable<VizBlock> Parse(string text)
    {
        foreach (Match m in BlockRx.Matches(text))
        {
            var attrs = ParseAttrs(m.Groups["attrs"].Value);
            if (!attrs.TryGetValue("type", out var type)) continue;
            if (!System.Enum.TryParse<VizKind>(type, true, out var kind)) continue;
            var inner = m.Groups["inner"].Success ? m.Groups["inner"].Value : null;
            yield return new VizBlock(kind, attrs, inner);
        }
    }

    private static Dictionary<string, string> ParseAttrs(string s)
    {
        var d = new Dictionary<string, string>(System.StringComparer.OrdinalIgnoreCase);
        foreach (Match m in Regex.Matches(s, "(?<k>\\w+)\\s*=\\s*(?:\"(?<v1>[^\"]*)\"|'(?<v2>[^']*)')"))
            d[m.Groups["k"].Value] = m.Groups["v1"].Success ? m.Groups["v1"].Value : m.Groups["v2"].Value;
        return d;
    }
}
