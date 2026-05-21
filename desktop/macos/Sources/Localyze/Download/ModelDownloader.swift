import Foundation
import CryptoKit

struct ManifestArtifact: Decodable {
    let url: URL
    let sha256: String
    let size_mb: Double?
}

struct ManifestTier: Decodable {
    let id: String
    let runtime: ManifestArtifact?
    let model: ManifestArtifact
}

struct Manifest: Decodable {
    let version: String
    let tiers: [ManifestTier]
}

struct DownloadProgress {
    let label: String
    let done: Int64
    let total: Int64
}

/// Resumable, sha-verified downloader. Reads via a streaming URLSessionDataTask
/// adapter so we can append to a .part file and resume from the same byte offset
/// after a network drop.
final class ModelDownloader: NSObject {
    static let manifestURL = URL(string: "https://cdn.localyze.app/desktop/manifest.json")!
    let cacheRoot: URL

    init(cacheRoot: URL) {
        self.cacheRoot = cacheRoot
        super.init()
        try? FileManager.default.createDirectory(at: cacheRoot, withIntermediateDirectories: true)
    }

    func resolveTier(id: String) async throws -> ManifestTier {
        let (data, _) = try await URLSession.shared.data(from: Self.manifestURL)
        let manifest = try JSONDecoder().decode(Manifest.self, from: data)
        guard let tier = manifest.tiers.first(where: { $0.id == id }) else {
            throw NSError(domain: "Localyze", code: 1,
                          userInfo: [NSLocalizedDescriptionKey: "no tier \(id)"])
        }
        return tier
    }

    func fetch(_ tier: ManifestTier) -> AsyncThrowingStream<DownloadProgress, Error> {
        AsyncThrowingStream { continuation in
            Task {
                do {
                    if let rt = tier.runtime { try await downloadOne(rt, label: "runtime", continuation: continuation) }
                    try await downloadOne(tier.model, label: "model", continuation: continuation)
                    continuation.finish()
                } catch { continuation.finish(throwing: error) }
            }
        }
    }

    private func downloadOne(_ a: ManifestArtifact, label: String,
                             continuation: AsyncThrowingStream<DownloadProgress, Error>.Continuation) async throws {
        let dest = cacheRoot.appendingPathComponent(a.url.lastPathComponent)
        let part = dest.appendingPathExtension("part")
        let target = Int64((a.size_mb ?? 0) * 1_000_000)

        if FileManager.default.fileExists(atPath: dest.path), try verify(dest, sha256: a.sha256) {
            continuation.yield(DownloadProgress(label: label, done: target, total: target))
            return
        }

        var startAt: Int64 = 0
        if FileManager.default.fileExists(atPath: part.path),
           let attrs = try? FileManager.default.attributesOfItem(atPath: part.path),
           let size = attrs[.size] as? NSNumber {
            startAt = size.int64Value
        }

        var req = URLRequest(url: a.url)
        if startAt > 0 { req.setValue("bytes=\(startAt)-", forHTTPHeaderField: "Range") }

        let (bytes, response) = try await URLSession.shared.bytes(for: req)
        let expectedAdded = (response as? HTTPURLResponse)?.expectedContentLength ?? -1
        let total = expectedAdded > 0 ? startAt + expectedAdded : max(target, startAt)

        if !FileManager.default.fileExists(atPath: part.path) {
            FileManager.default.createFile(atPath: part.path, contents: nil)
        }
        let handle = try FileHandle(forWritingTo: part)
        try handle.seekToEnd()

        var buffer = Data()
        var done = startAt
        for try await byte in bytes {
            buffer.append(byte)
            if buffer.count >= 1 << 20 {
                try handle.write(contentsOf: buffer)
                done += Int64(buffer.count)
                buffer.removeAll(keepingCapacity: true)
                continuation.yield(DownloadProgress(label: label, done: done, total: total))
            }
        }
        if !buffer.isEmpty {
            try handle.write(contentsOf: buffer)
            done += Int64(buffer.count)
        }
        try handle.close()

        guard try verify(part, sha256: a.sha256) else {
            try? FileManager.default.removeItem(at: part)
            throw NSError(domain: "Localyze", code: 2,
                          userInfo: [NSLocalizedDescriptionKey: "sha256 mismatch on \(label)"])
        }
        try? FileManager.default.removeItem(at: dest)
        try FileManager.default.moveItem(at: part, to: dest)
        continuation.yield(DownloadProgress(label: label, done: total, total: total))
    }

    // MARK: - Single-file API used by OnboardingView
    //
    // `fetch(_:)` above is the manifest-driven path used in production once the
    // tier is known. During first-run onboarding we sometimes only have a
    // direct URL + sha256 (e.g. when the user pastes a Hugging Face mirror or
    // we're testing against a staging CDN). This wrapper exposes that simpler
    // contract while reusing the same resumable / sha-verified primitive.
    //
    // Surface the live progress as a Combine-style @Published-equivalent
    // AsyncStream so the SwiftUI ProgressView can bind without a Subject.

    /// Start a single-file download. `dest` is the final on-disk location;
    /// a sibling `.part` file is used for resume across crashes / restarts.
    /// Emits `DownloadProgress` updates as bytes land; finishes (or throws)
    /// after sha256 verification.
    func start(url: URL, dest: URL, sha256: String, label: String = "model")
        -> AsyncThrowingStream<DownloadProgress, Error>
    {
        // Pretend this single URL is a one-artifact tier so we can reuse
        // `downloadOne`'s resume + verify + atomic-rename logic verbatim.
        let artifact = ManifestArtifact(url: url, sha256: sha256, size_mb: nil)
        return AsyncThrowingStream { continuation in
            Task {
                do {
                    // downloadOne writes into `cacheRoot/<lastPathComponent>`.
                    // If the caller wants the file at `dest`, move it there
                    // after the verified download lands.
                    try await downloadOne(artifact, label: label, continuation: continuation)
                    let landed = cacheRoot.appendingPathComponent(url.lastPathComponent)
                    if landed != dest {
                        try? FileManager.default.removeItem(at: dest)
                        try FileManager.default.createDirectory(
                            at: dest.deletingLastPathComponent(),
                            withIntermediateDirectories: true)
                        try FileManager.default.moveItem(at: landed, to: dest)
                    }
                    continuation.finish()
                } catch {
                    continuation.finish(throwing: error)
                }
            }
        }
    }

    private func verify(_ path: URL, sha256 expected: String) throws -> Bool {
        let handle = try FileHandle(forReadingFrom: path)
        defer { try? handle.close() }
        var hasher = SHA256()
        while true {
            let chunk = try handle.read(upToCount: 1 << 20) ?? Data()
            if chunk.isEmpty { break }
            hasher.update(data: chunk)
        }
        let hex = hasher.finalize().map { String(format: "%02x", $0) }.joined()
        return hex.caseInsensitiveCompare(expected) == .orderedSame
    }
}
