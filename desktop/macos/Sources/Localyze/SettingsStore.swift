import Foundation

/// Persisted user settings. UserDefaults-backed so we don't take any deps.
final class SettingsStore {
    static let shared = SettingsStore()

    private let defaults = UserDefaults.standard
    private enum K {
        static let webSearchEnabled = "localyze.webSearchEnabled"
        static let searxngUrl       = "localyze.searxngUrl"
        static let codeExecEnabled  = "localyze.codeExecEnabled"
        static let onboarded        = "localyze.onboarded"
    }

    var webSearchEnabled: Bool {
        get { defaults.bool(forKey: K.webSearchEnabled) }                 // default false → privacy-first
        set { defaults.set(newValue, forKey: K.webSearchEnabled) }
    }

    var searxngUrl: String {
        get { defaults.string(forKey: K.searxngUrl) ?? "https://search.brave.com/search" }
        set { defaults.set(newValue, forKey: K.searxngUrl) }
    }

    /// Whether the `run` tool may execute model-generated python/javascript.
    /// Default false — safety-first. Toggle in the chat UI / settings drawer.
    var codeExecEnabled: Bool {
        get { defaults.bool(forKey: K.codeExecEnabled) }                  // default false
        set { defaults.set(newValue, forKey: K.codeExecEnabled) }
    }

    /// True once the first-run flow has completed (hardware probe + model
    /// download/import + confirmation). Read on launch by `LocalyzeApp` to
    /// decide whether to show `OnboardingView` or `ChatView`.
    var onboarded: Bool {
        get { defaults.bool(forKey: K.onboarded) }
        set { defaults.set(newValue, forKey: K.onboarded) }
    }
}
