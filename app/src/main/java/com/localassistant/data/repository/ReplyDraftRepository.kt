package com.localassistant.data.repository

import com.localassistant.data.local.ReplyDraftDao
import com.localassistant.domain.models.ReplyDraft
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReplyDraftRepository @Inject constructor(
    private val replyDraftDao: ReplyDraftDao
) {
    fun getAll(): Flow<List<ReplyDraft>> = replyDraftDao.getAll()

    fun getPending(): Flow<List<ReplyDraft>> = replyDraftDao.getPending()

    suspend fun save(draft: ReplyDraft): ReplyDraft {
        val id = replyDraftDao.insert(draft)
        return draft.copy(id = id)
    }

    suspend fun update(draft: ReplyDraft) {
        replyDraftDao.update(draft)
    }

    suspend fun setHandled(id: Long, handled: Boolean = true) {
        replyDraftDao.setHandled(id, handled)
    }

    suspend fun delete(id: Long) {
        replyDraftDao.deleteById(id)
    }

    suspend fun clear() {
        replyDraftDao.clear()
    }
}
