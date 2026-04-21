package com.localassistant.data.repository

import com.localassistant.domain.models.Memory

/**
 * Repository interface for accessing stored memories.
 * Provides methods to search and retrieve user memories for context injection.
 */
interface MemoryRepository {

    /**
     * Search memories by query string, returning matching memories ranked by relevance.
     */
    suspend fun searchMemories(query: String): List<Memory>

    /**
     * Retrieve all stored memories.
     */
    suspend fun getAllMemories(): List<Memory>
}