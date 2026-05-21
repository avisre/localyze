#pragma once
#include <QJsonObject>
#include <QObject>
#include <QString>
#include <functional>

namespace localyze {

/// A tool is a name + a function that takes JSON args and returns a JSON result.
struct Tool {
    QString name;
    std::function<QJsonObject(const QJsonObject&)> call;
};

/// Builds the set of tools available given the current web-search toggle.
/// memory.search / files.search / calc / run / system.info are always there.
/// web.search is included only if includeWeb=true.
class Tools : public QObject {
    Q_OBJECT
public:
    explicit Tools(QObject* parent = nullptr);
    QList<Tool> build(bool includeWeb) const;

    static QJsonObject calc(const QJsonObject& args);
    static QJsonObject systemInfo(const QJsonObject& args);
    QJsonObject memorySearch(const QJsonObject& args) const;
    QJsonObject filesSearch(const QJsonObject& args) const;
    QJsonObject run(const QJsonObject& args) const;
    QJsonObject webSearch(const QJsonObject& args) const;
};

}  // namespace localyze
