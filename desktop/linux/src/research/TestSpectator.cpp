#include "TestSpectator.h"

#include <QFile>
#include <QFileInfo>
#include <QJsonDocument>
#include <QJsonObject>
#include <QTimer>

namespace localyze {

namespace {
constexpr auto kPath = "/tmp/qrepo/answered.jsonl";
}

TestSpectator::TestSpectator(QObject* parent) : QObject(parent), path_(kPath) {
    connect(&fsw_, &QFileSystemWatcher::fileChanged, this, &TestSpectator::onFileChanged);
}

void TestSpectator::start() {
    if (watching_) return;
    // Ensure the file exists so the watcher actually attaches.
    QFile f(path_);
    if (!f.exists()) f.open(QIODevice::WriteOnly | QIODevice::Append);
    f.close();
    fsw_.addPath(path_);
    offset_ = QFileInfo(path_).size();  // tail from current end — don't replay
    watching_ = true;
    emit watchingChanged();
}

void TestSpectator::stop() {
    if (!watching_) return;
    fsw_.removePath(path_);
    watching_ = false;
    emit watchingChanged();
}

void TestSpectator::replayAll() {
    offset_ = 0;
    count_ = 0;
    emit countChanged();
    emitFrom(offset_);
    if (!watching_) start();
}

void TestSpectator::onFileChanged(const QString& path) {
    // QFileSystemWatcher fires once and stops watching truncated/recreated
    // files. Re-add to be safe.
    if (!fsw_.files().contains(path)) fsw_.addPath(path);
    // Small debounce: file may be mid-write when notification fires.
    QTimer::singleShot(50, this, [this] { emitFrom(offset_); });
}

void TestSpectator::emitFrom(qint64& offset) {
    QFile f(path_);
    if (!f.open(QIODevice::ReadOnly)) return;
    if (offset > 0) f.seek(offset);
    while (!f.atEnd()) {
        const QByteArray line = f.readLine();
        offset += line.size();
        const auto trimmed = line.trimmed();
        if (trimmed.isEmpty()) continue;
        const auto doc = QJsonDocument::fromJson(trimmed);
        if (!doc.isObject()) continue;
        const auto o = doc.object();
        emit entry(
            o.value("id").toString(),
            o.value("tester").toString(),
            o.value("category").toString(),
            o.value("prompt").toString(),
            o.value("answer").toString(),
            o.value("grade").toString(),
            o.value("elapsed_s").toDouble()
        );
        ++count_;
    }
    f.close();
    emit countChanged();
}

}  // namespace localyze
