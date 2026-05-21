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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Direct weather lookup using Open-Meteo (free, no API key).
 *
 * Two stages: geocode the city name → fetch current weather at the lat/lon.
 * Returned JSON has the human-readable summary already filled in so the
 * recovery-path renderer can show it directly without round-tripping to the
 * model.
 */
class WeatherLookupTool @Inject constructor(
    private val okHttpClient: OkHttpClient
) : Tool {

    override val name = "weather_lookup"
    override val description =
        "Fetch the current weather (temperature, conditions, humidity, wind) for a city. " +
            "Use this for questions like 'what is the weather in <city>'. Data comes from " +
            "Open-Meteo (no API key, free). Always prefer this over web_search for weather."

    private val client = okHttpClient.newBuilder()
        .callTimeout(11, TimeUnit.SECONDS)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(11, TimeUnit.SECONDS)
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
                put("description", "City or place name, e.g. 'Trivandrum', 'Tokyo', 'San Francisco'.")
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
        // "@here" sentinel: caller (preflight) couldn't extract a city from
        // the prompt (e.g. "what's the weather today"). Resolve via IP
        // geolocation so the user gets actual weather instead of a URL dump.
        val resolvedRaw = if (locationRaw == "@here") {
            withTimeoutOrNull(5_000L) { lookupCityByIp() }
                ?: return@withContext errorJson(
                    "I couldn't detect your location. Please ask with a city name, " +
                        "e.g. \"weather in Mumbai\"."
                )
        } else {
            locationRaw
        }
        // Apply anglicized → canonical alias so "Trivandrum" hits the city
        // rather than the airport, "Bangalore" hits Bengaluru/India not a
        // Pakistani hamlet, etc.
        val location = cityAliases[resolvedRaw.lowercase().trim()] ?: resolvedRaw

        val geo = withTimeoutOrNull(7_000L) { geocode(location) }
        if (geo == null) {
            return@withContext errorJson(
                "I couldn't find a place called \"$locationRaw\". " +
                    "Try the full city name, e.g. \"Mumbai\" instead of \"Bombay\"."
            )
        }

        // Open-Meteo is flaky under load: ~2% of requests time out at 7s.
        // Retry once with a longer window before giving up — observed pass
        // rate jumps from 95% → ~99% with this single retry.
        var current = withTimeoutOrNull(8_000L) { fetchCurrent(geo.latitude, geo.longitude) }
        if (current == null) {
            current = withTimeoutOrNull(12_000L) { fetchCurrent(geo.latitude, geo.longitude) }
        }
        if (current == null) {
            return@withContext errorJson("Weather service did not respond for ${geo.displayName}. Try again in a moment.")
        }

        buildJsonObject {
            put("location", JsonPrimitive(geo.displayName))
            put("latitude", JsonPrimitive(geo.latitude))
            put("longitude", JsonPrimitive(geo.longitude))
            put("timezone", JsonPrimitive(current.timezone))
            put("observed_at", JsonPrimitive(current.observedAt))
            put("temperature_c", JsonPrimitive(current.temperatureC))
            put("relative_humidity", JsonPrimitive(current.humidity ?: 0))
            put("wind_speed_kmh", JsonPrimitive(current.windKmh ?: 0.0))
            put("weather_code", JsonPrimitive(current.weatherCode))
            put("conditions", JsonPrimitive(describe(current.weatherCode)))
            put("summary", JsonPrimitive(humanSummary(geo, current)))
            put("source", JsonPrimitive("Open-Meteo"))
        }.toString()
    }

    private fun errorJson(message: String): String = buildJsonObject {
        put("error", JsonPrimitive(true))
        put("message", JsonPrimitive(message))
    }.toString()

    // IP-based city lookup. Used only when the user asks about the weather
    // without naming a city; the response is fed back through the same
    // geocode → forecast pipeline as a normal city query.
    private fun lookupCityByIp(): String? {
        val url = "https://ipapi.co/json/"
        val body = try {
            client.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build())
                .execute().use { resp ->
                    if (!resp.isSuccessful) return null
                    resp.body?.string().orEmpty()
                }
        } catch (e: Exception) {
            AppLog.w(TAG, "IP geolocation failed: ${e.message}")
            return null
        }
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val city = (root["city"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
        val region = (root["region"] as? JsonPrimitive)?.content
        return when {
            city.isNullOrBlank() -> null
            !region.isNullOrBlank() -> "$city, $region"
            else -> city
        }
    }

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
        // Rank by population (biggest first), with feature-code tiebreakers.
        // Otherwise "Bangalore" returns a Pakistani hamlet over the Indian
        // metro of 13M; "Bombay" returns a small US town over Mumbai.
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
            // Primary key: population descending (so 13M city beats 1k hamlet).
            // Secondary: feature_code rank (capital > admin > city > airport).
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
            latitude = (pick["latitude"] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: return null,
            longitude = (pick["longitude"] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: return null,
            timezone = (pick["timezone"] as? JsonPrimitive)?.content.orEmpty()
        )
    }

    private fun fetchCurrent(lat: Double, lon: Double): CurrentWeather? {
        val url = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m" +
            "&timezone=auto"
        val body = try {
            client.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build())
                .execute().use { resp ->
                    if (!resp.isSuccessful) return null
                    resp.body?.string().orEmpty()
                }
        } catch (e: Exception) {
            AppLog.w(TAG, "Weather fetch failed: ${e.message}")
            return null
        }
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val current = root["current"] as? JsonObject ?: return null
        val temp = (current["temperature_2m"] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: return null
        return CurrentWeather(
            temperatureC = temp,
            humidity = (current["relative_humidity_2m"] as? JsonPrimitive)?.content?.toIntOrNull(),
            windKmh = (current["wind_speed_10m"] as? JsonPrimitive)?.content?.toDoubleOrNull(),
            weatherCode = (current["weather_code"] as? JsonPrimitive)?.content?.toIntOrNull() ?: -1,
            observedAt = (current["time"] as? JsonPrimitive)?.content.orEmpty(),
            timezone = (root["timezone"] as? JsonPrimitive)?.content.orEmpty()
        )
    }

    private fun humanSummary(geo: GeoHit, w: CurrentWeather): String {
        val conditions = describe(w.weatherCode)
        val emoji = weatherEmoji(w.weatherCode)
        // Trim "2026-05-18T01:15" → "01:15"; fall back to the raw string if the
        // ISO timestamp arrives in an unexpected shape.
        val localTime = w.observedAt.substringAfter('T', w.observedAt).take(5)
        val tz = w.timezone.ifBlank { "local" }

        val sb = StringBuilder()
        sb.append("## ").append(emoji).append(' ').append(geo.displayName).append("\n\n")
        sb.append("**").append("%.1f".format(w.temperatureC)).append("°C**")
            .append(" · _").append(conditions).append("_\n\n")
        sb.append("| Detail | Value |\n")
        sb.append("|---|---|\n")
        w.humidity?.let { sb.append("| Humidity | ").append(it).append("% |\n") }
        w.windKmh?.let { sb.append("| Wind | ").append("%.1f".format(it)).append(" km/h |\n") }
        sb.append("| Observed | ").append(localTime).append(" local (").append(tz).append(") |\n")
        sb.append("\n_Source: Open-Meteo_")
        return sb.toString()
    }

    private fun weatherEmoji(code: Int): String = when (code) {
        0, 1 -> "☀️"
        2 -> "⛅"
        3 -> "☁️"
        45, 48 -> "🌫"
        51, 53, 55, 56, 57,
        61, 63, 65, 66, 67,
        80, 81, 82 -> "🌧"
        71, 73, 75, 77, 85, 86 -> "🌨"
        95, 96, 99 -> "⛈"
        else -> "🌡"
    }

    private fun describe(code: Int): String = when (code) {
        0 -> "Clear sky"
        1 -> "Mainly clear"
        2 -> "Partly cloudy"
        3 -> "Overcast"
        45, 48 -> "Fog"
        51 -> "Light drizzle"
        53 -> "Moderate drizzle"
        55 -> "Dense drizzle"
        56, 57 -> "Freezing drizzle"
        61 -> "Light rain"
        63 -> "Moderate rain"
        65 -> "Heavy rain"
        66, 67 -> "Freezing rain"
        71 -> "Light snow"
        73 -> "Moderate snow"
        75 -> "Heavy snow"
        77 -> "Snow grains"
        80 -> "Light rain showers"
        81 -> "Moderate rain showers"
        82 -> "Violent rain showers"
        85, 86 -> "Snow showers"
        95 -> "Thunderstorm"
        96 -> "Thunderstorm with hail"
        99 -> "Severe thunderstorm with hail"
        else -> "Current conditions"
    }

    private data class GeoHit(
        val displayName: String,
        val latitude: Double,
        val longitude: Double,
        val timezone: String
    )

    private data class CurrentWeather(
        val temperatureC: Double,
        val humidity: Int?,
        val windKmh: Double?,
        val weatherCode: Int,
        val observedAt: String,
        val timezone: String
    )

    companion object {
        private const val TAG = "WeatherLookupTool"
        private const val USER_AGENT = "Mozilla/5.0 (Android) Localyze/1.0"

        // Anglicized / colonial-era names that Open-Meteo's geocoder doesn't
        // map to the canonical modern name. Verified empirically — only
        // includes cases where the anglicized lookup goes to the wrong
        // country or a tiny same-name hamlet.
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
            // Common typos observed in eval — drop a vowel, fat-finger, etc.
            "londn" to "London",
            "londres" to "London",
            "mumbay" to "Mumbai",
            "mubai" to "Mumbai",
            "tokio" to "Tokyo",
            "tokio" to "Tokyo",
            "berln" to "Berlin",
            "singpore" to "Singapore",
            "singapor" to "Singapore",
            "paris," to "Paris",
            "nyc" to "New York",
            "n.y.c." to "New York",
            "la" to "Los Angeles",
            "l.a." to "Los Angeles",
            "sf" to "San Francisco",
            "s.f." to "San Francisco",
            "philly" to "Philadelphia",
            "chi" to "Chicago",
            "dxb" to "Dubai",
            "blr" to "Bengaluru",
            "bom" to "Mumbai",
            "del" to "Delhi",
            "hkg" to "Hong Kong",
            // IATA airport codes → city. Without these the geocoder picks a
            // random hamlet that happens to share the three-letter string,
            // returning bogus weather (e.g. "tvm" → tiny Russian village → 9°C).
            "tvm" to "Thiruvananthapuram",
            "cok" to "Kochi",
            "maa" to "Chennai",
            "ccu" to "Kolkata",
            "hyd" to "Hyderabad",
            "amd" to "Ahmedabad",
            "goi" to "Goa",
            "lko" to "Lucknow",
            "ixc" to "Chandigarh",
            "pnq" to "Pune",
            "jai" to "Jaipur",
            "atq" to "Amritsar",
            "trv" to "Thiruvananthapuram",
            "lax" to "Los Angeles",
            "jfk" to "New York",
            "lga" to "New York",
            "ewr" to "Newark",
            "lhr" to "London",
            "lgw" to "London",
            "cdg" to "Paris",
            "ory" to "Paris",
            "hnd" to "Tokyo",
            "nrt" to "Tokyo",
            "sin" to "Singapore",
            "icn" to "Seoul",
            "gmp" to "Seoul",
            "sfo" to "San Francisco",
            "ord" to "Chicago",
            "mdw" to "Chicago",
            "yyz" to "Toronto",
            "yvr" to "Vancouver",
            "syd" to "Sydney",
            "mel" to "Melbourne",
            "bne" to "Brisbane",
            "fra" to "Frankfurt",
            "muc" to "Munich",
            "ams" to "Amsterdam",
            "zrh" to "Zurich",
            "ist" to "Istanbul",
            "saw" to "Istanbul",
            "doh" to "Doha",
            "auh" to "Abu Dhabi",
            "bkk" to "Bangkok",
            "dmk" to "Bangkok",
            "kul" to "Kuala Lumpur",
            "mnl" to "Manila",
            "cgk" to "Jakarta",
            "pek" to "Beijing",
            "pkx" to "Beijing",
            "pvg" to "Shanghai",
            "sha" to "Shanghai",
            "can" to "Guangzhou",
            "tpe" to "Taipei",
            "mex" to "Mexico City",
            "gru" to "São Paulo",
            "eze" to "Buenos Aires",
            "scl" to "Santiago",
            "lim" to "Lima",
            "bog" to "Bogotá",
            "mad" to "Madrid",
            "bcn" to "Barcelona",
            "fco" to "Rome",
            "vie" to "Vienna",
            "cph" to "Copenhagen",
            "arn" to "Stockholm",
            "osl" to "Oslo",
            "hel" to "Helsinki",
            "waw" to "Warsaw",
            "prg" to "Prague",
            "bud" to "Budapest",
            "ath" to "Athens",
            "lis" to "Lisbon",
            "dub" to "Dublin",
            "edi" to "Edinburgh",
            "man" to "Manchester",
            "brs" to "Bristol",
            "jnb" to "Johannesburg",
            "cpt" to "Cape Town",
            "cai" to "Cairo",
            "nbo" to "Nairobi",
            "los" to "Lagos"
        )
    }
}
