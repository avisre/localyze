#include "PromptFeeder.h"

#include <QDateTime>
#include <QFile>
#include <QFileInfo>
#include <QJsonDocument>
#include <QJsonObject>
#include <QSet>
#include <QTimer>

namespace localyze {

namespace {
constexpr auto kPath = "/tmp/qrepo/inject.txt";
}

PromptFeeder::PromptFeeder(QObject* parent) : QObject(parent), path_(kPath) {
    connect(&fsw_, &QFileSystemWatcher::fileChanged, this, &PromptFeeder::onFileChanged);
}

void PromptFeeder::start() {
    if (active_) return;
    QFile f(path_);
    if (!f.exists()) { f.open(QIODevice::WriteOnly | QIODevice::Append); f.close(); }
    fsw_.addPath(path_);
    offset_ = 0;
    drainFile();   // pick up anything already there

    // Skip prompts that are already in /tmp/qrepo/answered.jsonl. After an
    // app crash + restart this lets the feeder resume where the previous run
    // left off instead of burning GPU re-answering the first 150 prompts.
    QFile a("/tmp/qrepo/answered.jsonl");
    if (a.open(QIODevice::ReadOnly)) {
        QSet<QString> done;
        while (!a.atEnd()) {
            const auto line = a.readLine().trimmed();
            if (line.isEmpty()) continue;
            const auto doc = QJsonDocument::fromJson(line);
            if (!doc.isObject()) continue;
            const auto p = doc.object().value("prompt").toString().trimmed();
            if (!p.isEmpty()) done.insert(p);
        }
        a.close();
        if (!done.isEmpty()) {
            QList<QString> filtered;
            filtered.reserve(queue_.size());
            for (const auto& q : queue_) if (!done.contains(q.trimmed())) filtered.push_back(q);
            const int skipped = queue_.size() - filtered.size();
            queue_ = std::move(filtered);
            if (skipped > 0) emit pendingChanged();
        }
    }

    active_ = true;
    emit activeChanged();
    QTimer::singleShot(0, this, &PromptFeeder::emitNext);
}

void PromptFeeder::stop() {
    if (!active_) return;
    fsw_.removePath(path_);
    active_ = false;
    busy_ = false;
    emit activeChanged();
}

void PromptFeeder::markDone() {
    busy_ = false;
    // Yield the GUI thread for a beat before kicking the next turn. Calling
    // emitNext() synchronously from here was racing the just-completed decode
    // teardown in LlamaCppBackend and contributing to the back-to-back
    // segfault cluster — let Qt's event loop drain first.
    QTimer::singleShot(150, this, &PromptFeeder::emitNext);
}

void PromptFeeder::enqueueNow(const QString& prompt) {
    queue_.append(prompt);
    emit pendingChanged();
    emitNext();
}

void PromptFeeder::onFileChanged(const QString& path) {
    if (!fsw_.files().contains(path)) fsw_.addPath(path);
    QTimer::singleShot(50, this, [this] { drainFile(); emitNext(); });
}

void PromptFeeder::drainFile() {
    QFile f(path_);
    if (!f.open(QIODevice::ReadOnly)) return;
    if (offset_ > 0) f.seek(offset_);
    while (!f.atEnd()) {
        const QByteArray line = f.readLine();
        offset_ += line.size();
        const auto trimmed = QString::fromUtf8(line).trimmed();
        if (trimmed.isEmpty()) continue;
        queue_.append(trimmed);
    }
    f.close();
    emit pendingChanged();
}

void PromptFeeder::emitNext() {
    if (!active_ || busy_ || queue_.isEmpty()) return;
    const QString p = queue_.takeFirst();
    currentPrompt_ = p;
    busy_ = true;
    ++sent_;
    emit pendingChanged();
    emit sentChanged();
    // Delay the actual promptReady emission by 250 ms so the GUI thread has
    // time to settle between turns — the chat view's previous-turn finalize
    // work (scroll, persist, KV teardown signals) needs to complete before
    // we kick the next inference. The queue is still drained serially because
    // `busy_` is already true for the entire duration of this prompt's run.
    QTimer::singleShot(250, this, [this, p] { emit promptReady(p); });
}

void PromptFeeder::logAnswer(const QString& answer) {
    if (currentPrompt_.isEmpty()) return;
    QFile f("/tmp/qrepo/answered.jsonl");
    if (!f.open(QIODevice::WriteOnly | QIODevice::Append | QIODevice::Text)) return;
    QJsonObject obj;
    obj["ts"]     = QDateTime::currentDateTime().toString(Qt::ISODate);
    obj["prompt"] = currentPrompt_;
    obj["answer"] = answer;
    obj["via"]    = "live-app";
    const auto line = QJsonDocument(obj).toJson(QJsonDocument::Compact);
    f.write(line);
    f.write("\n");
    f.close();
    currentPrompt_.clear();
}

}  // namespace localyze
