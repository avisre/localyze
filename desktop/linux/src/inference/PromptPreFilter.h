#pragma once
#include <QRegularExpression>
#include <QString>
#include <QStringList>

namespace localyze {

/// Pre-filter that intercepts prompts before the model. Mirrors the Android
/// `ClarificationOrchestrator` + the injection-resistance block in
/// `SystemPromptBuilder`. When a prompt matches a known injection or vague
/// opener, this returns a canned safe response and the caller skips the
/// model entirely. Same Python implementation lives in
/// `desktop/tests/pre_filter.py` for the eval harness.
struct PromptPreFilter {
    struct Decision {
        bool passthrough = true;   // false → use `response` instead of model
        QString response;
        QString reason;            // "injection" | "vague" | "passthrough"
    };
    static Decision decide(const QString& prompt);
};

}  // namespace localyze
