package com.localyze.ai

import com.localyze.domain.models.Memory
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds system prompts for Gemma 4 E4B.
 *
 * Design: minimize prefill cost. Everything that can be enforced after the
 * model has generated (markdown formatting, chart-table shaping, length
 * adherence) lives in ResponsePostProcessor. Everything that filters bad
 * input (prompt-injection, vague one-liners that need a clarifier) lives in
 * InputGuardrails. The prompt itself only carries rules that actually have
 * to be in front of the model at generation time: persona, digit accuracy,
 * identifier accuracy (code mode), and minimal tool-routing guidance.
 *
 * Target budget: ~250-400 tokens per mode (vs ~4600 before).
 */
@Singleton
class SystemPromptBuilder @Inject constructor() {

    companion object {
        private const val MODE_CHAT = "chat"
        private const val MODE_SEE = "see"
        private const val MODE_WRITE = "write"
        private const val MODE_BRAINSTORM = "brainstorm"
        private const val MODE_CODE = "code"
        private const val MODE_DATA = "data"
        private const val MODE_COMMUNICATION = "communication"
    }

    // ── Shared base ─────────────────────────────────────────────────────────
    // Applies to every mode. Carries only rules that the model itself must
    // enforce (no Kotlin post-process can fix a wrong digit or a hallucinated
    // tool call).
    private val sharedBase = """
        You are Localyze.ai, an on-device assistant.

        ANSWER FIRST. Do not narrate ("The user is asking…", "I will…"). Speak only the result.

        DIGITS: Copy every digit the user wrote, exactly. Never round, drop zeros, or shorten ("60" stays "60", "9:30" stays "9:30").

        TOOLS: For any arithmetic, factorial, power, percentage, or unit conversion, call `calculator` — mental math is unreliable. Use `weather_lookup` for weather, `web_search` only for live data (prices, news, scores) when enabled. Answer general knowledge, dates, definitions, and translations from your own knowledge — do not search for them.

        AFTER TOOLS: When a tool returns data, you MUST write a natural-language summary or markdown table using that data. Never end your turn with only tool output. If the user asked for a table or comparison, render a markdown table (`| col | col |` rows). The user sees only your final text — not the raw tool JSON.

        If the user already gave you data in the prompt, use it. Don't ask them to re-supply it.
    """.trimIndent()

    // ── Per-mode deltas ─────────────────────────────────────────────────────
    // Each delta is the smallest set of rules that meaningfully changes
    // generation for that mode. Surface formatting (markdown, tables, charts)
    // is handled by ResponsePostProcessor and is NOT repeated here.

    private val chatDelta = """
        Mode: general chat. Be concise.
    """.trimIndent()

    private val codeDelta = """
        Mode: programming assistant.

        IDENTIFIERS: The name you define in `def`, `function`, `class`, `let`, `const`, `var`, `fun` is the EXACT name you must use everywhere else. If you defined `longest_run`, never call it `longestrun` or `longestRun`. Re-read the signature before any call site.

        SYNTAX: Brackets balance one-for-one. No `nums[i-1]]`, `func(x))`, `[1, 2,, 3]`.
    """.trimIndent()

    private val dataDelta = """
        Mode: data analysis. When you cite numbers from the user's input, copy them digit-for-digit. When you generate comparisons (over time, by category, or as percentages), output them as a markdown table — the app renders charts from it.
    """.trimIndent()

    private val seeDelta = """
        Mode: visual analysis. Describe what you see in detail, read text (OCR), identify objects, summarize charts. Be precise.
    """.trimIndent()

    private val writeDelta = """
        Mode: writing assistant. Match the tone the user asked for. Output the text inline. Do not call composer tools unless the user gave a recipient.
    """.trimIndent()

    private val brainstormDelta = """
        Mode: ideation. Offer many ideas first, then refine. Play devil's advocate when useful.
    """.trimIndent()

    private val communicationDelta = """
        Mode: messages and email. Match the tone. If asked to send or reply, use the draft tools (open the system composer for review — never auto-send).
    """.trimIndent()

    // ── Thinking mode (channelled, not prose) ───────────────────────────────
    private val thinkingInstruction = """
        Before answering, reason inside <thought>...</thought>, then give the final answer outside those tags.
    """.trimIndent()

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Build the complete system prompt for the given capability mode.
     */
    fun buildSystemPrompt(
        capabilityMode: String,
        enableThinking: Boolean,
        @Suppress("UNUSED_PARAMETER") includeToolDescriptions: Boolean = false
    ): String {
        val sb = StringBuilder()
        sb.appendLine(sharedBase)
        sb.appendLine()
        sb.appendLine(ASSISTANT_IDENTITY_INSTRUCTION)
        sb.appendLine()
        sb.appendLine(selectModeDelta(capabilityMode))

        // Thinking instruction disabled for the single-model build: Gemma 3n
        // E2B mishandles the <thought>...</thought> framework and dumps the
        // whole response into a hidden channel.
        @Suppress("ControlFlowWithEmptyBody")
        if (false && enableThinking) {
            sb.appendLine()
            sb.appendLine(thinkingInstruction)
        }

        sb.appendLine()
        sb.append("Date: ${java.time.LocalDate.now()}")

        return sb.toString().trimEnd()
    }

    /**
     * Legacy tool-description prose. Retained as a no-op because callers
     * still pass includeToolDescriptions=false; tools are attached natively
     * via ToolProvider in the engine. Returns empty so nothing extra ships.
     */
    fun buildToolSystemPrompt(): String = ""

    /**
     * Memory injection prefix. The engine bakes memories into the system
     * instruction directly via [GemmaInferenceEngine.setMemorySnapshot];
     * this helper is retained for callers that want the same formatting
     * elsewhere (e.g. debug surfaces).
     */
    fun buildMemoryPrompt(memories: List<Memory>): String {
        if (memories.isEmpty()) return ""
        val sb = StringBuilder()
        sb.appendLine("You remember these facts about the user:")
        for (memory in memories) {
            sb.appendLine("- ${memory.content}")
        }
        return sb.toString()
    }

    private fun selectModeDelta(capabilityMode: String): String = when (capabilityMode) {
        MODE_CHAT -> chatDelta
        MODE_SEE -> seeDelta
        MODE_WRITE -> writeDelta
        MODE_BRAINSTORM -> brainstormDelta
        MODE_CODE -> codeDelta
        MODE_DATA -> dataDelta
        MODE_COMMUNICATION -> communicationDelta
        else -> chatDelta
    }
}
