package com.localyze.ai

import com.localyze.data.repository.MemoryRepository
import com.localyze.domain.models.Memory
import com.localyze.domain.models.Message
import com.localyze.domain.models.MessageRole
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the context window for the Gemma 4 E4B model.
 *
 * Ensures that the total token count of the conversation history, system prompt,
 * and reserved space stays within the model's maximum context window of 128K tokens.
 * Truncates older messages when the budget is exceeded, always preserving the most
 * recent messages and the system prompt.
 */
@Singleton
class ContextWindowManager @Inject constructor(
    private val memoryRepository: MemoryRepository
) {

    companion object {
        /** Gemma 4 E4B's maximum context window size in tokens. */
        const val MAX_CONTEXT_TOKENS = 128_000

        /** Start truncating when total tokens exceed this threshold. */
        const val TRUNCATION_THRESHOLD = 100_000

        /** Reserve this many tokens for the system prompt. */
        const val RESERVE_FOR_SYSTEM = 4_000

        /** Reserve this many tokens for the new response. */
        const val RESERVE_FOR_RESPONSE = 2_048
    }

    /**
     * Estimate the token count for a given text string.
     *
     * Uses a heuristic approximation:
     * - For text that looks like code (contains braces, semicolons, brackets): ~2 chars/token
     * - For regular English text: ~4 chars/token
     * - Refined with word count * 1.3 approximation
     */
    fun estimateTokenCount(text: String): Int {
        if (text.isBlank()) return 0

        // Detect if text looks like code
        val codeIndicators = countCodeIndicators(text)
        val isCodeLike = codeIndicators > text.length * 0.05 // >5% code chars

        val charsPerToken = if (isCodeLike) 2.0 else 4.0
        val charEstimate = (text.length / charsPerToken).toInt()

        // Also estimate by word count * 1.3 and take the average for better accuracy
        val wordCount = text.split(Regex("\\s+")).filter { it.isNotEmpty() }.size
        val wordEstimate = (wordCount * 1.3).toInt()

        // Return the average of both estimates, minimum 1
        return maxOf(1, (charEstimate + wordEstimate) / 2)
    }

    /**
     * Count characters that are indicative of code content.
     */
    private fun countCodeIndicators(text: String): Int {
        var count = 0
        for (char in text) {
            when (char) {
                '{', '}', ';', '[', ']', '<', '>', '=', '+', '-', '*', '/', '\\', '|' -> count++
            }
        }
        return count
    }

    /**
     * Build a context window that fits within the model's token budget.
     *
     * @param messages The full conversation history.
     * @param systemPrompt The system prompt text.
     * @param memories Optional memories to inject into the system message.
     * @return A trimmed list of messages that fits within the context window.
     */
    fun buildContextWindow(
        messages: List<Message>,
        systemPrompt: String,
        memories: List<Memory> = emptyList()
    ): List<Message> {
        val systemTokenCount = estimateTokenCount(systemPrompt) + RESERVE_FOR_SYSTEM
        val memoryTokenCount = if (memories.isNotEmpty()) {
            estimateTokenCount(memories.joinToString("; ") { it.content })
        } else {
            0
        }

        val reservedTokens = systemTokenCount + memoryTokenCount + RESERVE_FOR_RESPONSE
        val availableForMessages = MAX_CONTEXT_TOKENS - reservedTokens

        // Start with the most recent messages and work backwards
        val result = mutableListOf<Message>()
        var usedTokens = 0

        for (message in messages.reversed()) {
            val messageTokens = estimateTokenCount(message.content)
            // Also account for thinking content if present
            val thinkingTokens = if (!message.thinkingContent.isNullOrBlank()) {
                estimateTokenCount(message.thinkingContent)
            } else {
                0
            }
            // Account for tool result content
            val toolResultTokens = if (!message.toolResult.isNullOrBlank()) {
                estimateTokenCount(message.toolResult)
            } else {
                0
            }

            val totalMessageTokens = messageTokens + thinkingTokens + toolResultTokens

            if (usedTokens + totalMessageTokens <= availableForMessages) {
                result.add(0, message)
                usedTokens += totalMessageTokens
            } else {
                // Cannot fit more messages; stop here
                break
            }
        }

        // If we have memories, inject them as a system message at the beginning
        if (memories.isNotEmpty()) {
            val memoryText = buildMemoryText(memories)
            val memoryMessage = Message(
                conversationId = result.firstOrNull()?.conversationId ?: 0,
                role = MessageRole.SYSTEM,
                content = memoryText
            )
            result.add(0, memoryMessage)
        }

        return result
    }

    /**
     * Calculate the total token count for a list of messages.
     */
    fun getTokenCountForMessages(messages: List<Message>): Int {
        var total = 0
        for (message in messages) {
            total += estimateTokenCount(message.content)
            if (!message.thinkingContent.isNullOrBlank()) {
                total += estimateTokenCount(message.thinkingContent)
            }
            if (!message.toolResult.isNullOrBlank()) {
                total += estimateTokenCount(message.toolResult)
            }
        }
        return total
    }

    /**
     * Check whether adding new tokens would still fit within the context window.
     *
     * @param existingTokens Current token count of existing content.
     * @param newTokens Number of tokens to add.
     * @return True if the new tokens can fit without exceeding the budget.
     */
    fun canFitInContext(existingTokens: Int, newTokens: Int): Boolean {
        return existingTokens + newTokens < MAX_CONTEXT_TOKENS - RESERVE_FOR_RESPONSE
    }

    /**
     * Build a formatted memory text for injection into the system message.
     */
    private fun buildMemoryText(memories: List<Memory>): String {
        if (memories.isEmpty()) return ""
        val sb = StringBuilder()
        sb.appendLine("Relevant memories about the user:")
        for (memory in memories) {
            sb.appendLine("- ${memory.content}")
        }
        return sb.toString()
    }
}