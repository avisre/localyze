#pragma once
#include "SystemPromptBuilder.h"

#include <QObject>
#include <QSettings>
#include <QString>

namespace localyze {

/// Process-wide singleton holding the current Capability Mode. The QML
/// ModePicker writes this property; LlamaCppBackend reads it at the top of
/// every generate() turn so the model gets the right SystemPromptBuilder
/// system prompt without any restart or per-call wiring through the agent.
///
/// Values: "chat" | "code" | "data" | "write" | "brainstorm" | "communication" | "research".
/// Persisted via QSettings under Localyze/Localyze, key "currentMode".
class ModeStore : public QObject {
    Q_OBJECT
    Q_PROPERTY(QString currentMode READ currentMode WRITE setCurrentMode NOTIFY currentModeChanged)
public:
    static ModeStore& instance();

    QString currentMode() const;
    void setCurrentMode(const QString& v);

    /// Maps the string mode to the SystemPromptBuilder enum. Falls back to
    /// Mode::Chat for unknown values so a corrupt settings file never bricks
    /// the chat path.
    SystemPromptBuilder::Mode resolvedMode() const;

signals:
    void currentModeChanged();

private:
    explicit ModeStore(QObject* parent = nullptr);
    QSettings settings_;
};

}  // namespace localyze
