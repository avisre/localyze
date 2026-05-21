#pragma once

#include <QObject>
#include <QSettings>
#include <QString>

namespace localyze {

/// Process-wide singleton holding the current response Style. The QML
/// StylePicker writes this property; LlamaCppBackend reads styleAddendum()
/// at the top of every generate() turn and appends it to the system prompt
/// so the model adjusts tone/length without disturbing the per-mode prompt.
///
/// Values: "default" | "concise" | "explanatory" | "formal" | "creative".
/// Persisted via QSettings under Localyze/Localyze, key "currentStyle".
class StyleStore : public QObject {
    Q_OBJECT
    Q_PROPERTY(QString currentStyle READ currentStyle WRITE setCurrentStyle NOTIFY currentStyleChanged)
public:
    static StyleStore& instance();

    QString currentStyle() const;
    void setCurrentStyle(const QString& v);

    /// Returns the one-line addendum to append to the system prompt for the
    /// current style. Empty for "default" so the baseline prompt is unchanged.
    Q_INVOKABLE QString styleAddendum() const;

signals:
    void currentStyleChanged();

private:
    explicit StyleStore(QObject* parent = nullptr);
    QSettings settings_;
};

}  // namespace localyze
