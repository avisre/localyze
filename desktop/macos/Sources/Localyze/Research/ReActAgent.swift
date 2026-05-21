import Foundation

@MainActor
final class ReActAgent {
    enum StepKind: String { case plan, reason, act, observe, final }
    struct Step { let kind: StepKind; let text: String }

    private let backend: MLXBackend
    private let maxSteps = 12

    init(backend: MLXBackend) {
        self.backend = backend
    }

    var webSearchEnabled: Bool {
        get { SettingsStore.shared.webSearchEnabled }
        set { SettingsStore.shared.webSearchEnabled = newValue }
    }

    func run(userPrompt: String) -> AsyncThrowingStream<Step, Error> {
        AsyncThrowingStream { continuation in
            Task {
                do {
                    let webOn = self.webSearchEnabled
                    let tools = Tools.shared.build(includeWeb: webOn)
                    let dispatch = Dictionary(uniqueKeysWithValues: tools.map { ($0.name, $0.call) })

                    var conversation = "<start_of_turn>system\n"
                        + Self.systemPrompt(webEnabled: webOn) + "<end_of_turn>\n"
                        + "<start_of_turn>user\n" + userPrompt + "<end_of_turn>\n"
                        + "<start_of_turn>model\n"

                    for step in 0..<self.maxSteps {
                        continuation.yield(Step(kind: .reason, text: "step \(step + 1)"))

                        var stepOutput = ""
                        for try await tok in self.backend.generate(prompt: conversation, maxTokens: 512) {
                            stepOutput += tok
                        }

                        if let finalText = Self.matchFinal(stepOutput) {
                            continuation.yield(Step(kind: .final, text: finalText))
                            continuation.finish()
                            return
                        }

                        guard let call = Self.matchToolCall(stepOutput) else {
                            // No tool call, no <final> — treat as direct answer.
                            continuation.yield(Step(kind: .final, text: stepOutput.trimmingCharacters(in: .whitespacesAndNewlines)))
                            continuation.finish()
                            return
                        }

                        continuation.yield(Step(kind: .act, text: "\(call.name) \(call.argsJson)"))

                        let result: [String: Any]
                        if let fn = dispatch[call.name] {
                            do {
                                result = try await fn(call.argsObj)
                            } catch {
                                result = ["error": error.localizedDescription]
                            }
                        } else {
                            result = ["error": "tool '\(call.name)' is not available"]
                        }

                        let resultJson = (try? JSONSerialization.data(withJSONObject: result))
                            .flatMap { String(data: $0, encoding: .utf8) } ?? "{}"
                        continuation.yield(Step(kind: .observe, text: resultJson))

                        conversation += stepOutput
                            + "\n<tool_result name=\"\(call.name)\">\n"
                            + resultJson + "\n</tool_result>\n"
                    }

                    continuation.yield(Step(kind: .final, text: "[hit max steps]"))
                    continuation.finish()
                } catch {
                    continuation.finish(throwing: error)
                }
            }
        }
    }

    // MARK: - parsing

    struct ToolCall { let name: String; let argsObj: [String: Any]; let argsJson: String }

    private static func matchFinal(_ text: String) -> String? {
        let pattern = #"<final>([\s\S]*?)</final>"#
        guard let rx = try? NSRegularExpression(pattern: pattern) else { return nil }
        let ns = text as NSString
        guard let m = rx.firstMatch(in: text, range: NSRange(location: 0, length: ns.length))
        else { return nil }
        return ns.substring(with: m.range(at: 1)).trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func matchToolCall(_ text: String) -> ToolCall? {
        let pattern = #"<tool\s+name="([^"]+)"\s*>([\s\S]*?)</tool>"#
        guard let rx = try? NSRegularExpression(pattern: pattern) else { return nil }
        let ns = text as NSString
        guard let m = rx.firstMatch(in: text, range: NSRange(location: 0, length: ns.length))
        else { return nil }
        let name = ns.substring(with: m.range(at: 1))
        let argsJson = ns.substring(with: m.range(at: 2)).trimmingCharacters(in: .whitespacesAndNewlines)
        let obj = (try? JSONSerialization.jsonObject(with: Data(argsJson.utf8))) as? [String: Any] ?? [:]
        return ToolCall(name: name, argsObj: obj, argsJson: argsJson)
    }

    private static func systemPrompt(webEnabled: Bool) -> String {
        var s =
            "You are Localyze, a private on-device assistant.\n\n"
            + "Tools (call by emitting <tool name=\"NAME\">{json args}</tool>):\n"
            + "  memory.search(query, limit=5)\n"
            + "  files.search(query, limit=5)\n"
            + "  calc(expr)\n"
            + "  run(lang, code)\n"
            + "  system.info()\n"
        if webEnabled { s += "  web.search(query, n=5)\n" }
        s += "\nFinalize with: <final>...answer...</final>\n"
           + "Cite tool results. Prefer 1-3 tool calls. Be concise.\n"
        return s
    }
}
