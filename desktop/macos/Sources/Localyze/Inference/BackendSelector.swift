import Foundation

/// Which inference backend should run on this Mac.
///
/// `mlx`              — real MLX on Apple Silicon (only fully-supported path today).
/// `llamaCppMetal`    — Intel Mac path. Currently served by
///                      `LlamaCppMetalBackendStub`, which throws an honest
///                      "Intel Mac support pending" error at load time. See
///                      that file's header for the choice of stub-over-real.
/// `unsupportedHardware` — selector refuses up-front; main-app code shows a
///                      message and skips backend construction entirely.
enum BackendKind: String { case mlx, llamaCppMetal, unsupportedHardware }

struct Selection {
    let kind: BackendKind
    let tierId: String
    let reason: String
    let quantization: String   // "fp16" | "int8" | "int4"
}

struct UnsupportedHardwareError: LocalizedError {
    let reason: String
    var errorDescription: String? { reason }
}

enum BackendSelector {
    /// Always picks a Gemma 4 E4B tier. Throws if the device can't fit it at any tier —
    /// we refuse to install rather than swap in a smaller model. Matches mobile policy.
    static func pick(_ h: HardwareReport) throws -> Selection {
        // Apple Silicon — MLX dispatches across ANE + GPU + unified memory.
        if h.chip == .appleSilicon {
            if h.ramGB >= 16 {
                return Selection(kind: .mlx, tierId: "mac-apple-silicon-fp16",
                                 reason: "Apple Silicon ≥16 GB — MLX fp16",
                                 quantization: "fp16")
            }
            if h.ramGB >= 8 {
                return Selection(kind: .mlx, tierId: "mac-apple-silicon-int4",
                                 reason: "Apple Silicon 8–16 GB — MLX int4",
                                 quantization: "int4")
            }
        }
        // Intel Macs — llama.cpp Metal.
        // No llama.cpp Swift bridge or bundled `llama-cli` ships in v1, so the
        // Intel path is currently stubbed. If a user has dropped a `llama-cli`
        // at the documented fallback location we still surface `.llamaCppMetal`
        // (the stub's `load()` will return a specific "binary found but runner
        // not wired" error — that's the seam the real Intel backend plugs into).
        // Without any fallback we return `.unsupportedHardware` so main-app
        // code skips backend construction entirely instead of constructing
        // `MLXBackend` and crashing inside an arm64-only framework.
        if h.chip == .intel, h.metalSupported, h.ramGB >= 16 {
            if LlamaCppMetalBackendStub.fallbackBinaryPresent {
                return Selection(kind: .llamaCppMetal, tierId: "mac-intel-metal",
                                 reason: "Intel Mac + Metal — user llama-cli detected (runner pending)",
                                 quantization: "int4")
            }
            return Selection(kind: .unsupportedHardware, tierId: "mac-intel-metal-unsupported",
                             reason: LlamaCppMetalBackendStub.unsupportedMessage,
                             quantization: "int4")
        }

        // No tier matches: tell the caller to refuse up-front rather than
        // construct any backend at all.
        throw UnsupportedHardwareError(reason:
            "Localyze for Mac needs Apple Silicon with ≥8 GB unified memory, " +
            "or an Intel Mac with Metal and ≥16 GB RAM. " +
            "Detected: \(h.chip == .appleSilicon ? "Apple Silicon" : "Intel"), \(h.ramGB) GB RAM.")
    }
}
