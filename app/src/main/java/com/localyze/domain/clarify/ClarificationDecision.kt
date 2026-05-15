package com.localyze.domain.clarify

/**
 * Clarification flow types.
 *
 * The orchestrator looks at every outgoing user message and decides:
 *   - Already specific → pass through to the normal flow.
 *   - Too vague → ask 1-3 clarifying questions WITH options, capped at
 *     2 rounds per conversation.
 *   - Enough info gathered → compose a refined query and pass that to
 *     the normal flow.
 *
 * North star is accuracy: the assistant must not return a generic
 * "top 10 news" guess when the user's intent could have been narrowed
 * with two quick options-style questions.
 */

/** A single clarifying question with a small list of suggested answers. */
data class ClarifyQuestion(
    val text: String,
    val options: List<String>,
)

/** What the orchestrator decided to do next for this user message. */
sealed class ClarificationDecision {
    /** Question is already specific; pass through to normal flow. */
    data object PassThrough : ClarificationDecision()

    /** We need more info. Render these as the assistant's next turn.
     *  `topic` is the original user query (verbatim) — used to echo back
     *  what we're clarifying about so the user keeps context. */
    data class AskMore(
        val questions: List<ClarifyQuestion>,
        val topic: String? = null,
    ) : ClarificationDecision()

    /**
     * We've gathered enough — here's the consolidated, refined query
     * to actually run through Gemma / web search.
     */
    data class Specific(val refinedQuery: String) : ClarificationDecision()
}

/** Per-conversation state: which clarification rounds have been asked. */
data class ClarifyState(
    val originalQuery: String,
    /** The most-recent round of questions we asked, awaiting the user's reply. */
    val pendingQuestions: List<ClarifyQuestion> = emptyList(),
    /** Fully completed rounds (questions + the user's reply to them). */
    val completedRounds: List<RoundQA> = emptyList(),
    val maxRounds: Int = 2,
) {
    val roundsCompleted: Int get() = completedRounds.size
    val capReached: Boolean get() = roundsCompleted >= maxRounds

    /** Convert pending → completed by attaching the user's reply. */
    fun finishPending(userReply: String): ClarifyState = copy(
        pendingQuestions = emptyList(),
        completedRounds = completedRounds + RoundQA(pendingQuestions, userReply),
    )

    /** Stash a new round of questions as pending. */
    fun withPending(questions: List<ClarifyQuestion>): ClarifyState =
        copy(pendingQuestions = questions)
}

/** A completed clarification round: what we asked + what the user said. */
data class RoundQA(
    val questions: List<ClarifyQuestion>,
    val userReply: String,
)

/**
 * Render a clarify decision as a Markdown assistant message.
 * V1: plain text with options in italics. V2: tappable chips.
 */
fun ClarificationDecision.AskMore.toMarkdown(): String = buildString {
    if (!topic.isNullOrBlank()) {
        // Echo what the user actually asked about so the conversation
        // keeps context. Without this, "should I buy Bitcoin?" gets back
        // bare "Why are you asking? / Region? / Time horizon?" with no
        // mention of Bitcoin — confusing and easy to lose track of.
        appendLine("About **${topic.trim()}** — quick question first to give you a useful answer:")
    } else {
        appendLine("Quick question first — to give you a useful answer:")
    }
    appendLine()
    questions.forEachIndexed { i, q ->
        append("${i + 1}. ${q.text}")
        if (q.options.isNotEmpty()) {
            append(" *(${q.options.joinToString(" / ")})*")
        }
        appendLine()
    }
    appendLine()
    appendLine("If you'd rather I just go with reasonable defaults, say \"go ahead\".")
}
