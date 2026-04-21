package com.localassistant.domain.usecases

/**
 * Sealed class representing events emitted during a chat response stream.
 *
 * The ViewModel collects these events from [SendMessageUseCase] and updates
 * the UI state accordingly.
 */
sealed class ChatResponseEvent {

    /** A regular text token has been produced by the model. */
    data class StreamingToken(val text: String) : ChatResponseEvent()

    /** A thinking/reasoning token has been produced by the model. */
    data class ThinkingToken(val text: String) : ChatResponseEvent()

    /** A tool call has been detected and is about to be executed. */
    data class ToolCallStarted(val toolName: String) : ChatResponseEvent()

    /** A tool call has finished executing with the given result. */
    data class ToolCallCompleted(val toolName: String, val result: String) : ChatResponseEvent()

    /** The response stream has completed. */
    data class Completed(
        val fullText: String,
        val thinkingText: String?
    ) : ChatResponseEvent()

    /** An error occurred during generation. */
    data class Error(val message: String) : ChatResponseEvent()
}