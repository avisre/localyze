#include "ReActAgent.h"
#include "../inference/LlamaCppBackend.h"
#include "../inference/ModeStore.h"
#include "../inference/SystemPromptBuilder.h"
#include "../settings/SettingsStore.h"

#include <QJsonDocument>
#include <QRegularExpression>

namespace localyze {

namespace {
QString systemPromptText(bool webEnabled) {
    QString s =
        "You are Localyze. For ANY arithmetic (4+ digit numbers, "
        "percentages, exponents, sqrt, compound interest, kinematics, "
        "conversions), call calc — never compute mentally.\n\n"
        "Tools (emit one per step, then wait for <tool_result>):\n"
        "  <tool name=\"calc\">{\"expr\":\"5000 * Math.pow(1.04, 5)\"}</tool>\n"
        "  <tool name=\"memory.search\">{\"query\":\"...\"}</tool>\n"
        "  <tool name=\"files.search\">{\"query\":\"...\"}</tool>\n";
    if (webEnabled)
        s += "  <tool name=\"web.search\">{\"query\":\"...\"}</tool>\n";
    s += "\nAfter tool results, finalize: <final>your answer</final>\n";

    // When the user has selected Research mode, splice the mode's full
    // OUTPUT-SHAPE system prompt onto the front of the tool-protocol
    // instructions. Without this, Research-mode prompts that happen to
    // match the agent-routing heuristic (containing "search", "what is
    // the current", "latest news", etc.) bypass SystemPromptBuilder
    // entirely and produce free-form bold/numbered output instead of
    // the mandatory `# / ## TL;DR / ## Key findings / …` report shape.
    // The agent's tool-protocol block stays at the END so the model
    // still sees the `<tool …>` schema as the active turn's protocol.
    const auto mode = ModeStore::instance().resolvedMode();
    if (mode == SystemPromptBuilder::Mode::Research) {
        const QString modePrompt =
            SystemPromptBuilder::build(mode, /*enableThinking=*/false);
        s = modePrompt + QStringLiteral("\n\n---\n\n") + s
          + QStringLiteral(
              "\nFINAL-ANSWER FORMAT (overrides the tool-protocol "
              "`<final>` shape for Research mode): when you write the "
              "`<final>...</final>` block, the content INSIDE it MUST "
              "be a full Markdown report in the OUTPUT SHAPE above — "
              "starting with `# <title>` and including every required "
              "`##` section. Do NOT collapse it into a single paragraph "
              "or a `**1. X**` bulleted list. If a web_search tool "
              "errored or returned no results, silently fall back to "
              "model knowledge and still produce the full report — "
              "never write \"Given the error in the web search\" or "
              "any other tool-failure narration in the final answer.\n");
    }
    return s;
}
}  // namespace

ReActAgent::ReActAgent(QObject* parent) : QObject(parent), tools_(this) {}

void ReActAgent::setBackend(LlamaCppBackend* backend) {
    // Defensive: disconnect any prior backend so we don't get duplicated
    // signals (which previously caused double-emit + reentry crashes when
    // setBackend was called twice).
    if (backend_ && backend_ != backend) {
        disconnect(backend_, nullptr, this, nullptr);
    }
    backend_ = backend;
    if (!backend_) return;
    // Persistent connections — the agent reacts to backend events as a state
    // machine on the GUI thread; no nested QEventLoop, no UI freeze.
    // Qt::UniqueConnection prevents duplicate slot invocations if setBackend
    // is called more than once with the same pointer.
    connect(backend_, &LlamaCppBackend::tokenStream, this, &ReActAgent::onBackendToken,    Qt::UniqueConnection);
    connect(backend_, &LlamaCppBackend::finished,    this, &ReActAgent::onBackendFinished, Qt::UniqueConnection);
    connect(backend_, &LlamaCppBackend::failed,      this, &ReActAgent::onBackendFailed,   Qt::UniqueConnection);
}

bool ReActAgent::webSearchEnabled() const { return SettingsStore::instance().webSearchEnabled(); }
void ReActAgent::setWebSearchEnabled(bool v) {
    SettingsStore::instance().setWebSearchEnabled(v);
    emit webSearchChanged();
}

void ReActAgent::stop() {
    if (!running_) return;
    // Flip the agent's running flag synchronously so the next user-initiated
    // run() can start immediately, and so the soon-to-arrive backend
    // finished() callback is ignored (it would otherwise emit a stale final).
    running_ = false;
    if (backend_) QMetaObject::invokeMethod(backend_, "stop", Qt::QueuedConnection);
    emit finished();
}

void ReActAgent::run(const QString& userPrompt) {
    if (running_) {
        // Surface "busy" state to the caller without disturbing the in-flight
        // turn (which still owns the upcoming `finished` emission).
        emit step("reason", "agent busy — ignoring new request");
        return;
    }
    if (!backend_) {
        emit step("final", "Backend not loaded.");
        emit finished();
        return;
    }

    try {
        const bool webOn = webSearchEnabled();
        conversation_ = "<start_of_turn>system\n" + systemPromptText(webOn) + "<end_of_turn>\n"
                      + "<start_of_turn>user\n"   + userPrompt              + "<end_of_turn>\n"
                      + "<start_of_turn>model\n";

        dispatch_.clear();
        for (const auto& t : tools_.build(webOn)) {
            if (t.name.isEmpty() || !t.call) continue;   // never insert a null callable
            dispatch_.insert(t.name, t.call);
        }

        currentStep_ = 0;
        running_     = true;
        startNextStep();
    } catch (const std::exception& e) {
        running_ = false;
        emit step("final", QString("[error] run() threw: ") + e.what());
        emit finished();
    } catch (...) {
        running_ = false;
        emit step("final", "[error] run() threw unknown exception");
        emit finished();
    }
}

void ReActAgent::startNextStep() {
    if (!running_) return;
    if (!backend_) {
        // Backend was torn out from under us mid-turn — fail closed.
        emitFinalAndStop("[error] backend disappeared mid-turn");
        return;
    }
    if (currentStep_ >= maxSteps_) {
        emitFinalAndStop("[hit max steps]");
        return;
    }
    stepBuffer_.clear();
    emit step("reason", QString("step %1").arg(currentStep_ + 1));
    // generateRaw — we already built the full Gemma chat template ourselves;
    // calling generate() would double-wrap and scramble the ReAct framing.
    // Research mode needs a higher cap so the mandatory `## Sources` section
    // isn't truncated mid-bullet; bump from 1024 → 2048 tokens when active.
    const int maxToks =
        (ModeStore::instance().resolvedMode() == SystemPromptBuilder::Mode::Research)
            ? 2048 : 1024;
    QMetaObject::invokeMethod(backend_, "generateRaw", Qt::QueuedConnection,
                              Q_ARG(QString, conversation_), Q_ARG(int, maxToks));
}

void ReActAgent::onBackendToken(QString token) {
    if (!running_) return;
    stepBuffer_ += token;
    emit tokenStream(token);
}

void ReActAgent::onBackendFinished() {
    if (!running_) return;

    // Did the model emit <final>?
    static const QRegularExpression finalRx(R"R(<final>(.*?)</final>)R",
        QRegularExpression::DotMatchesEverythingOption);
    if (auto m = finalRx.match(stepBuffer_); m.hasMatch()) {
        emitFinalAndStop(m.captured(1).trimmed());
        return;
    }

    // Did the model emit a tool call?
    static const QRegularExpression toolRx(R"R(<tool\s+name="([^"]+)"\s*>(.*?)</tool>)R",
        QRegularExpression::DotMatchesEverythingOption);
    auto m = toolRx.match(stepBuffer_);
    if (!m.hasMatch()) {
        // Plain text response with no tool call. Treat as final.
        emitFinalAndStop(stepBuffer_.trimmed());
        return;
    }

    const auto toolName = m.captured(1);
    const auto argsJson = m.captured(2).trimmed();
    const auto argsObj  = QJsonDocument::fromJson(argsJson.toUtf8()).object();
    emit step("act", QString("%1 %2").arg(toolName, argsJson));

    QJsonObject result;
    if (auto it = dispatch_.find(toolName); it != dispatch_.end() && it.value()) {
        try {
            result = it.value()(argsObj);
        } catch (const std::exception& e) {
            result = {{"error", QStringLiteral("tool '%1' threw: %2").arg(toolName, e.what())}};
        } catch (...) {
            result = {{"error", QStringLiteral("tool '%1' threw unknown exception").arg(toolName)}};
        }
    } else {
        result = {{"error", QStringLiteral("tool '%1' is not available").arg(toolName)}};
    }
    const auto resultJson = QString::fromUtf8(QJsonDocument(result).toJson(QJsonDocument::Compact));
    emit step("observe", resultJson);

    conversation_ += stepBuffer_
                   + "\n<tool_result name=\"" + toolName + "\">\n"
                   + resultJson
                   + "\n</tool_result>\n";
    ++currentStep_;
    startNextStep();
}

void ReActAgent::onBackendFailed(QString error) {
    if (!running_) return;
    emitFinalAndStop("[error] " + error);
}

void ReActAgent::emitFinalAndStop(const QString& text) {
    emit step("final", text);
    running_ = false;
    emit finished();
}

}  // namespace localyze
