#pragma once
#include <QFileSystemWatcher>
#include <QObject>
#include <QString>

namespace localyze {

/// Reads prompts one at a time from /tmp/qrepo/inject.txt and emits them so
/// ChatView can render each as a normal user turn. Use case: parallel test
/// agents append lines to inject.txt; the running app picks them up in order
/// and drives the actual chat UI (no separate spectator). When `paced=true`
/// (default) the feeder only emits the next prompt after `markDone()` is
/// called — usually wired to ReActAgent::finished — so the app shows one
/// streaming answer at a time.
class PromptFeeder : public QObject {
    Q_OBJECT
    Q_PROPERTY(int  pending READ pending NOTIFY pendingChanged)
    Q_PROPERTY(int  sent    READ sent    NOTIFY sentChanged)
    Q_PROPERTY(bool active  READ active  NOTIFY activeChanged)
public:
    explicit PromptFeeder(QObject* parent = nullptr);

    int  pending() const { return queue_.size(); }
    int  sent()    const { return sent_; }
    bool active()  const { return active_; }

public slots:
    void start();        // begin watching + draining
    void stop();
    void markDone();     // call when the current prompt's answer finished
    void enqueueNow(const QString& prompt);  // for testing / manual feed
    /// Append the (prompt, answer) pair the running app just finished to
    /// /tmp/qrepo/answered.jsonl. ChatView calls this from `onStep("final")`
    /// so a post-run summary can read pass-rates without scraping the UI.
    Q_INVOKABLE void logAnswer(const QString& answer);

signals:
    void promptReady(QString prompt);    // ChatView simulates a user turn
    void pendingChanged();
    void sentChanged();
    void activeChanged();

private slots:
    void onFileChanged(const QString& path);

private:
    void drainFile();
    void emitNext();

    QFileSystemWatcher fsw_;
    QString  path_;
    qint64   offset_  = 0;
    QList<QString> queue_;
    bool     active_  = false;
    bool     busy_    = false;
    int      sent_    = 0;
    QString  currentPrompt_;   // the prompt the app is currently answering
};

}  // namespace localyze
