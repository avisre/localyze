#pragma once
#include <QFileSystemWatcher>
#include <QObject>
#include <QString>

namespace localyze {

/// Tails /tmp/qrepo/answered.jsonl and emits one signal per new entry. QML
/// renders these into a live "Test Spectator" view so the user can watch the
/// parallel tester agents in real time. Implementation: file-system watcher
/// keeps a byte offset and reads only the new tail of the JSONL on each
/// change notification.
class TestSpectator : public QObject {
    Q_OBJECT
    Q_PROPERTY(bool watching READ watching NOTIFY watchingChanged)
    Q_PROPERTY(int  count    READ count    NOTIFY countChanged)
public:
    explicit TestSpectator(QObject* parent = nullptr);

    bool watching() const { return watching_; }
    int  count()    const { return count_; }

public slots:
    void start();          // begin tailing from current EOF + replay any tail
    void stop();
    void replayAll();      // emit every entry from the start (used on first show)

signals:
    void entry(QString id, QString tester, QString category,
               QString prompt, QString answer, QString grade, double elapsed);
    void watchingChanged();
    void countChanged();

private slots:
    void onFileChanged(const QString& path);

private:
    void emitFrom(qint64& offset);

    QFileSystemWatcher fsw_;
    QString  path_;
    qint64   offset_   = 0;   // bytes already emitted
    bool     watching_ = false;
    int      count_    = 0;
};

}  // namespace localyze
