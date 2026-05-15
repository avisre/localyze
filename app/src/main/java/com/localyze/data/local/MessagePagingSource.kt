package com.localyze.data.local

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.localyze.domain.models.Message

/**
 * Paging3 source for loading messages in a conversation incrementally.
 *
 * Messages are ordered by timestamp ascending. The paging source
 * loads pages in chunks to avoid holding thousands of messages in RAM.
 */
class MessagePagingSource(
    private val messageDao: MessageDao,
    private val conversationId: Long
) : PagingSource<Int, Message>() {

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Message> {
        val page = params.key ?: 0
        val pageSize = params.loadSize
        val offset = page * pageSize

        return try {
            val messages = messageDao.getMessagesPage(conversationId, limit = pageSize, offset = offset)
            val nextKey = if (messages.size < pageSize) null else page + 1
            val prevKey = if (page == 0) null else page - 1

            LoadResult.Page(
                data = messages,
                prevKey = prevKey,
                nextKey = nextKey
            )
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }

    override fun getRefreshKey(state: PagingState<Int, Message>): Int? {
        return state.anchorPosition?.let { anchorPosition ->
            val anchorPage = state.closestPageToPosition(anchorPosition)
            anchorPage?.prevKey?.plus(1) ?: anchorPage?.nextKey?.minus(1)
        }
    }
}
