#pragma once
#include <QObject>
#include <QSettings>
#include <QString>

namespace localyze {

class SettingsStore : public QObject {
    Q_OBJECT
    Q_PROPERTY(bool webSearchEnabled READ webSearchEnabled WRITE setWebSearchEnabled NOTIFY webSearchChanged)
    Q_PROPERTY(QString searxngUrl   READ searxngUrl       WRITE setSearxngUrl       NOTIFY searxngUrlChanged)
    Q_PROPERTY(QString modelPath    READ modelPath        WRITE setModelPath        NOTIFY modelPathChanged)
    Q_PROPERTY(QString modelDownloadUrl    READ modelDownloadUrl    WRITE setModelDownloadUrl    NOTIFY modelDownloadUrlChanged)
    Q_PROPERTY(QString modelDownloadSha256 READ modelDownloadSha256 WRITE setModelDownloadSha256 NOTIFY modelDownloadSha256Changed)
    Q_PROPERTY(int contextSizeOverride READ contextSizeOverride WRITE setContextSizeOverride NOTIFY contextSizeOverrideChanged)
    Q_PROPERTY(int recommendedContextSize MEMBER recommendedContextSize_ NOTIFY recommendedContextSizeChanged)
    Q_PROPERTY(bool onboarded       READ onboarded            WRITE setOnboarded        NOTIFY onboardedChanged)
public:
    static SettingsStore& instance();

    bool webSearchEnabled() const;
    void setWebSearchEnabled(bool v);

    QString searxngUrl() const;
    void setSearxngUrl(const QString& v);

    /// Where the GGUF model lives on disk.
    QString modelPath() const;
    void setModelPath(const QString& v);

    /// URL the onboarding "Download model" button hits. Placeholder by default;
    /// QML can override it at runtime (test harnesses, manifest-resolver, etc.).
    QString modelDownloadUrl() const;
    void setModelDownloadUrl(const QString& v);

    /// Expected sha256 hex digest of the downloaded GGUF. Placeholder by default
    /// (sixty-four zeros) — ModelDownloader will fail verification on mismatch,
    /// which is the safe default until the real hash is wired in.
    QString modelDownloadSha256() const;
    void setModelDownloadSha256(const QString& v);

    /// User-chosen context length in tokens. 0 = "use recommended". The
    /// hardware-aware default lives in [recommendedContextSize].
    int contextSizeOverride() const;
    void setContextSizeOverride(int v);

    /// Set the recommendedContextSize at startup based on detected VRAM/RAM
    /// so the UI can show "Auto (8192)" etc. without recomputing.
    void setRecommendedContextSize(int v);

    /// Effective context size = override if set, else recommended.
    int effectiveContextSize() const;

    /// Whether the user has finished the first-launch onboarding wizard.
    bool onboarded() const;
    void setOnboarded(bool v);

signals:
    void webSearchChanged();
    void searxngUrlChanged();
    void modelPathChanged();
    void modelDownloadUrlChanged();
    void modelDownloadSha256Changed();
    void contextSizeOverrideChanged();
    void recommendedContextSizeChanged();
    void onboardedChanged();

private:
    explicit SettingsStore(QObject* parent = nullptr);
    QSettings settings_;
    int recommendedContextSize_ = 4096;
};

}  // namespace localyze
