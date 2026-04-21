package com.localyze.data.repository

import com.localyze.data.local.MemoryDao
import com.localyze.domain.models.Memory
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MemoryRepositoryImpl @Inject constructor(
    private val memoryDao: MemoryDao
) : MemoryRepository {

    // â”€â”€ Interface methods â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    override suspend fun searchMemories(query: String): List<Memory> {
        return memoryDao.searchMemories(query)
    }

    override suspend fun getAllMemories(): List<Memory> {
        return memoryDao.getAllMemoriesList()
    }

    // â”€â”€ Additional methods â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /** Save a new memory with the given content and keywords, returning it with the generated ID. */
    suspend fun saveMemory(content: String, keywords: List<String>): Memory {
        val memory = Memory(
            content = content,
            keywords = keywords,
            createdAt = System.currentTimeMillis(),
            lastAccessedAt = System.currentTimeMillis()
        )
        val id = memoryDao.insert(memory)
        return memory.copy(id = id)
    }

    /** Delete a memory by ID. */
    suspend fun deleteMemory(id: Long) {
        memoryDao.deleteById(id)
    }

    /** Update a saved memory. */
    suspend fun updateMemory(memory: Memory) {
        memoryDao.update(memory.copy(lastAccessedAt = System.currentTimeMillis()))
    }

    /** Get a single memory by ID. */
    suspend fun getMemoryById(id: Long): Memory? {
        return memoryDao.getMemoryById(id)
    }

    /** Update the last-accessed timestamp for a memory. */
    suspend fun updateLastAccessed(id: Long) {
        memoryDao.updateLastAccessed(id, System.currentTimeMillis())
    }

    /** Search memories by keyword. */
    suspend fun searchByKeyword(keyword: String): List<Memory> {
        return memoryDao.searchByKeyword(keyword)
    }

    /** Observe all memories as a Flow. */
    fun getAllMemoriesFlow(): Flow<List<Memory>> {
        return memoryDao.getAllMemories()
    }

    /** Get the total count of stored memories. */
    suspend fun getMemoryCount(): Int {
        return memoryDao.getMemoryCount()
    }
}
