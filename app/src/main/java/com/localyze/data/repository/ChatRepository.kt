package com.localyze.data.repository

import com.localyze.domain.models.Conversation
import com.localyze.domain.models.Message
import androidx.paging.PagingData
import kotlinx.coroutines.flow.Flow

/**
 * Interface for chat persistence operations.
 *
 * Will be implemented with Room in Step 8. For now, this defines the contract
 * that the ViewModel and use cases depend on.
 */
interface ChatRepository {

    // â”€â”€ Conversations â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /** Observe all conversations, sorted by pinned status then last updated. */
    fun getAllConversations(): Flow<List<Conversation>>

    /** Get a single conversation by ID. */
    suspend fun getConversation(id: Long): Conversation?

    /** Create a new conversation with the given capability mode. */
    suspend fun createConversation(capabilityMode: String = "chat"): Conversation

    /** Update an existing conversation. */
    suspend fun updateConversation(conversation: Conversation)

    /** Archive or restore a conversation. */
    suspend fun archiveConversation(id: Long, archived: Boolean)

    /** Mark or unmark a favorite conversation. */
    suspend fun favoriteConversation(id: Long, favorite: Boolean)

    /** Move a conversation into a folder/project. */
    suspend fun updateConversationFolder(id: Long, folder: String)

    /** Delete a conversation and all its messages. */
    suspend fun deleteConversation(id: Long)

    /** Bulk delete conversations and their messages. */
    suspend fun deleteConversations(ids: Collection<Long>)

    /** Bulk archive or restore conversations. */
    suspend fun archiveConversations(ids: Collection<Long>, archived: Boolean)

    /** Search conversations by title. */
    fun searchConversations(query: String): Flow<List<Conversation>>

    /** Observe all non-empty folders/projects. */
    fun getFolders(): Flow<List<String>>

    /** Search all saved messages by text content. */
    fun searchMessages(query: String): Flow<List<Message>>

    /** Export a conversation as markdown text. */
    suspend fun exportConversation(id: Long): String?

    /** Export multiple conversations as markdown text. */
    suspend fun exportConversations(ids: Collection<Long>): String

    // â”€â”€ Messages â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /** Observe all messages for a conversation, ordered by timestamp ascending. */
    fun getMessagesForConversation(conversationId: Long): Flow<List<Message>>

    /** Observe messages for a conversation as paginated data. */
    fun getMessagesForConversationPaged(conversationId: Long): Flow<PagingData<Message>>

    /** Get a single message by ID. */
    suspend fun getMessage(id: Long): Message?

    /** Save a new message and return it with the generated ID. */
    suspend fun saveMessage(message: Message): Message

    /** Update an existing message. */
    suspend fun updateMessage(message: Message)

    /** Delete a message by ID. */
    suspend fun deleteMessage(id: Long)

    /** Get the most recent [limit] messages for a conversation, newest first. */
    suspend fun getRecentMessages(conversationId: Long, limit: Int): List<Message>
}
