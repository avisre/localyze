#pragma once
#include <QHash>
#include <QJsonObject>
#include <QObject>
#include <QString>
#include <functional>
#include "Tools.h"

namespace localyze {

class LlamaCppBackend;

class ReActAgent : public QObject {
    Q_OBJECT
    Q_PROPERTY(bool webSearchEnabled READ webSearchEnabled WRITE setWebSearchEnabled NOTIFY webSearchChanged)

public:
    explicit ReActAgent(QObject* parent = nullptr);
    ~ReActAgent() override = default;

    void setBackend(LlamaCppBackend* backend);

    bool webSearchEnabled() const;
    void setWebSearchEnabled(bool v);

public slots:
    void run(const QString& userPrompt);
    void stop();

signals:
    void step(QString kind, QString text);   // {reason, act, observe, final}
    void tokenStream(QString token);
    void finished();
    void webSearchChanged();

private slots:
    void onBackendFinished();
    void onBackendFailed(QString error);
    void onBackendToken(QString token);

private:
    void startNextStep();
    void emitFinalAndStop(const QString& text);

    LlamaCppBackend* backend_ = nullptr;
    Tools tools_;

    // ReAct state, persisted across the model's async completions so we can
    // run on the GUI thread without a nested QEventLoop.
    QHash<QString, std::function<QJsonObject(const QJsonObject&)>> dispatch_;
    QString conversation_;
    QString stepBuffer_;
    int     currentStep_ = 0;
    int     maxSteps_    = 12;
    bool    running_     = false;
};

}  // namespace localyze
