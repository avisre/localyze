package com.localassistant.domain.usecases

import com.localassistant.data.repository.MemoryRepository
import com.localassistant.domain.models.Memory
import javax.inject.Inject

/**
 * Use case for managing user memories — saving, searching, deleting,
 * and extracting keywords from text.
 *
 * Memories are short factual notes about the user that the assistant
 * can recall in future conversations to provide personalised responses.
 */
class ManageMemoryUseCase @Inject constructor(
    private val memoryRepository: MemoryRepository
) {

    companion object {
        /** Common English stop words to filter out during keyword extraction. */
        private val STOP_WORDS = setOf(
            "a", "an", "the", "and", "or", "but", "in", "on", "at", "to", "for",
            "of", "with", "by", "from", "is", "it", "this", "that", "was", "are",
            "i", "you", "he", "she", "we", "they", "me", "him", "her", "us",
            "them", "my", "your", "his", "its", "our", "their", "what", "which",
            "who", "whom", "be", "been", "being", "have", "has", "had", "do",
            "does", "did", "will", "would", "could", "should", "may", "might",
            "can", "shall", "not", "no", "nor", "so", "if", "then", "than",
            "too", "very", "just", "about", "also", "now", "here", "there",
            "when", "where", "why", "how", "all", "each", "every", "both",
            "few", "more", "most", "other", "some", "such", "only", "own",
            "same", "up", "out", "into", "over", "after", "before", "between"
        )

        private const val MAX_KEYWORDS = 5
    }

    /**
     * Save a new memory with the given content and keywords.
     *
     * Note: This will be fully functional once MemoryRepository is expanded
     * with persistence support in a later step.
     */
    suspend fun saveMemory(content: String, keywords: List<String>): Memory {
        val memory = Memory(
            content = content,
            keywords = keywords,
            createdAt = System.currentTimeMillis(),
            lastAccessedAt = System.currentTimeMillis()
        )
        // MemoryRepository will be expanded with save in a later step;
        // for now we return the constructed Memory object.
        return memory
    }

    /**
     * Search memories by query string, returning matching memories ranked by relevance.
     */
    suspend fun searchMemories(query: String): List<Memory> {
        return memoryRepository.searchMemories(query)
    }

    /**
     * Delete a memory by its ID.
     *
     * Note: This will be fully functional once MemoryRepository is expanded
     * with delete support in a later step.
     */
    suspend fun deleteMemory(id: Long) {
        // Will be implemented when MemoryRepository gains delete capability
    }

    /**
     * Retrieve all stored memories.
     */
    suspend fun getAllMemories(): List<Memory> {
        return memoryRepository.getAllMemories()
    }

    /**
     * Extract significant keywords from the given text.
     *
     * The algorithm:
     * 1. Split text into words
     * 2. Lowercase and remove punctuation
     * 3. Filter out stop words and short words (< 3 chars)
     * 4. Count word frequencies
     * 5. Return the top [MAX_KEYWORDS] most frequent significant words
     */
    fun extractKeywords(text: String): List<String> {
        val words = text.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), "")
            .split(Regex("\\s+"))
            .filter { it.length >= 3 }
            .filter { it !in STOP_WORDS }

        // Count frequencies and take top keywords
        val frequency = mutableMapOf<String, Int>()
        for (word in words) {
            frequency[word] = (frequency[word] ?: 0) + 1
        }

        return frequency.entries
            .sortedByDescending { it.value }
            .take(MAX_KEYWORDS)
            .map { it.key }
    }
}