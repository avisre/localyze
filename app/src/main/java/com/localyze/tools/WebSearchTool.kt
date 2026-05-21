package com.localyze.tools

import com.localyze.data.local.SettingsDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
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
        .callTimeout(6, TimeUnit.SECONDS)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    // Separate, shorter-timeout client for the per-page readable-content
    // extraction so a single slow page can't stall the whole pipeline.
    private val contentClient = okHttpClient.newBuilder()
        .callTimeout(4, TimeUnit.SECONDS)
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

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
            withContext(Dispatchers.IO) {
                val results = mutableListOf<SearchResult>()
                val searchedProviders = linkedSetOf<String>()
                val directResults = safeSearch { directFreshResultsFor(normalizedQuery, maxResults) }
                if (directResults.isNotEmpty()) {
                    searchedProviders += "Direct live source"
                    results += directResults
                }

                if (results.size < maxResults) {
                    val providerCalls = plannedQueries.flatMap { plannedQuery ->
                        val encodedQuery = URLEncoder.encode(plannedQuery, "UTF-8")
                        providersFor(plannedQuery).map { provider ->
                            suspend {
                                provider.displayName to safeSearch {
                                    provider.search(encodedQuery, maxResults)
                                }
                            }
                        }
                    }

                    val parallelResults = withTimeoutOrNull(12_000) {
                        supervisorScope {
                            providerCalls.map { call -> async { call() } }.awaitAll()
                        }
                    } ?: emptyList()

                    for ((providerName, providerResults) in parallelResults) {
                        if (results.size >= maxResults) break
                        if (providerResults.isNotEmpty()) {
                            searchedProviders += providerName
                            results += providerResults
                        }
                    }
                }

                // Dedupe by URL, then rank by domain-trust + snippet-strength +
                // query-term overlap so we keep the best results (not just the
                // first the providers returned). Group by host afterward so
                // we don't ship 3 results from a single domain.
                val deduped = results.distinctBy { it.url.ifBlank { it.title } }
                val ranked = deduped
                    .sortedByDescending { scoreResult(it, normalizedQuery) }
                val grouped = groupByHostKeepingBest(ranked, maxResults)
                // Extract readable content from top 3 pages so the on-device
                // model has real text to summarize, not anti-scrape stub
                // snippets.
                val enriched = enrichWithExtractedContent(grouped)
                formatResults(
                    results = enriched,
                    query = normalizedQuery,
                    plannedQueries = plannedQueries,
                    searchedProviders = searchedProviders.toList()
                )
            }
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

    private fun searchWikipediaApi(encodedQuery: String, maxResults: Int): List<SearchResult> {
        if (maxResults <= 0) return emptyList()
        val url = "https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=$encodedQuery&format=json&srlimit=$maxResults&utf8=1"
        return searchClient.newCall(searchRequest(url)).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val responseBody = response.body?.string().orEmpty()
            if (responseBody.isBlank()) return emptyList()
            parseWikipediaResponse(responseBody, maxResults)
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
        .header("Accept", "application/json,application/rss+xml,text/html;q=0.9,*/*;q=0.8")
        .build()

    private fun parseInstantAnswerResponse(jsonStr: String, maxResults: Int): List<SearchResult> {
        val results = mutableListOf<SearchResult>()

        try {
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

    private fun parseWikipediaResponse(jsonStr: String, maxResults: Int): List<SearchResult> {
        return runCatching {
            val root = json.parseToJsonElement(jsonStr).jsonObject
            root["query"]?.jsonObject
                ?.get("search")?.jsonArray
                ?.mapNotNull { element ->
                    val obj = element.jsonObject
                    val title = obj["title"]?.jsonPrimitive?.content.orEmpty().trim()
                    val pageId = obj["pageid"]?.jsonPrimitive?.content.orEmpty().trim()
                    val snippet = Jsoup.parse(obj["snippet"]?.jsonPrimitive?.content.orEmpty())
                        .text()
                        .replace(Regex("\\s+"), " ")
                        .trim()
                    val timestamp = obj["timestamp"]?.jsonPrimitive?.content.orEmpty().trim()
                    val url = when {
                        pageId.isNotBlank() -> "https://en.wikipedia.org/?curid=$pageId"
                        title.isNotBlank() -> "https://en.wikipedia.org/wiki/${URLEncoder.encode(title.replace(' ', '_'), "UTF-8")}"
                        else -> ""
                    }
                    val enrichedSnippet = listOf(
                        snippet,
                        timestamp.takeIf { it.isNotBlank() }?.let { "Last indexed: $it" }
                    ).filterNotNull().filter { it.isNotBlank() }.joinToString(" ")
                    if (title.isNotBlank() && url.isNotBlank()) {
                        SearchResult(title, url, enrichedSnippet, "Wikipedia API")
                    } else {
                        null
                    }
                }
                ?.take(maxResults)
                .orEmpty()
        }.getOrDefault(emptyList())
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
            val snippet = Jsoup.parse(rawSnippet).text()
                .replace(Regex("\\s+"), " ")
                .trim()
            val sourceName = item.selectFirst("source")?.text()?.trim().orEmpty()
            val sourceUrl = item.selectFirst("source")?.attr("url")?.trim().orEmpty()
            val publishedAt = item.selectFirst("pubDate")?.text()?.trim().orEmpty()
            val enrichedSnippet = listOf(
                snippet,
                sourceName.takeIf { it.isNotBlank() }?.let { "Source: $it" },
                sourceUrl.takeIf { it.startsWith("http") }?.let { "Publisher: $it" },
                publishedAt.takeIf { it.isNotBlank() }?.let { "Published: $it" }
            ).filterNotNull().filter { it.isNotBlank() }.joinToString(" ")
            if (title.isNotBlank() && url.startsWith("http")) {
                results.add(SearchResult(title, url, enrichedSnippet, "Google News RSS"))
            }
        }

        return results
    }

    private fun directFreshResultsFor(query: String, maxResults: Int): List<SearchResult> {
        if (maxResults <= 0) return emptyList()
        val lower = query.lowercase()
        // Multi-year company-financials intent must be checked BEFORE the
        // simple "current AAPL price" branch — otherwise a query like
        // "Apple revenue last 3 fiscal years" gets routed to the spot-quote
        // path and never reaches SEC EDGAR.
        val companyFinancialIntent = parseCompanyFinancialIntent(query)
        if (companyFinancialIntent != null) {
            return fetchStockFinancials(companyFinancialIntent, maxResults)
        }
        return when {
            lower.contains("weather") && lower.contains("new york") ->
                fetchNewYorkWeather().take(maxResults)
            lower.contains("bitcoin") || lower.contains("btc") ->
                fetchBitcoinPrice().take(maxResults)
            lower.contains("usd") && lower.contains("eur") && lower.contains("exchange") ->
                fetchUsdEurRate().take(maxResults)
            lower.contains("aapl") || lower.contains("apple stock") || lower.contains("stock price of apple") ->
                fetchAaplPrice().take(maxResults)
            (lower.contains("twitter") || Regex("\\bx\\b").containsMatchIn(lower)) && lower.contains("trend") ->
                fetchTwitterTrends().take(maxResults)
            lower.contains("bbc") && (lower.contains("headline") || lower.contains("news")) ->
                fetchBbcHeadlines(maxResults)
            else -> emptyList()
        }
    }

    private fun fetchStockFinancials(
        intent: CompanyFinancialIntent,
        maxResults: Int
    ): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        intent.companies.forEach { company ->
            if (results.size >= maxResults) return@forEach
            val officialEvidence = fetchOfficialCompanyEvidence(company, intent.metric)
            // SEC XBRL only works for US-listed companies (non-empty CIK).
            val secPoints = if (company.cik.isNotBlank()) {
                fetchSecAnnualFinancials(company, intent.metric, intent.years)
            } else emptyList()
            // For any company without SEC data (Indian, Chinese, Vietnamese,
            // etc.), fall back to the curated annual-report fact table so we
            // can still produce a numeric visualization instead of letting
            // the model hedge. Trigger condition: no SEC source available
            // (empty CIK) or SEC fetch returned nothing.
            val nativeFallback = if (secPoints.isEmpty() && lookupNativeFinancialPoints(company, intent.metric, intent.years).isNotEmpty()) {
                lookupNativeFinancialPoints(company, intent.metric, intent.years)
            } else emptyList()
            val points = secPoints.ifEmpty { nativeFallback }
            if (officialEvidence == null && points.isEmpty()) return@forEach

            results.add(
                SearchResult(
                    title = "${company.name} ${intent.metric.displayName} RAG data",
                    url = officialEvidence?.url ?: secBrowseUrl(company),
                    snippet = buildFinancialRagSnippet(
                        company = company,
                        metric = intent.metric,
                        years = intent.years,
                        points = points,
                        officialEvidence = officialEvidence
                    ),
                    source = if (officialEvidence != null) {
                        "${company.ticker} official website and SEC XBRL"
                    } else {
                        "SEC EDGAR company facts"
                    }
                )
            )

            if (results.size < maxResults && secPoints.isNotEmpty()) {
                results.add(
                    SearchResult(
                        title = "${company.ticker} SEC XBRL company facts",
                        url = secBrowseUrl(company),
                        snippet = buildSecFactsSnippet(company, intent.metric, secPoints),
                        source = "SEC EDGAR company facts"
                    )
                )
            }
        }
        return results.take(maxResults)
    }

    private fun fetchOfficialCompanyEvidence(
        company: CompanyProfile,
        metric: FinancialMetric
    ): SearchResult? {
        return company.officialUrls.asSequence().mapNotNull { url ->
            runCatching {
                val body = searchClient.newCall(searchRequest(url)).execute().use { response ->
                    if (!response.isSuccessful) return@runCatching null
                    response.body?.string().orEmpty()
                }
                if (body.isBlank()) return@runCatching null
                val document = Jsoup.parse(body, url)
                val title = document.selectFirst("h1, .article-heading, title")
                    ?.text()
                    ?.replace(Regex("\\s+"), " ")
                    ?.trim()
                    .orEmpty()
                    .ifBlank { "${company.name} investor relations" }
                val pageText = document.body()
                    ?.text()
                    ?.replace(Regex("\\s+"), " ")
                    ?.trim()
                    .orEmpty()
                val snippet = extractOfficialFinancialSnippet(pageText, metric)
                    .ifBlank { "Crawled ${company.name}'s official investor-relations page for financial context." }
                SearchResult(
                    title = title,
                    url = url,
                    snippet = snippet,
                    source = "${company.ticker} official website"
                )
            }.getOrNull()
        }.firstOrNull()
    }

    private fun fetchSecAnnualFinancials(
        company: CompanyProfile,
        metric: FinancialMetric,
        years: Int
    ): List<AnnualFinancialPoint> {
        val edgarUrl = "https://data.sec.gov/api/xbrl/companyfacts/CIK${company.cik}.json"
        val body = searchClient.newCall(
            searchRequest(edgarUrl).newBuilder()
                .header("User-Agent", "Localyze/1.0 (contact@localyze.app)")
                .build()
        ).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            response.body?.string().orEmpty()
        }
        if (body.isBlank()) return emptyList()
        return parseAnnualFinancialPointsFromCompanyFactsJson(body, metric, years)
    }

    private fun buildFinancialRagSnippet(
        company: CompanyProfile,
        metric: FinancialMetric,
        years: Int,
        points: List<AnnualFinancialPoint>,
        officialEvidence: SearchResult?
    ): String {
        return buildString {
            appendLine("Company: ${company.name} (${company.ticker})")
            appendLine("Metric: ${metric.displayName}")
            appendLine("Requested years: $years")
            if (officialEvidence != null) {
                appendLine("Crawled official website: ${officialEvidence.title} - ${officialEvidence.url}")
                appendLine("Official website excerpt: ${officialEvidence.snippet}")
            }
            if (points.isNotEmpty()) {
                appendLine("Annual data:")
                points.forEach { point ->
                    appendLine(formatPointForSnippet(point, company.currency))
                }
            }
            val basis = if (officialEvidence != null) {
                "RAG basis: official company website pages were crawled for context; published annual-report figures supplied the structured annual values."
            } else {
                "RAG basis: published annual-report figures supplied the structured annual values; no official company website excerpt was available in this run."
            }
            appendLine(basis)
        }.trim()
    }

    private fun formatPointForSnippet(point: AnnualFinancialPoint, currency: String): String {
        val v = point.valueUsd
        val sign = if (v < 0) "-" else ""
        val absBillions = kotlin.math.abs(v) / 1_000_000_000.0
        val absBillionsStr = "%.1f".format(absBillions)
        return when (currency) {
            "INR" -> {
                val crore = (kotlin.math.abs(v) / 10_000_000.0).toLong()
                "FY${point.fiscalYear}: $sign₹${"%,d".format(crore)} crore"
            }
            "CNY" -> "FY${point.fiscalYear}: $sign¥${absBillionsStr} billion"
            "EUR" -> "FY${point.fiscalYear}: $sign€${absBillionsStr}B"
            "GBP" -> "FY${point.fiscalYear}: $sign£${absBillionsStr}B"
            "CAD" -> "FY${point.fiscalYear}: ${sign}C\$${absBillionsStr}B"
            "CHF" -> "FY${point.fiscalYear}: ${sign}CHF ${absBillionsStr}B"
            "MXN" -> "FY${point.fiscalYear}: ${sign}MX\$${absBillionsStr}B"
            "JPY" -> "FY${point.fiscalYear}: $sign¥${absBillionsStr}B"
            else -> "FY${point.fiscalYear}: $sign\$${absBillionsStr}B"
        }
    }

    private fun buildSecFactsSnippet(
        company: CompanyProfile,
        metric: FinancialMetric,
        points: List<AnnualFinancialPoint>
    ): String {
        return buildString {
            appendLine("Company: ${company.name} (${company.ticker})")
            appendLine("Metric: ${metric.displayName}")
            appendLine("Annual data:")
            points.forEach { point ->
                appendLine("FY${point.fiscalYear}: \$${formatUsdBillions(point.valueUsd)}B")
            }
            appendLine("Source detail: SEC EDGAR XBRL company facts, latest annual 10-K facts by fiscal period end.")
        }.trim()
    }

    private fun extractOfficialFinancialSnippet(text: String, metric: FinancialMetric): String {
        if (text.isBlank()) return ""
        val metricPattern = when (metric) {
            FinancialMetric.REVENUE -> "revenue|revenues|sales|net sales|turnover|operating revenue"
            FinancialMetric.NET_INCOME -> "net income|profit|earnings|net profit|profit after tax"
            FinancialMetric.TOTAL_ASSETS -> "total assets|assets|book value"
        }
        // US-style: "For the full year 2024, revenue was $X.XB"
        val usStyle = Regex(
            """For the full year\s+20\d{2}.{0,420}?(?:$metricPattern).{0,220}?(?:\.|$)""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).find(text)?.value

        // Indian-style: "Revenue for FY 2024 was ₹2,40,893 crore" or "FY24 revenue
        // stood at Rs. 2,40,893 crore" or "Year ended March 31, 2024 ... ₹X crore".
        val indianStyle = Regex(
            """(?:FY\s*'?\d{2,4}|fiscal\s+year\s+\d{4}|year\s+ended\s+(?:march\s+31[,\s]+)?\d{4}).{0,260}?(?:$metricPattern).{0,180}?(?:₹|Rs\.?|INR)?\s*[\d,.]+\s*(?:lakh\s+crore|crore|cr|lakh)""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).find(text)?.value

        // Chinese-style: "Revenue for FY2024 was ¥X.XB" or "Total revenue ... CNY X billion".
        val chineseStyle = Regex(
            """(?:FY\s*'?\d{2,4}|fiscal\s+year\s+\d{4}|full\s+year\s+\d{4}).{0,260}?(?:$metricPattern).{0,180}?(?:¥|CNY|RMB)\s*[\d,.]+\s*(?:billion|million|B|bn|M)?""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).find(text)?.value

        val sentence = usStyle ?: indianStyle ?: chineseStyle
        if (!sentence.isNullOrBlank()) {
            return sentence.replace(Regex("\\s+"), " ").trim().take(900)
        }

        val firstMetricIndex = Regex(metricPattern, RegexOption.IGNORE_CASE).find(text)?.range?.first ?: return ""
        val start = (firstMetricIndex - 220).coerceAtLeast(0)
        val end = (firstMetricIndex + 480).coerceAtMost(text.length)
        return text.substring(start, end).replace(Regex("\\s+"), " ").trim()
    }

    private fun secBrowseUrl(company: CompanyProfile): String {
        return "https://www.sec.gov/cgi-bin/browse-edgar?action=getcompany&CIK=${company.cik}&type=10-K"
    }

    private fun formatUsdBillions(valueUsd: Long): String {
        return "%.1f".format(valueUsd / 1_000_000_000.0)
    }

    private fun fetchWttrWeather(location: String): List<SearchResult> {
        val encodedLocation = URLEncoder.encode(location, "UTF-8")
        val apiUrl = "https://wttr.in/$encodedLocation?format=j1"
        val pageUrl = "https://wttr.in/$encodedLocation"
        val body = searchClient.newCall(searchRequest(apiUrl)).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            response.body?.string().orEmpty()
        }
        val root = json.parseToJsonElement(body).jsonObject
        val current = root["current_condition"]?.jsonArray?.firstOrNull()?.jsonObject ?: return emptyList()
        val description = current["weatherDesc"]?.jsonArray
            ?.firstOrNull()
            ?.jsonObject
            ?.get("value")
            ?.jsonPrimitive
            ?.content
            .orEmpty()
        val tempC = current["temp_C"]?.jsonPrimitive?.content.orEmpty()
        val tempF = current["temp_F"]?.jsonPrimitive?.content.orEmpty()
        val feelsF = current["FeelsLikeF"]?.jsonPrimitive?.content.orEmpty()
        val humidity = current["humidity"]?.jsonPrimitive?.content.orEmpty()
        val windMph = current["windspeedMiles"]?.jsonPrimitive?.content.orEmpty()
        val snippet = listOfNotNull(
            description.ifBlank { null }?.let { "Conditions: $it" },
            tempF.ifBlank { null }?.let { "Temperature: ${it}F" },
            tempC.ifBlank { null }?.let { "(${it}C)" },
            feelsF.ifBlank { null }?.let { "feels like ${it}F" },
            humidity.ifBlank { null }?.let { "humidity $it%" },
            windMph.ifBlank { null }?.let { "wind $it mph" },
            "Fetched: ${Instant.now()}"
        ).joinToString("; ")
        return listOf(
            SearchResult(
                title = "Current weather in $location",
                url = pageUrl,
                snippet = snippet,
                source = "wttr.in"
            )
        )
    }

    private fun fetchNewYorkWeather(): List<SearchResult> {
        val apiUrl = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=40.7128&longitude=-74.0060" +
            "&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m" +
            "&temperature_unit=fahrenheit&wind_speed_unit=mph&timezone=America%2FNew_York"
        val body = searchClient.newCall(searchRequest(apiUrl)).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            response.body?.string().orEmpty()
        }
        val root = json.parseToJsonElement(body).jsonObject
        val current = root["current"]?.jsonObject ?: return emptyList()
        val tempF = current["temperature_2m"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return emptyList()
        val feelsF = current["apparent_temperature"]?.jsonPrimitive?.content?.toDoubleOrNull()
        val humidity = current["relative_humidity_2m"]?.jsonPrimitive?.content?.toIntOrNull()
        val windMph = current["wind_speed_10m"]?.jsonPrimitive?.content?.toDoubleOrNull()
        val weatherCode = current["weather_code"]?.jsonPrimitive?.content?.toIntOrNull()
        val observedAt = current["time"]?.jsonPrimitive?.content.orEmpty()
        val snippet = listOfNotNull(
            "Conditions: ${weatherCode?.let(::weatherDescriptionFor) ?: "current observation"}",
            "Temperature: ${"%.0f".format(tempF)}F",
            feelsF?.let { "feels like ${"%.0f".format(it)}F" },
            humidity?.let { "humidity $it%" },
            windMph?.let { "wind ${"%.0f".format(it)} mph" },
            observedAt.takeIf { it.isNotBlank() }?.let { "observed: $it" },
            "Fetched: ${Instant.now()}"
        ).joinToString("; ")
        return listOf(
            SearchResult(
                title = "Current weather in New York City",
                url = "https://open-meteo.com/en/docs",
                snippet = snippet,
                source = "Open-Meteo"
            )
        )
    }

    private fun weatherDescriptionFor(code: Int): String {
        return when (code) {
            0 -> "Clear sky"
            1, 2, 3 -> "Mainly clear to partly cloudy"
            45, 48 -> "Fog"
            51, 53, 55 -> "Drizzle"
            56, 57 -> "Freezing drizzle"
            61, 63, 65 -> "Rain"
            66, 67 -> "Freezing rain"
            71, 73, 75, 77 -> "Snow"
            80, 81, 82 -> "Rain showers"
            85, 86 -> "Snow showers"
            95, 96, 99 -> "Thunderstorm"
            else -> "Current observation"
        }
    }

    private fun fetchBitcoinPrice(): List<SearchResult> {
        val url = "https://api.coingecko.com/api/v3/simple/price?ids=bitcoin&vs_currencies=usd&include_last_updated_at=true"
        val body = searchClient.newCall(searchRequest(url)).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            response.body?.string().orEmpty()
        }
        val bitcoin = json.parseToJsonElement(body).jsonObject["bitcoin"]?.jsonObject ?: return emptyList()
        val usd = bitcoin["usd"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return emptyList()
        val updatedAt = bitcoin["last_updated_at"]?.jsonPrimitive?.content?.toLongOrNull()
            ?.let { Instant.ofEpochSecond(it).toString() }
            ?: Instant.now().toString()
        return listOf(
            SearchResult(
                title = "Bitcoin BTC price in USD",
                url = "https://www.coingecko.com/en/coins/bitcoin",
                snippet = "Bitcoin is trading at about \$${"%,.2f".format(usd)} USD. Updated: $updatedAt.",
                source = "CoinGecko API"
            )
        )
    }

    private fun fetchUsdEurRate(): List<SearchResult> {
        val url = "https://open.er-api.com/v6/latest/USD"
        val body = searchClient.newCall(searchRequest(url)).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            response.body?.string().orEmpty()
        }
        val root = json.parseToJsonElement(body).jsonObject
        val eur = root["rates"]?.jsonObject?.get("EUR")?.jsonPrimitive?.content?.toDoubleOrNull() ?: return emptyList()
        val updated = root["time_last_update_utc"]?.jsonPrimitive?.content ?: Instant.now().toString()
        return listOf(
            SearchResult(
                title = "USD to EUR exchange rate",
                url = "https://www.exchangerate-api.com/",
                snippet = "1 USD is about ${"%.4f".format(eur)} EUR. Updated: $updated.",
                source = "ExchangeRate-API"
            )
        )
    }

    private fun fetchAaplPrice(): List<SearchResult> {
        val url = "https://stooq.com/q/l/?s=aapl.us&i=d"
        val body = searchClient.newCall(searchRequest(url)).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            response.body?.string().orEmpty()
        }
        val lines = body.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.isEmpty()) return emptyList()
        val first = lines.first().split(",")
        val row = if (first.any { it.equals("Symbol", ignoreCase = true) } && lines.size >= 2) {
            val values = lines[1].split(",")
            first.zip(values).toMap()
        } else if (first.size >= 7) {
            mapOf(
                "Date" to first[1],
                "Time" to first[2],
                "Open" to first[3],
                "High" to first[4],
                "Low" to first[5],
                "Close" to first[6]
            )
        } else {
            return emptyList()
        }
        val close = row["Close"]?.takeUnless { it.equals("N/D", ignoreCase = true) } ?: return emptyList()
        val date = row["Date"].orEmpty()
        val time = row["Time"].orEmpty()
        return listOf(
            SearchResult(
                title = "Apple AAPL stock price",
                url = "https://stooq.com/q/?s=aapl.us",
                snippet = "AAPL latest quoted close is \$$close. Quote date/time: $date $time. Market data may be delayed.",
                source = "Stooq"
            )
        )
    }

    private fun fetchTwitterTrends(): List<SearchResult> {
        val url = "https://trends24.in/united-states/"
        val html = searchClient.newCall(searchRequest(url)).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            response.body?.string().orEmpty()
        }
        val document = Jsoup.parse(html)
        val description = document
            .selectFirst("meta[name=description]")
            ?.attr("content")
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            .orEmpty()
        val topics = description
            .substringAfter("United States:", "")
            .substringBefore("Explore")
            .trim()
            .trimEnd('.')
        if (topics.isBlank()) return emptyList()
        return listOf(
            SearchResult(
                title = "United States X/Twitter trending topics",
                url = url,
                snippet = "Top United States X/Twitter trends right now: $topics.",
                source = "trends24.in"
            )
        )
    }

    private fun fetchBbcHeadlines(maxResults: Int): List<SearchResult> {
        val url = "https://feeds.bbci.co.uk/news/rss.xml"
        val xml = searchClient.newCall(searchRequest(url)).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            response.body?.string().orEmpty()
        }
        val document = Jsoup.parse(xml, "", Parser.xmlParser())
        return document.select("item")
            .mapNotNull { item ->
                val title = item.selectFirst("title")?.text()?.trim().orEmpty()
                val link = item.selectFirst("link")?.text()?.trim().orEmpty()
                val description = item.selectFirst("description")?.text()?.trim().orEmpty()
                val publishedAt = item.selectFirst("pubDate")?.text()?.trim().orEmpty()
                if (title.isBlank() || !link.startsWith("http")) {
                    null
                } else {
                    SearchResult(
                        title = title,
                        url = link,
                        snippet = listOf(
                            description,
                            publishedAt.takeIf { it.isNotBlank() }?.let { "Published: $it" }
                        ).filterNotNull().filter { it.isNotBlank() }.joinToString(" "),
                        source = "BBC RSS"
                    )
                }
            }
            .take(maxResults)
    }

    private inline fun safeSearch(block: () -> List<SearchResult>): List<SearchResult> {
        return runCatching { block() }.getOrDefault(emptyList())
    }

    // Score a result by domain trust + snippet strength + query-term overlap.
    // Higher is better. Used to sort before .take(maxResults) so we keep the
    // strongest results regardless of which provider returned them first.
    private fun scoreResult(result: SearchResult, query: String): Int {
        val host = hostOf(result.url).lowercase()
        val trust = domainTrustFor(host)
        val snippetScore = (result.snippet.length / 100).coerceAtMost(3)
        val queryTerms = query.lowercase()
            .split(Regex("\\W+"))
            .filter { it.length >= 3 }
            .toSet()
        val haystack = (result.title + " " + result.snippet).lowercase()
        val overlap = queryTerms.count { term -> haystack.contains(term) }
        return trust + snippetScore + overlap
    }

    private fun domainTrustFor(host: String): Int {
        if (host.isBlank()) return 0
        // gov/edu suffix check
        if (host.endsWith(".gov") || host.contains(".gov.")) return 5
        if (host.endsWith(".edu") || host.contains(".edu.")) return 4
        // Match against the static map by suffix so subdomains
        // (en.wikipedia.org, www.bbc.com) still get credit.
        for ((domain, score) in DOMAIN_TRUST) {
            if (host == domain || host.endsWith(".$domain")) return score
        }
        return 0
    }

    private fun hostOf(url: String): String {
        if (url.isBlank()) return ""
        return runCatching { java.net.URI(url).host.orEmpty() }.getOrDefault("")
    }

    // Round-robin pick results across distinct hosts so we don't ship three
    // results from the same domain. Falls back to remaining results once each
    // host bucket is exhausted, in case maxResults > distinct host count.
    private fun groupByHostKeepingBest(
        ranked: List<SearchResult>,
        maxResults: Int
    ): List<SearchResult> {
        if (ranked.isEmpty() || maxResults <= 0) return emptyList()
        val byHost = linkedMapOf<String, MutableList<SearchResult>>()
        ranked.forEach { r ->
            val host = hostOf(r.url).ifBlank { r.source }
            byHost.getOrPut(host) { mutableListOf() }.add(r)
        }
        val picked = mutableListOf<SearchResult>()
        // First pass: one per host (best-scoring, since ranked was sorted).
        for ((_, list) in byHost) {
            if (picked.size >= maxResults) break
            picked.add(list.removeAt(0))
        }
        // Second pass: fill remaining slots from leftover entries.
        if (picked.size < maxResults) {
            val leftover = byHost.values.flatten()
            for (r in leftover) {
                if (picked.size >= maxResults) break
                picked.add(r)
            }
        }
        return picked
    }

    // Fetch the top 3 results and replace their snippet with extracted readable
    // body text. Skip when the snippet is already strong, when the response is
    // not HTML, or on any failure. Tolerant: failure to extract one page must
    // not block the others, so each call is wrapped in runCatching.
    private fun enrichWithExtractedContent(results: List<SearchResult>): List<SearchResult> {
        if (results.isEmpty()) return results
        return results.mapIndexed { index, result ->
            if (index >= 3) return@mapIndexed result
            if (result.snippet.length >= 150) return@mapIndexed result
            if (result.url.isBlank() || !result.url.startsWith("http")) return@mapIndexed result
            runCatching {
                contentClient.newCall(searchRequest(result.url)).execute().use { response ->
                    if (!response.isSuccessful) return@use result
                    val contentType = response.header("Content-Type").orEmpty().lowercase()
                    if (contentType.isNotBlank() &&
                        !contentType.contains("html") &&
                        !contentType.contains("xml") &&
                        !contentType.contains("text/plain")
                    ) {
                        return@use result
                    }
                    val source = response.body?.source() ?: return@use result
                    // Cap body read at 500KB to keep memory bounded.
                    source.request(MAX_EXTRACT_BYTES)
                    val buffer = source.buffer.snapshot(
                        minOf(source.buffer.size, MAX_EXTRACT_BYTES).toInt()
                    )
                    val html = buffer.utf8()
                    if (html.isBlank()) return@use result
                    val extracted = extractReadable(html)
                    if (extracted.isBlank()) result
                    else result.copy(snippet = extracted)
                }
            }.getOrDefault(result)
        }
    }

    // Pull readable text from an HTML page. Strategy: <article>, then <main>,
    // then the longest cluster of <p> tags. Nav/script/style is stripped.
    // Returns 500-800 chars of clean text or empty if nothing usable is found.
    private fun extractReadable(html: String): String {
        if (html.isBlank()) return ""
        return runCatching {
            val doc = Jsoup.parse(html)
            doc.select("script, style, nav, header, footer, aside, noscript, iframe, form").remove()

            val article = doc.selectFirst("article")?.text()?.cleanedWhitespace().orEmpty()
            if (article.length >= 200) return@runCatching truncateReadable(article)

            val main = doc.selectFirst("main")?.text()?.cleanedWhitespace().orEmpty()
            if (main.length >= 200) return@runCatching truncateReadable(main)

            // Longest <p> cluster: pick the parent whose direct <p> children
            // have the most total text. Common pattern for news/blog bodies.
            val paragraphs = doc.select("p")
            if (paragraphs.isEmpty()) return@runCatching ""
            val byParent = paragraphs.groupBy { it.parent() }
            val bestCluster = byParent.maxByOrNull { (_, ps) ->
                ps.sumOf { it.text().length }
            }?.value.orEmpty()
            val clusterText = bestCluster.joinToString(" ") { it.text() }.cleanedWhitespace()
            if (clusterText.length >= 100) return@runCatching truncateReadable(clusterText)

            // Last resort: join all paragraphs.
            paragraphs.joinToString(" ") { it.text() }.cleanedWhitespace().let(::truncateReadable)
        }.getOrDefault("")
    }

    private fun String.cleanedWhitespace(): String =
        this.replace(Regex("\\s+"), " ").trim()

    private fun truncateReadable(text: String): String {
        if (text.length <= 800) return text
        // Prefer cutting at the last sentence boundary inside the 500-800 window.
        val window = text.substring(0, 800)
        val cut = window.lastIndexOfAny(charArrayOf('.', '!', '?'), startIndex = 799)
        return if (cut >= 500) window.substring(0, cut + 1) else window
    }

    private fun formatResults(results: List<SearchResult>, query: String): String {
        return formatResults(results, query, plannedQueries = listOf(query), searchedProviders = emptyList())
    }

    private fun formatResults(
        results: List<SearchResult>,
        query: String,
        plannedQueries: List<String>,
        searchedProviders: List<String>
    ): String {
        if (results.isEmpty()) {
            return buildJsonObject {
                put("query", query)
                put("query_plan", JsonArray(plannedQueries.map { JsonPrimitive(it) }))
                put("searched_providers", JsonArray(searchedProviders.map { JsonPrimitive(it) }))
                put("results", buildJsonArray { })
                put("count", 0)
                put("fetched_at", Instant.now().toString())
                put("message", "No web results found for query")
            }.toString()
        }

        val enrichedResults = results.map { result ->
            // When snippets are empty (common due to anti-scraping), fall back to the title
            // so the model always has text to synthesize from.
            val effectiveSnippet = result.snippet.takeIf { it.isNotBlank() }
                ?: result.title.takeIf { it.isNotBlank() }
                ?: "No description available."
            result.copy(snippet = effectiveSnippet)
        }

        return buildJsonObject {
            put("query", query)
            put("query_plan", JsonArray(plannedQueries.map { JsonPrimitive(it) }))
            put("searched_providers", JsonArray(searchedProviders.map { JsonPrimitive(it) }))
            put(
                "synthesis_instruction",
                "Synthesize a useful answer from the titles and snippets below. " +
                    "Extract key facts, names, dates, and figures. Do not simply list links."
            )
            put(
                "results",
                JsonArray(enrichedResults.map { result ->
                    buildJsonObject {
                        put("title", result.title)
                        put("url", result.url)
                        put("snippet", result.snippet)
                        put("source", result.source)
                    }
                })
            )
            put("count", enrichedResults.size)
            put("fetched_at", Instant.now().toString())
        }.toString()
    }

    private fun providersFor(query: String): List<SearchProvider> {
        return if (requiresNewsFirst(query)) {
            listOf(
                SearchProvider("Google News RSS", ::searchGoogleNewsRss),
                SearchProvider("Bing Web", ::searchBingHtml),
                SearchProvider("Wikipedia API", ::searchWikipediaApi),
                SearchProvider("DuckDuckGo Lite", ::searchDuckDuckGoLite),
                SearchProvider("DuckDuckGo Instant Answer", ::searchInstantAnswer),
                SearchProvider("DuckDuckGo Web", ::searchDuckDuckGoHtml)
            )
        } else if (requiresFreshSources(query)) {
            listOf(
                SearchProvider("Bing Web", ::searchBingHtml),
                SearchProvider("DuckDuckGo Lite", ::searchDuckDuckGoLite),
                SearchProvider("DuckDuckGo Instant Answer", ::searchInstantAnswer),
                SearchProvider("Wikipedia API", ::searchWikipediaApi),
                SearchProvider("Google News RSS", ::searchGoogleNewsRss),
                SearchProvider("DuckDuckGo Web", ::searchDuckDuckGoHtml)
            )
        } else {
            listOf(
                SearchProvider("Wikipedia API", ::searchWikipediaApi),
                SearchProvider("Bing Web", ::searchBingHtml),
                SearchProvider("Google News RSS", ::searchGoogleNewsRss),
                SearchProvider("DuckDuckGo Instant Answer", ::searchInstantAnswer),
                SearchProvider("DuckDuckGo Lite", ::searchDuckDuckGoLite),
                SearchProvider("DuckDuckGo Web", ::searchDuckDuckGoHtml)
            )
        }
    }

    private data class SearchProvider(
        val displayName: String,
        val search: (encodedQuery: String, maxResults: Int) -> List<SearchResult>
    )

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
        const val MAX_EXTRACT_BYTES = 500L * 1024L

        // Static domain-trust map. gov/edu are handled separately via host
        // suffix in domainTrustFor(). Positive = boost, negative = penalize.
        private val DOMAIN_TRUST = mapOf(
            "wikipedia.org" to 5,
            "reuters.com" to 4,
            "bbc.com" to 4,
            "apnews.com" to 4,
            "nytimes.com" to 3,
            "github.com" to 3,
            "stackoverflow.com" to 3,
            "medium.com" to -1,
            "quora.com" to -1,
            "pinterest.com" to -3
        )
    }
}

internal fun requiresFreshSources(query: String): Boolean {
    return Regex(
        "\\b(latest|recent|today|current|now|news|price|weather|schedule|deadline|release notes?|version|api changes?|breaking changes?|stock|score|trending|viral|won|winner|results?|headlines?|updates?|status|performing|announced|released|launched|202[0-9]|203\\d)\\b",
        RegexOption.IGNORE_CASE
    ).containsMatchIn(query) || Regex(
        "\\b(top\\s+(news|headlines|stories|trending|movies|songs|albums)|this\\s+(week|month|year)|major\\s+categories|market\\s+performing)\\b",
        RegexOption.IGNORE_CASE
    ).containsMatchIn(query)
}

internal fun requiresNewsFirst(query: String): Boolean {
    return Regex(
        "\\b(latest\\s+news|news\\s+about|today'?s\\s+headlines?|top\\s+(news|headlines|stories)|breaking\\s+news|headlines\\s+from)\\b",
        RegexOption.IGNORE_CASE
    ).containsMatchIn(query)
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

    val currentIntent = requiresFreshSources(trimmed)

    if (currentIntent && !cleaned.contains(year.toString())) {
        queries += "$cleaned $year"
    }

    return queries
        .map { it.trim() }
        .filter { it.length >= 2 }
        .distinct()
        .take(3)
}
