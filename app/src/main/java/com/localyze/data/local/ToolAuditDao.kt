package com.localyze.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.localyze.domain.models.ToolAudit
import kotlinx.coroutines.flow.Flow

@Dao
interface ToolAuditDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(audit: ToolAudit): Long

    @Query("SELECT * FROM tool_audits ORDER BY createdAt DESC LIMIT :limit")
    fun getRecent(limit: Int = 100): Flow<List<ToolAudit>>

    @Query("SELECT * FROM tool_audits WHERE toolName = :toolName ORDER BY createdAt DESC LIMIT :limit")
    fun getRecentForTool(toolName: String, limit: Int = 50): Flow<List<ToolAudit>>

    @Query("DELETE FROM tool_audits")
    suspend fun clear()
}
