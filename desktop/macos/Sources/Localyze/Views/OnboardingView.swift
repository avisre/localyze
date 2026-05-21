import SwiftUI
import AppKit

/// First-run flow. Three linear steps:
///
///   1) Detect hardware     →  HardwareProbe.run() + BackendSelector.pick()
///   2) Download model      →  ModelDownloader.start(url:dest:sha256:)
///                              (or pick a local file via NSOpenPanel)
///   3) All set             →  SettingsStore.onboarded = true, dismiss
///
/// We deliberately keep this as a single view rather than a NavigationStack
/// because the steps are strictly linear and we want the back/forward state to
/// be obvious and recoverable on relaunch.
struct OnboardingView: View {
    /// Dismiss handler — the parent (`LocalyzeApp`) swaps in `ChatView`.
    var onComplete: () -> Void

    @State private var step: Step = .detecting
    @State private var report: HardwareReport?
    @State private var selection: Selection?
    @State private var selectionError: String?

    // Download state — bound to the SwiftUI ProgressView.
    @State private var downloadBytes: Int64 = 0
    @State private var downloadTotal: Int64 = 1
    @State private var downloadLabel: String = ""
    @State private var downloadError: String?
    @State private var downloadTask: Task<Void, Never>?

    enum Step { case detecting, download, downloading, done }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            header
            Divider()
            content
            Spacer()
            footer
        }
        .padding(24)
        .frame(minWidth: 560, minHeight: 420)
        .task { await stepDetect() }
    }

    // MARK: - Header

    private var header: some View {
        HStack(alignment: .firstTextBaseline) {
            Text("Welcome to Localyze.ai")
                .font(.largeTitle).bold()
            Spacer()
            Text("\(stepNumber)/3")
                .font(.headline)
                .foregroundStyle(.secondary)
        }
    }

    private var stepNumber: Int {
        switch step {
        case .detecting: return 1
        case .download, .downloading: return 2
        case .done: return 3
        }
    }

    // MARK: - Content (per step)

    @ViewBuilder
    private var content: some View {
        switch step {
        case .detecting:        detectingView
        case .download:         downloadView
        case .downloading:      downloadingView
        case .done:             doneView
        }
    }

    // ----- Step 1: detect ---------------------------------------------------

    private var detectingView: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Detecting hardware").font(.title2)

            if let h = report {
                LabeledRow("CPU",       value: "\(h.cpuName) — \(h.cpuCores) cores")
                LabeledRow("Memory",    value: "\(h.ramGB) GB unified RAM")
                LabeledRow("GPU",       value: h.metalSupported ? "Metal-capable" : "no Metal")
                LabeledRow("Chip",      value: h.chip == .appleSilicon ? "Apple Silicon" : "Intel")
                LabeledRow("OS",        value: h.osVersion)

                if let sel = selection {
                    Divider().padding(.vertical, 4)
                    LabeledRow("Backend",     value: sel.kind == .mlx ? "MLX (ANE + GPU + UMA)" : "llama.cpp + Metal")
                    LabeledRow("Tier",        value: sel.tierId)
                    LabeledRow("Quantization", value: sel.quantization)
                    Text(sel.reason).font(.caption).foregroundStyle(.secondary)

                    if sel.kind == .llamaCppMetal {
                        // Intel path is a stub today — be honest about it.
                        Text("Note: Intel Mac support uses the llama.cpp Metal backend, which currently runs in stub mode. You can complete setup, but inference will be a no-op until the helper ships in 1.1.")
                            .font(.caption)
                            .foregroundStyle(.orange)
                            .padding(.top, 4)
                    }
                } else if let err = selectionError {
                    Text(err)
                        .foregroundStyle(.red)
                        .padding(.top, 8)
                }
            } else {
                HStack { ProgressView().controlSize(.small); Text("Probing…") }
            }
        }
    }

    // ----- Step 2: download (idle) -----------------------------------------

    private var downloadView: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Download the model").font(.title2)
            Text("Localyze runs Google's Gemma 4 E4B locally — the same model the mobile app uses. The MLX-packaged weights are about 5 GB and live in ~/Library/Application Support/Localyze.")
                .foregroundStyle(.secondary)

            HStack {
                Button("Download now") {
                    Task { await beginDownload() }
                }
                .keyboardShortcut(.defaultAction)

                Button("Browse local file…") { browseForLocalModel() }
            }

            if let err = downloadError {
                Text(err).foregroundStyle(.red)
            }
        }
    }

    // ----- Step 2b: download (in progress) ---------------------------------

    private var downloadingView: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Downloading model").font(.title2)
            Text(downloadLabel).foregroundStyle(.secondary)

            // ProgressView is bound to @State, refreshed by the AsyncStream loop.
            ProgressView(value: Double(downloadBytes),
                         total: Double(max(downloadTotal, 1)))
                .progressViewStyle(.linear)

            Text("\(human(downloadBytes)) / \(human(downloadTotal))")
                .font(.caption)
                .foregroundStyle(.secondary)

            HStack {
                Button("Cancel", role: .destructive) {
                    downloadTask?.cancel()
                    step = .download
                }
            }

            if let err = downloadError {
                Text(err).foregroundStyle(.red)
            }
        }
    }

    // ----- Step 3: all set --------------------------------------------------

    private var doneView: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("All set").font(.title2)
            Text("Localyze is ready to run entirely on your Mac. Web Search is off by default — toggle it in the chat window if you want the agent to reach the internet.")
                .foregroundStyle(.secondary)
            Button("Open Localyze") { finish() }
                .keyboardShortcut(.defaultAction)
        }
    }

    // MARK: - Footer

    private var footer: some View {
        HStack {
            Spacer()
            switch step {
            case .detecting:
                Button("Next") { step = .download }
                    .disabled(selection == nil)
                    .keyboardShortcut(.defaultAction)
            case .download, .downloading, .done:
                EmptyView()
            }
        }
    }

    // MARK: - Step actions

    private func stepDetect() async {
        let h = HardwareProbe.run()
        report = h
        do {
            selection = try BackendSelector.pick(h)
        } catch {
            selectionError = (error as? LocalizedError)?.errorDescription
                ?? "This Mac does not meet Localyze's minimum requirements."
        }
    }

    private func beginDownload() async {
        downloadError = nil
        downloadBytes = 0
        downloadTotal = 1
        downloadLabel = "Resolving manifest…"
        step = .downloading

        downloadTask?.cancel()
        downloadTask = Task {
            do {
                try ModelPath.ensureDirectories()
                let downloader = ModelDownloader(cacheRoot: ModelPath.modelDir)

                guard let sel = selection else {
                    throw NSError(domain: "Localyze", code: 10,
                                  userInfo: [NSLocalizedDescriptionKey: "no backend selection"])
                }

                let tier = try await downloader.resolveTier(id: sel.tierId)
                downloadLabel = "Downloading \(tier.model.url.lastPathComponent)…"

                for try await p in downloader.fetch(tier) {
                    downloadBytes = p.done
                    downloadTotal = max(p.total, p.done)
                    downloadLabel = "Downloading \(p.label)…  \(human(p.done)) / \(human(p.total))"
                }

                step = .done
            } catch is CancellationError {
                step = .download
            } catch {
                downloadError = error.localizedDescription
                step = .download
            }
        }
    }

    private func browseForLocalModel() {
        let panel = NSOpenPanel()
        panel.canChooseFiles = false
        panel.canChooseDirectories = true
        panel.allowsMultipleSelection = false
        panel.message = "Pick the local MLX model directory (contains config.json, tokenizer.json, weights)."

        guard panel.runModal() == .OK, let src = panel.url else { return }

        do {
            try ModelPath.ensureDirectories()
            // Copy or symlink the user's directory into the canonical location.
            let dest = ModelPath.modelDir
            if FileManager.default.fileExists(atPath: dest.path) {
                try FileManager.default.removeItem(at: dest)
            }
            try FileManager.default.createSymbolicLink(at: dest, withDestinationURL: src)
            step = .done
        } catch {
            downloadError = "Couldn't link local model: \(error.localizedDescription)"
        }
    }

    private func finish() {
        SettingsStore.shared.onboarded = true
        onComplete()
    }

    // MARK: - Helpers

    private func human(_ bytes: Int64) -> String {
        let f = ByteCountFormatter()
        f.allowedUnits = [.useMB, .useGB]
        f.countStyle = .file
        return f.string(fromByteCount: bytes)
    }
}

/// Small two-column row used in the hardware summary table.
private struct LabeledRow: View {
    let label: String
    let value: String
    init(_ label: String, value: String) { self.label = label; self.value = value }
    var body: some View {
        HStack(alignment: .firstTextBaseline) {
            Text(label).foregroundStyle(.secondary).frame(width: 110, alignment: .leading)
            Text(value).textSelection(.enabled)
        }
    }
}
