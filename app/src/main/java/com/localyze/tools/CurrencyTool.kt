package com.localyze.tools

import com.localyze.utils.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.NumberFormat
import java.time.LocalDate
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Deterministic currency conversion using Frankfurter (free, no API key,
 * ECB-backed daily rates).
 *
 * The model gives blank or hallucinates rates for "1 USD to INR" style
 * questions, so the recovery-path renderer uses this tool's pre-formatted
 * `summary` field directly without round-tripping back through the LLM.
 */
class CurrencyTool @Inject constructor(
    private val okHttpClient: OkHttpClient
) : Tool {

    override val name = "currency_convert"
    override val description =
        "Convert an amount from one currency to another using today's ECB-grade " +
            "exchange rate. Use this for questions like '1 USD to INR', " +
            "'convert 100 EUR to GBP', '50 yen in dollars'. Data comes from " +
            "Frankfurter (no API key, free, ECB daily refresh). Always prefer " +
            "this over web_search for currency conversion."

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
            put("amount", buildJsonObject {
                put("type", "number")
                put("description", "Amount in the source currency, e.g. 100.")
            })
            put("from", buildJsonObject {
                put("type", "string")
                put("description", "3-letter ISO source currency code, e.g. 'USD'.")
            })
            put("to", buildJsonObject {
                put("type", "string")
                put("description", "3-letter ISO target currency code, e.g. 'INR'.")
            })
        })
        put("required", buildJsonArray {
            add(JsonPrimitive("amount"))
            add(JsonPrimitive("from"))
            add(JsonPrimitive("to"))
        })
    }

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        val amount = (args["amount"] as? JsonPrimitive)?.content?.toDoubleOrNull()
        val fromRaw = (args["from"] as? JsonPrimitive)?.content?.trim()?.uppercase().orEmpty()
        val toRaw = (args["to"] as? JsonPrimitive)?.content?.trim()?.uppercase().orEmpty()

        if (amount == null || amount < 0.0 || amount.isNaN() || amount.isInfinite()) {
            return@withContext errorJson("Amount must be a non-negative number.")
        }
        if (!isIsoCode(fromRaw)) {
            return@withContext errorJson("'from' must be a 3-letter ISO currency code (e.g. USD).")
        }
        if (!isIsoCode(toRaw)) {
            return@withContext errorJson("'to' must be a 3-letter ISO currency code (e.g. INR).")
        }

        // Same currency → identity conversion, no network call.
        if (fromRaw == toRaw) {
            val today = LocalDate.now().toString()
            return@withContext buildResult(
                amount = amount,
                from = fromRaw,
                to = toRaw,
                rate = 1.0,
                converted = amount,
                asOf = today,
                source = "Identity"
            )
        }

        // Try Frankfurter first (ECB-grade rates, ~30 majors), then fall back
        // to the open currency-api (~200 currencies including exotic ones).
        var quote: RateQuote? = withTimeoutOrNull(7_000L) { fetchRate(fromRaw, toRaw) }
        var sourceLabel = "Frankfurter (ECB)"
        if (quote == null || quote.rate <= 0.0) {
            quote = withTimeoutOrNull(8_000L) { fetchRateFallback(fromRaw, toRaw) }
            sourceLabel = "currency-api"
        }
        if (quote == null || quote.rate <= 0.0) {
            return@withContext errorJson(
                "Couldn't fetch a rate for $fromRaw → $toRaw. Check the currency codes or try again."
            )
        }

        val rate = quote.rate
        val converted = amount * rate
        buildResult(
            amount = amount,
            from = fromRaw,
            to = toRaw,
            rate = rate,
            converted = converted,
            asOf = quote.date,
            source = sourceLabel
        )
    }

    private fun buildResult(
        amount: Double,
        from: String,
        to: String,
        rate: Double,
        converted: Double,
        asOf: String,
        source: String
    ): String = buildJsonObject {
        put("amount", JsonPrimitive(amount))
        put("from", JsonPrimitive(from))
        put("to", JsonPrimitive(to))
        put("rate", JsonPrimitive(round2(rate)))
        put("converted", JsonPrimitive(round2(converted)))
        put("as_of", JsonPrimitive(asOf))
        put("summary", JsonPrimitive(humanSummary(amount, from, to, rate, converted, asOf, source)))
        put("source", JsonPrimitive(source))
    }.toString()

    private fun humanSummary(
        amount: Double,
        from: String,
        to: String,
        rate: Double,
        converted: Double,
        asOf: String,
        source: String
    ): String {
        val amtFmt = formatNumber(amount)
        val convFmt = formatNumber(converted)
        // Small rates (e.g. JPY→USD ~0.0064) need more precision so they don't
        // collapse to "0.01" or "0.00". Use 4-6 decimals depending on magnitude.
        val rateFmt = when {
            rate >= 1.0 -> "%.2f".format(rate)
            rate >= 0.01 -> "%.4f".format(rate)
            else -> "%.6f".format(rate)
        }
        // Claude-style structured markdown: H2 headline + 2-column table + italic source line.
        return buildString {
            append("## 💱 ").append(amtFmt).append(' ').append(from)
                .append(" = **").append(convFmt).append(' ').append(to).append("**\n\n")
            append("| | |\n")
            append("|---|---|\n")
            append("| Amount | ").append(amtFmt).append(' ').append(from).append(" |\n")
            append("| Rate | 1 ").append(from).append(" = ").append(rateFmt)
                .append(' ').append(to).append(" |\n")
            append("| Converted | **").append(convFmt).append(' ').append(to).append("** |\n\n")
            append("_Source: ").append(source).append(" · As of ").append(asOf).append('_')
        }
    }

    private fun formatNumber(value: Double): String {
        // Thousands separators + 2 decimals, US locale for consistency with
        // the markdown summary regardless of device locale.
        val nf = NumberFormat.getNumberInstance(Locale.US)
        nf.minimumFractionDigits = 2
        nf.maximumFractionDigits = 2
        return nf.format(value)
    }

    private fun round2(value: Double): Double =
        kotlin.math.round(value * 100.0) / 100.0

    private fun isIsoCode(s: String): Boolean =
        s.length == 3 && s.all { it.isLetter() }

    private fun fetchRate(from: String, to: String): RateQuote? {
        val url = "https://api.frankfurter.dev/v1/latest?base=$from&symbols=$to"
        val body = try {
            client.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build())
                .execute().use { resp ->
                    if (!resp.isSuccessful) {
                        AppLog.w(TAG, "Frankfurter HTTP ${resp.code} for $from→$to")
                        return null
                    }
                    resp.body?.string().orEmpty()
                }
        } catch (e: Exception) {
            AppLog.w(TAG, "Frankfurter fetch failed: ${e.message}")
            return null
        }
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val rates = root["rates"] as? JsonObject ?: return null
        val rate = (rates[to] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: return null
        val date = (root["date"] as? JsonPrimitive)?.content ?: LocalDate.now().toString()
        return RateQuote(rate = rate, date = date)
    }

    private fun errorJson(message: String): String = buildJsonObject {
        put("error", JsonPrimitive(true))
        put("message", JsonPrimitive(message))
    }.toString()

    /**
     * Fallback: fawazahmed0/exchange-api on jsDelivr. Free, no key,
     * ~200 currencies including ones Frankfurter doesn't have (CLP, ARS,
     * SEK, MYR, NGN, etc.). Daily updates from various sources.
     */
    private fun fetchRateFallback(from: String, to: String): RateQuote? {
        val fromLc = from.lowercase(java.util.Locale.ROOT)
        val toLc = to.lowercase(java.util.Locale.ROOT)
        val url = "https://cdn.jsdelivr.net/npm/@fawazahmed0/currency-api@latest/v1/currencies/$fromLc.json"
        val body = try {
            client.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build())
                .execute().use { resp ->
                    if (!resp.isSuccessful) {
                        AppLog.w(TAG, "currency-api HTTP ${resp.code} for $from→$to")
                        return null
                    }
                    resp.body?.string().orEmpty()
                }
        } catch (e: Exception) {
            AppLog.w(TAG, "currency-api fetch failed: ${e.message}")
            return null
        }
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val rates = root[fromLc] as? JsonObject ?: return null
        val rate = (rates[toLc] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: return null
        val date = (root["date"] as? JsonPrimitive)?.content ?: LocalDate.now().toString()
        return RateQuote(rate = rate, date = date)
    }

    private data class RateQuote(val rate: Double, val date: String)

    companion object {
        private const val TAG = "CurrencyTool"
        private const val USER_AGENT = "Mozilla/5.0 (Android) Localyze/1.0"
    }
}
