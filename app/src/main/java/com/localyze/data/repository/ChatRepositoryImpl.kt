package com.localyze.data.repository

import com.localyze.data.local.AppDatabase
import com.localyze.data.local.ConversationDao
import com.localyze.data.local.MessageDao
import com.localyze.domain.models.Conversation
import com.localyze.domain.models.Message
import com.localyze.domain.models.MessageRole
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChatRepositoryImpl @Inject constructor(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val appDatabase: AppDatabase
) : ChatRepository {

    // â”€â”€ Conversations â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    override fun getAllConversations(): Flow<List<Conversation>> {
        return conversationDao.getAllConversations()
    }

    override suspend fun getConversation(id: Long): Conversation? {
        return conversationDao.getConversationById(id)
    }

    override suspend fun createConversation(capabilityMode: String): Conversation {
        val conversation = Conversation(
            title = "New Chat",
            capabilityMode = capabilityMode,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        val id = conversationDao.insert(conversation)
        return conversation.copy(id = id)
    }

    override suspend fun updateConversation(conversation: Conversation) {
        conversationDao.update(conversation)
    }

    override suspend fun archiveConversation(id: Long, archived: Boolean) {
        conversationDao.updateArchivedStatus(id, archived)
    }

    override suspend fun favoriteConversation(id: Long, favorite: Boolean) {
        conversationDao.updateFavoriteStatus(id, favorite)
    }

    override suspend fun updateConversationFolder(id: Long, folder: String) {
        conversationDao.updateFolder(id, folder.trim())
    }

    override suspend fun deleteConversation(id: Long) {
        messageDao.deleteByConversationId(id)
        conversationDao.deleteById(id)
    }

    override suspend fun deleteConversations(ids: Collection<Long>) {
        ids.forEach { id -> deleteConversation(id) }
    }

    override suspend fun archiveConversations(ids: Collection<Long>, archived: Boolean) {
        ids.forEach { id -> conversationDao.updateArchivedStatus(id, archived) }
    }

    override fun searchConversations(query: String): Flow<List<Conversation>> {
        return conversationDao.searchConversations(query)
    }

    override fun getFolders(): Flow<List<String>> {
        return conversationDao.getFolders()
    }

    // â”€â”€ Messages â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    override fun getMessagesForConversation(conversationId: Long): Flow<List<Message>> {
        return messageDao.getMessagesForConversation(conversationId)
    }

    override suspend fun getMessage(id: Long): Message? {
        return messageDao.getMessageById(id)
    }

    override suspend fun saveMessage(message: Message): Message {
        val id = messageDao.insert(message)
        refreshConversationMetadata(message.conversationId)
        return message.copy(id = id)
    }

    override suspend fun updateMessage(message: Message) {
        messageDao.update(message)
        refreshConversationMetadata(message.conversationId)
    }

    override suspend fun deleteMessage(id: Long) {
        val message = messageDao.getMessageById(id)
        messageDao.deleteById(id)
        if (message != null) {
            refreshConversationMetadata(message.conversationId)
        }
    }

    override suspend fun getRecentMessages(conversationId: Long, limit: Int): List<Message> {
        return messageDao.getRecentMessages(conversationId, limit)
    }

    private suspend fun refreshConversationMetadata(conversationId: Long) {
        val messageCount = messageDao.getMessageCount(conversationId)
        conversationDao.updateTimestampAndCount(
            id = conversationId,
            updatedAt = System.currentTimeMillis(),
            messageCount = messageCount
        )
    }

    // â”€â”€ Additional methods â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /** Search messages by content using LIKE-based fallback. */
    override fun searchMessages(query: String): Flow<List<Message>> {
        return messageDao.searchMessagesSimple(query)
    }

    /** Export a conversation as markdown-formatted text. */
    override suspend fun exportConversation(id: Long): String? {
        val conversation = conversationDao.getConversationById(id) ?: return null
        val messages = messageDao.getRecentMessages(id, Int.MAX_VALUE).reversed()
        val sb = StringBuilder()
        sb.appendLine("# ${conversation.title}")
        sb.appendLine()
        sb.appendLine("> Capability: ${conversation.capabilityMode}")
        sb.appendLine("> Created: ${conversation.createdAt}")
        sb.appendLine()
        for (message in messages) {
            val roleLabel = when (message.role) {
                MessageRole.USER -> "ðŸ‘¤ User"
                MessageRole.ASSISTANT -> "ðŸ¤– Assistant"
                MessageRole.SYSTEM -> "âš™ï¸ System"
                MessageRole.TOOL -> "ðŸ”§ Tool"
            }
            sb.appendLine("### $roleLabel")
            sb.appendLine()
            sb.appendLine(message.content)
            if (!message.thinkingContent.isNullOrEmpty()) {
                sb.appendLine()
                sb.appendLine("<details><summary>Thinking</summary>")
                sb.appendLine()
                sb.appendLine(message.thinkingContent)
                sb.appendLine()
                sb.appendLine("</details>")
            }
            if (!message.toolResult.isNullOrEmpty()) {
                sb.appendLine()
                sb.appendLine("**Tool Result (${message.toolName ?: "unknown"}):**")
                sb.appendLine()
                sb.appendLine("```")
                sb.appendLine(message.toolResult)
                sb.appendLine("```")
            }
            sb.appendLine()
            sb.appendLine("---")
            sb.appendLine()
        }
        return sb.toString()
    }

    override suspend fun exportConversations(ids: Collection<Long>): String {
        val selectedIds = ids.toSet()
        val conversations = if (selectedIds.isEmpty()) {
            conversationDao.getAllConversationsList()
        } else {
            conversationDao.getConversationsByIds(selectedIds.toList())
        }
        return buildString {
            appendLine("# Localyze Conversation Export")
            appendLine()
            appendLine("> Conversations: ${conversations.size}")
            appendLine("> Exported: ${System.currentTimeMillis()}")
            appendLine()
            conversations.forEach { conversation ->
                exportConversation(conversation.id)?.let { export ->
                    appendLine(export)
                    appendLine()
                }
            }
        }
    }
}
