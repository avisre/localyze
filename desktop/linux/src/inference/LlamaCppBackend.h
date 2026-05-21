#pragma once
#include "BackendSelector.h"

#include <QObject>
#include <QString>
#include <QThread>
#include <atomic>
#include <memory>
#include <string>

// Forward-declare so this header is independent of llama.cpp's public headers.
struct llama_model;
struct llama_context;
struct llama_sampler;

namespace localyze {

/// Real Gemma 4 E4B inference via llama.cpp. The GGUF on disk is the same
/// Gemma 3n weights as the mobile app, quantized per the BackendSelector tier.
class LlamaCppBackend : public QObject {
    Q_OBJECT
public:
    explicit LlamaCppBackend(BackendKind kind, QString gguPath, QObject* parent = nullptr);
    ~LlamaCppBackend() override;

    /// Loads the model + context. Blocks. Run on a worker thread.
    /// Returns empty on success or an error message on failure.
    /// `contextSize` defaults to a value derived from VRAM (see
    /// LlamaCppBackend::recommendedContext()).
    QString load(int nGpuLayers, int contextSize = 8192);

    /// Suggest a default n_ctx for the given VRAM budget. The mobile app uses
    /// 4096; on desktop with 16+ GB VRAM we can comfortably stretch this so
    /// long-context Q&A stops getting truncated.
    static int recommendedContext(int vramGB, int ramGB);

    /// Optional system-prompt override. Defaults to the full SystemPromptBuilder
    /// chat prompt if not set.
    void setSystemPromptOverride(QString s) { systemPromptOverride_ = std::move(s); }

public slots:
    /// Streams tokens via the tokenStream() signal. Run on a worker thread.
    /// Wraps `prompt` in the Gemma chat template with the SystemPromptBuilder
    /// chat-mode system prompt. Use this for the user-facing chat path.
    void generate(QString prompt, int maxTokens = 1024);
    /// Same as generate(), but passes `prompt` to the model RAW — no Gemma
    /// template wrap, no system-prompt injection. Used by ReActAgent which
    /// builds its own multi-turn conversation with a ReAct-specific system
    /// prompt; double-wrapping would scramble the framing.
    void generateRaw(QString prompt, int maxTokens = 1024);
    void stop();

signals:
    void tokenStream(QString token);
    void finished();
    void failed(QString error);

private:
    BackendKind kind_;
    QString gguPath_;
    llama_model*   model_   = nullptr;
    llama_context* ctx_     = nullptr;
    llama_sampler* sampler_ = nullptr;
    std::atomic<bool> stopFlag_{false};
    std::atomic<bool> generating_{false};
    // True until the first successful llama_decode() completes. Used to skip
    // llama_memory_clear on the very first turn — there's nothing to clear,
    // and on Vulkan backends the clear-before-anything-decoded path has been
    // observed to SIGSEGV inside the compute queue setup.
    std::atomic<bool> first_decode_{true};
    QString systemPromptOverride_;

    void decodePrompt(const QString& templatedPrompt, int maxTokens);
};

}  // namespace localyze
