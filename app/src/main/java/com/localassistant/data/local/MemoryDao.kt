package com.localassistant.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.localassistant.domain.models.Memory
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(memory: Memory): Long

    @Update
    suspend fun update(memory: Memory)

    @Delete
    suspend fun delete(memory: Memory)

    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM memories ORDER BY lastAccessedAt DESC")
    fun getAllMemories(): Flow<List<Memory>>

    @Query("SELECT * FROM memories ORDER BY lastAccessedAt DESC")
    suspend fun getAllMemoriesList(): List<Memory>

    @Query("SELECT * FROM memories WHERE id = :id")
    suspend fun getMemoryById(id: Long): Memory?

    @Query("SELECT * FROM memories WHERE content LIKE '%' || :query || '%' ORDER BY lastAccessedAt DESC")
    suspend fun searchMemories(query: String): List<Memory>

    // Search by keyword — since keywords is stored as a JSON string, use LIKE
    @Query("SELECT * FROM memories WHERE keywords LIKE '%' || :keyword || '%' ORDER BY lastAccessedAt DESC")
    suspend fun searchByKeyword(keyword: String): List<Memory>

    @Query("UPDATE memories SET lastAccessedAt = :timestamp WHERE id = :id")
    suspend fun updateLastAccessed(id: Long, timestamp: Long)

    @Query("SELECT COUNT(*) FROM memories")
    suspend fun getMemoryCount(): Int
}