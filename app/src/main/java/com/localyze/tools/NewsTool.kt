package com.localyze.tools

import com.localyze.utils.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.net.URLEncoder
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Direct news lookup via Google News RSS (free, no API key).
 *
 * Returns a JSON payload that already includes a Markdown-formatted summary
 * (numbered list with title → url, source, and short date) so the
 * recovery-path renderer can show the bulleted headlines without bouncing
 * off the model. This avoids the "single raw Google News RSS link" fallback
 * we used to get when WebSearchTool returned only one item.
 */
class NewsTool @Inject constructor(
    private val okHttpClient: OkHttpClient
) : Tool {

    override val name = "news_lookup"
    override val description =
        "Fetch the latest news headlines from Google News RSS. " +
            "Use this for questions like 'latest news from <country>', " +
            "'today's headlines', 'news about <topic>', or 'what's happening in <country>'. " +
            "Optional 'query' filters by topic; optional 'country' (2-letter ISO, default US) " +
            "controls locale. Always prefer this over web_search for news questions."

    private val client = okHttpClient.newBuilder()
        .callTimeout(7, TimeUnit.SECONDS)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(7, TimeUnit.SECONDS)
        .build()

    override fun getParameterSchema(): JsonObject = buildJsonObject {
        put("name", JsonPrimitive(name))
        put("description", JsonPrimitive(description))
        put("type", "object")
        put("properties", buildJsonObject {
            put("query", buildJsonObject {
                put("type", "string")
                put(
                    "description",
                    "Optional topic to filter headlines, e.g. 'india tech', 'Tesla', " +
                        "'AI'. If omitted, returns the top headlines for the country."
                )
            })
            put("country", buildJsonObject {
                put("type", "string")
                put(
                    "description",
                    "Optional 2-letter ISO country code (e.g. 'US', 'IN', 'GB'). Default 'US'."
                )
            })
        })
        // No required fields — both are optional.
        put("required", buildJsonArray { })
    }

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        val query = (args["query"] as? JsonPrimitive)?.content?.trim().orEmpty()
        val countryRaw = (args["country"] as? JsonPrimitive)?.content?.trim().orEmpty()
        // Resolve to a 2-letter ISO code. Accept either a 2-letter code as-is,
        // OR a full English country name ("United Kingdom" → "GB") via the
        // map. Anything else falls back to US.
        val country = when {
            countryRaw.length == 2 -> countryRaw.uppercase(Locale.ROOT)
            countryRaw.isNotBlank() -> countryNameToIso[countryRaw.lowercase(Locale.ROOT).trim()] ?: "US"
            else -> "US"
        }
        val locale = localeFor(country)

        val url = if (query.isBlank()) {
            "https://news.google.com/rss" +
                "?hl=${locale.hl}&gl=${locale.gl}&ceid=${locale.gl}:${locale.lang}"
        } else {
            "https://news.google.com/rss/search?q=${URLEncoder.encode(query, "UTF-8")}" +
                "&hl=${locale.hl}&gl=${locale.gl}&ceid=${locale.gl}:${locale.lang}"
        }

        val body = withTimeoutOrNull(8_000L) { fetchRss(url) }
        if (body.isNullOrBlank()) {
            return@withContext errorJson("News service did not respond. Please try again.")
        }

        val items = parseItems(body, MAX_ITEMS)
        if (items.isEmpty()) {
            return@withContext errorJson(
                if (query.isNotBlank()) {
                    "No news found for \"$query\" in ${locale.displayName}."
                } else {
                    "No top headlines available for ${locale.displayName} right now."
                }
            )
        }

        val topic = when {
            query.isNotBlank() -> "${titleCase(query)} (${locale.displayName})"
            else -> "${locale.displayName} top headlines"
        }

        buildJsonObject {
            put("topic", JsonPrimitive(topic))
            put("country", JsonPrimitive(country))
            put("query", JsonPrimitive(query))
            put("source", JsonPrimitive("Google News RSS"))
            put(
                "results",
                JsonArray(items.map { item ->
                    buildJsonObject {
                        put("title", JsonPrimitive(item.title))
                        put("url", JsonPrimitive(item.url))
                        put("source", JsonPrimitive(item.source))
                        put("published", JsonPrimitive(item.publishedRaw))
                    }
                })
            )
            put("summary", JsonPrimitive(buildSummary(query, locale.displayName, items)))
        }.toString()
    }

    private fun fetchRss(url: String): String? = try {
        client.newCall(
            Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/rss+xml,application/xml,text/xml;q=0.9,*/*;q=0.8")
                .build()
        ).execute().use { resp ->
            if (!resp.isSuccessful) null else resp.body?.string()
        }
    } catch (e: Exception) {
        AppLog.w(TAG, "News fetch failed: ${e.message}")
        null
    }

    private fun parseItems(xml: String, max: Int): List<NewsItem> {
        val document = Jsoup.parse(xml, "", Parser.xmlParser())
        val out = mutableListOf<NewsItem>()
        for (item in document.select("item")) {
            if (out.size >= max) break
            val title = stripCdata(item.selectFirst("title")?.text().orEmpty()).trim()
            val link = item.selectFirst("link")?.text()?.trim().orEmpty()
            val pubDate = item.selectFirst("pubDate")?.text()?.trim().orEmpty()
            val sourceName = item.selectFirst("source")?.text()?.trim().orEmpty()
            // Some Google News titles ship as "Headline - Publisher". Strip
            // the trailing " - Publisher" so we don't duplicate the source.
            val cleanTitle = if (sourceName.isNotBlank() &&
                title.endsWith(" - $sourceName", ignoreCase = true)
            ) {
                title.removeSuffix(" - $sourceName").trim()
            } else {
                title
            }
            if (cleanTitle.isBlank() || !link.startsWith("http")) continue
            out += NewsItem(
                title = cleanTitle,
                url = link,
                source = sourceName.ifBlank { "Google News" },
                publishedRaw = pubDate
            )
        }
        return out
    }

    private fun buildSummary(query: String, countryName: String, items: List<NewsItem>): String {
        val heading = when {
            query.isNotBlank() -> "## 📰 News about ${titleCase(query)}"
            countryName.isNotBlank() -> "## 📰 Latest news from $countryName"
            else -> "## 📰 Top headlines"
        }
        val blocks = items.mapIndexed { index, item ->
            val short = shortDate(item.publishedRaw)
            val meta = listOfNotNull(
                item.source.takeIf { it.isNotBlank() },
                short.takeIf { it.isNotBlank() }
            ).joinToString(" · ")
            val titleLine = "**${index + 1}.** [${item.title}](${item.url})"
            if (meta.isNotBlank()) "$titleLine\n_${meta}_" else titleLine
        }
        val storyWord = if (items.size == 1) "story" else "stories"
        val footer = "_${items.size} $storyWord · Source: Google News_"
        return (listOf(heading, "") + blocks.flatMap { listOf(it, "") } + listOf(footer))
            .joinToString("\n")
    }

    private fun shortDate(raw: String): String {
        if (raw.isBlank()) return ""
        // Google News RSS uses RFC-1123 (e.g. "Sun, 17 May 2026 19:20:17 GMT").
        return runCatching {
            val parsed = ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME)
            parsed.format(DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH))
        }.getOrElse { raw.take(16) }
    }

    private fun stripCdata(s: String): String {
        val trimmed = s.trim()
        if (trimmed.startsWith("<![CDATA[") && trimmed.endsWith("]]>")) {
            return trimmed.removePrefix("<![CDATA[").removeSuffix("]]>").trim()
        }
        return trimmed
    }

    private fun titleCase(s: String): String =
        s.split(' ').filter { it.isNotBlank() }.joinToString(" ") { word ->
            word.lowercase(Locale.ROOT).replaceFirstChar { c -> c.titlecase(Locale.ROOT) }
        }

    private fun errorJson(message: String): String = buildJsonObject {
        put("error", JsonPrimitive(true))
        put("message", JsonPrimitive(message))
    }.toString()

    private fun localeFor(country: String): NewsLocale {
        return localeMap[country] ?: NewsLocale(
            displayName = country,
            hl = "en-$country",
            gl = country,
            lang = "en"
        )
    }

    private data class NewsItem(
        val title: String,
        val url: String,
        val source: String,
        val publishedRaw: String
    )

    private data class NewsLocale(
        val displayName: String,
        val hl: String,
        val gl: String,
        val lang: String
    )

    companion object {
        private const val TAG = "NewsTool"
        private const val USER_AGENT = "Mozilla/5.0 (Android) Localyze/1.0"
        private const val MAX_ITEMS = 5

        // Common locales the model is most likely to ask for. Anything not
        // listed falls back to "en-XX / XX:en" which Google News also accepts.
        private val localeMap = mapOf(
            "US" to NewsLocale("United States", "en-US", "US", "en"),
            "IN" to NewsLocale("India", "en-IN", "IN", "en"),
            "GB" to NewsLocale("United Kingdom", "en-GB", "GB", "en"),
            "UK" to NewsLocale("United Kingdom", "en-GB", "GB", "en"),
            "CA" to NewsLocale("Canada", "en-CA", "CA", "en"),
            "AU" to NewsLocale("Australia", "en-AU", "AU", "en"),
            "NZ" to NewsLocale("New Zealand", "en-NZ", "NZ", "en"),
            "IE" to NewsLocale("Ireland", "en-IE", "IE", "en"),
            "ZA" to NewsLocale("South Africa", "en-ZA", "ZA", "en"),
            "SG" to NewsLocale("Singapore", "en-SG", "SG", "en"),
            "PH" to NewsLocale("Philippines", "en-PH", "PH", "en"),
            "NG" to NewsLocale("Nigeria", "en-NG", "NG", "en"),
            "KE" to NewsLocale("Kenya", "en-KE", "KE", "en"),
            "PK" to NewsLocale("Pakistan", "en-PK", "PK", "en"),
            "BD" to NewsLocale("Bangladesh", "en-BD", "BD", "en"),
            "LK" to NewsLocale("Sri Lanka", "en-LK", "LK", "en"),
            "MY" to NewsLocale("Malaysia", "en-MY", "MY", "en"),
            "HK" to NewsLocale("Hong Kong", "en-HK", "HK", "en"),
            "JP" to NewsLocale("Japan", "ja", "JP", "ja"),
            "DE" to NewsLocale("Germany", "de", "DE", "de"),
            "FR" to NewsLocale("France", "fr", "FR", "fr"),
            "ES" to NewsLocale("Spain", "es", "ES", "es"),
            "IT" to NewsLocale("Italy", "it", "IT", "it"),
            "NL" to NewsLocale("Netherlands", "nl", "NL", "nl"),
            "BR" to NewsLocale("Brazil", "pt-BR", "BR", "pt-419"),
            "MX" to NewsLocale("Mexico", "es-419", "MX", "es-419"),
            "AR" to NewsLocale("Argentina", "es-419", "AR", "es-419"),
            "CN" to NewsLocale("China", "zh-CN", "CN", "zh-Hans"),
            "TW" to NewsLocale("Taiwan", "zh-TW", "TW", "zh-Hant"),
            "KR" to NewsLocale("South Korea", "ko", "KR", "ko"),
            "RU" to NewsLocale("Russia", "ru", "RU", "ru"),
            "AE" to NewsLocale("United Arab Emirates", "en-AE", "AE", "en"),
            "SA" to NewsLocale("Saudi Arabia", "ar", "SA", "ar"),
            "TR" to NewsLocale("Turkey", "tr", "TR", "tr"),
            "EG" to NewsLocale("Egypt", "ar", "EG", "ar"),
            "IL" to NewsLocale("Israel", "en", "IL", "en"),
            "ID" to NewsLocale("Indonesia", "id", "ID", "id"),
            "TH" to NewsLocale("Thailand", "th", "TH", "th"),
            "VN" to NewsLocale("Vietnam", "vi", "VN", "vi"),
            "PE" to NewsLocale("Peru", "es-419", "PE", "es-419"),
            "CL" to NewsLocale("Chile", "es-419", "CL", "es-419"),
            "CO" to NewsLocale("Colombia", "es-419", "CO", "es-419"),
            "UA" to NewsLocale("Ukraine", "uk", "UA", "uk")
        )

        // Full English name → ISO 2-letter, for when the news preflight
        // captures "news from United Kingdom" / "what's happening in Japan".
        internal val countryNameToIso: Map<String, String> = mapOf(
            "us" to "US", "usa" to "US", "united states" to "US", "america" to "US",
            "uk" to "GB", "united kingdom" to "GB", "britain" to "GB", "great britain" to "GB", "england" to "GB",
            "india" to "IN",
            "canada" to "CA",
            "australia" to "AU",
            "new zealand" to "NZ",
            "ireland" to "IE",
            "south africa" to "ZA",
            "singapore" to "SG",
            "philippines" to "PH",
            "nigeria" to "NG",
            "kenya" to "KE",
            "pakistan" to "PK",
            "bangladesh" to "BD",
            "sri lanka" to "LK",
            "malaysia" to "MY",
            "hong kong" to "HK",
            "japan" to "JP",
            "germany" to "DE", "deutschland" to "DE",
            "france" to "FR",
            "spain" to "ES", "españa" to "ES",
            "italy" to "IT", "italia" to "IT",
            "netherlands" to "NL", "holland" to "NL",
            "brazil" to "BR", "brasil" to "BR",
            "mexico" to "MX", "méxico" to "MX",
            "argentina" to "AR",
            "china" to "CN", "prc" to "CN",
            "taiwan" to "TW",
            "south korea" to "KR", "korea" to "KR",
            "russia" to "RU",
            "uae" to "AE", "united arab emirates" to "AE", "emirates" to "AE",
            "saudi arabia" to "SA", "saudi" to "SA",
            "turkey" to "TR", "türkiye" to "TR",
            "egypt" to "EG",
            "israel" to "IL",
            "indonesia" to "ID",
            "thailand" to "TH",
            "vietnam" to "VN",
            "peru" to "PE",
            "chile" to "CL",
            "colombia" to "CO",
            "ukraine" to "UA"
        )
    }
}
