import Foundation
import MLX
import MLXLLM
import MLXLMCommon

/// Real MLX-backed inference for Gemma 4 E4B (Gemma 3n E4B Instruct) on Apple Silicon.
/// Same weights as the mobile app; just packaged as MLX so it can use ANE + GPU
/// + unified memory automatically.
@MainActor
final class MLXBackend {
    private let modelConfig: ModelConfiguration
    private var container: ModelContainer?

    init(modelPath: URL) {
        // Local MLX bundle on disk (the downloaded artifact). MLX expects a
        // directory containing config.json, tokenizer.json, weights.safetensors.
        self.modelConfig = ModelConfiguration(
            directory: modelPath,
            overrideTokenizer: nil,
            defaultPrompt: ""
        )
    }

    func load() async throws {
        // mlx-swift loads everything (weights + tokenizer + tokenizer model) in one call.
        // Limits the wired-memory cache so the OS stays responsive on 8 GB devices.
        MLX.GPU.set(cacheLimit: 32 * 1024 * 1024)
        self.container = try await LLMModelFactory.shared.loadContainer(
            configuration: modelConfig
        ) { progress in
            // Progress callback — could surface to UI later.
            _ = progress.fractionCompleted
        }
    }

    /// Streams the assistant's reply token by token. Format follows Gemma's
    /// chat template (<start_of_turn>user .. model ..).
    func generate(prompt: String, system: String? = nil, maxTokens: Int = 1024) -> AsyncThrowingStream<String, Error> {
        AsyncThrowingStream { continuation in
            Task {
                do {
                    guard let container = self.container else {
                        throw NSError(domain: "Localyze", code: 10,
                                      userInfo: [NSLocalizedDescriptionKey: "MLXBackend.load() not called"])
                    }
                    let templated = PromptTemplate.gemma(prompt, system: system)
                    try await container.perform { context in
                        let input = try await context.processor.prepare(
                            input: .init(prompt: templated)
                        )
                        let params = GenerateParameters(
                            maxTokens: maxTokens,
                            temperature: 0.7,
                            topP: 0.95,
                            repetitionPenalty: 1.1
                        )
                        try MLXLMCommon.generate(
                            input: input,
                            parameters: params,
                            context: context
                        ) { tokens in
                            // Decode the latest piece and emit. mlx-swift-examples
                            // streams tokens to this callback; converting them
                            // to text and yielding gives the UI a token stream.
                            let text = context.tokenizer.decode(tokens: tokens)
                            continuation.yield(text)
                            return .more
                        }
                        continuation.finish()
                    }
                } catch {
                    continuation.finish(throwing: error)
                }
            }
        }
    }
}

enum PromptTemplate {
    /// Gemma 3n chat template. Same format the mobile app's SystemPromptBuilder uses.
    static func gemma(_ userText: String, system: String? = nil) -> String {
        var s = ""
        if let system, !system.isEmpty {
            s += "<start_of_turn>user\n\(system)\n\n\(userText)<end_of_turn>\n"
        } else {
            s += "<start_of_turn>user\n\(userText)<end_of_turn>\n"
        }
        s += "<start_of_turn>model\n"
        return s
    }
}
