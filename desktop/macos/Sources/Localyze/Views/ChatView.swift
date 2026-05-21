import SwiftUI

struct ChatMessage: Identifiable {
    let id = UUID()
    let role: String   // "user" | "assistant"
    var text: String
}

struct ChatView: View {
    @State private var messages: [ChatMessage] = []
    @State private var input: String = ""
    @State private var webSearchOn: Bool = SettingsStore.shared.webSearchEnabled
    @State private var codeExecOn:  Bool = SettingsStore.shared.codeExecEnabled
    @State private var status: String = "Probing hardware…"
    @State private var sending: Bool = false
    @State private var backend: MLXBackend?

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(status).font(.caption).foregroundStyle(.secondary).padding(.horizontal)

            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 8) {
                        ForEach(messages) { m in
                            Text("\(m.role == "user" ? "You" : "Localyze"): \(m.text)")
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .textSelection(.enabled)
                                .id(m.id)
                        }
                    }
                    .padding()
                }
                .onChange(of: messages.last?.id) {
                    if let id = messages.last?.id { proxy.scrollTo(id, anchor: .bottom) }
                }
            }

            HStack {
                TextField("Ask Localyze…", text: $input, axis: .vertical)
                    .lineLimit(1...5)
                    .textFieldStyle(.roundedBorder)
                    .disabled(sending)
                Toggle("Web", isOn: $webSearchOn)
                    .toggleStyle(.switch)
                    .onChange(of: webSearchOn) { _, new in
                        SettingsStore.shared.webSearchEnabled = new
                    }
                Toggle("Code exec", isOn: $codeExecOn)
                    .toggleStyle(.switch)
                    .onChange(of: codeExecOn) { _, new in
                        SettingsStore.shared.codeExecEnabled = new
                    }
                Button(sending ? "…" : "Send", action: send)
                    .keyboardShortcut(.return, modifiers: .command)
                    .disabled(sending || input.trimmingCharacters(in: .whitespaces).isEmpty)
            }
            .padding()
        }
        .task { await bootstrap() }
    }

    private func bootstrap() async {
        let hw = HardwareProbe.run()
        do {
            let sel = try BackendSelector.pick(hw)
            status = "Gemma 4 E4B \(sel.quantization) on \(sel.kind.rawValue) — \(sel.reason)"
            // Intel Mac with no llama.cpp fallback: selector returns
            // `.unsupportedHardware` and has already formatted the refusal
            // into `sel.reason`. Don't construct MLXBackend (which would
            // dyld-crash on Intel) or the stub (which would just throw the
            // same message back).
            guard sel.kind != .unsupportedHardware else { return }
            try ModelPath.ensureDirectories()
            guard FileManager.default.fileExists(atPath: ModelPath.modelDir.appendingPathComponent("config.json").path)
            else {
                status += "  •  Model not yet downloaded. Open Settings → Download model."
                return
            }
            let b = MLXBackend(modelPath: ModelPath.modelDir)
            try await b.load()
            self.backend = b
            status += "  •  Loaded."
        } catch {
            status = "Unsupported / load error — \(error.localizedDescription)"
        }
    }

    private func send() {
        let text = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, let backend else { return }
        input = ""
        messages.append(ChatMessage(role: "user", text: text))
        let assistantId = UUID()
        messages.append(ChatMessage(role: "assistant", text: ""))
        let lastIndex = messages.count - 1
        sending = true

        Task {
            do {
                for try await chunk in backend.generate(prompt: text, system: SystemPrompt.chat()) {
                    messages[lastIndex].text += chunk
                }
            } catch {
                messages[lastIndex].text += "\n[error: \(error.localizedDescription)]"
            }
            sending = false
            _ = assistantId
        }
    }
}
