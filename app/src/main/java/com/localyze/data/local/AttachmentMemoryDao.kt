package com.localyze.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.localyze.domain.models.AttachmentMemory
import kotlinx.coroutines.flow.Flow

@Dao
interface AttachmentMemoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(attachment: AttachmentMemory): Long

    @Update
    suspend fun update(attachment: AttachmentMemory)

    @Delete
    suspend fun delete(attachment: AttachmentMemory)

    @Query("DELETE FROM attachment_memories WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM attachment_memories ORDER BY lastAccessedAt DESC")
    fun getAll(): Flow<List<AttachmentMemory>>

    @Query("SELECT * FROM attachment_memories ORDER BY lastAccessedAt DESC")
    suspend fun getAllList(): List<AttachmentMemory>

    @Query("SELECT * FROM attachment_memories WHERE id = :id")
    suspend fun getById(id: Long): AttachmentMemory?

    @Query(
        "SELECT * FROM attachment_memories WHERE displayName LIKE '%' || :query || '%' " +
            "OR extractedText LIKE '%' || :query || '%' OR summary LIKE '%' || :query || '%' " +
            "ORDER BY lastAccessedAt DESC"
    )
    fun search(query: String): Flow<List<AttachmentMemory>>

    @Query("UPDATE attachment_memories SET lastAccessedAt = :timestamp WHERE id = :id")
    suspend fun touch(id: Long, timestamp: Long)
}
