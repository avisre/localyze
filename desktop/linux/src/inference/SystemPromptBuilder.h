#pragma once
#include <QString>

namespace localyze {

/// Mirrors the Android SystemPromptBuilder. Builds the same ~5000-token system
/// prompt the mobile Gemma 4 E4B model receives, so desktop responses match
/// the quality bar set by the mobile eval suite.
struct SystemPromptBuilder {
    enum class Mode { Chat, Code, Data, Write, Brainstorm, Communication, Research };

    /// Build the full system prompt (mode prompt + clarification policy +
    /// response-format instruction + knowledge/tool guidance + date stamp).
    static QString build(Mode mode = Mode::Chat,
                         bool enableThinking = false,
                         bool includeToolDescriptions = false);

    /// The wire-format wrap for Gemma 3n / Gemma 4 E4B with an optional system
    /// prompt prepended to the user turn. Matches the Android template.
    static QString wrapGemma(const QString& userText, const QString& systemPrompt = {});

    /// Strip a "thinking-style" preamble from the model's raw output before it
    /// reaches the user. Matches the Android SendMessageUseCase.strip*Preamble
    /// behavior — the model sometimes leaks "I'm analyzing the question…" or
    /// "The user is asking…" lines we don't want surfaced.
    static QString stripThinkingPreamble(const QString& raw);
};

}  // namespace localyze
