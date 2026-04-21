package com.localyze.tools

import com.localyze.data.local.SettingsDataStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject

class WebSearchTool @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val settingsDataStore: SettingsDataStore
) : Tool {

    override val name = "web_search"
    override val description = "Search the web using DuckDuckGo Instant Answer API. Only available when web search is enabled in settings."

    // â”€â”€ Schema â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    override fun getParameterSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("query", buildJsonObject {
                put("type", "string")
                put("description", "Search query string")
            })
            put("max_results", buildJsonObject {
                put("type", "integer")
                put("description", "Maximum number of results to return (default 3)")
            })
        })
        put("required", buildJsonArray {
            add(JsonPrimitive("query"))
        })
    }

    // â”€â”€ Execute â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    override suspend fun execute(args: JsonObject): String {
        if (!settingsDataStore.allowWebSearch.first()) {
            return errorResult("Web search is disabled in Settings. Enable Allow web search before using this tool.")
        }

        val query = args["query"]?.let { (it as JsonPrimitive).content }
            ?: return errorResult("Missing required parameter: query")
        val maxResults = args["max_results"]?.let { (it as JsonPrimitive).content?.toIntOrNull() } ?: 3

        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val url = "https://api.duckduckgo.com/?q=$encodedQuery&format=json&no_html=1"

        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Localyze/1.0")
                .build()

            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                return errorResult("Search request failed with HTTP ${response.code}")
            }

            val responseBody = response.body?.string()
                ?: return errorResult("Empty response from search API")

            parseDuckDuckGoResponse(responseBody, maxResults)
        } catch (e: java.net.SocketTimeoutException) {
            errorResult("Search request timed out. Please try again.")
        } catch (e: java.net.UnknownHostException) {
            errorResult("No internet connection available. Please check your network settings.")
        } catch (e: Exception) {
            errorResult("Error performing web search: ${e.message}")
        }
    }

    // â”€â”€ Parse DuckDuckGo response â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private fun parseDuckDuckGoResponse(jsonStr: String, maxResults: Int): String {
        val results = mutableListOf<JsonObject>()

        try {
            val json = kotlinx.serialization.json.Json {
                ignoreUnknownKeys = true
                isLenient = true
            }
            val root = json.parseToJsonElement(jsonStr).jsonObject

            // 1. Abstract (instant answer)
            val abstract = root["Abstract"]?.jsonPrimitive?.content
            if (!abstract.isNullOrBlank()) {
                val abstractTitle = root["Heading"]?.jsonPrimitive?.content ?: ""
                val abstractUrl = root["AbstractURL"]?.jsonPrimitive?.content ?: ""
                results.add(buildJsonObject {
                    put("title", abstractTitle)
                    put("url", abstractUrl)
                    put("snippet", abstract)
                })
            }

            // 2. Results array
            val resultsArray = root["Results"]?.jsonArray
            resultsArray?.forEach { element ->
                if (results.size >= maxResults) return@forEach
                val obj = element.jsonObject
                val title = obj["Text"]?.jsonPrimitive?.content ?: ""
                val url = obj["FirstURL"]?.jsonPrimitive?.content ?: ""
                if (title.isNotBlank() || url.isNotBlank()) {
                    results.add(buildJsonObject {
                        put("title", title)
                        put("url", url)
                        put("snippet", title)
                    })
                }
            }

            // 3. RelatedTopics (take top-level entries with a URL)
            val relatedTopics = root["RelatedTopics"]?.jsonArray
            relatedTopics?.forEach { element ->
                if (results.size >= maxResults) return@forEach
                try {
                    val obj = element.jsonObject
                    val text = obj["Text"]?.jsonPrimitive?.content ?: ""
                    val url = obj["FirstURL"]?.jsonPrimitive?.content ?: ""
                    if (text.isNotBlank() && url.isNotBlank()) {
                        results.add(buildJsonObject {
                            put("title", text.substringBefore(" - ", text))
                            put("url", url)
                            put("snippet", text)
                        })
                    }
                } catch (_: Exception) {
                    // Skip entries that are not objects (e.g., nested topic groups)
                }
            }

            // 4. Answer (for calculators/conversions)
            val answer = root["Answer"]?.jsonPrimitive?.content
            if (!answer.isNullOrBlank() && results.size < maxResults) {
                val answerType = root["AnswerType"]?.jsonPrimitive?.content ?: ""
                results.add(0, buildJsonObject {
                    put("title", answerType.ifBlank { "Answer" })
                    put("url", "")
                    put("snippet", answer)
                })
            }

            // 5. Definition (for dictionary lookups)
            val definition = root["Definition"]?.jsonPrimitive?.content
            if (!definition.isNullOrBlank() && results.size < maxResults) {
                val defSource = root["DefinitionSource"]?.jsonPrimitive?.content ?: ""
                results.add(buildJsonObject {
                    put("title", "Definition (${defSource})")
                    put("url", root["DefinitionURL"]?.jsonPrimitive?.content ?: "")
                    put("snippet", definition)
                })
            }
        } catch (e: Exception) {
            return errorResult("Error parsing search results: ${e.message}")
        }

        if (results.isEmpty()) {
            return buildJsonObject {
                put("results", buildJsonArray { })
                put("count", 0)
                put("message", "No results found for query")
            }.toString()
        }

        // Trim to maxResults
        val trimmed = results.take(maxResults)

        return buildJsonObject {
            put("results", JsonArray(trimmed))
            put("count", trimmed.size)
        }.toString()
    }

    // â”€â”€ Helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private fun errorResult(message: String): String = buildJsonObject {
        put("error", message)
    }.toString()
}
