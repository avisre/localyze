#include "ModeStore.h"

#include <QDebug>
#include <QFile>
#include <QDateTime>

namespace localyze {

namespace {
constexpr auto kCurrentMode = "currentMode";
constexpr auto kDefault     = "chat";

bool isValidMode(const QString& v) {
    return v == QLatin1String("chat")        || v == QLatin1String("code")
        || v == QLatin1String("data")        || v == QLatin1String("write")
        || v == QLatin1String("brainstorm")  || v == QLatin1String("communication")
        || v == QLatin1String("research");
}
}  // namespace

ModeStore& ModeStore::instance() {
    static ModeStore s;
    return s;
}

ModeStore::ModeStore(QObject* parent)
    : QObject(parent),
      settings_(QSettings::IniFormat, QSettings::UserScope, "Localyze", "Localyze") {}

QString ModeStore::currentMode() const {
    const_cast<QSettings&>(settings_).sync();
    const auto v = settings_.value(kCurrentMode, QString::fromLatin1(kDefault)).toString();
    const auto result = isValidMode(v) ? v : QString::fromLatin1(kDefault);
    // Forensics: log every read so we can see what mode the app thinks it's in
    static QString last_logged;
    if (result != last_logged) {
        last_logged = result;
        QFile f("/tmp/qrepo/modestore_trace.log");
        if (f.open(QIODevice::WriteOnly | QIODevice::Append | QIODevice::Text)) {
            const auto now = QDateTime::currentDateTime().toString(Qt::ISODateWithMs);
            const auto fileName = settings_.fileName();
            QByteArray msg = (now + " currentMode() read=\"" + v + "\" from " + fileName + "\n").toUtf8();
            f.write(msg);
            f.close();
        }
    }
    return result;
}

void ModeStore::setCurrentMode(const QString& v) {
    // Forensics: log every call so we can find the autonomous flip-to-research bug
    QFile f("/tmp/qrepo/modestore_trace.log");
    if (f.open(QIODevice::WriteOnly | QIODevice::Append | QIODevice::Text)) {
        const auto now = QDateTime::currentDateTime().toString(Qt::ISODateWithMs);
        const auto cur = settings_.value(kCurrentMode, QString::fromLatin1(kDefault)).toString();
        QByteArray msg = (now + " setCurrentMode(\"" + v + "\")  current_was=\"" + cur + "\"\n").toUtf8();
        f.write(msg);
        f.close();
    }
    qInfo().noquote() << "[ModeStore] setCurrentMode(" << v << ") was=" << currentMode();
    if (!isValidMode(v)) return;
    if (v == currentMode()) return;
    settings_.setValue(kCurrentMode, v);
    emit currentModeChanged();
}

SystemPromptBuilder::Mode ModeStore::resolvedMode() const {
    const auto m = currentMode();
    using M = SystemPromptBuilder::Mode;
    if (m == QLatin1String("code"))          return M::Code;
    if (m == QLatin1String("data"))          return M::Data;
    if (m == QLatin1String("write"))         return M::Write;
    if (m == QLatin1String("brainstorm"))    return M::Brainstorm;
    if (m == QLatin1String("communication")) return M::Communication;
    if (m == QLatin1String("research"))      return M::Research;
    return M::Chat;
}

}  // namespace localyze
