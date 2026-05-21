#include "VizParser.h"
#include <QRegularExpression>

namespace localyze {

namespace {
const QHash<QString, VizKind> kKinds = {
    {"chart", VizKind::Chart}, {"table", VizKind::Table}, {"map", VizKind::Map},
    {"run", VizKind::Run}, {"code", VizKind::Code}, {"form", VizKind::Form},
    {"image", VizKind::Image}, {"pdf", VizKind::Pdf},
};

QHash<QString, QString> parseAttrs(const QString& s) {
    // Delimited raw string so the embedded )" in `[^"]*)"` doesn't terminate the literal.
    static const QRegularExpression rx(R"R((\w+)\s*=\s*(?:"([^"]*)"|'([^']*)'))R");
    QHash<QString, QString> out;
    auto it = rx.globalMatch(s);
    while (it.hasNext()) {
        auto m = it.next();
        out.insert(m.captured(1), m.captured(2).isNull() ? m.captured(3) : m.captured(2));
    }
    return out;
}
}  // namespace

QVector<VizBlock> VizParser::parse(const QString& text) {
    static const QRegularExpression rx(
        R"R(<viz\s+([^>]+?)(?:/>|>(.*?)</viz>))R",
        QRegularExpression::DotMatchesEverythingOption);

    QVector<VizBlock> out;
    auto it = rx.globalMatch(text);
    while (it.hasNext()) {
        auto m = it.next();
        const auto attrs = parseAttrs(m.captured(1));
        const auto type = attrs.value("type").toLower();
        const auto kindIt = kKinds.find(type);
        if (kindIt == kKinds.end()) continue;
        out.push_back({kindIt.value(), attrs, m.captured(2)});
    }
    return out;
}

}  // namespace localyze
