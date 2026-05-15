package com.localyze.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject

/**
 * Decodes a METAR (aviation weather observation) string into structured
 * JSON the model can reason about. Pure local computation — no network.
 *
 * Example input:
 *   "KAUS 281553Z 18012G18KT 8SM SCT040 BKN250 27/22 A2992"
 *
 * Returns: station, observed time, wind dir/speed/gust, visibility, cloud
 * layers (with bases in feet), temperature/dewpoint, altimeter setting,
 * and a derived `vfr_status` (VFR / MVFR / IFR / LIFR) using the standard
 * FAA flight-category rules so the model doesn't have to.
 */
class MetarDecoderTool @Inject constructor() : Tool {

    override val name = "parse_metar"
    override val description = "Decode a METAR aviation weather string. Returns wind, visibility, clouds, ceiling, VFR/IFR category."

    override fun getParameterSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("metar", buildJsonObject {
                put("type", "string")
                put("description", "Raw METAR string")
            })
        })
        put("required", buildJsonArray { add(JsonPrimitive("metar")) })
    }

    override suspend fun execute(args: JsonObject): String {
        // Accept several aliases — Adreno occasionally garbles the parameter
        // name and the model has been observed calling parse_metar without
        // populating "metar". Take whichever string-valued arg looks like a
        // METAR (starts with 4 uppercase letters).
        val metar = (args["metar"] as? JsonPrimitive)?.content
            ?: (args["text"] as? JsonPrimitive)?.content
            ?: (args["raw"] as? JsonPrimitive)?.content
            ?: (args["string"] as? JsonPrimitive)?.content
            ?: (args["input"] as? JsonPrimitive)?.content
            ?: args.values.mapNotNull { (it as? JsonPrimitive)?.content }
                .firstOrNull { it.length > 8 && it.matches(Regex("^[A-Z]{4}\\b.*")) }
            ?: return errorJson("Missing required parameter: metar (the raw METAR string)")

        val tokens = metar.trim().split(Regex("\\s+"))
        if (tokens.isEmpty()) return errorJson("Empty METAR string")

        var station: String? = null
        var observed: String? = null
        var wind: JsonObject? = null
        var visibilitySm: Double? = null
        val clouds = mutableListOf<JsonObject>()
        var tempC: Int? = null
        var dewpointC: Int? = null
        var altimeterInHg: Double? = null

        // ICAO station identifier (4 uppercase letters), normally first token.
        val stationRe = Regex("^[A-Z]{4}$")
        val timeRe = Regex("^(\\d{2})(\\d{2})(\\d{2})Z$")
        val windRe = Regex("^(\\d{3}|VRB)(\\d{2,3})(?:G(\\d{2,3}))?KT$")
        val visRe = Regex("^(\\d{1,2}(?:/\\d)?)SM$")
        val cloudRe = Regex("^(SKC|CLR|FEW|SCT|BKN|OVC|VV)(\\d{3})(CB|TCU)?$")
        val tempRe = Regex("^(M?\\d{1,2})/(M?\\d{1,2})$")
        val altRe = Regex("^A(\\d{4})$")

        for (t in tokens) {
            when {
                station == null && stationRe.matches(t) -> station = t
                observed == null && timeRe.matches(t) -> {
                    val m = timeRe.find(t)!!
                    observed = "day ${m.groupValues[1]} at ${m.groupValues[2]}:${m.groupValues[3]} UTC"
                }
                wind == null && windRe.matches(t) -> {
                    val m = windRe.find(t)!!
                    wind = buildJsonObject {
                        put("direction", if (m.groupValues[1] == "VRB") "variable" else "${m.groupValues[1]}°")
                        put("speed_kt", m.groupValues[2].toInt())
                        if (m.groupValues[3].isNotEmpty()) put("gust_kt", m.groupValues[3].toInt())
                    }
                }
                visibilitySm == null && visRe.matches(t) -> {
                    val raw = visRe.find(t)!!.groupValues[1]
                    visibilitySm = if ("/" in raw) {
                        val (n, d) = raw.split("/").map { it.toDouble() }
                        n / d
                    } else raw.toDouble()
                }
                cloudRe.matches(t) -> {
                    val m = cloudRe.find(t)!!
                    clouds.add(buildJsonObject {
                        put("coverage", coverageDescription(m.groupValues[1]))
                        put("base_ft_agl", m.groupValues[2].toInt() * 100)
                        if (m.groupValues[3].isNotEmpty()) put("type", m.groupValues[3])
                    })
                }
                tempC == null && tempRe.matches(t) -> {
                    val m = tempRe.find(t)!!
                    tempC = parseSignedTemp(m.groupValues[1])
                    dewpointC = parseSignedTemp(m.groupValues[2])
                }
                altimeterInHg == null && altRe.matches(t) -> {
                    val raw = altRe.find(t)!!.groupValues[1]
                    altimeterInHg = raw.toDouble() / 100.0
                }
            }
        }

        // FAA flight-category derivation from ceiling + visibility.
        val ceilingFt = clouds
            .filter { (it["coverage"] as? JsonPrimitive)?.content in setOf("broken", "overcast") }
            .mapNotNull { (it["base_ft_agl"] as? JsonPrimitive)?.content?.toIntOrNull() }
            .minOrNull()
        val vis = visibilitySm ?: 99.0
        val vfrStatus = when {
            (ceilingFt != null && ceilingFt < 500) || vis < 1.0 -> "LIFR"
            (ceilingFt != null && ceilingFt < 1000) || vis < 3.0 -> "IFR"
            (ceilingFt != null && ceilingFt < 3000) || vis < 5.0 -> "MVFR"
            else -> "VFR"
        }

        return buildJsonObject {
            put("station", station ?: "unknown")
            observed?.let { put("observed", it) }
            wind?.let { put("wind", it) }
            visibilitySm?.let { put("visibility_sm", it) }
            put("clouds", buildJsonArray { clouds.forEach { add(it) } })
            ceilingFt?.let { put("ceiling_ft_agl", it) }
            tempC?.let { put("temperature_c", it) }
            dewpointC?.let { put("dewpoint_c", it) }
            altimeterInHg?.let { put("altimeter_inhg", it) }
            put("vfr_status", vfrStatus)
            put("vfr_status_explanation", vfrExplanation(vfrStatus))
        }.toString()
    }

    private fun parseSignedTemp(raw: String): Int =
        if (raw.startsWith("M")) -raw.removePrefix("M").toInt() else raw.toInt()

    private fun coverageDescription(code: String): String = when (code) {
        "SKC", "CLR" -> "clear"
        "FEW" -> "few"
        "SCT" -> "scattered"
        "BKN" -> "broken"
        "OVC" -> "overcast"
        "VV" -> "vertical visibility"
        else -> code.lowercase()
    }

    private fun vfrExplanation(status: String): String = when (status) {
        "VFR" -> "Ceiling >= 3000 ft AGL AND visibility >= 5 SM. VFR pleasure flight permitted."
        "MVFR" -> "Marginal VFR: ceiling 1000-3000 ft AGL OR visibility 3-5 SM. VFR allowed but caution advised."
        "IFR" -> "IFR conditions: ceiling 500-1000 ft AGL OR visibility 1-3 SM. VFR not permitted in controlled airspace without Special VFR clearance."
        "LIFR" -> "Low IFR: ceiling < 500 ft AGL OR visibility < 1 SM. VFR strictly prohibited."
        else -> "Unknown"
    }

    private fun errorJson(message: String): String =
        buildJsonObject { put("error", message) }.toString()
}
