import SwiftUI

@main
struct LocalyzeApp: App {
    /// Triggers a re-render of the WindowGroup body when onboarding finishes.
    /// SettingsStore is UserDefaults-backed, so we can't observe it directly;
    /// flipping this @State on completion (and on first appear) is enough.
    @State private var onboarded: Bool = SettingsStore.shared.onboarded && Self.modelPresent()

    var body: some Scene {
        WindowGroup("Localyze") {
            Group {
                if onboarded {
                    ChatView()
                } else {
                    OnboardingView {
                        // Re-check the model exists in case the user picked
                        // a local path; refresh the @State to swap views.
                        onboarded = SettingsStore.shared.onboarded && Self.modelPresent()
                    }
                }
            }
            .frame(minWidth: 720, minHeight: 480)
            .onAppear {
                // Recover from the "onboarded flag is true but the model file
                // was deleted by the user / TimeMachine restore / etc." case.
                onboarded = SettingsStore.shared.onboarded && Self.modelPresent()
            }
        }
        .windowResizability(.contentMinSize)
    }

    /// True iff the canonical MLX model bundle exists on disk. Used as a guard
    /// alongside `SettingsStore.shared.onboarded` so we don't drop the user
    /// into a chat view with no model behind it.
    private static func modelPresent() -> Bool {
        let dir = ModelPath.modelDir
        guard FileManager.default.fileExists(atPath: dir.path) else { return false }
        // Cheap sanity check: at least one weights file present.
        let contents = (try? FileManager.default.contentsOfDirectory(atPath: dir.path)) ?? []
        return !contents.isEmpty
    }
}
