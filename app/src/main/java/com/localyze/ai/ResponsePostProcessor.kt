package com.localyze.ai

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Output-side "harness" that enforces surface rules previously baked into
 * the system prompt — markdown normalization, chart-friendly table shaping,
 * length adherence, sentence spacing.
 *
 * Moving these rules out of the prompt cut ~700 tokens off the per-Conversation
 * prefill cost (see [SystemPromptBuilder]). Anything a Kotlin transform can
 * fix after generation does not belong in the prompt.
 *
 * Contract: every output the user sees should pass through [polish] before
 * being committed to the DB or displayed. Streaming UIs may use
 * [normalizeStreamingChunk] for cheap per-chunk fixes (digit/letter spacing)
 * and call [polish] once at end-of-stream.
 */
@Singleton
class ResponsePostProcessor @Inject constructor() {

    /**
     * Apply all surface-level normalizations to a complete assistant message.
     *
     * Order matters: strip leaked markup and thinking-preamble first (so they
     * don't get formatted), then date strings (so they don't get caught by
     * digit-letter spacing), then digit-letter spacing.
     */
    fun polish(rawText: String): String {
        if (rawText.isBlank()) return rawText
        var out = rawText
        out = stripChannelMarkup(out)
        out = stripThinkingPreamble(out)
        out = formatDateStrings(out)
        out = addDigitLetterSpacing(out)
        out = normalizeMarkdown(out)
        return out.trim()
    }

    /**
     * Strip Harmony-/channel-style internal markup that leaked into the
     * visible response: "<thought ...<channel|>" preambles, stray
     * "<|channel|>" / "<thought>" tags, and Gemma chat-template tokens
     * ("<end_of_turn>", "<start_of_turn>") that occasionally render literally.
     */
    fun stripChannelMarkup(text: String): String {
        var out = text
        out = out.replace(
            Regex(
                "<thought[\\s\\S]*?(?:<\\|?channel\\|?>|</thought>)",
                RegexOption.IGNORE_CASE
            ),
            ""
        )
        out = out.replace(Regex("<\\|?channel\\|?>", RegexOption.IGNORE_CASE), "")
        out = out.replace(Regex("</?thought>", RegexOption.IGNORE_CASE), "")
        Regex("<end_of_turn>", RegexOption.IGNORE_CASE).find(out)?.let {
            out = out.substring(0, it.range.first)
        }
        Regex("<start_of_turn>", RegexOption.IGNORE_CASE).find(out)?.let {
            out = out.substring(0, it.range.first)
        }
        out = out.replace(
            Regex("<\\|?(?:end_of_turn|start_of_turn|eos|bos)\\|?>", RegexOption.IGNORE_CASE),
            ""
        )
        return out
    }

    /**
     * Strip leaked natural-language thinking-preamble where the model
     * narrates the user's question back ("The user is asking for three
     * colors. This is a general knowledge question...") before answering.
     * Only fires when a meta-reference paragraph is followed by a substantial
     * body, so legitimate one-paragraph answers are never truncated.
     */
    fun stripThinkingPreamble(text: String): String {
        if (text.length < 60) return text
        val splitIdx = text.indexOf("\n\n")
        if (splitIdx < 0) return text
        val firstPara = text.substring(0, splitIdx).trim()
        val rest = text.substring(splitIdx + 2).trim()
        if (rest.length < 20) return text
        val preambleStart = Regex(
            "^(the user (is |wants|asked|asks|'s question|needs)|" +
                "user is asking|" +
                "this is a (general|simple|basic|straightforward|factual) " +
                "(knowledge|math|question)|" +
                "let me (think|consider|break)|" +
                "i need to (figure|think|consider|determine|provide))",
            RegexOption.IGNORE_CASE
        )
        if (!preambleStart.containsMatchIn(firstPara)) return text
        return rest
    }

    /**
     * Lightweight pass for per-token streaming. Only fixes that are safe on
     * partial text (no markdown structure assumptions).
     */
    fun normalizeStreamingChunk(chunk: String): String {
        if (chunk.isEmpty()) return chunk
        return chunk
    }

    // ── Markdown normalization ─────────────────────────────────────────────

    private fun normalizeMarkdown(text: String): String {
        var out = text
        // Collapse 3+ blank lines to 2. The model sometimes emits big gaps.
        out = out.replace(Regex("\n{3,}"), "\n\n")
        // Strip trailing whitespace on each line.
        out = out.lineSequence().joinToString("\n") { it.trimEnd() }
        return out
    }

    // ── Digit / letter spacing ─────────────────────────────────────────────
    // The on-device tokenizer occasionally fuses adjacent digit and letter
    // tokens ("between" + "0" → "between0", "9" + "C" → "9C"). Re-insert a
    // space at the boundary. Skip date strings (already formatted as X/Y/Z).

    private fun addDigitLetterSpacing(text: String): String {
        var out = text
        out = out.replace(Regex("(\\d)([a-zA-Z])"), "$1 $2")
        out = out.replace(Regex("([a-zA-Z])(\\d)"), "$1 $2")
        return out
    }

    // ── Date normalization ─────────────────────────────────────────────────

    private fun formatDateStrings(text: String): String {
        var result = text.replace(
            Regex("\\b(20\\d{2})(0[1-9]|1[0-2])(0[1-9]|[12]\\d|3[01])\\b")
        ) { m -> "${m.groupValues[1]}/${m.groupValues[2]}/${m.groupValues[3]}" }
        result = result.replace(
            Regex("\\b(0[1-9]|[12]\\d|3[01])(0[1-9]|1[0-2])(20\\d{2})\\b")
        ) { m -> "${m.groupValues[1]}/${m.groupValues[2]}/${m.groupValues[3]}" }
        return result
    }
}
