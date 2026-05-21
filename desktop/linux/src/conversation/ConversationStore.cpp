#include "ConversationStore.h"

#include <QDateTime>
#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QSaveFile>
#include <QStandardPaths>
#include <QUuid>
#include <QVariantMap>
#include <algorithm>

namespace localyze {

ConversationStore& ConversationStore::instance() {
    static ConversationStore s;
    return s;
}

ConversationStore::ConversationStore(QObject* parent) : QObject(parent) {
    load();
}

QString ConversationStore::storagePath() const {
    const QString dir = QStandardPaths::writableLocation(QStandardPaths::AppDataLocation);
    QDir().mkpath(dir);
    return dir + "/conversations.json";
}

QString ConversationStore::makeId() {
    return QUuid::createUuid().toString(QUuid::WithoutBraces);
}

int ConversationStore::indexOf(const QString& id) const {
    for (int i = 0; i < convs_.size(); ++i) {
        if (convs_[i].id == id) return i;
    }
    return -1;
}

void ConversationStore::touch(Conv& c) {
    c.ts = QDateTime::currentMSecsSinceEpoch();
}

void ConversationStore::load() {
    QFile f(storagePath());
    if (!f.exists() || !f.open(QIODevice::ReadOnly)) return;
    const auto bytes = f.readAll();
    f.close();
    QJsonParseError err{};
    const auto doc = QJsonDocument::fromJson(bytes, &err);
    if (err.error != QJsonParseError::NoError || !doc.isObject()) return;
    const auto root = doc.object();
    currentId_ = root.value("currentId").toString();
    const auto arr = root.value("conversations").toArray();
    convs_.clear();
    convs_.reserve(arr.size());
    for (const auto& v : arr) {
        const auto o = v.toObject();
        Conv c;
        c.id = o.value("id").toString();
        c.title = o.value("title").toString();
        c.ts = static_cast<qint64>(o.value("ts").toDouble());
        const auto msgs = o.value("messages").toArray();
        for (const auto& mv : msgs) {
            const auto mo = mv.toObject();
            Message m;
            m.role = mo.value("role").toString();
            m.content = mo.value("content").toString();
            c.messages.append(m);
        }
        if (!c.id.isEmpty()) convs_.append(std::move(c));
    }
    // Defensive: if currentId points to a deleted conv, clear it.
    if (!currentId_.isEmpty() && indexOf(currentId_) < 0) currentId_.clear();
}

void ConversationStore::save() const {
    QJsonArray arr;
    for (const auto& c : convs_) {
        QJsonObject o;
        o.insert("id", c.id);
        o.insert("title", c.title);
        o.insert("ts", static_cast<double>(c.ts));
        QJsonArray msgs;
        for (const auto& m : c.messages) {
            QJsonObject mo;
            mo.insert("role", m.role);
            mo.insert("content", m.content);
            msgs.append(mo);
        }
        o.insert("messages", msgs);
        arr.append(o);
    }
    QJsonObject root;
    root.insert("currentId", currentId_);
    root.insert("conversations", arr);

    QSaveFile f(storagePath());
    if (!f.open(QIODevice::WriteOnly | QIODevice::Truncate)) return;
    f.write(QJsonDocument(root).toJson(QJsonDocument::Indented));
    f.commit();
}

QVariantList ConversationStore::conversations() const {
    // Build a newest-first view with the lightweight fields the drawer needs.
    QList<int> order;
    order.reserve(convs_.size());
    for (int i = 0; i < convs_.size(); ++i) order.append(i);
    std::sort(order.begin(), order.end(),
              [this](int a, int b) { return convs_[a].ts > convs_[b].ts; });

    QVariantList out;
    out.reserve(convs_.size());
    for (int i : order) {
        const auto& c = convs_[i];
        QVariantMap m;
        m.insert("id", c.id);
        m.insert("title", c.title.isEmpty() ? QStringLiteral("New chat") : c.title);
        m.insert("ts", c.ts);
        m.insert("count", c.messages.size());
        QString preview;
        if (!c.messages.isEmpty()) {
            preview = c.messages.last().content;
            preview = preview.left(120).replace('\n', QLatin1Char(' '));
        }
        m.insert("preview", preview);
        out.append(m);
    }
    return out;
}

void ConversationStore::newConversation() {
    Conv c;
    c.id = makeId();
    c.title.clear();   // auto-titled from first user message
    touch(c);
    convs_.prepend(c);
    currentId_ = c.id;
    save();
    emit conversationsChanged();
    emit currentChanged();
}

void ConversationStore::switchTo(QString id) {
    const int idx = indexOf(id);
    if (idx < 0) return;
    currentId_ = id;
    save();
    emit currentChanged();
    // Replay messages so the chat view can rebuild its model.
    QVariantList payload;
    payload.reserve(convs_[idx].messages.size());
    for (const auto& m : convs_[idx].messages) {
        QVariantMap mm;
        mm.insert("role", m.role);
        mm.insert("content", m.content);
        payload.append(mm);
    }
    emit conversationLoaded(payload);
}

void ConversationStore::deleteConversation(QString id) {
    const int idx = indexOf(id);
    if (idx < 0) return;
    const bool wasCurrent = (currentId_ == id);
    convs_.removeAt(idx);
    if (wasCurrent) {
        currentId_.clear();
        emit currentChanged();
    }
    save();
    emit conversationsChanged();
}

void ConversationStore::appendMessage(QString role, QString content) {
    // Auto-create a conversation if none is current — covers the case where a
    // user starts typing before clicking "New chat".
    if (currentId_.isEmpty()) {
        Conv c;
        c.id = makeId();
        touch(c);
        convs_.prepend(c);
        currentId_ = c.id;
        emit currentChanged();
    }
    const int idx = indexOf(currentId_);
    if (idx < 0) return;
    auto& c = convs_[idx];
    Message m{std::move(role), std::move(content)};
    // Auto-title from the first user message, capped at 40 chars.
    if (c.title.isEmpty() && m.role == QLatin1String("user")) {
        QString t = m.content.trimmed();
        t.replace('\n', QLatin1Char(' '));
        if (t.size() > 40) t = t.left(40).trimmed() + QStringLiteral("…");
        c.title = t.isEmpty() ? QStringLiteral("New chat") : t;
    }
    c.messages.append(std::move(m));
    touch(c);
    save();
    emit conversationsChanged();
}

void ConversationStore::renameConversation(QString id, QString title) {
    const int idx = indexOf(id);
    if (idx < 0) return;
    convs_[idx].title = std::move(title);
    save();
    emit conversationsChanged();
}

}  // namespace localyze
