package com.localassistant.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.localassistant.domain.models.Conversation
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(conversation: Conversation): Long

    @Update
    suspend fun update(conversation: Conversation)

    @Delete
    suspend fun delete(conversation: Conversation)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM conversations ORDER BY isArchived ASC, isPinned DESC, isFavorite DESC, updatedAt DESC")
    fun getAllConversations(): Flow<List<Conversation>>

    @Query("SELECT * FROM conversations ORDER BY isArchived ASC, isPinned DESC, isFavorite DESC, updatedAt DESC")
    suspend fun getAllConversationsList(): List<Conversation>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getConversationById(id: Long): Conversation?

    @Query("SELECT * FROM conversations WHERE id IN (:ids) ORDER BY updatedAt DESC")
    suspend fun getConversationsByIds(ids: List<Long>): List<Conversation>

    @Query("UPDATE conversations SET title = :title WHERE id = :id")
    suspend fun updateTitle(id: Long, title: String)

    @Query("UPDATE conversations SET isPinned = :isPinned WHERE id = :id")
    suspend fun updatePinnedStatus(id: Long, isPinned: Boolean)

    @Query("UPDATE conversations SET isArchived = :isArchived WHERE id = :id")
    suspend fun updateArchivedStatus(id: Long, isArchived: Boolean)

    @Query("UPDATE conversations SET isFavorite = :isFavorite WHERE id = :id")
    suspend fun updateFavoriteStatus(id: Long, isFavorite: Boolean)

    @Query("UPDATE conversations SET folder = :folder WHERE id = :id")
    suspend fun updateFolder(id: Long, folder: String)

    @Query("UPDATE conversations SET capabilityMode = :mode WHERE id = :id")
    suspend fun updateCapabilityMode(id: Long, mode: String)

    @Query("UPDATE conversations SET updatedAt = :updatedAt, messageCount = :messageCount WHERE id = :id")
    suspend fun updateTimestampAndCount(id: Long, updatedAt: Long, messageCount: Int)

    @Query(
        "SELECT * FROM conversations WHERE title LIKE '%' || :query || '%' " +
            "OR folder LIKE '%' || :query || '%' OR tags LIKE '%' || :query || '%' " +
            "OR summary LIKE '%' || :query || '%' ORDER BY updatedAt DESC"
    )
    fun searchConversations(query: String): Flow<List<Conversation>>

    @Query("SELECT DISTINCT folder FROM conversations WHERE folder != '' ORDER BY folder ASC")
    fun getFolders(): Flow<List<String>>

    @Query("SELECT COUNT(*) FROM conversations")
    suspend fun getConversationCount(): Int
}
