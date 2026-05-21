package com.localyze.ai

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Input-side "harness" that previously lived as system-prompt prose.
 *
 * - Prompt-injection detection used to be a ~200-token INJECTION_RESISTANCE
 *   block in the chat prompt. Now it's a regex check here: if the user is
 *   trying to override the system prompt or extract it, we short-circuit
 *   in Kotlin and never hit the model.
 *
 * Clarification-trigger detection lives in [com.localyze.domain.clarify.ClarificationOrchestrator];
 * the old CLARIFICATION_POLICY prompt block is gone from the system prompt
 * for the same reason — the orchestrator already decides this in code.
 */
@Singleton
class InputGuardrails @Inject constructor() {

    private val injectionPatterns = listOf(
        Regex("\\bignore\\s+(all\\s+)?(previous|prior|above)\\s+instructions?\\b", RegexOption.IGNORE_CASE),
        Regex("\\bdisregard\\s+(all\\s+)?(previous|prior|above)\\s+(instructions?|prompts?|rules?)\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(reveal|show|print|repeat|output)\\s+(your\\s+)?(system\\s+prompt|initial\\s+prompt|instructions)\\b", RegexOption.IGNORE_CASE),
        Regex("\\bwhat\\s+(are|were)\\s+your\\s+(original\\s+)?(instructions|system\\s+prompt)\\b", RegexOption.IGNORE_CASE),
        Regex("\\byou\\s+are\\s+now\\s+(?!Localyze)[A-Z]", RegexOption.IGNORE_CASE),
    )

    /** Result of a guardrail check. */
    sealed interface Decision {
        /** Pass through unchanged. */
        data object Allow : Decision
        /** Reject without calling the model; show [reply] to the user. */
        data class Refuse(val reply: String) : Decision
    }

    /**
     * Decide whether [userMessage] should be forwarded to the model.
     */
    fun check(userMessage: String): Decision {
        if (userMessage.isBlank()) return Decision.Allow
        if (looksLikeInjection(userMessage)) {
            return Decision.Refuse(
                "I'll stick with my normal behavior — I won't override my " +
                    "instructions or share my system prompt. Ask me a real question " +
                    "and I'll help."
            )
        }
        return Decision.Allow
    }

    private fun looksLikeInjection(text: String): Boolean {
        return injectionPatterns.any { it.containsMatchIn(text) }
    }
}
