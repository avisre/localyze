import Foundation

// MARK: - Choice of approach: STUB
//
// Why a stub and not a real backend?
//   • MLX (the Apple Silicon path) is wired in via SwiftPM in Package.swift —
//     we have a single, batteries-included dependency. There is no equivalent
//     pure-Swift llama.cpp package vendored in the project today, and there
//     is no `Process()`-launchable `llama-cli` binary bundled in the .app.
//   • Building a real Intel-Mac backend means EITHER (a) vendoring a
//     llama.cpp Swift bridge target (large, needs Metal shader compilation
//     into the bundle) OR (b) committing to ship a pre-built `llama-cli`
//     fat/Intel binary signed and notarised for distribution. Both are
//     multi-PR efforts, not a one-file fix.
//   • What we MUST avoid right now is the current behaviour: `BackendSelector`
//     hands back `.llamaCppMetal`, `ChatView` ignores `sel.kind` and constructs
//     `MLXBackend` anyway, and MLX dlopens an arm64-only framework on an Intel
//     CPU — which crashes the app with a confusing dyld error.
//
// This stub gives Intel users an honest, in-product error message instead.
// Replace this file with a real implementation when llama.cpp ships.
//
// Shape mirrors `MLXBackend` exactly (no formal protocol exists in the
// project yet — both classes expose the same `load()` and
// `generate(prompt:system:maxTokens:)` surface so `ChatView` / `ReActAgent`
// can swap between them without further changes).

@MainActor
final class LlamaCppMetalBackendStub {
    /// Where a user could drop a self-built `llama-cli` to enable the real path
    /// in a future build. Documented in the error message below.
    static let fallbackBinaryURL: URL = {
        let appSupport = FileManager.default.urls(for: .applicationSupportDirectory,
                                                  in: .userDomainMask).first!
        return appSupport
            .appendingPathComponent("Localyze", isDirectory: true)
            .appendingPathComponent("llama-cli")
    }()

    static var fallbackBinaryPresent: Bool {
        FileManager.default.isExecutableFile(atPath: fallbackBinaryURL.path)
    }

    /// Public so the selector can construct a stable error string identical
    /// to the one `load()` would throw, for the "refuse at selection time"
    /// path.
    static let unsupportedMessage: String =
        "Intel Mac support pending — please use an Apple Silicon Mac to run " +
        "Localyze, or build llama.cpp manually and place the binary at " +
        "~/Library/Application Support/Localyze/llama-cli"

    private let modelPath: URL

    init(modelPath: URL) {
        self.modelPath = modelPath
    }

    func load() async throws {
        // If a user has dropped a llama-cli binary at the documented path,
        // we still cannot run it (no Process()-based shelling implemented
        // here yet) — but we throw a more helpful "almost there" message
        // instead of the generic one, so they know the path is recognised.
        if Self.fallbackBinaryPresent {
            throw NSError(domain: "Localyze", code: 21, userInfo: [NSLocalizedDescriptionKey:
                "Found llama-cli at \(Self.fallbackBinaryURL.path) but the Intel-Mac " +
                "subprocess runner is not implemented in this build. Track the issue or " +
                "upgrade to an Apple Silicon Mac."])
        }
        throw NSError(domain: "Localyze", code: 20,
                      userInfo: [NSLocalizedDescriptionKey: Self.unsupportedMessage])
    }

    /// Same `AsyncThrowingStream<String, Error>` surface as `MLXBackend.generate`.
    /// Honours the `system:` argument signature (HARDREFUSALS-aware system
    /// prompts get passed through from `SystemPrompt.chat()` exactly as on
    /// Apple Silicon) — but since `load()` always throws on this build, the
    /// stream just yields a single thrown error so the UI can render it.
    func generate(prompt: String, system: String? = nil, maxTokens: Int = 1024)
        -> AsyncThrowingStream<String, Error>
    {
        AsyncThrowingStream { continuation in
            continuation.finish(throwing: NSError(domain: "Localyze", code: 20,
                userInfo: [NSLocalizedDescriptionKey: Self.unsupportedMessage]))
        }
    }
}
