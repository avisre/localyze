package com.localyze.ui.screens

/**
 * Pure scroll policy for the chat transcript.
 *
 * The UI renders a tiny bottom anchor after the final message. Following that
 * anchor keeps the newest streamed text visible even when the last assistant
 * bubble grows taller than the viewport.
 */
object ChatScrollPolicy {
    const val MESSAGE_LIST_TAG = "chatMessageList"
    const val BOTTOM_ANCHOR_TAG = "chatBottomAnchor"
    const val BOTTOM_ANCHOR_KEY = "chat-bottom-anchor"
    private const val DEFAULT_BOTTOM_THRESHOLD_PX = 48

    fun isAtBottom(
        snapshot: ChatListViewportSnapshot,
        thresholdPx: Int = DEFAULT_BOTTOM_THRESHOLD_PX
    ): Boolean {
        if (snapshot.totalItemsCount == 0) return true
        if (!snapshot.canScrollForward) return true

        val lastIndex = snapshot.totalItemsCount - 1
        val lastVisibleIndex = snapshot.lastVisibleItemIndex ?: return false
        if (lastVisibleIndex < lastIndex) return false

        val lastVisibleBottom = snapshot.lastVisibleItemBottom ?: return false
        return snapshot.viewportEndOffset - lastVisibleBottom >= -thresholdPx
    }
}

data class ChatListViewportSnapshot(
    val totalItemsCount: Int,
    val lastVisibleItemIndex: Int?,
    val lastVisibleItemBottom: Int?,
    val viewportEndOffset: Int,
    val canScrollForward: Boolean
)
