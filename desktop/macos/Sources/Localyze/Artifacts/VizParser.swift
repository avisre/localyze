import Foundation

enum VizKind: String { case chart, table, map, run, code, form, image, pdf }

struct VizBlock {
    let kind: VizKind
    let attrs: [String: String]
    let inner: String?
}

/// Streaming-friendly parser for <viz ...> blocks.
/// Spec: ../../shared/viz-schema.md
enum VizParser {
    static func parse(_ text: String) -> [VizBlock] {
        var out: [VizBlock] = []
        let pattern = #"<viz\s+([^>]+?)(?:/>|>(.*?)</viz>)"#
        guard let rx = try? NSRegularExpression(pattern: pattern, options: [.dotMatchesLineSeparators]) else { return [] }
        let ns = text as NSString
        rx.enumerateMatches(in: text, range: NSRange(location: 0, length: ns.length)) { m, _, _ in
            guard let m = m else { return }
            let attrsStr = ns.substring(with: m.range(at: 1))
            let inner = m.numberOfRanges > 2 && m.range(at: 2).location != NSNotFound
                ? ns.substring(with: m.range(at: 2)) : nil
            let attrs = parseAttrs(attrsStr)
            guard let typeStr = attrs["type"], let kind = VizKind(rawValue: typeStr.lowercased()) else { return }
            out.append(VizBlock(kind: kind, attrs: attrs, inner: inner))
        }
        return out
    }

    private static func parseAttrs(_ s: String) -> [String: String] {
        var d: [String: String] = [:]
        let pattern = #"(\w+)\s*=\s*(?:"([^"]*)"|'([^']*)')"#
        guard let rx = try? NSRegularExpression(pattern: pattern) else { return d }
        let ns = s as NSString
        rx.enumerateMatches(in: s, range: NSRange(location: 0, length: ns.length)) { m, _, _ in
            guard let m = m else { return }
            let k = ns.substring(with: m.range(at: 1))
            let v = m.range(at: 2).location != NSNotFound
                ? ns.substring(with: m.range(at: 2))
                : ns.substring(with: m.range(at: 3))
            d[k] = v
        }
        return d
    }
}
