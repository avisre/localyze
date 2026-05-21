import Foundation

/// Where the on-disk model bundle lives. Single source of truth for both the
/// downloader (writes here) and the backend (reads here).
enum ModelPath {
    static let supportRoot: URL = {
        let appSupport = FileManager.default.urls(for: .applicationSupportDirectory,
                                                  in: .userDomainMask).first!
        return appSupport.appendingPathComponent("Localyze", isDirectory: true)
    }()

    static let modelDir: URL = supportRoot.appendingPathComponent("models/gemma-4-e4b-it-mlx",
                                                                  isDirectory: true)

    static let artifactsDir: URL = {
        let home = FileManager.default.homeDirectoryForCurrentUser
        return home.appendingPathComponent("Localyze/artifacts", isDirectory: true)
    }()

    static func ensureDirectories() throws {
        for dir in [supportRoot, modelDir, artifactsDir] {
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        }
    }
}
