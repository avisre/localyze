package com.localassistant.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.localassistant.domain.models.ReplyDraft
import kotlinx.coroutines.flow.Flow

@Dao
interface ReplyDraftDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(replyDraft: ReplyDraft): Long

    @Update
    suspend fun update(replyDraft: ReplyDraft)

    @Query("SELECT * FROM reply_drafts ORDER BY createdAt DESC")
    fun getAll(): Flow<List<ReplyDraft>>

    @Query("SELECT * FROM reply_drafts ORDER BY createdAt DESC")
    suspend fun getAllList(): List<ReplyDraft>

    @Query("SELECT * FROM reply_drafts WHERE isHandled = 0 ORDER BY createdAt DESC")
    fun getPending(): Flow<List<ReplyDraft>>

    @Query("SELECT * FROM reply_drafts WHERE id = :id")
    suspend fun getById(id: Long): ReplyDraft?

    @Query("UPDATE reply_drafts SET isHandled = :isHandled WHERE id = :id")
    suspend fun setHandled(id: Long, isHandled: Boolean)

    @Query("DELETE FROM reply_drafts WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM reply_drafts")
    suspend fun clear()
}
