import Foundation
import JavaScriptCore

/// A tool name + a function that takes JSON args and returns a JSON dict.
struct Tool {
    let name: String
    let call: (_ args: [String: Any]) async throws -> [String: Any]
}

@MainActor
final class Tools {
    static let shared = Tools()

    func build(includeWeb: Bool) -> [Tool] {
        var out: [Tool] = [
            Tool(name: "memory.search", call: memorySearch),
            Tool(name: "files.search",  call: filesSearch),
            Tool(name: "calc",          call: calc),
            Tool(name: "run",           call: run),
            Tool(name: "system.info",   call: systemInfo),
        ]
        if includeWeb {
            out.append(Tool(name: "web.search", call: webSearch))
        }
        return out
    }

    // MARK: - calc

    private func calc(_ args: [String: Any]) async throws -> [String: Any] {
        let expr = (args["expr"] as? String ?? "")
            .replacingOccurrences(of: "pi", with: "\(Double.pi)")
            .replacingOccurrences(of: "\\be\\b", with: "\(M_E)", options: .regularExpression)
        let ctx = JSContext()!
        let value = ctx.evaluateScript(expr)
        if let n = value?.toNumber()?.doubleValue, !n.isNaN {
            return ["value": n]
        }
        return ["error": "could not evaluate: \(expr)"]
    }

    // MARK: - system.info

    private func systemInfo(_ args: [String: Any]) async throws -> [String: Any] {
        let info = ProcessInfo.processInfo
        return [
            "os":      info.operatingSystemVersionString,
            "ram_gb":  Int(info.physicalMemory / 1_000_000_000),
            "cores":   info.activeProcessorCount,
        ]
    }

    // MARK: - memory.search

    private func memorySearch(_ args: [String: Any]) async throws -> [String: Any] {
        let query = (args["query"] as? String ?? "").lowercased()
        let limit = args["limit"] as? Int ?? 5
        let root  = ModelPath.supportRoot.appendingPathComponent("memory", isDirectory: true)
        try? FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)

        var hits: [[String: Any]] = []
        for url in (try? FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: nil)) ?? [] {
            if hits.count >= limit { break }
            guard let body = try? String(contentsOf: url),
                  let range = body.lowercased().range(of: query)
            else { continue }
            let start = body.index(range.lowerBound, offsetBy: -80, limitedBy: body.startIndex) ?? body.startIndex
            let end   = body.index(range.upperBound, offsetBy: 120, limitedBy: body.endIndex)  ?? body.endIndex
            hits.append([
                "id":      url.lastPathComponent,
                "snippet": String(body[start..<end]),
            ])
        }
        return ["results": hits]
    }

    // MARK: - files.search

    private func filesSearch(_ args: [String: Any]) async throws -> [String: Any] {
        let query = (args["query"] as? String ?? "").lowercased()
        let limit = args["limit"] as? Int ?? 5
        let root  = FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Localyze/files")

        var hits: [[String: Any]] = []
        for url in (try? FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: nil)) ?? [] {
            if hits.count >= limit { break }
            guard let body = try? String(contentsOf: url, encoding: .utf8),
                  let range = body.lowercased().range(of: query)
            else { continue }
            let start = body.index(range.lowerBound, offsetBy: -80, limitedBy: body.startIndex) ?? body.startIndex
            let end   = body.index(range.upperBound, offsetBy: 120, limitedBy: body.endIndex)  ?? body.endIndex
            hits.append([
                "path":    url.path,
                "snippet": String(body[start..<end]),
            ])
        }
        return ["results": hits]
    }

    // MARK: - run

    // Hard caps for the sandboxed code-exec tool.
    private static let runTimeoutNs:  UInt64 = 10 * 1_000_000_000   // 10 s
    private static let runOutputCapB: Int    = 64 * 1024            // 64 KB

    private func run(_ args: [String: Any]) async throws -> [String: Any] {
        // 1. Off-by-default. Agent cannot exec code unless user opts in.
        guard SettingsStore.shared.codeExecEnabled else {
            return ["error": "code execution is disabled in settings"]
        }

        let lang = (args["lang"] as? String ?? "").lowercased()
        let code = args["code"] as? String ?? ""

        // 2. Language allowlist. No shell, no bash, no zsh.
        let program: String
        let ext: String
        switch lang {
        case "python", "python3":
            program = "/usr/bin/python3"; ext = "py"
        case "javascript", "node":
            program = "/usr/bin/env";     ext = "js"   // node lives under /usr/local on macOS
        default:
            return ["error": "unsupported lang: \(lang) (allowed: python, python3, javascript, node)"]
        }

        // 3. Empty tempdir cwd; code in a tempfile, never `-c` / `-e`.
        let fm = FileManager.default
        let workDir = fm.temporaryDirectory.appendingPathComponent("localyze-run-\(UUID().uuidString)", isDirectory: true)
        do {
            try fm.createDirectory(at: workDir, withIntermediateDirectories: true)
        } catch {
            return ["error": "could not create sandbox: \(error.localizedDescription)"]
        }
        defer { try? fm.removeItem(at: workDir) }

        let codeURL = workDir.appendingPathComponent("snippet.\(ext)")
        do {
            try code.write(to: codeURL, atomically: true, encoding: .utf8)
        } catch {
            return ["error": "could not stage code: \(error.localizedDescription)"]
        }

        let task = Process()
        task.executableURL = URL(fileURLWithPath: program)
        if program.hasSuffix("/env") {
            // /usr/bin/env <node> <file>  — lets us find node on PATH=/usr/bin:/usr/local/bin
            task.arguments = ["node", codeURL.path]
        } else {
            task.arguments = [codeURL.path]
        }
        task.currentDirectoryURL = workDir

        // 4. & 6. PATH lockdown — strip inherited env, set minimal PATH.
        task.environment = [
            "PATH": "/usr/bin:/usr/local/bin",
        ]

        let pipe = Pipe()
        task.standardOutput = pipe
        task.standardError  = pipe

        do {
            try task.run()
        } catch {
            return ["error": "execution failed: \(error.localizedDescription)"]
        }

        // 5. Hard 10s timeout; SIGKILL on overrun. We target the child PID
        //    directly (not -pid) because the child inherits our process group;
        //    sending to -pid would kill ourselves.
        let pid = task.processIdentifier
        let timeoutTask = Task<Bool, Never> {
            try? await Task.sleep(nanoseconds: Self.runTimeoutNs)
            if task.isRunning {
                kill(pid, SIGKILL)
                task.terminate()
                return true
            }
            return false
        }

        task.waitUntilExit()
        let timedOut = await timeoutTask.value

        // 6. Output cap.
        let data = pipe.fileHandleForReading.readDataToEndOfFile()
        var output = String(data: data, encoding: .utf8) ?? ""
        var truncated = false
        if output.utf8.count > Self.runOutputCapB {
            let bytes = Array(output.utf8.prefix(Self.runOutputCapB))
            output = String(decoding: bytes, as: UTF8.self)
            truncated = true
        }

        if timedOut {
            return ["error": "execution timeout (10s)", "stdout": output]
        }

        var result: [String: Any] = [
            "stdout":    output,
            "exit_code": Int(task.terminationStatus),
        ]
        if truncated { result["truncated"] = true }
        return result
    }

    // MARK: - web.search

    private func webSearch(_ args: [String: Any]) async throws -> [String: Any] {
        guard SettingsStore.shared.webSearchEnabled else { return ["error": "web.search disabled"] }
        let query = args["query"] as? String ?? ""
        let n     = args["n"] as? Int ?? 5
        guard var components = URLComponents(string: SettingsStore.shared.searxngUrl) else {
            return ["error": "invalid searxngUrl"]
        }
        components.queryItems = [
            URLQueryItem(name: "q", value: query),
            URLQueryItem(name: "format", value: "json"),
        ]
        guard let url = components.url else { return ["error": "could not build url"] }

        let (data, _) = try await URLSession.shared.data(from: url)
        let json = try JSONSerialization.jsonObject(with: data) as? [String: Any] ?? [:]
        let raw = (json["results"] as? [[String: Any]] ?? []).prefix(n)
        let hits = raw.map { item in [
            "title":   item["title"]   as? String ?? "",
            "url":     item["url"]     as? String ?? "",
            "snippet": item["content"] as? String ?? "",
        ] as [String: Any] }
        return ["results": Array(hits)]
    }
}
