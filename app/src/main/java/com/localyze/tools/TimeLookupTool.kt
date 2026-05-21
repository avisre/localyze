package com.localyze.tools

import com.localyze.utils.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Deterministic time-of-day lookup. Geocodes the city via Open-Meteo to
 * recover its IANA timezone (e.g. "Asia/Tokyo"), then computes the current
 * time locally with java.time — no second network call, no API key, no LLM.
 *
 * The summary is filled in here so the recovery-path renderer can show it
 * directly without round-tripping to the model (which goes blank on "what
 * time is it in Tokyo" style questions).
 */
class TimeLookupTool @Inject constructor(
    private val okHttpClient: OkHttpClient
) : Tool {

    override val name = "time_lookup"
    override val description =
        "Get the current local time and date for a city. Use this for questions like " +
            "'what time is it in <city>' or 'current time in <city>'. Uses Open-Meteo " +
            "geocoding to resolve the city's IANA timezone, then computes the time " +
            "locally on-device. Always prefer this over web_search for time queries."

    private val client = okHttpClient.newBuilder()
        .callTimeout(6, TimeUnit.SECONDS)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    override fun getParameterSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("location", buildJsonObject {
                put("type", "string")
                put("description", "City or place name, e.g. 'Tokyo', 'New York', 'Trivandrum'.")
            })
        })
        put("required", buildJsonArray { add(JsonPrimitive("location")) })
    }

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        val locationRaw = (args["location"] as? JsonPrimitive)?.content?.trim().orEmpty()
        if (locationRaw.isBlank()) {
            return@withContext errorJson("Missing required parameter: location")
        }
        if (locationRaw.length > 100) {
            return@withContext errorJson("Location name is too long — try a city name.")
        }
        // Apply anglicized → canonical alias so "Bombay" hits Mumbai, etc.
        val location = cityAliases[locationRaw.lowercase().trim()] ?: locationRaw

        val geo = withTimeoutOrNull(7_000L) { geocode(location) }
        if (geo == null) {
            return@withContext errorJson(
                "I couldn't find a place called \"$locationRaw\". " +
                    "Try the full city name, e.g. \"Mumbai\" instead of \"Bombay\"."
            )
        }
        if (geo.timezone.isBlank()) {
            return@withContext errorJson("Geocoder returned no timezone for ${geo.displayName}.")
        }

        val zoned = try {
            ZonedDateTime.now(ZoneId.of(geo.timezone))
        } catch (e: Exception) {
            AppLog.w(TAG, "Invalid timezone '${geo.timezone}': ${e.message}")
            return@withContext errorJson(
                "Got an unrecognized timezone (\"${geo.timezone}\") for ${geo.displayName}."
            )
        }

        val timeStr = zoned.format(TIME_FMT)
        val dateStr = zoned.format(DATE_FMT)
        val longDateStr = zoned.format(LONG_DATE_FMT)
        // Short city label for the header: just the city name (first comma-token).
        val cityShort = geo.displayName.substringBefore(",").trim()
        // Country tail for the header: last comma-token if present.
        val countryShort = geo.displayName.substringAfterLast(",", "").trim()
        val headerPlace = if (countryShort.isNotEmpty() && !countryShort.equals(cityShort, ignoreCase = true))
            "$cityShort, $countryShort" else cityShort
        val clockEmoji = clockEmojiForHour(zoned.hour)
        val summary = buildString {
            append("## ").append(clockEmoji).append(' ').append(timeStr)
                .append(" in ").append(headerPlace).append("\n\n")
            append("**").append(longDateStr).append("**\n\n")
            append("_Timezone: ").append(geo.timezone).append('_')
        }

        buildJsonObject {
            put("location", JsonPrimitive(geo.displayName))
            put("timezone", JsonPrimitive(geo.timezone))
            put("time", JsonPrimitive(timeStr))
            put("date", JsonPrimitive(dateStr))
            put("iso", JsonPrimitive(zoned.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)))
            put("summary", JsonPrimitive(summary))
            put("source", JsonPrimitive("Open-Meteo geocoding + on-device clock"))
        }.toString()
    }

    private fun errorJson(message: String): String = buildJsonObject {
        put("error", JsonPrimitive(true))
        put("message", JsonPrimitive(message))
    }.toString()

    private fun geocode(name: String): GeoHit? {
        val url = "https://geocoding-api.open-meteo.com/v1/search" +
            "?name=${URLEncoder.encode(name, "UTF-8")}&count=5&format=json&language=en"
        val body = try {
            client.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build())
                .execute().use { resp ->
                    if (!resp.isSuccessful) return null
                    resp.body?.string().orEmpty()
                }
        } catch (e: Exception) {
            AppLog.w(TAG, "Geocoding failed for '$name': ${e.message}")
            return null
        }
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val results = root["results"] as? JsonArray ?: return null
        // Rank by population (biggest first), with feature-code tiebreakers —
        // same ranking as WeatherLookupTool for cross-tool consistency. Stops
        // "Bombay" landing on a US hamlet over Mumbai, etc.
        val ranked = results.mapNotNull { it as? JsonObject }
            .map { obj ->
                val code = (obj["feature_code"] as? JsonPrimitive)?.content.orEmpty()
                val pop = (obj["population"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L
                val featureRank = when {
                    code.startsWith("PPLC") -> 0       // capital
                    code.startsWith("PPLA") -> 1       // admin-seat
                    code.startsWith("PPL") -> 2        // populated place
                    code == "AIRP" -> 9
                    else -> 5
                }
                Triple(obj, featureRank, pop)
            }
            .sortedWith(compareByDescending<Triple<JsonObject, Int, Long>> { it.third }.thenBy { it.second })
        val pick = ranked.firstOrNull()?.first ?: return null
        return GeoHit(
            displayName = buildString {
                append((pick["name"] as? JsonPrimitive)?.content.orEmpty())
                val admin = (pick["admin1"] as? JsonPrimitive)?.content
                val country = (pick["country"] as? JsonPrimitive)?.content
                if (!admin.isNullOrBlank()) append(", ").append(admin)
                if (!country.isNullOrBlank()) append(", ").append(country)
            },
            timezone = (pick["timezone"] as? JsonPrimitive)?.content.orEmpty()
        )
    }

    private data class GeoHit(
        val displayName: String,
        val timezone: String
    )

    companion object {
        private const val TAG = "TimeLookupTool"
        private const val USER_AGENT = "Mozilla/5.0 (Android) Localyze/1.0"

        // 12-hour clock with AM/PM, e.g. "08:45 AM".
        private val TIME_FMT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("hh:mm a", Locale.US)
        // Short date, e.g. "Sun May 18 2026" — kept for the structured `date` field.
        private val DATE_FMT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("EEE MMM d yyyy", Locale.US)
        // Long-form date for the human summary, e.g. "Monday, May 18, 2026".
        private val LONG_DATE_FMT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", Locale.US)

        // Map a 24h hour to the matching Unicode clock-face emoji (1..12).
        // Used as the H2 prefix in the markdown summary.
        private fun clockEmojiForHour(hour24: Int): String {
            val faces = arrayOf(
                "🕐", "🕑", "🕒", "🕓",
                "🕔", "🕕", "🕖", "🕗",
                "🕘", "🕙", "🕚", "🕛"
            )
            val h12 = ((hour24 % 12).let { if (it == 0) 12 else it })
            return faces[h12 - 1]
        }

        // Anglicized / colonial-era names — same map as WeatherLookupTool for
        // consistency across the toolset.
        private val cityAliases = mapOf(
            // Anglicized / historical names → canonical
            "trivandrum" to "Thiruvananthapuram",
            "bombay" to "Mumbai",
            "bangalore" to "Bengaluru",
            "calcutta" to "Kolkata",
            "madras" to "Chennai",
            "peking" to "Beijing",
            "saigon" to "Ho Chi Minh City",
            "burma" to "Myanmar",
            "allahabad" to "Prayagraj",
            // Common typos
            "londn" to "London",
            "londres" to "London",
            "mumbay" to "Mumbai",
            "mubai" to "Mumbai",
            "tokio" to "Tokyo",
            "berln" to "Berlin",
            "singpore" to "Singapore",
            "singapor" to "Singapore",
            // Airport / common short codes (useful for time-in-airport-city)
            "nyc" to "New York",
            "la" to "Los Angeles",
            "sf" to "San Francisco",
            "dxb" to "Dubai",
            "blr" to "Bengaluru",
            "bom" to "Mumbai",
            "del" to "Delhi",
            "hkg" to "Hong Kong"
        )
    }
}
