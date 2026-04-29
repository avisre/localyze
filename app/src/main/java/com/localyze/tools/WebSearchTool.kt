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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.net.URLDecoder
import java.net.URLEncoder
import java.time.Instant
import java.time.Year
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class WebSearchTool @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val settingsDataStore: SettingsDataStore
) : Tool {

    override val name = "web_search"
    override val description =
        "Search the web for current, recent, live, or explicitly requested information. " +
            "Returns sourced web results with title, URL, snippet, and fetch time. Use when " +
            "the user asks for the latest data, news, prices, schedules, current APIs, or " +
            "facts that may be outside the model knowledge base. Only available when web " +
            "search is enabled in settings."

    private val searchClient = okHttpClient.newBuilder()
        .callTimeout(20, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    override fun getParameterSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("query", buildJsonObject {
                put("type", "string")
                put("description", "Search query string")
            })
            put("max_results", buildJsonObject {
                put("type", "integer")
                put("description", "Maximum number of results to return, from 1 to 8 (default 5)")
            })
        })
        put("required", buildJsonArray {
            add(JsonPrimitive("query"))
        })
    }

    override suspend fun execute(args: JsonObject): String {
        if (!settingsDataStore.allowWebSearch.first()) {
            return errorResult("Web search is disabled in Settings. Enable Allow web search before using this tool.")
        }

        val query = (args["query"] as? JsonPrimitive)?.content
            ?: return errorResult("Missing required parameter: query")
        if (query.isBlank()) {
            return errorResult("Missing required parameter: query")
        }

        val maxResults = (args["max_results"] as? JsonPrimitive)
            ?.content
            ?.toIntOrNull()
            ?.coerceIn(1, MAX_RESULTS_LIMIT)
            ?: DEFAULT_MAX_RESULTS
        val normalizedQuery = query.trim()
        val plannedQueries = normalizeWebSearchQueries(normalizedQuery)

        return try {
            val results = mutableListOf<SearchResult>()
            for (plannedQuery in plannedQueries) {
                val encodedQuery = URLEncoder.encode(plannedQuery, "UTF-8")
                if (results.isEmpty()) {
                    results += safeSearch { searchInstantAnswer(encodedQuery, maxResults) }
                }
                if (results.size < maxResults) {
                    results += safeSearch { searchDuckDuckGoHtml(encodedQuery, maxResults - results.size) }
                }
                if (results.size < maxResults) {
                    results += safeSearch { searchDuckDuckGoLite(encodedQuery, maxResults - results.size) }
                }
                if (results.size < maxResults) {
                    results += safeSearch { searchBingHtml(encodedQuery, maxResults - results.size) }
                }
                if (results.size < maxResults) {
                    results += safeSearch { searchGoogleNewsRss(encodedQuery, maxResults - results.size) }
                }
                if (results.distinctBy { it.url.ifBlank { it.title } }.size >= maxResults) {
                    break
                }
            }

            formatResults(
                results = results
                    .distinctBy { it.url.ifBlank { it.title } }
                    .take(maxResults),
                query = normalizedQuery,
                plannedQueries = plannedQueries
            )
        } catch (e: java.net.SocketTimeoutException) {
            errorResult("Search request timed out. Please try again.")
        } catch (e: java.net.UnknownHostException) {
            errorResult("No internet connection available. Please check your network settings.")
        } catch (e: Exception) {
            errorResult("Error performing web search: ${e.message}")
        }
    }

    private fun searchInstantAnswer(encodedQuery: String, maxResults: Int): List<SearchResult> {
        val url = "https://api.duckduckgo.com/?q=$encodedQuery&format=json&no_html=1&skip_disambig=1"
        return searchClient.newCall(searchRequest(url)).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val responseBody = response.body?.string().orEmpty()
            if (responseBody.isBlank()) return emptyList()
            parseInstantAnswerResponse(responseBody, maxResults)
        }
    }

    private fun searchDuckDuckGoHtml(encodedQuery: String, maxResults: Int): List<SearchResult> {
        if (maxResults <= 0) return emptyList()
        val urls = listOf(
            "https://html.duckduckgo.com/html/?q=$encodedQuery",
            "https://duckduckgo.com/html/?q=$encodedQuery"
        )
        for (url in urls) {
            val results = searchClient.newCall(searchRequest(url)).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList()
                val responseBody = response.body?.string().orEmpty()
                if (responseBody.isBlank()) return@use emptyList()
                parseHtmlSearchResults(responseBody, maxResults)
            }
            if (results.isNotEmpty()) return results
        }
        return emptyList()
    }

    private fun searchDuckDuckGoLite(encodedQuery: String, maxResults: Int): List<SearchResult> {
        if (maxResults <= 0) return emptyList()
        val url = "https://lite.duckduckgo.com/lite/?q=$encodedQuery"
        return searchClient.newCall(searchRequest(url)).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val responseBody = response.body?.string().orEmpty()
            if (responseBody.isBlank()) return emptyList()
            parseLiteSearchResults(responseBody, maxResults)
        }
    }

    private fun searchBingHtml(encodedQuery: String, maxResults: Int): List<SearchResult> {
        if (maxResults <= 0) return emptyList()
        val url = "https://www.bing.com/search?q=$encodedQuery"
        return searchClient.newCall(searchRequest(url)).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val responseBody = response.body?.string().orEmpty()
            if (responseBody.isBlank()) return emptyList()
            parseBingSearchResults(responseBody, maxResults)
        }
    }

    private fun searchGoogleNewsRss(encodedQuery: String, maxResults: Int): List<SearchResult> {
        if (maxResults <= 0) return emptyList()
        val url = "https://news.google.com/rss/search?q=$encodedQuery&hl=en-US&gl=US&ceid=US:en"
        return searchClient.newCall(searchRequest(url)).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val responseBody = response.body?.string().orEmpty()
            if (responseBody.isBlank()) return emptyList()
            parseGoogleNewsRss(responseBody, maxResults)
        }
    }

    private fun searchRequest(url: String) = Request.Builder()
        .url(url)
        .header("User-Agent", "Mozilla/5.0 (Android) Localyze/1.0")
        .header("Accept", "text/html,application/json")
        .build()

    private fun parseInstantAnswerResponse(jsonStr: String, maxResults: Int): List<SearchResult> {
        val results = mutableListOf<SearchResult>()

        try {
            val json = kotlinx.serialization.json.Json {
                ignoreUnknownKeys = true
                isLenient = true
            }
            val root = json.parseToJsonElement(jsonStr).jsonObject

            val answer = root["Answer"]?.jsonPrimitive?.content
            if (!answer.isNullOrBlank()) {
                val answerType = root["AnswerType"]?.jsonPrimitive?.content ?: ""
                results.add(
                    SearchResult(
                        title = answerType.ifBlank { "Answer" },
                        url = "",
                        snippet = answer,
                        source = "DuckDuckGo Answer"
                    )
                )
            }

            val abstract = root["Abstract"]?.jsonPrimitive?.content
            if (!abstract.isNullOrBlank() && results.size < maxResults) {
                val abstractTitle = root["Heading"]?.jsonPrimitive?.content ?: ""
                val abstractUrl = root["AbstractURL"]?.jsonPrimitive?.content ?: ""
                results.add(
                    SearchResult(
                        title = abstractTitle.ifBlank { "Instant answer" },
                        url = abstractUrl,
                        snippet = abstract,
                        source = "DuckDuckGo Instant Answer"
                    )
                )
            }

            root["Results"]?.jsonArray?.forEach { element ->
                if (results.size >= maxResults) return@forEach
                val obj = element.jsonObject
                val title = obj["Text"]?.jsonPrimitive?.content ?: ""
                val url = obj["FirstURL"]?.jsonPrimitive?.content ?: ""
                if (title.isNotBlank() || url.isNotBlank()) {
                    results.add(
                        SearchResult(
                            title = title.substringBefore(" - ").ifBlank { title },
                            url = url,
                            snippet = title,
                            source = "DuckDuckGo"
                        )
                    )
                }
            }

            root["RelatedTopics"]?.jsonArray?.forEach { element ->
                if (results.size >= maxResults) return@forEach
                runCatching {
                    val obj = element.jsonObject
                    val text = obj["Text"]?.jsonPrimitive?.content ?: ""
                    val url = obj["FirstURL"]?.jsonPrimitive?.content ?: ""
                    if (text.isNotBlank() && url.isNotBlank()) {
                        results.add(
                            SearchResult(
                                title = text.substringBefore(" - ", text),
                                url = url,
                                snippet = text,
                                source = "DuckDuckGo Related Topic"
                            )
                        )
                    }
                }
            }

            val definition = root["Definition"]?.jsonPrimitive?.content
            if (!definition.isNullOrBlank() && results.size < maxResults) {
                val defSource = root["DefinitionSource"]?.jsonPrimitive?.content ?: ""
                results.add(
                    SearchResult(
                        title = "Definition (${defSource.ifBlank { "source" }})",
                        url = root["DefinitionURL"]?.jsonPrimitive?.content ?: "",
                        snippet = definition,
                        source = "DuckDuckGo Definition"
                    )
                )
            }
        } catch (_: Exception) {
            return emptyList()
        }

        return results.take(maxResults)
    }

    private fun parseHtmlSearchResults(html: String, maxResults: Int): List<SearchResult> {
        val document = Jsoup.parse(html)
        val results = mutableListOf<SearchResult>()

        document.select(".result").forEach { item ->
            if (results.size >= maxResults) return@forEach
            val link = item.selectFirst("a.result__a") ?: return@forEach
            val title = link.text().trim()
            val url = normalizeDuckDuckGoUrl(link.attr("href"))
            val snippet = item.selectFirst(".result__snippet")?.text()?.trim().orEmpty()
            if (title.isNotBlank() && url.isNotBlank()) {
                results.add(SearchResult(title, url, snippet, "DuckDuckGo Web"))
            }
        }

        if (results.isEmpty()) {
            document.select("a.result-link, a.result__a").forEach { link ->
                if (results.size >= maxResults) return@forEach
                val title = link.text().trim()
                val url = normalizeDuckDuckGoUrl(link.attr("href"))
                if (title.isNotBlank() && url.isNotBlank()) {
                    results.add(SearchResult(title, url, "", "DuckDuckGo Web"))
                }
            }
        }

        return results
    }

    private fun parseLiteSearchResults(html: String, maxResults: Int): List<SearchResult> {
        val document = Jsoup.parse(html)
        val results = mutableListOf<SearchResult>()
        document.select("a[href]").forEach { link ->
            if (results.size >= maxResults) return@forEach
            val title = link.text().trim()
            val url = normalizeDuckDuckGoUrl(link.attr("href"))
            val isSearchResult = title.length > 3 &&
                url.startsWith("http") &&
                !url.contains("duckduckgo.com/y.js") &&
                !url.contains("duckduckgo.com/settings")
            if (isSearchResult) {
                val snippet = link.parent()?.nextElementSibling()?.text()?.trim().orEmpty()
                results.add(SearchResult(title, url, snippet, "DuckDuckGo Lite"))
            }
        }
        return results
    }

    private fun parseBingSearchResults(html: String, maxResults: Int): List<SearchResult> {
        val document = Jsoup.parse(html)
        val results = mutableListOf<SearchResult>()

        document.select("li.b_algo").forEach { item ->
            if (results.size >= maxResults) return@forEach
            val link = item.selectFirst("h2 a") ?: return@forEach
            val title = link.text().trim()
            val url = normalizeDuckDuckGoUrl(link.attr("href"))
            val snippet = item.selectFirst(".b_caption p, p")?.text()?.trim().orEmpty()
            if (title.isNotBlank() && url.startsWith("http")) {
                results.add(SearchResult(title, url, snippet, "Bing Web"))
            }
        }

        return results
    }

    private fun parseGoogleNewsRss(xml: String, maxResults: Int): List<SearchResult> {
        val document = Jsoup.parse(xml, "", Parser.xmlParser())
        val results = mutableListOf<SearchResult>()

        document.select("item").forEach { item ->
            if (results.size >= maxResults) return@forEach
            val title = item.selectFirst("title")?.text()?.trim().orEmpty()
            val url = item.selectFirst("link")?.text()?.trim().orEmpty()
            val rawSnippet = item.selectFirst("description")?.text().orEmpty()
            val snippet = Jsoup.parse(rawSnippet).text().trim()
            if (title.isNotBlank() && url.startsWith("http")) {
                results.add(SearchResult(title, url, snippet, "Google News RSS"))
            }
        }

        return results
    }

    private inline fun safeSearch(block: () -> List<SearchResult>): List<SearchResult> {
        return runCatching { block() }.getOrDefault(emptyList())
    }

    private fun formatResults(results: List<SearchResult>, query: String): String {
        return formatResults(results, query, plannedQueries = listOf(query))
    }

    private fun formatResults(
        results: List<SearchResult>,
        query: String,
        plannedQueries: List<String>
    ): String {
        if (results.isEmpty()) {
            return buildJsonObject {
                put("query", query)
                put("query_plan", JsonArray(plannedQueries.map { JsonPrimitive(it) }))
                put("results", buildJsonArray { })
                put("count", 0)
                put("fetched_at", Instant.now().toString())
                put("message", "No web results found for query")
            }.toString()
        }

        return buildJsonObject {
            put("query", query)
            put("query_plan", JsonArray(plannedQueries.map { JsonPrimitive(it) }))
            put(
                "results",
                JsonArray(results.map { result ->
                    buildJsonObject {
                        put("title", result.title)
                        put("url", result.url)
                        put("snippet", result.snippet)
                        put("source", result.source)
                    }
                })
            )
            put("count", results.size)
            put("fetched_at", Instant.now().toString())
        }.toString()
    }

    private fun normalizeDuckDuckGoUrl(rawUrl: String): String {
        val absoluteUrl = when {
            rawUrl.startsWith("//") -> "https:$rawUrl"
            rawUrl.startsWith("/") -> "https://duckduckgo.com$rawUrl"
            else -> rawUrl
        }
        val redirected = absoluteUrl.toHttpUrlOrNull()?.queryParameter("uddg")
        return if (redirected.isNullOrBlank()) {
            absoluteUrl
        } else {
            URLDecoder.decode(redirected, "UTF-8")
        }
    }

    private fun errorResult(message: String): String = buildJsonObject {
        put("error", message)
    }.toString()

    private data class SearchResult(
        val title: String,
        val url: String,
        val snippet: String,
        val source: String
    )

    private companion object {
        const val DEFAULT_MAX_RESULTS = 5
        const val MAX_RESULTS_LIMIT = 8
    }
}

internal fun normalizeWebSearchQueries(query: String, year: Int = Year.now().value): List<String> {
    val trimmed = query
        .trim()
        .replace(Regex("\\s+"), " ")
        .removeSurrounding("\"")

    if (trimmed.isBlank()) return emptyList()

    val cleaned = trimmed
        .replace(
            Regex(
                "^(please\\s+)?(search|look up|google|find|browse|check)\\s+(the\\s+)?(web|internet|online)?\\s*(for|about)?\\s*",
                RegexOption.IGNORE_CASE
            ),
            ""
        )
        .replace(Regex("^(what is|who is|tell me about|give me)\\s+", RegexOption.IGNORE_CASE), "")
        .trim(' ', '?', '.', '!')

    val queries = linkedSetOf<String>()
    queries += cleaned.ifBlank { trimmed }
    queries += trimmed

    val currentIntent = Regex(
        "\\b(latest|recent|today|current|now|news|price|version|release|schedule|deadline|trending|viral|won|winner|results?|headlines?|updates?|status|performing|announced|released|launched|202[5-9]|203\\d)\\b",
        RegexOption.IGNORE_CASE
    ).containsMatchIn(trimmed) || Regex(
        "\\b(top\\s+(news|headlines|stories|trending|movies|songs|albums)|this\\s+(week|month|year)|major\\s+categories|market\\s+performing)\\b",
        RegexOption.IGNORE_CASE
    ).containsMatchIn(trimmed)

    if (currentIntent && !cleaned.contains(year.toString())) {
        queries += "$cleaned $year"
    }

    return queries
        .map { it.trim() }
        .filter { it.length >= 2 }
        .distinct()
        .take(3)
}
