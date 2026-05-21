#pragma once
#include <QObject>
#include <QSettings>

namespace localyze {

/// Tiny bridge to persist a "darkMode" flag in QSettings so the QML Theme
/// singleton can bind to it. Manual override wins over system-palette hints.
///
/// Tri-state via two properties:
///   - hasUserPreference: true if the user has explicitly chosen a theme
///   - darkMode: the current chosen value (or system default if no preference)
class ThemeBridge : public QObject {
    Q_OBJECT
    Q_PROPERTY(bool darkMode READ darkMode WRITE setDarkMode NOTIFY changed)
    Q_PROPERTY(bool hasUserPreference READ hasUserPreference NOTIFY changed)
public:
    explicit ThemeBridge(QObject* parent = nullptr);

    bool darkMode() const;
    void setDarkMode(bool v);

    bool hasUserPreference() const;

    /// Clear the user override so Theme falls back to the system palette hint.
    Q_INVOKABLE void clearPreference();

signals:
    void changed();

private:
    QSettings settings_;
};

}  // namespace localyze
