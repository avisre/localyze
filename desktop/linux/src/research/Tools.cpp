#include "Tools.h"
#include "../settings/SettingsStore.h"

#include <QCoreApplication>
#include <QDir>
#include <QEventLoop>
#include <QFile>
#include <QJSEngine>
#include <QJsonArray>
#include <QJsonDocument>
#include <QNetworkAccessManager>
#include <QNetworkReply>
#include <QNetworkRequest>
#include <QProcess>
#include <QRegularExpression>
#include <QStandardPaths>
#include <QSysInfo>
#include <QThread>
#include <QUrl>
#include <QUrlQuery>
#include <sys/sysinfo.h>
#include <cmath>

namespace localyze {

namespace {
constexpr double kGravityMs2 = 9.8;  // standard "g" used in kinematics prompts
}

Tools::Tools(QObject* parent) : QObject(parent) {}

QList<Tool> Tools::build(bool includeWeb) const {
    QList<Tool> out;
    out.append({"memory.search", [this](const QJsonObject& a) { return memorySearch(a); }});
    out.append({"files.search",  [this](const QJsonObject& a) { return filesSearch(a); }});
    out.append({"calc",          [](const QJsonObject& a) { return calc(a); }});
    out.append({"run",           [this](const QJsonObject& a) { return run(a); }});
    out.append({"system.info",   [](const QJsonObject& a) { return systemInfo(a); }});
    if (includeWeb) {
        out.append({"web.search", [this](const QJsonObject& a) { return webSearch(a); }});
    }
    return out;
}

QJsonObject Tools::calc(const QJsonObject& args) {
    const QString expr = args.value("expr").toString();
    if (expr.trimmed().isEmpty())
        return {{"error", QStringLiteral("calc: empty expression")}};
    // Cap the expression length defensively: pathological input has caused
    // QJSEngine to recurse deeply and blow the stack.
    if (expr.size() > 4096)
        return {{"error", QStringLiteral("calc: expression too long")}};
    try {
        QJSEngine engine;   // local, no globals, fresh per call — no shared state.
        auto result = engine.evaluate(expr);
        if (result.isError())
            return {{"error", "calc: " + result.toString()}};
        if (result.isNumber())
            return {{"value", result.toNumber()}};
        return {{"value", result.toString()}};
    } catch (const std::exception& e) {
        return {{"error", QString("calc threw: ") + e.what()}};
    } catch (...) {
        return {{"error", QStringLiteral("calc threw unknown exception")}};
    }
}

QJsonObject Tools::systemInfo(const QJsonObject&) {
    struct sysinfo si{};
    sysinfo(&si);
    QJsonObject o;
    o["os"]      = QSysInfo::prettyProductName();
    o["kernel"]  = QSysInfo::kernelVersion();
    o["arch"]    = QSysInfo::currentCpuArchitecture();
    o["ram_gb"]  = qint64(si.totalram / 1'000'000'000);
    o["free_gb"] = qint64(si.freeram  / 1'000'000'000);
    o["cores"]   = QThread::idealThreadCount();
    return o;
}

QJsonObject Tools::memorySearch(const QJsonObject& args) const {
    const auto query = args.value("query").toString();
    const auto limit = args.value("limit").toInt(5);
    const auto root  = QStandardPaths::writableLocation(QStandardPaths::GenericDataLocation)
                     + "/Localyze/memory";
    QDir d(root);
    QJsonArray hits;
    for (const auto& name : d.entryList(QStringList{"*.txt", "*.md"}, QDir::Files)) {
        if (hits.size() >= limit) break;
        QFile f(d.filePath(name));
        if (!f.open(QIODevice::ReadOnly | QIODevice::Text)) continue;
        const auto body = QString::fromUtf8(f.readAll());
        const int idx = body.indexOf(query, 0, Qt::CaseInsensitive);
        if (idx < 0) continue;
        QJsonObject hit;
        hit["id"]      = name;
        hit["snippet"] = body.mid(qMax(0, idx - 80), 200);
        hits.append(hit);
    }
    return {{"results", hits}};
}

QJsonObject Tools::filesSearch(const QJsonObject& args) const {
    const auto query = args.value("query").toString();
    const auto limit = args.value("limit").toInt(5);
    const auto root  = QDir::homePath() + "/Localyze/files";
    QDir d(root);
    QJsonArray hits;
    for (const auto& fi : d.entryInfoList(QDir::Files | QDir::Readable)) {
        if (hits.size() >= limit) break;
        QFile f(fi.absoluteFilePath());
        if (!f.open(QIODevice::ReadOnly)) continue;
        const auto body = QString::fromUtf8(f.read(64 * 1024));
        const int idx = body.indexOf(query, 0, Qt::CaseInsensitive);
        if (idx < 0) continue;
        QJsonObject hit;
        hit["path"]    = fi.absoluteFilePath();
        hit["snippet"] = body.mid(qMax(0, idx - 80), 200);
        hits.append(hit);
    }
    return {{"results", hits}};
}

QJsonObject Tools::run(const QJsonObject& args) const {
    const auto lang = args.value("lang").toString();
    const auto code = args.value("code").toString();
    QProcess p;
    p.setProcessChannelMode(QProcess::MergedChannels);

    QString program;
    QStringList qargs;
    if (lang == "python")          { program = "python3"; qargs << "-c" << code; }
    else if (lang == "javascript") { program = "node";    qargs << "-e" << code; }
    else if (lang == "shell")      { program = "bash";    qargs << "-c" << code; }
    else return {{"error", "unsupported lang: " + lang}};

    p.start(program, qargs);
    if (!p.waitForFinished(8000)) {
        p.kill();
        return {{"error", "execution timeout"}};
    }
    return {
        {"stdout",    QString::fromUtf8(p.readAllStandardOutput())},
        {"exit_code", p.exitCode()},
    };
}

QJsonObject Tools::webSearch(const QJsonObject& args) const {
    if (!SettingsStore::instance().webSearchEnabled())
        return {{"error", "web.search disabled"}};
    const auto query = args.value("query").toString();
    const auto n     = args.value("n").toInt(5);

    QUrl url(SettingsStore::instance().searxngUrl());
    QUrlQuery q;
    q.addQueryItem("q", query);
    q.addQueryItem("format", "json");
    url.setQuery(q);

    // The previous default (searx.be) returns HTTP 403 for anonymous JSON
    // queries. searx.tiekoetter.com allows anonymous /search?format=json.
    // Anonymous SearXNG instances also reject blank User-Agents, so set a
    // real one and accept JSON explicitly.
    QNetworkRequest request(url);
    request.setRawHeader("User-Agent", "Localyze/1.0 (desktop)");
    request.setRawHeader("Accept",     "application/json");

    QNetworkAccessManager nam;
    QEventLoop loop;
    auto* reply = nam.get(request);
    QObject::connect(reply, &QNetworkReply::finished, &loop, &QEventLoop::quit);
    loop.exec();

    if (reply->error() != QNetworkReply::NoError) {
        const auto err = reply->errorString();
        reply->deleteLater();
        return {{"error", "web.search: " + err}};
    }
    const auto doc = QJsonDocument::fromJson(reply->readAll());
    reply->deleteLater();

    QJsonArray hits;
    for (const auto& v : doc.object().value("results").toArray()) {
        if (hits.size() >= n) break;
        const auto o = v.toObject();
        QJsonObject hit;
        hit["title"]   = o.value("title").toString();
        hit["url"]     = o.value("url").toString();
        hit["snippet"] = o.value("content").toString();
        hits.append(hit);
    }
    return {{"results", hits}};
}

}  // namespace localyze
