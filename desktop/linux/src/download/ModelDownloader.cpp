#include "ModelDownloader.h"

#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QNetworkAccessManager>
#include <QNetworkReply>
#include <QNetworkRequest>
#include <QVariant>

namespace localyze {

ModelDownloader::ModelDownloader(QObject* parent)
    : QObject(parent), nam_(new QNetworkAccessManager(this)) {}

ModelDownloader::~ModelDownloader() {
    // Reply and file are auto-cleaned by Qt parentage / our finish path, but if
    // we're destroyed mid-flight we still want the FD closed so the .part file
    // is flushed for the next resume.
    if (file_) {
        file_->close();
        file_->deleteLater();
        file_ = nullptr;
    }
}

double ModelDownloader::progress() const {
    if (total_ <= 0) return 0.0;
    const double r = static_cast<double>(downloaded_) / static_cast<double>(total_);
    return r < 0.0 ? 0.0 : (r > 1.0 ? 1.0 : r);
}

void ModelDownloader::setState(State s, const QString& errorMessage) {
    if (s == state_ && errorMessage == errorMessage_) return;
    state_ = s;
    errorMessage_ = errorMessage;
    emit stateChanged();
}

void ModelDownloader::fail(const QString& message) {
    if (file_) {
        file_->close();
        file_->deleteLater();
        file_ = nullptr;
    }
    if (reply_) {
        reply_->disconnect(this);
        reply_->abort();
        reply_->deleteLater();
        reply_.clear();
    }
    setState(State::Error, message);
    emit failed(message);
}

void ModelDownloader::start(QString url, QString destPath, QString sha256) {
    // Idempotency: if a download is already in flight, ignore. QML can call
    // start() on every onClicked without worrying about double-fire.
    if (state_ == State::Downloading || state_ == State::Verifying) return;

    destPath_       = destPath;
    expectedSha256_ = sha256.toLower();
    errorMessage_.clear();
    hasher_.reset();

    // Ensure the destination directory exists. SettingsStore.modelPath defaults
    // to ~/.local/share/Localyze/Localyze/models/... which won't exist on a
    // fresh install.
    QDir().mkpath(QFileInfo(destPath_).absolutePath());

    const QString partPath = destPath_ + ".part";

    // Fast path: file already on disk and verified. We still emit a single
    // progress tick + finished() so QML observers see a consistent flow.
    if (QFileInfo::exists(destPath_)) {
        QFile existing(destPath_);
        if (existing.open(QIODevice::ReadOnly)) {
            QCryptographicHash h(QCryptographicHash::Sha256);
            if (h.addData(&existing)
                && h.result().toHex().toLower() == expectedSha256_.toUtf8()) {
                existing.close();
                const qint64 size = QFileInfo(destPath_).size();
                downloaded_ = size;
                total_      = size;
                emit progressChanged();
                setState(State::Done);
                emit finished();
                return;
            }
        }
    }

    // Resume support: if a partial download exists, send Range: bytes=N-.
    // We also pre-feed those bytes into the SHA hasher so the running digest
    // covers the whole file by the time the reply finishes.
    startOffset_ = 0;
    if (QFileInfo::exists(partPath)) {
        QFile prev(partPath);
        if (prev.open(QIODevice::ReadOnly)) {
            QByteArray chunk;
            chunk.resize(kChunkBytes);
            while (true) {
                const qint64 n = prev.read(chunk.data(), kChunkBytes);
                if (n <= 0) break;
                hasher_.addData(QByteArray(chunk.constData(), static_cast<int>(n)));
                startOffset_ += n;
            }
            prev.close();
        }
    }
    downloaded_ = startOffset_;
    total_      = 0;  // filled in by the first downloadProgress signal

    QNetworkRequest req{QUrl(url)};
    if (startOffset_ > 0) {
        req.setRawHeader("Range",
                         QByteArray("bytes=") + QByteArray::number(startOffset_) + "-");
    }
    // Some CDNs (Cloudflare) demand a UA on range requests; default Qt UA is
    // empty, which trips a 403 on certain edge configs. Match the macOS port's
    // implicit URLSession UA shape.
    req.setRawHeader("User-Agent", "Localyze/1.0 (Linux)");
    req.setAttribute(QNetworkRequest::RedirectPolicyAttribute,
                     QNetworkRequest::NoLessSafeRedirectPolicy);

    file_ = new QFile(partPath, this);
    if (!file_->open(QIODevice::Append)) {
        const QString err = QStringLiteral("cannot open %1 for writing: %2")
                                .arg(partPath, file_->errorString());
        file_->deleteLater();
        file_ = nullptr;
        fail(err);
        return;
    }

    reply_ = nam_->get(req);
    connect(reply_.data(), &QNetworkReply::readyRead,
            this, &ModelDownloader::onReadyRead);
    connect(reply_.data(), &QNetworkReply::downloadProgress,
            this, &ModelDownloader::onDownloadProgress);
    connect(reply_.data(), &QNetworkReply::finished,
            this, &ModelDownloader::onReplyFinished);

    setState(State::Downloading);
    emit progressChanged();
}

void ModelDownloader::cancel() {
    if (!reply_) return;
    reply_->disconnect(this);
    reply_->abort();
    reply_->deleteLater();
    reply_.clear();
    if (file_) {
        file_->close();
        file_->deleteLater();
        file_ = nullptr;
    }
    // .part is preserved deliberately so the next start() resumes.
    setState(State::Idle);
}

void ModelDownloader::onReadyRead() {
    if (!reply_ || !file_) return;
    // Drain in 64 KB slices so we never let the body balloon in memory.
    QByteArray chunk;
    chunk.resize(kChunkBytes);
    while (reply_->bytesAvailable() > 0) {
        const qint64 n = reply_->read(chunk.data(), kChunkBytes);
        if (n <= 0) break;
        const QByteArray slice(chunk.constData(), static_cast<int>(n));
        if (file_->write(slice) != n) {
            fail(QStringLiteral("write failed: %1").arg(file_->errorString()));
            return;
        }
        hasher_.addData(slice);
        downloaded_ += n;
    }
    emit progressChanged();
}

void ModelDownloader::onDownloadProgress(qint64 /*received*/, qint64 totalBytes) {
    // totalBytes from QNetworkReply is the size of *this* range, so add the
    // resume offset to surface a true total. Some servers return -1 when the
    // Content-Length is unknown; treat that as "we don't know yet" so the QML
    // progress bar stays indeterminate (progress() clamps to 0..1).
    if (totalBytes > 0) {
        const qint64 newTotal = startOffset_ + totalBytes;
        if (newTotal != total_) {
            total_ = newTotal;
            emit progressChanged();
        }
    }
}

void ModelDownloader::onReplyFinished() {
    if (!reply_) return;
    const auto err = reply_->error();
    const auto errStr = reply_->errorString();

    // Drain anything still buffered before we close — readyRead may have been
    // edge-triggered and missed the final bytes when finished() fired first.
    onReadyRead();

    reply_->deleteLater();
    reply_.clear();

    if (err != QNetworkReply::NoError) {
        if (file_) {
            file_->close();
            file_->deleteLater();
            file_ = nullptr;
        }
        fail(QStringLiteral("download failed: %1").arg(errStr));
        return;
    }

    if (file_) {
        if (!file_->flush()) {
            const QString fe = file_->errorString();
            file_->close();
            file_->deleteLater();
            file_ = nullptr;
            fail(QStringLiteral("flush failed: %1").arg(fe));
            return;
        }
        file_->close();
        file_->deleteLater();
        file_ = nullptr;
    }

    setState(State::Verifying);
    finishVerify();
}

void ModelDownloader::finishVerify() {
    const QString partPath = destPath_ + ".part";
    const QByteArray digest = hasher_.result().toHex().toLower();

    if (digest != expectedSha256_.toUtf8()) {
        QFile::remove(partPath);
        fail(QStringLiteral("sha256 mismatch (got %1, want %2)")
                 .arg(QString::fromUtf8(digest), expectedSha256_));
        return;
    }

    // Atomic-ish rename. QFile::rename refuses to overwrite, so if a stale
    // final file exists (e.g. corrupt prior run), drop it first.
    if (QFileInfo::exists(destPath_)) QFile::remove(destPath_);
    if (!QFile::rename(partPath, destPath_)) {
        fail(QStringLiteral("rename %1 -> %2 failed").arg(partPath, destPath_));
        return;
    }

    total_      = QFileInfo(destPath_).size();
    downloaded_ = total_;
    emit progressChanged();
    setState(State::Done);
    emit finished();
}

}  // namespace localyze
