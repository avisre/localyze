import SwiftUI
import Charts

/// Renders one VizBlock with native SwiftUI/Charts widgets.
/// Spec: ../../shared/viz-schema.md
struct ArtifactView: View {
    let block: VizBlock

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            if let title = block.attrs["title"], !title.isEmpty {
                Text(title).font(.headline)
            }
            switch block.kind {
            case .chart: ChartArtifact(block: block)
            case .table: TableArtifact(block: block)
            case .code:  CodeArtifact(block: block)
            case .run:   RunArtifact(block: block)
            case .image: ImageArtifact(block: block)
            default:     Text("Unsupported viz type: \(block.kind.rawValue)").foregroundStyle(.secondary)
            }
            HStack {
                Button("Save") { saveToDisk() }
                Button("PDF")  { exportPdf() }
            }
        }
        .padding(8)
        .background(RoundedRectangle(cornerRadius: 8).strokeBorder(.secondary.opacity(0.3)))
    }

    private func saveToDisk() {
        guard let data = block.attrs["data"] ?? block.inner else { return }
        let fileURL = ModelPath.artifactsDir
            .appendingPathComponent("artifact-\(Int(Date().timeIntervalSince1970)).json")
        try? FileManager.default.createDirectory(at: ModelPath.artifactsDir, withIntermediateDirectories: true)
        try? data.write(to: fileURL, atomically: true, encoding: .utf8)
    }
    private func exportPdf() {
        // Real impl uses NSPrintOperation. Stub for now — see STATUS.md.
    }
}

// MARK: - chart
private struct ChartArtifact: View {
    let block: VizBlock
    var body: some View {
        let kind = block.attrs["kind"] ?? "line"
        let xKey = block.attrs["x"] ?? "x"
        let yKey = block.attrs["y"] ?? "y"
        let dataStr = block.attrs["data"] ?? "[]"
        let points = (try? JSONSerialization.jsonObject(with: Data(dataStr.utf8))) as? [[String: Any]] ?? []
        Chart {
            ForEach(Array(points.enumerated()), id: \.offset) { _, p in
                let xVal = (p[xKey] as? NSNumber)?.doubleValue ?? 0
                let yVal = (p[yKey] as? NSNumber)?.doubleValue ?? 0
                if kind == "bar" {
                    BarMark(x: .value(xKey, xVal), y: .value(yKey, yVal))
                } else {
                    LineMark(x: .value(xKey, xVal), y: .value(yKey, yVal))
                }
            }
        }
        .frame(height: 240)
    }
}

// MARK: - table
private struct TableArtifact: View {
    let block: VizBlock
    var body: some View {
        let dataStr = block.attrs["data"] ?? "[]"
        let rows = (try? JSONSerialization.jsonObject(with: Data(dataStr.utf8))) as? [[String: Any]] ?? []
        let keys = rows.first.map { Array($0.keys).sorted() } ?? []
        VStack(alignment: .leading, spacing: 2) {
            HStack(spacing: 8) {
                ForEach(keys, id: \.self) { k in
                    Text(k).bold().frame(width: 120, alignment: .leading)
                }
            }
            Divider()
            ForEach(Array(rows.enumerated()), id: \.offset) { _, row in
                HStack(spacing: 8) {
                    ForEach(keys, id: \.self) { k in
                        Text(String(describing: row[k] ?? "")).frame(width: 120, alignment: .leading)
                    }
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private struct CodeArtifact: View {
    let block: VizBlock
    var body: some View {
        let code = block.inner ?? ""
        ScrollView {
            Text(code)
                .font(.system(.body, design: .monospaced))
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(8)
        }
        .frame(minHeight: 120, maxHeight: 320)
        .background(RoundedRectangle(cornerRadius: 4).fill(.black.opacity(0.85)))
        .foregroundStyle(.white)
    }
}

private struct RunArtifact: View {
    let block: VizBlock
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("Code (\(block.attrs["lang"] ?? ""))").font(.caption).foregroundStyle(.secondary)
            CodeArtifact(block: block)
            Text("Output").font(.caption).foregroundStyle(.secondary)
            Text(block.attrs["stdout"] ?? "(run by agent — output rendered when ready)")
                .font(.system(.body, design: .monospaced))
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(8)
                .background(RoundedRectangle(cornerRadius: 4).fill(.gray.opacity(0.15)))
        }
    }
}

private struct ImageArtifact: View {
    let block: VizBlock
    var body: some View {
        if let src = block.attrs["src"], let url = URL(string: src) {
            AsyncImage(url: url) { image in image.resizable().scaledToFit() }
                placeholder: { ProgressView() }
                .frame(maxHeight: 320)
        }
    }
}
