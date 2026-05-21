#pragma once
#include <QHash>
#include <QString>
#include <QVector>

namespace localyze {

enum class VizKind { Chart, Table, Map, Run, Code, Form, Image, Pdf };

struct VizBlock {
    VizKind kind;
    QHash<QString, QString> attrs;
    QString inner;
};

struct VizParser {
    static QVector<VizBlock> parse(const QString& text);
};

}  // namespace localyze
