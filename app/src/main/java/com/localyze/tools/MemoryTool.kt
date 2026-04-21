package com.localyze.tools

import com.localyze.data.repository.MemoryRepositoryImpl
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import javax.inject.Inject

class MemoryTool @Inject constructor(
    private val memoryRepository: MemoryRepositoryImpl
) : Tool {

    override val name = "memory"
    override val description = "Save facts about the user to long-term memory, or search previously saved memories"

    companion object {
        /** Common English stop words to filter out when auto-extracting keywords. */
        private val STOP_WORDS = setOf(
            "a", "an", "the", "is", "are", "was", "were", "be", "been", "being",
            "have", "has", "had", "do", "does", "did", "will", "would", "could",
            "should", "may", "might", "shall", "can", "need", "dare", "ought",
            "used", "to", "of", "in", "for", "on", "with", "at", "by", "from",
            "as", "into", "through", "during", "before", "after", "above", "below",
            "between", "out", "off", "over", "under", "again", "further", "then",
            "once", "here", "there", "when", "where", "why", "how", "all", "each",
            "every", "both", "few", "more", "most", "other", "some", "such", "no",
            "nor", "not", "only", "own", "same", "so", "than", "too", "very",
            "just", "because", "but", "and", "or", "if", "while", "about", "up",
            "i", "me", "my", "myself", "we", "our", "ours", "ourselves", "you",
            "your", "yours", "yourself", "yourselves", "he", "him", "his",
            "himself", "she", "her", "hers", "herself", "it", "its", "itself",
            "they", "them", "their", "theirs", "themselves", "what", "which",
            "who", "whom", "this", "that", "these", "those", "am", "also", "like"
        )
    }

    // â”€â”€ Schema â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    override fun getParameterSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("action", buildJsonObject {
                put("type", "string")
                put("description", "Action to perform: 'save' to store a fact, 'search' to find saved memories")
                put("enum", buildJsonArray {
                    add(JsonPrimitive("save")); add(JsonPrimitive("search"))
                })
            })
            put("content", buildJsonObject {
                put("type", "string")
                put("description", "The fact or information to save (required for save action)")
            })
            put("keywords", buildJsonObject {
                put("type", "array")
                put("description", "Keywords associated with the memory (optional for save action, auto-extracted if omitted)")
                put("items", buildJsonObject { put("type", "string") })
            })
            put("query", buildJsonObject {
                put("type", "string")
                put("description", "Search query to find matching memories (required for search action)")
            })
            put("max_results", buildJsonObject {
                put("type", "integer")
                put("description", "Maximum number of memories to return (default 5, for search action)")
            })
        })
        put("required", buildJsonArray {
            add(JsonPrimitive("action"))
        })
    }

    /**
     * Memory save persists data to storage, so it requires confirmation.
     */
    override fun requiresConfirmation(): Boolean = true

    // â”€â”€ Execute â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    override suspend fun execute(args: JsonObject): String {
        val action = args["action"]?.let { (it as JsonPrimitive).content }
            ?: return errorResult("Missing required parameter: action")

        return when (action) {
            "save" -> saveMemory(args)
            "search" -> searchMemories(args)
            else -> errorResult("Unknown action: $action. Use 'save' or 'search'.")
        }
    }

    // â”€â”€ Save â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private suspend fun saveMemory(args: JsonObject): String {
        val content = args["content"]?.let { (it as JsonPrimitive).content }
            ?: return errorResult("Missing required parameter: content for save action")

        // Extract keywords: use provided ones, or auto-extract from content
        val keywords = args["keywords"]?.jsonArray?.mapNotNull {
            (it as? JsonPrimitive)?.content
        } ?: extractKeywords(content)

        return try {
            val memory = memoryRepository.saveMemory(content, keywords)
            buildJsonObject {
                put("success", true)
                put("id", memory.id)
                put("content", content)
                put("keywords", JsonArray(keywords.map { JsonPrimitive(it) }))
                put("message", "Saved to memory: $content")
            }.toString()
        } catch (e: Exception) {
            errorResult("Error saving memory: ${e.message}")
        }
    }

    // â”€â”€ Search â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private suspend fun searchMemories(args: JsonObject): String {
        val query = args["query"]?.let { (it as JsonPrimitive).content }
            ?: return errorResult("Missing required parameter: query for search action")
        val maxResults = args["max_results"]?.let { (it as JsonPrimitive).content?.toIntOrNull() } ?: 5

        return try {
            val memories = memoryRepository.searchMemories(query)

            // Update lastAccessedAt for returned memories
            val results = memories.take(maxResults).map { memory ->
                try {
                    memoryRepository.updateLastAccessed(memory.id)
                } catch (_: Exception) { }

                buildJsonObject {
                    put("id", memory.id)
                    put("content", memory.content)
                    put("keywords", JsonArray(memory.keywords.map { JsonPrimitive(it) }))
                    put("last_accessed", memory.lastAccessedAt)
                }
            }

            buildJsonObject {
                put("memories", JsonArray(results))
                put("count", results.size)
            }.toString()
        } catch (e: Exception) {
            errorResult("Error searching memories: ${e.message}")
        }
    }

    // â”€â”€ Keyword extraction â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private fun extractKeywords(content: String): List<String> {
        return content.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), "")
            .split(Regex("\\s+"))
            .filter { it.length > 2 && it !in STOP_WORDS }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(5)
            .map { it.key }
    }

    // â”€â”€ Helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private fun errorResult(message: String): String = buildJsonObject {
        put("error", message)
    }.toString()
}