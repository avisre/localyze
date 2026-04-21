package com.localassistant.ai

import com.localassistant.domain.models.ToolCall

/**
 * Sealed class representing tokens emitted during streaming inference.
 * Each subclass represents a different type of content produced by the model.
 */
sealed class InferenceToken {

    /**
     * A regular text token produced by the model.
     */
    data class TextToken(val text: String) : InferenceToken()

    /**
     * A thinking/reasoning token produced inside <think>...</think> blocks.
     */
    data class ThinkingToken(val text: String) : InferenceToken()

    /**
     * A parsed tool call detected in the model output.
     */
    data class ToolCallToken(val toolCall: ToolCall) : InferenceToken()

    /**
     * Sentinel indicating the end of the inference stream.
     */
    data object EndOfStream : InferenceToken()

    /**
     * An error occurred during inference.
     */
    data class Error(val message: String) : InferenceToken()
}
