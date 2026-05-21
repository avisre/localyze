#pragma once

#include <QCryptographicHash>
#include <QObject>
#include <QPointer>
#include <QString>
#include <QUrl>

QT_BEGIN_NAMESPACE
class QFile;
class QNetworkAccessManager;
class QNetworkReply;
QT_END_NAMESPACE

namespace localyze {

/**
 * Resumable, sha-verified model downloader for the Linux port. Mirrors the
 * Windows (ModelDownloader.cs) and macOS (ModelDownloader.swift) ports:
 *   - HTTP Range requests resume a partial .part file after a network drop
 *   - 64 KB streaming buffer; the body never lives in RAM
 *   - sha256 verified on completion; mismatch wipes the .part and surfaces error
 *   - Exposed to QML as Q_PROPERTYs (progress / downloaded / total / state /
 *     errorMessage) so OnboardingView.qml can drive a progress bar
 *
 * The single entry point is the Q_INVOKABLE start(url, destPath, sha256).
 * The previous version of this class hard-coded a manifest URL; QML/QML drivers
 * supply the URL directly now so test fleets and the manifest-resolver can
 * share the same downloader.
 */
class ModelDownloader : public QObject {
    Q_OBJECT
    Q_PROPERTY(double  progress     READ progress     NOTIFY progressChanged)
    Q_PROPERTY(qint64  downloaded   READ downloaded   NOTIFY progressChanged)
    Q_PROPERTY(qint64  total        READ total        NOTIFY progressChanged)
    Q_PROPERTY(State   state        READ state        NOTIFY stateChanged)
    Q_PROPERTY(QString errorMessage READ errorMessage NOTIFY stateChanged)

public:
    enum class State {
        Idle,
        Downloading,
        Verifying,
        Done,
        Error,
    };
    Q_ENUM(State)

    explicit ModelDownloader(QObject* parent = nullptr);
    ~ModelDownloader() override;

    /// Begin (or resume) downloading `url` to `destPath`. The file is streamed
    /// to `destPath + ".part"` and atomically renamed once sha256 matches the
    /// expected hex digest. Safe to call after a previous failure — it'll
    /// resume from the existing .part bytes. Calling while already downloading
    /// is a no-op.
    Q_INVOKABLE void start(QString url, QString destPath, QString sha256);

    /// Abort the in-flight download. The .part file is preserved so the next
    /// start() resumes from the same byte offset.
    Q_INVOKABLE void cancel();

    double  progress()     const;
    qint64  downloaded()   const { return downloaded_; }
    qint64  total()        const { return total_; }
    State   state()        const { return state_; }
    QString errorMessage() const { return errorMessage_; }

signals:
    void progressChanged();
    void stateChanged();
    void finished();
    void failed(QString error);

private slots:
    void onReadyRead();
    void onDownloadProgress(qint64 received, qint64 totalBytes);
    void onReplyFinished();

private:
    void setState(State s, const QString& errorMessage = {});
    void fail(const QString& message);
    void finishVerify();

    QNetworkAccessManager* nam_ = nullptr;
    QPointer<QNetworkReply> reply_;
    QFile*                  file_ = nullptr;
    QCryptographicHash      hasher_{QCryptographicHash::Sha256};

    QString destPath_;
    QString expectedSha256_;
    qint64  startOffset_  = 0;       // bytes already on disk before this start()
    qint64  downloaded_   = 0;       // total bytes confirmed on disk (incl. resume)
    qint64  total_        = 0;       // expected total bytes
    State   state_        = State::Idle;
    QString errorMessage_;
    // 64 KB streaming chunk per the spec; balances syscall cost and resume granularity.
    static constexpr int kChunkBytes = 64 * 1024;
};

}  // namespace localyze
