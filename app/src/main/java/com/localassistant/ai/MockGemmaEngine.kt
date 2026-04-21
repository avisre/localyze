package com.localassistant.ai

import android.graphics.Bitmap
import com.localassistant.domain.models.Message
import com.localassistant.domain.models.MessageRole
import com.localassistant.domain.models.ToolCall
import kotlinx.serialization.json.jsonObject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mock implementation of the inference engine for debug builds and unsupported devices.
 *
 * IMPORTANT: This is NOT "because the real model doesn't exist." The real Gemma 4 E4B
 * LiteRT-LM model (3.65 GB) IS publicly available at:
 * https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm
 *
 * Mock mode is retained for:
 * - Development velocity (no 3.65 GB download for every build)
 * - CI/CD automation (tests without model dependency)
 * - Unsupported-device fallback (devices with <8GB RAM)
 * - UI/UX validation (streaming behavior, tool-calling UI)
 * - Tool integration testing without inference cost
 *
 * Emits fake streaming responses token by token (60ms delay between tokens),
 * cycling through 5 hardcoded response types:
 * 1. Plain text answer
 * 2. Code block
 * 3. Numbered list
 * 4. Thinking-mode response with divider
 * 5. Tool call response (function_call JSON)
 *
 * A visible "⚡ MOCK MODE" yellow banner should be shown in the Chat screen
 * when this engine is active.
 *
 * To use real model: Set USE_MOCK_ENGINE=false in build.gradle.kts.
 * See BLOCKERS.md for detailed migration path.
 */
@Singleton
class MockGemmaEngine @Inject constructor() {

    private var responseIndex = 0

    private val fakeResponses = listOf(
        // 1. Plain text answer
        "Hello! I'm your local AI assistant. All my processing happens right here on your device — no data ever leaves your phone. How can I help you today?",

        // 2. Code block
        "Here's a simple Python example:\n\n```python\ndef greet(name):\n    \"\"\"Say hello to someone.\"\"\"\n    return f\"Hello, {name}! Welcome!\"\n\nif __name__ == \"__main__\":\n    print(greet(\"World\"))\n```\n\nThis defines a function that takes a name and returns a greeting string.",

        // 3. Numbered list
        "Here are some tips for staying productive:\n\n1. **Start with your hardest task** — tackle it when your energy is highest\n2. **Use the Pomodoro technique** — 25 minutes focused, 5 minutes break\n3. **Minimize distractions** — put your phone on Do Not Disturb\n4. **Batch similar tasks** — group emails, calls, and admin together\n5. **Take real breaks** — step away from the screen and move your body",

        // 4. Thinking-mode response
        "That's an interesting question. Let me think through this step by step.\n\n---\n\nAfter considering the options, I'd recommend starting with the simplest approach and iterating from there. The key insight is that premature optimization often leads to overcomplicated solutions that are harder to maintain.",

        // 5. Tool call response (triggers function calling)
        """I'll check your calendar for you.

{"name": "calendar_read", "arguments": {"days_ahead": 7}}

Let me look that up for you."""
    )

    /**
     * Generate a mock streaming response.
     * Emits tokens one at a time with 60ms delay.
     */
    fun generateResponse(
        messages: List<Message>,
        systemPrompt: String,
        capabilityMode: String,
        enableThinking: Boolean
    ): Flow<InferenceToken> = flow {
        val responseText = fakeResponses[responseIndex % fakeResponses.size]
        responseIndex++

        // If thinking mode is enabled, emit thinking tokens first
        if (enableThinking) {
            val thinkingText = "Let me analyze the user's request and formulate a helpful response."
            for (chunk in thinkingText.chunked(3)) {
                emit(InferenceToken.ThinkingToken(chunk))
                delay(60)
            }
        }

        // Check if this response contains a tool call
        val toolCallRegex = Regex("""\{"name":\s*"([^"]+)",\s*"arguments":\s*(\{[^}]+\})\}""""")
        val toolCallMatch = toolCallRegex.find(responseText)

        if (toolCallMatch != null) {
            // Emit text before the tool call
            val beforeToolCall = responseText.substring(0, toolCallMatch.range.first).trim()
            if (beforeToolCall.isNotEmpty()) {
                for (chunk in beforeToolCall.chunked(2)) {
                    emit(InferenceToken.TextToken(chunk))
                    delay(60)
                }
            }

            // Emit the tool call token
            val toolName = toolCallMatch.groupValues[1]
            val argsStr = toolCallMatch.groupValues[2]
            val jsonParser = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }
            val args = try {
                jsonParser.parseToJsonElement(argsStr).jsonObject
            } catch (_: Exception) {
                JsonObject(emptyMap())
            }
            emit(InferenceToken.ToolCallToken(ToolCall(name = toolName, arguments = args, callId = java.util.UUID.randomUUID().toString())))

            // Emit text after the tool call
            val afterToolCall = responseText.substring(toolCallMatch.range.last + 1).trim()
            if (afterToolCall.isNotEmpty()) {
                for (chunk in afterToolCall.chunked(2)) {
                    emit(InferenceToken.TextToken(chunk))
                    delay(60)
                }
            }
        } else {
            // Emit normal text tokens
            for (chunk in responseText.chunked(2)) {
                emit(InferenceToken.TextToken(chunk))
                delay(60)
            }
        }

        emit(InferenceToken.EndOfStream)
    }

    /**
     * Generate a mock response with image input (ignores the image).
     */
    fun generateResponseWithImage(
        messages: List<Message>,
        imageBitmap: Bitmap,
        prompt: String,
        capabilityMode: String,
        enableThinking: Boolean
    ): Flow<InferenceToken> = flow {
        val responseText = "I can see you've shared an image. In mock mode, I'll describe what I would analyze: the image content, colors, objects, text, and overall composition. This would work with a real model loaded."

        if (enableThinking) {
            emit(InferenceToken.ThinkingToken("The user shared an image. I need to analyze it."))
            delay(200)
        }

        for (chunk in responseText.chunked(2)) {
            emit(InferenceToken.TextToken(chunk))
            delay(60)
        }
        emit(InferenceToken.EndOfStream)
    }

    /**
     * Generate a mock response with audio input (ignores the audio).
     */
    fun generateResponseWithAudio(
        messages: List<Message>,
        audioBytes: ByteArray,
        prompt: String,
        capabilityMode: String,
        enableThinking: Boolean
    ): Flow<InferenceToken> = flow {
        val responseText = "I've processed your audio input. In mock mode, I'm treating this as a voice message and generating a text response."

        if (enableThinking) {
            emit(InferenceToken.ThinkingToken("The user sent audio. I should transcribe and respond."))
            delay(200)
        }

        for (chunk in responseText.chunked(2)) {
            emit(InferenceToken.TextToken(chunk))
            delay(60)
        }
        emit(InferenceToken.EndOfStream)
    }

    fun isMockEngine(): Boolean = true
}