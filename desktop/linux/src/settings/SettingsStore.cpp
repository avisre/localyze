#include "SettingsStore.h"

#include <QCoreApplication>
#include <QStandardPaths>

namespace localyze {

namespace {
constexpr auto kWebSearch = "webSearchEnabled";
constexpr auto kSearxngUrl = "searxngUrl";
constexpr auto kModelPath  = "modelPath";
constexpr auto kModelDownloadUrl    = "modelDownloadUrl";
constexpr auto kModelDownloadSha256 = "modelDownloadSha256";
constexpr auto kContextSizeOverride = "contextSizeOverride";
constexpr auto kOnboarded  = "onboarded";
}

SettingsStore& SettingsStore::instance() {
    static SettingsStore s;
    return s;
}

SettingsStore::SettingsStore(QObject* parent)
    : QObject(parent),
      settings_(QSettings::IniFormat, QSettings::UserScope, "Localyze", "Localyze") {}

bool SettingsStore::webSearchEnabled() const {
    return settings_.value(kWebSearch, false).toBool();   // default OFF — privacy first
}

void SettingsStore::setWebSearchEnabled(bool v) {
    if (v == webSearchEnabled()) return;
    settings_.setValue(kWebSearch, v);
    emit webSearchChanged();
}

QString SettingsStore::searxngUrl() const {
    // searx.be returns HTTP 403 to anonymous JSON queries; searx.tiekoetter.com
    // permits them. Switched default so web.search works out-of-the-box.
    return settings_.value(kSearxngUrl, "https://searx.tiekoetter.com/search").toString();
}

void SettingsStore::setSearxngUrl(const QString& v) {
    if (v == searxngUrl()) return;
    settings_.setValue(kSearxngUrl, v);
    emit searxngUrlChanged();
}

QString SettingsStore::modelPath() const {
    const auto def = QStandardPaths::writableLocation(QStandardPaths::AppDataLocation)
                   + "/models/gemma-4-e4b-it-q4.gguf";
    return settings_.value(kModelPath, def).toString();
}

void SettingsStore::setModelPath(const QString& v) {
    if (v == modelPath()) return;
    settings_.setValue(kModelPath, v);
    emit modelPathChanged();
}

QString SettingsStore::modelDownloadUrl() const {
    // Placeholder — real URL is swapped in once the GGUF is mirrored to a CDN
    // with a fixed sha256. QML can override at runtime.
    constexpr auto kDefault =
        "https://huggingface.co/ggml-org/gemma-4-e4b-it-Q4_K_M-GGUF/resolve/main/gemma-4-e4b-it-q4.gguf";
    return settings_.value(kModelDownloadUrl, kDefault).toString();
}

void SettingsStore::setModelDownloadUrl(const QString& v) {
    if (v == modelDownloadUrl()) return;
    settings_.setValue(kModelDownloadUrl, v);
    emit modelDownloadUrlChanged();
}

QString SettingsStore::modelDownloadSha256() const {
    // 64 zeros = "no hash known yet". ModelDownloader will fail verification
    // until the real digest is wired in, which is the safe default.
    constexpr auto kDefault = "0000000000000000000000000000000000000000000000000000000000000000";
    return settings_.value(kModelDownloadSha256, kDefault).toString();
}

void SettingsStore::setModelDownloadSha256(const QString& v) {
    if (v == modelDownloadSha256()) return;
    settings_.setValue(kModelDownloadSha256, v);
    emit modelDownloadSha256Changed();
}

int SettingsStore::contextSizeOverride() const {
    return settings_.value(kContextSizeOverride, 0).toInt();   // 0 = auto
}

void SettingsStore::setContextSizeOverride(int v) {
    if (v == contextSizeOverride()) return;
    settings_.setValue(kContextSizeOverride, v);
    emit contextSizeOverrideChanged();
}

void SettingsStore::setRecommendedContextSize(int v) {
    if (v == recommendedContextSize_) return;
    recommendedContextSize_ = v;
    emit recommendedContextSizeChanged();
}

int SettingsStore::effectiveContextSize() const {
    const int o = contextSizeOverride();
    return o > 0 ? o : recommendedContextSize_;
}

bool SettingsStore::onboarded() const {
    return settings_.value(kOnboarded, false).toBool();   // default = false, show wizard
}

void SettingsStore::setOnboarded(bool v) {
    if (v == onboarded()) return;
    settings_.setValue(kOnboarded, v);
    emit onboardedChanged();
}

}  // namespace localyze
