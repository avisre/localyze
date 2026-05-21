#pragma once

#include <QDateTime>
#include <QList>
#include <QObject>
#include <QString>
#include <QVariantList>
#include <QVariantMap>

namespace localyze {

/**
 * Singleton chat-history store, ported from the Android sibling's
 * ConversationRepository. Persists every chat as a JSON document under
 * QStandardPaths::AppDataLocation/conversations.json so users can resume
 * a previous chat from the drawer UI.
 *
 * Storage shape on disk:
 * {
 *   "currentId": "uuid-or-empty",
 *   "conversations": [
 *     { "id": "...", "title": "...", "ts": 1700000000000,
 *       "messages": [ {"role":"user","content":"..."}, ... ] },
 *     ...
 *   ]
 * }
 */
class ConversationStore : public QObject {
    Q_OBJECT
    Q_PROPERTY(QVariantList conversations READ conversations NOTIFY conversationsChanged)
    Q_PROPERTY(QString currentId READ currentId NOTIFY currentChanged)

public:
    static ConversationStore& instance();

    QVariantList conversations() const;
    QString currentId() const { return currentId_; }

    Q_INVOKABLE void newConversation();
    Q_INVOKABLE void switchTo(QString id);
    Q_INVOKABLE void deleteConversation(QString id);
    Q_INVOKABLE void appendMessage(QString role, QString content);
    Q_INVOKABLE void renameConversation(QString id, QString title);

signals:
    void conversationsChanged();
    void currentChanged();
    /// Emitted after switchTo loads a saved chat. `messages` is a list of
    /// {role, content} maps in send order — the ChatView re-populates its
    /// ListModel from this payload.
    void conversationLoaded(QVariantList messages);

private:
    explicit ConversationStore(QObject* parent = nullptr);

    struct Message {
        QString role;
        QString content;
    };
    struct Conv {
        QString id;
        QString title;
        qint64 ts = 0;          // last-modified, ms since epoch
        QList<Message> messages;
    };

    QString storagePath() const;
    void load();
    void save() const;
    int indexOf(const QString& id) const;
    void touch(Conv& c);
    static QString makeId();

    QList<Conv> convs_;
    QString currentId_;
};

}  // namespace localyze
