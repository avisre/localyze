#include "ThemeBridge.h"

namespace localyze {

namespace {
constexpr auto kDarkMode = "darkMode";
}

ThemeBridge::ThemeBridge(QObject* parent)
    : QObject(parent),
      settings_(QSettings::IniFormat, QSettings::UserScope, "Localyze", "Localyze") {}

bool ThemeBridge::darkMode() const {
    return settings_.value(kDarkMode, false).toBool();
}

void ThemeBridge::setDarkMode(bool v) {
    // Always write (even if value unchanged) so hasUserPreference becomes true.
    const bool wasPresent = settings_.contains(kDarkMode);
    const bool prev       = darkMode();
    settings_.setValue(kDarkMode, v);
    if (!wasPresent || prev != v) emit changed();
}

bool ThemeBridge::hasUserPreference() const {
    return settings_.contains(kDarkMode);
}

void ThemeBridge::clearPreference() {
    if (!settings_.contains(kDarkMode)) return;
    settings_.remove(kDarkMode);
    emit changed();
}

}  // namespace localyze
