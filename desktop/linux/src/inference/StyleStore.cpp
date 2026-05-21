#include "StyleStore.h"

namespace localyze {

namespace {
constexpr auto kCurrentStyle = "currentStyle";
constexpr auto kDefault      = "default";

bool isValidStyle(const QString& v) {
    return v == QLatin1String("default")
        || v == QLatin1String("concise")
        || v == QLatin1String("explanatory")
        || v == QLatin1String("formal")
        || v == QLatin1String("creative");
}
}  // namespace

StyleStore& StyleStore::instance() {
    static StyleStore s;
    return s;
}

StyleStore::StyleStore(QObject* parent)
    : QObject(parent),
      settings_(QSettings::IniFormat, QSettings::UserScope, "Localyze", "Localyze") {}

QString StyleStore::currentStyle() const {
    const auto v = settings_.value(kCurrentStyle, QString::fromLatin1(kDefault)).toString();
    return isValidStyle(v) ? v : QString::fromLatin1(kDefault);
}

void StyleStore::setCurrentStyle(const QString& v) {
    if (!isValidStyle(v)) return;
    if (v == currentStyle()) return;
    settings_.setValue(kCurrentStyle, v);
    emit currentStyleChanged();
}

QString StyleStore::styleAddendum() const {
    const auto s = currentStyle();
    if (s == QLatin1String("concise")) {
        return QStringLiteral("\nSTYLE: Be concise. Maximum 3 sentences unless the question requires more. No preamble.");
    }
    if (s == QLatin1String("explanatory")) {
        return QStringLiteral("\nSTYLE: Explain clearly with worked examples. Use short paragraphs and bullets. Define jargon.");
    }
    if (s == QLatin1String("formal")) {
        return QStringLiteral("\nSTYLE: Formal register. Full sentences. No contractions. No emojis.");
    }
    if (s == QLatin1String("creative")) {
        return QStringLiteral("\nSTYLE: Creative voice. Vivid language and metaphors. Add a memorable opening line.");
    }
    return QString();
}

}  // namespace localyze
