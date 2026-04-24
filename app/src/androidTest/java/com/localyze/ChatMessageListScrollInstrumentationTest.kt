package com.localyze

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.localyze.domain.models.Message
import com.localyze.domain.models.MessageRole
import com.localyze.ui.screens.ChatMessageList
import com.localyze.ui.screens.ChatScrollPolicy
import com.localyze.ui.theme.LocalyzeTheme
import com.localyze.ui.viewmodels.ChatUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatMessageListScrollInstrumentationTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun streamingLongResponse_keepsBottomAnchorVisibleWhenFollowingOutput() {
        // Start short so the anchor is visible without scrolling,
        // then grow the content and verify the reactive follow effect keeps it visible.
        var streamingText by mutableStateOf("Short response.")

        setChatContent { streamingText }

        composeRule.onNodeWithTag(ChatScrollPolicy.BOTTOM_ANCHOR_TAG).assertIsDisplayed()

        composeRule.runOnIdle {
            streamingText = longStreamingText(lineCount = 120)
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag(ChatScrollPolicy.BOTTOM_ANCHOR_TAG).assertIsDisplayed()

        composeRule.runOnIdle {
            streamingText = longStreamingText(lineCount = 180)
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag(ChatScrollPolicy.BOTTOM_ANCHOR_TAG).assertIsDisplayed()
    }

    @Test
    fun userScrollUpDuringStreaming_pausesFollowUntilUserReturnsToBottom() {
        // Start short so the anchor is visible, then grow content.
        var streamingText by mutableStateOf("Short response.")

        setChatContent { streamingText }

        composeRule.onNodeWithTag(ChatScrollPolicy.BOTTOM_ANCHOR_TAG).assertIsDisplayed()

        composeRule.runOnIdle {
            streamingText = longStreamingText(lineCount = 120)
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(ChatScrollPolicy.BOTTOM_ANCHOR_TAG).assertIsDisplayed()

        // User scrolls up (swipe down in Compose = scroll up)
        composeRule.onNodeWithTag(ChatScrollPolicy.MESSAGE_LIST_TAG).performTouchInput {
            swipeDown()
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(ChatScrollPolicy.BOTTOM_ANCHOR_TAG).assertIsNotDisplayed()

        // More content arrives while user is scrolled up
        composeRule.runOnIdle {
            streamingText = longStreamingText(lineCount = 150)
        }
        composeRule.waitForIdle()
        // Anchor should still be off-screen because follow is paused
        composeRule.onNodeWithTag(ChatScrollPolicy.BOTTOM_ANCHOR_TAG).assertIsNotDisplayed()

        // User taps scroll-to-bottom FAB
        composeRule.onNodeWithContentDescription("Scroll to bottom").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(ChatScrollPolicy.BOTTOM_ANCHOR_TAG).assertIsDisplayed()

        // Now follow should resume: new content keeps anchor visible
        composeRule.runOnIdle {
            streamingText = longStreamingText(lineCount = 180)
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(ChatScrollPolicy.BOTTOM_ANCHOR_TAG).assertIsDisplayed()
    }

    @Test
    fun streamingShortResponse_keepsAnchorVisible() {
        var streamingText by mutableStateOf("Short response.")

        setChatContent { streamingText }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(ChatScrollPolicy.BOTTOM_ANCHOR_TAG).assertIsDisplayed()

        composeRule.runOnIdle {
            streamingText = "Short response. Now a bit longer."
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(ChatScrollPolicy.BOTTOM_ANCHOR_TAG).assertIsDisplayed()
    }

    @Test
    fun multipleBackToBackMessages_scrollsToBottomOnNewMessage() {
        var messages by mutableStateOf(
            listOf(
                Message(
                    id = 1L,
                    conversationId = 1L,
                    role = MessageRole.USER,
                    content = "First",
                    timestamp = 1L
                )
            )
        )

        composeRule.setContent {
            LocalyzeTheme {
                ChatMessageList(
                    uiState = ChatUiState(
                        currentConversationId = 1L,
                        messages = messages,
                        isStreaming = false,
                        streamingText = ""
                    ),
                    expandedThinking = emptySet(),
                    onToggleThinking = {},
                    onCopyMessage = {},
                    onLongClickMessage = {},
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(ChatScrollPolicy.BOTTOM_ANCHOR_TAG).assertIsDisplayed()

        // Add many messages to push anchor off-screen
        composeRule.runOnIdle {
            messages = (1..20).map { index ->
                Message(
                    id = index.toLong(),
                    conversationId = 1L,
                    role = if (index % 2 == 1) MessageRole.USER else MessageRole.ASSISTANT,
                    content = "Message number $index with some padding text to make it tall enough.",
                    timestamp = index.toLong()
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(ChatScrollPolicy.BOTTOM_ANCHOR_TAG).assertIsDisplayed()
    }

    @Test
    fun scrollToBottomFabAppearsWhenScrolledUpAndHidesWhenAtBottom() {
        var streamingText by mutableStateOf(longStreamingText(lineCount = 120))

        setChatContent { streamingText }
        composeRule.waitForIdle()

        // FAB should not be visible when at bottom
        composeRule.onNodeWithContentDescription("Scroll to bottom").assertIsNotDisplayed()

        // Scroll up
        composeRule.onNodeWithTag(ChatScrollPolicy.MESSAGE_LIST_TAG).performTouchInput {
            swipeDown()
        }
        composeRule.waitForIdle()

        // FAB should be visible
        composeRule.onNodeWithContentDescription("Scroll to bottom").assertIsDisplayed()

        // Click FAB
        composeRule.onNodeWithContentDescription("Scroll to bottom").performClick()
        composeRule.waitForIdle()

        // FAB should hide again
        composeRule.onNodeWithContentDescription("Scroll to bottom").assertIsNotDisplayed()
    }

    @Test
    fun userCanScrollDuringStreaming_andDoesNotGetYankedBack() {
        var streamingText by mutableStateOf(longStreamingText(lineCount = 60))

        setChatContent { streamingText }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(ChatScrollPolicy.BOTTOM_ANCHOR_TAG).assertIsDisplayed()

        // Simulate continuous streaming by rapidly updating text
        composeRule.runOnIdle {
            streamingText = longStreamingText(lineCount = 70)
        }
        composeRule.waitForIdle()

        // User scrolls up during streaming
        composeRule.onNodeWithTag(ChatScrollPolicy.MESSAGE_LIST_TAG).performTouchInput {
            swipeDown()
        }
        composeRule.waitForIdle()

        // Should be scrolled up
        composeRule.onNodeWithTag(ChatScrollPolicy.BOTTOM_ANCHOR_TAG).assertIsNotDisplayed()

        // More streaming arrives
        composeRule.runOnIdle {
            streamingText = longStreamingText(lineCount = 80)
        }
        composeRule.waitForIdle()

        // User should still be scrolled up (not forcibly yanked down)
        composeRule.onNodeWithTag(ChatScrollPolicy.BOTTOM_ANCHOR_TAG).assertIsNotDisplayed()
    }

    private fun setChatContent(streamingText: () -> String) {
        composeRule.setContent {
            LocalyzeTheme {
                ChatMessageList(
                    uiState = ChatUiState(
                        currentConversationId = 1L,
                        messages = listOf(
                            Message(
                                id = 1L,
                                conversationId = 1L,
                                role = MessageRole.USER,
                                content = "Write a long answer",
                                timestamp = 1L
                            )
                        ),
                        isStreaming = true,
                        streamingText = streamingText()
                    ),
                    expandedThinking = emptySet(),
                    onToggleThinking = {},
                    onCopyMessage = {},
                    onLongClickMessage = {},
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun longStreamingText(lineCount: Int): String =
        (1..lineCount).joinToString(separator = "\n") { line ->
            "Line ${line.toString().padStart(3, '0')}: streamed assistant content keeps growing."
        }
}
