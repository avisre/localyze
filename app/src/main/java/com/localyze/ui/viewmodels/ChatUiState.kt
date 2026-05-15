package com.localyze.ui.viewmodels

import com.localyze.domain.models.Message
import com.localyze.tools.DispatchResult

/**
 * Represents the complete UI state of the Chat screen.
 *
 * All state required by [ChatScreen] is captured here so the composable
 * can be a pure function of this data class.
 */
data class ChatUiState(
    /** ID of the currently active conversation, or -1L if none. */
    val currentConversationId: Long = -1L,
    /** Title of the currently active conversation. */
    val currentConversationTitle: String = "New Chat",
    /** Ordered list of messages in the current conversation. */
    val messages: List<Message> = emptyList(),
    /** Whether the model is currently streaming a response. */
    val isStreaming: Boolean = false,
    /** Whether the model is in a "thinking" phase (producing thinking tokens). */
    val isThinking: Boolean = false,
    /** Accumulated streaming text for the in-progress assistant response. */
    val streamingText: String = "",
    /** Accumulated thinking text for the in-progress response. */
    val thinkingText: String = "",
    /** Human-readable generation phase shown while the assistant is working. */
    val generationStatus: String = "",
    /** Tool calls that are currently active (executing or completed). */
    val activeToolCalls: List<ActiveToolCall> = emptyList(),
    /** Active capability mode (chat, see, write, brainstorm, code, data). */
    val capabilityMode: String = "chat",
    /** Whether thinking/trace mode is enabled. */
    val enableThinking: Boolean = false,
    /** Whether partial tokens should be shown while the model is generating. */
    val streamTokens: Boolean = true,
    /** Whether completed assistant responses should be read aloud. */
    val voiceAutoPlay: Boolean = false,
    /** Whether the web search tool is allowed to leave the device. */
    val allowWebSearch: Boolean = false,
    /** Whether to show the robot mascot prominently (when no messages). */
    val showMascot: Boolean = true,
    /** Whether the model is using prior messages in this thread as context. */
    val isUsingThreadContext: Boolean = false,
    /** Optional notice shown when conversation context was reset to avoid exhaustion. */
    val contextResetNotice: String? = null,
    /** Optional error message to display in a Snackbar. */
    val error: String? = null,
    /** Pending tool confirmation that requires user approval. */
    val pendingToolConfirmation: DispatchResult.PendingConfirmation? = null
)

/**
 * Represents a tool call that is currently visible in the chat UI.
 *
 * When [isExecuting] is true, the tool is being called and a spinner is shown.
 * When false, the tool has completed and [result] contains the outcome.
 */
data class ActiveToolCall(
    /** The name of the tool being called. */
    val toolName: String,
    /** Whether the tool is still executing. */
    val isExecuting: Boolean = true,
    /** The result of the tool call, available once execution completes. */
    val result: String? = null
)
