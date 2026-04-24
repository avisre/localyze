package com.localyze

import com.localyze.ui.screens.ChatListViewportSnapshot
import com.localyze.ui.screens.ChatScrollPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatScrollPolicyTest {
    @Test
    fun emptyList_isConsideredAtBottom() {
        val snapshot = ChatListViewportSnapshot(
            totalItemsCount = 0,
            lastVisibleItemIndex = null,
            lastVisibleItemBottom = null,
            viewportEndOffset = 1000,
            canScrollForward = false
        )

        assertTrue(ChatScrollPolicy.isAtBottom(snapshot))
    }

    @Test
    fun cannotScrollForward_isAtBottomEvenWhenAnchorIsTiny() {
        val snapshot = ChatListViewportSnapshot(
            totalItemsCount = 4,
            lastVisibleItemIndex = 3,
            lastVisibleItemBottom = 1000,
            viewportEndOffset = 1000,
            canScrollForward = false
        )

        assertTrue(ChatScrollPolicy.isAtBottom(snapshot))
    }

    @Test
    fun lastAnchorVisibleWithinThreshold_isAtBottom() {
        val snapshot = ChatListViewportSnapshot(
            totalItemsCount = 4,
            lastVisibleItemIndex = 3,
            lastVisibleItemBottom = 1020,
            viewportEndOffset = 1000,
            canScrollForward = true
        )

        assertTrue(ChatScrollPolicy.isAtBottom(snapshot, thresholdPx = 48))
    }

    @Test
    fun lastAnchorNotVisible_isNotAtBottom() {
        val snapshot = ChatListViewportSnapshot(
            totalItemsCount = 4,
            lastVisibleItemIndex = 2,
            lastVisibleItemBottom = 1100,
            viewportEndOffset = 1000,
            canScrollForward = true
        )

        assertFalse(ChatScrollPolicy.isAtBottom(snapshot))
    }

    @Test
    fun lastAnchorFarBelowViewport_isNotAtBottom() {
        val snapshot = ChatListViewportSnapshot(
            totalItemsCount = 4,
            lastVisibleItemIndex = 3,
            lastVisibleItemBottom = 1120,
            viewportEndOffset = 1000,
            canScrollForward = true
        )

        assertFalse(ChatScrollPolicy.isAtBottom(snapshot, thresholdPx = 48))
    }
}
