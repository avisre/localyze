package com.localyze.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject

/**
 * Curated drug-interaction lookup. Pure local data — small static table of
 * commonly-tested interactions seen in pharmacist counseling scenarios.
 *
 * This is NOT a full drug-interaction database. It's a guard against the
 * 4B model's known failure mode where it confabulates the *direction* of
 * an interaction (e.g. claiming calcium increases warfarin effect when in
 * reality calcium chelates ciprofloxacin and reduces its absorption). The
 * tool returns the canonical mechanism + counseling so the model has a
 * grounded answer to cite.
 */
class DrugInteractionTool @Inject constructor() : Tool {

    override val name = "check_drugs"
    override val description = "Look up interactions between drugs. Returns mechanism, severity, counseling."

    override fun getParameterSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("drugs", buildJsonObject {
                put("type", "string")
                put("description", "Comma-separated list of generic drug names, e.g. 'warfarin, ciprofloxacin, calcium'")
            })
        })
        put("required", buildJsonArray { add(JsonPrimitive("drugs")) })
    }

    override suspend fun execute(args: JsonObject): String {
        val raw = (args["drugs"] as? JsonPrimitive)?.content
            ?: return errorJson("Missing required parameter: drugs (comma-separated string)")
        val drugs = raw.split(",")
            .map { it.lowercase().trim() }
            .filter { it.isNotEmpty() }
        if (drugs.size < 2) return errorJson("Need at least 2 drugs to check interactions (comma-separated)")

        val hits = mutableListOf<JsonObject>()
        for (i in drugs.indices) {
            for (j in i + 1 until drugs.size) {
                val a = drugs[i]; val b = drugs[j]
                interactionFor(a, b)?.let { (mech, severity, counseling) ->
                    hits.add(buildJsonObject {
                        put("drug_a", a); put("drug_b", b)
                        put("mechanism", trimAtWord(asciify(mech), 90))
                        put("severity", trimAtWord(asciify(severity), 40))
                        put("counseling", trimAtWord(asciify(counseling), 90))
                    })
                }
            }
        }

        return buildJsonObject {
            put("checked", buildJsonArray { drugs.forEach { add(JsonPrimitive(it)) } })
            put("interactions", buildJsonArray { hits.forEach { add(it) } })
            if (hits.isEmpty()) {
                put("note", "No interactions found in the curated table. This does not guarantee safety; always cross-check a comprehensive drug-interaction database for clinical decisions.")
            }
        }.toString()
    }

    /** Strip non-ASCII em-dashes, smart quotes, etc. — Adreno GPU driver
     *  on Snapdragon 8 Gen 1 has aborted on tool results containing them. */
    private fun asciify(s: String): String = s
        .replace('—', '-').replace('–', '-')
        .replace('"', '"').replace('"', '"')
        .replace('\'', '\'').replace('\'', '\'')
        .replace('§', 'S')
        .replace(Regex("[^\\x20-\\x7e]"), "")

    /** Truncate at the last word boundary at-or-before [max], so output ends
     *  on a whole word instead of "calcium-co". */
    private fun trimAtWord(s: String, max: Int): String {
        if (s.length <= max) return s
        val window = s.substring(0, max)
        val cut = window.lastIndexOfAny(charArrayOf(' ', ',', ';', '.'))
        return if (cut > max / 2) window.substring(0, cut).trimEnd() + "..." else "$window..."
    }

    /** Returns Triple(mechanism, severity, counseling) or null if not in table. */
    private fun interactionFor(a: String, b: String): Triple<String, String, String>? {
        val pair = setOf(normalize(a), normalize(b))
        return TABLE[pair]
    }

    private fun normalize(name: String): String {
        val n = name.lowercase().trim()
        return when {
            n in CALCIUM_ALIASES -> "calcium"
            n in IRON_ALIASES -> "iron"
            n in MAGNESIUM_ALIASES -> "magnesium"
            n in ANTACID_ALIASES -> "antacid"
            n in DAIRY_ALIASES -> "dairy"
            n.contains("warfarin") -> "warfarin"
            n.contains("ciprofloxacin") || n == "cipro" -> "ciprofloxacin"
            n.contains("levofloxacin") -> "levofloxacin"
            n.contains("amiodarone") -> "amiodarone"
            n.contains("atorvastatin") -> "atorvastatin"
            n.contains("simvastatin") -> "simvastatin"
            n.contains("metoprolol") -> "metoprolol"
            n.contains("clarithromycin") -> "clarithromycin"
            n.contains("erythromycin") -> "erythromycin"
            n.contains("azithromycin") -> "azithromycin"
            n.contains("tetracycline") -> "tetracycline"
            n.contains("doxycycline") -> "doxycycline"
            else -> n
        }
    }

    companion object {
        private val CALCIUM_ALIASES = setOf("calcium", "ca", "tums", "calcium carbonate", "calcium citrate")
        private val IRON_ALIASES = setOf("iron", "fe", "ferrous sulfate", "ferrous gluconate")
        private val MAGNESIUM_ALIASES = setOf("magnesium", "mg")
        private val ANTACID_ALIASES = setOf("antacid", "maalox", "mylanta", "aluminum hydroxide")
        private val DAIRY_ALIASES = setOf("milk", "dairy", "yogurt")

        private val TABLE: Map<Set<String>, Triple<String, String, String>> = mapOf(
            setOf("ciprofloxacin", "calcium") to Triple(
                "Calcium chelates ciprofloxacin in the GI tract, reducing cipro absorption and risking treatment failure. (Calcium does NOT increase cipro's effect — it neutralizes it.)",
                "moderate (separation required)",
                "Take ciprofloxacin at least 2 hours BEFORE or 6 hours AFTER calcium-containing products (supplements, antacids, dairy). The same rule applies to iron, magnesium, and aluminum. Do not stop calcium for osteoporosis — just space the doses."
            ),
            setOf("levofloxacin", "calcium") to Triple(
                "Same chelation mechanism as cipro: calcium binds the fluoroquinolone, reducing absorption.",
                "moderate (separation required)",
                "Take levofloxacin 2 hours before or 6 hours after calcium / iron / magnesium / antacids."
            ),
            setOf("ciprofloxacin", "iron") to Triple(
                "Iron chelates fluoroquinolones, dramatically reducing absorption.",
                "moderate (separation required)",
                "Separate cipro and iron supplements by at least 2 hours."
            ),
            setOf("ciprofloxacin", "antacid") to Triple(
                "Aluminum/magnesium-based antacids chelate cipro, reducing absorption.",
                "moderate (separation required)",
                "Separate by 2-6 hours."
            ),
            setOf("ciprofloxacin", "dairy") to Triple(
                "Dairy calcium reduces cipro absorption.",
                "minor-moderate",
                "Avoid dairy 2 hours before / 6 hours after cipro."
            ),
            setOf("ciprofloxacin", "warfarin") to Triple(
                "Cipro inhibits CYP1A2 / CYP3A4 and disrupts gut flora producing vitamin K, increasing warfarin effect and INR.",
                "major",
                "Check INR within 3-5 days of starting cipro. Counsel patient on bleeding signs (bruising, dark stools, gum bleeding). Anticipate possible warfarin dose reduction."
            ),
            setOf("clarithromycin", "warfarin") to Triple(
                "Macrolide CYP3A4 inhibition increases warfarin effect.",
                "major",
                "Check INR within 3-5 days. Bleeding precautions."
            ),
            setOf("erythromycin", "warfarin") to Triple(
                "CYP3A4 inhibition raises warfarin levels.",
                "major",
                "Check INR within 3-5 days."
            ),
            setOf("amiodarone", "warfarin") to Triple(
                "Amiodarone potently inhibits CYP2C9 and CYP3A4, increasing warfarin effect over weeks; INR can rise gradually.",
                "major (anticipate dose reduction)",
                "Empirically reduce warfarin dose 30-50% when starting amiodarone. Check INR every 2-3 days for first 2 weeks, then weekly until stable. Then resume monthly monitoring."
            ),
            setOf("clarithromycin", "atorvastatin") to Triple(
                "Strong CYP3A4 inhibition by clarithromycin sharply increases atorvastatin levels, risking myopathy / rhabdomyolysis.",
                "major",
                "Hold atorvastatin during clarithromycin course, or switch macrolide to azithromycin (minimal CYP3A4 effect)."
            ),
            setOf("clarithromycin", "simvastatin") to Triple(
                "Same CYP3A4 mechanism — even higher rhabdomyolysis risk with simvastatin.",
                "major (contraindicated)",
                "Do not co-prescribe. Switch to azithromycin or hold simvastatin."
            ),
            setOf("tetracycline", "calcium") to Triple(
                "Calcium chelates tetracyclines.",
                "moderate (separation required)",
                "Separate by 2-3 hours."
            ),
            setOf("doxycycline", "calcium") to Triple(
                "Calcium chelates doxycycline (less than tetracycline but still significant).",
                "minor-moderate",
                "Separate by 2 hours when feasible."
            ),
            setOf("doxycycline", "iron") to Triple(
                "Iron chelates doxycycline.",
                "moderate",
                "Separate by 2-3 hours."
            ),
            setOf("ciprofloxacin", "metoprolol") to Triple(
                "Cipro is a CYP1A2 inhibitor; metoprolol is mostly CYP2D6 — interaction is minimal but theoretical bradycardia risk exists in heavy CYP1A2 inhibition.",
                "minor",
                "Routine monitoring; no dose change usually needed."
            ),
            setOf("ciprofloxacin", "atorvastatin") to Triple(
                "Cipro has weak CYP3A4 inhibition; atorvastatin is a CYP3A4 substrate.",
                "minor",
                "Counsel patient to report muscle pain; otherwise no dose change."
            )
        )
    }

    private fun errorJson(message: String): String =
        buildJsonObject { put("error", message) }.toString()
}
