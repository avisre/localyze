package com.localyze.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import kotlin.math.pow

/**
 * Food-safety helper for time-temperature pasteurization questions.
 *
 * Two operations:
 *   1. `pasteurization_time` for a given protein at a given target temperature
 *      — uses USDA FSIS Appendix A time-temperature tables for poultry / beef.
 *   2. `temp_danger_zone` — checks if a held food was within the 40-140 °F
 *      (4-60 °C) danger zone for too long (the "2-hour rule" / "4-hour rule").
 *
 * Designed for sous-vide / confit / hold-and-plate scenarios where the
 * model otherwise oversimplifies to "cook to 165 °F" without considering
 * pasteurization at lower temperatures.
 */
class FoodSafetyTool @Inject constructor() : Tool {

    override val name = "check_food"
    override val description = "Pasteurization time at a temperature, or check if food was too long in the danger zone."

    override fun getParameterSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("operation", buildJsonObject {
                put("type", "string")
                put("description", "'pasteurize' to compute pasteurization hold-time at temp_f, or 'dangerzone' to check whether food held at temp_f for hold_minutes is still safe")
                put("enum", buildJsonArray {
                    add(JsonPrimitive("pasteurize"))
                    add(JsonPrimitive("dangerzone"))
                })
            })
            put("temp_f", buildJsonObject {
                put("type", "number")
                put("description", "Temperature in Fahrenheit. For 'pasteurize' it is the cooking/target temp. For 'dangerzone' it is the current holding temp.")
            })
            put("hold_minutes", buildJsonObject {
                put("type", "number")
                put("description", "How many minutes the food has been held at temp_f. Required for 'dangerzone'.")
            })
            put("protein", buildJsonObject {
                put("type", "string")
                put("description", "'poultry' or 'beef'. Required for 'pasteurize'.")
            })
            put("immuno", buildJsonObject {
                put("type", "boolean")
                put("description", "True if any diner is immunocompromised (chemo, transplant, pregnant, elderly). Stricter rules apply.")
            })
        })
        put("required", buildJsonArray {
            add(JsonPrimitive("operation"))
            add(JsonPrimitive("temp_f"))
        })
    }

    override suspend fun execute(args: JsonObject): String {
        val op = (args["operation"] as? JsonPrimitive)?.content?.lowercase()?.trim()
            ?: return errorJson("Missing required parameter: operation")
        val immunoFlag = (args["immuno"] as? JsonPrimitive)?.content?.toBoolean()
            ?: (args["immunocompromised_guest"] as? JsonPrimitive)?.content?.toBoolean()
            ?: false
        // Tolerate Adreno-garbled operation names by canonicalising on alphanumerics.
        val canonical = op.filter { it.isLetterOrDigit() }
        return when {
            canonical.contains("pasteur") || canonical.contains("pastur") -> pasteurizationTime(args, immunoFlag)
            canonical.contains("danger") || canonical.contains("zone") || canonical.contains("hold") ->
                tempDangerZone(args, immunoFlag)
            else -> errorJson("Unknown operation: $op. Use 'pasteurize' or 'dangerzone'.")
        }
    }

    /** Read temp from any of: temp_f, target_temp_f, current_temp_f, hold_temp_f, temp. */
    private fun readTemp(args: JsonObject): Double? {
        val keys = listOf("temp_f", "target_temp_f", "current_temp_f", "hold_temp_f", "temp")
        for (k in keys) {
            val v = (args[k] as? JsonPrimitive)?.content?.toDoubleOrNull()
            if (v != null) return v
        }
        return null
    }

    private fun pasteurizationTime(args: JsonObject, immuno: Boolean): String {
        val protein = ((args["protein"] as? JsonPrimitive)?.content ?: "poultry").lowercase()
        val tempF = readTemp(args)
            ?: return errorJson("'temp_f' is required for pasteurize (cooking/target temperature in Fahrenheit)")

        // FSIS Appendix A — 7-log10 reduction of Salmonella for poultry,
        // 6.5-log10 for beef. Approximated piecewise-linear in log time.
        val tableF: List<Pair<Double, Double>> = when (protein) {
            "poultry" -> listOf(
                136.0 to 81.4 * 60, 138.0 to 32.7 * 60, 140.0 to 13.0 * 60,
                142.0 to 7.7 * 60,  144.0 to 4.6 * 60,  146.0 to 2.7 * 60,
                148.0 to 100.0,     150.0 to 67.0,      152.0 to 39.0,
                154.0 to 23.0,      156.0 to 14.0,      158.0 to 8.0,
                160.0 to 5.0,       162.0 to 3.0,       165.0 to 1.0
            )
            "beef" -> listOf(
                130.0 to 121.0 * 60, 133.0 to 50.0 * 60, 136.0 to 21.0 * 60,
                140.0 to 8.0 * 60,   145.0 to 2.5 * 60,  150.0 to 1.0 * 60,
                155.0 to 30.0,       160.0 to 9.0,       165.0 to 0.0
            )
            else -> return errorJson("Unsupported protein: $protein. Use 'poultry' or 'beef'.")
        }

        val (lower, upper) = tableF.zipWithNext().firstOrNull { (lo, hi) ->
            tempF >= lo.first && tempF <= hi.first
        } ?: run {
            // Below table: too cold to pasteurize at any sane time. Above: instant kill.
            return if (tempF < tableF.first().first) {
                buildJsonObject {
                    put("verdict", "below_pasteurization_range")
                    put("message", "Target temp ${tempF}°F is below the pasteurization range for $protein. The product is in the danger zone — DO NOT serve.")
                }.toString()
            } else {
                buildJsonObject {
                    put("verdict", "instant_kill")
                    put("message", "Target temp ${tempF}°F is above 165°F — pasteurization is essentially instantaneous.")
                    put("required_hold_seconds", 1)
                }.toString()
            }
        }

        // Log-linear interpolate between known points.
        val t = (tempF - lower.first) / (upper.first - lower.first)
        val logSeconds = (1 - t) * kotlin.math.ln(lower.second.coerceAtLeast(0.5)) +
            t * kotlin.math.ln(upper.second.coerceAtLeast(0.5))
        val seconds = kotlin.math.exp(logSeconds)
        val safetyFactor = if (immuno) 2.0 else 1.0
        val recommended = seconds * safetyFactor

        return buildJsonObject {
            put("protein", protein)
            put("target_temp_f", tempF)
            put("required_hold_seconds", "%.0f".format(seconds))
            put("recommended_hold_seconds", "%.0f".format(recommended))
            put("recommended_hold_human", humanize(recommended))
            if (immuno) put("safety_factor_applied", "2x for immunocompromised guest")
            put("source", "USDA FSIS Appendix A (poultry: 7-log Salmonella; beef: 6.5-log)")
        }.toString()
    }

    private fun tempDangerZone(args: JsonObject, immuno: Boolean): String {
        val tempF = readTemp(args)
            ?: return errorJson("'temp_f' is required for dangerzone (current holding temperature in Fahrenheit)")
        val minutes = (args["hold_minutes"] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.0
        val inDanger = tempF in 40.0..140.0
        val maxAllowedMin = when {
            immuno -> 60.0   // strict 1-hour rule for high-risk guests
            tempF in 90.0..140.0 -> 60.0
            else -> 240.0    // FDA 4-hour rule below 90°F
        }

        val verdict = when {
            !inDanger -> "safe"
            minutes <= maxAllowedMin -> "still_safe_but_act_now"
            else -> "discard_or_repasteurize"
        }
        val advice = when (verdict) {
            "safe" -> "Temperature ${tempF}°F is outside the 40-140°F danger zone. Continue normal handling."
            "still_safe_but_act_now" -> "Within danger zone but under the ${maxAllowedMin.toInt()}-minute limit. Either bring back above 140°F (return to circulator / oven / steam) or chill rapidly below 40°F immediately."
            "discard_or_repasteurize" -> "Past the safe-hold window. Do NOT serve as-is. Either repasteurize at 165°F+ for the protein-appropriate time, or discard. For an immunocompromised guest, default to discard or substitute the dish."
            else -> ""
        }

        return buildJsonObject {
            put("current_temp_f", tempF)
            put("hold_minutes", minutes)
            put("in_danger_zone", inDanger)
            put("max_allowed_minutes", maxAllowedMin)
            put("verdict", verdict)
            put("advice", advice)
            if (immuno) put("safety_note", "Stricter 60-minute rule applied for high-risk / immunocompromised guest.")
            put("source", "FDA Food Code §3-501.19 (TPHC) + HACCP guidance")
        }.toString()
    }

    private fun humanize(seconds: Double): String = when {
        seconds < 60 -> "%.0f seconds".format(seconds)
        seconds < 3600 -> "%.1f minutes".format(seconds / 60)
        else -> "%.1f hours".format(seconds / 3600)
    }

    private fun errorJson(message: String): String =
        buildJsonObject { put("error", message) }.toString()
}
