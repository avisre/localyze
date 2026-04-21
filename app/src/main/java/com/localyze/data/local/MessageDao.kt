package com.localyze.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Update
import androidx.sqlite.db.SupportSQLiteQuery
import com.localyze.domain.models.Message
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: Message): Long

    @Update
    suspend fun update(message: Message)

    @Delete
    suspend fun delete(message: Message)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteByConversationId(conversationId: Long)

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    fun getMessagesForConversation(conversationId: Long): Flow<List<Message>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentMessages(conversationId: Long, limit: Int): List<Message>

    @Query("SELECT * FROM messages ORDER BY timestamp ASC")
    suspend fun getAllMessagesList(): List<Message>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun getMessageById(id: Long): Message?

    @Query("UPDATE messages SET content = :content WHERE id = :id")
    suspend fun updateContent(id: Long, content: String)

    @Query("UPDATE messages SET thinkingContent = :thinkingContent WHERE id = :id")
    suspend fun updateThinkingContent(id: Long, thinkingContent: String?)

    @Query("UPDATE messages SET isStreaming = :isStreaming WHERE id = :id")
    suspend fun updateStreamingState(id: Long, isStreaming: Boolean)

    @Query("UPDATE messages SET toolResult = :toolResult, toolName = :toolName, toolCallId = :toolCallId WHERE id = :id")
    suspend fun updateToolResult(id: Long, toolCallId: String, toolName: String, toolResult: String)

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastMessage(conversationId: Long): Message?

    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId")
    suspend fun getMessageCount(conversationId: Long): Int

    // FTS4 search across all messages
    @RawQuery
    suspend fun searchMessages(query: SupportSQLiteQuery): List<Message>

    // Helper for FTS search â€” fallback LIKE-based search
    @Query("SELECT * FROM messages WHERE content LIKE '%' || :query || '%' OR thinkingContent LIKE '%' || :query || '%' ORDER BY timestamp DESC")
    fun searchMessagesSimple(query: String): Flow<List<Message>>
}
