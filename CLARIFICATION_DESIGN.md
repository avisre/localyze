# Clarification orchestrator — design

## Problem

Today's clarification policy lives only in the system prompt
([SystemPromptBuilder.kt](app/src/main/java/com/localyze/ai/SystemPromptBuilder.kt)).
The model decides whether to ask follow-ups, and it often skips them
(especially for web-routed live-data queries that bypass the LLM entirely
via the curator). When a user sends "top 10 news", they should see:

> Quick question first — to give you a useful answer:
> 1. About what topic? (finance / culture / sports / tech / politics)
> 2. Which region? (US / India / UK / global)
> 3. What's the time window? (today / this week)

…then after the user answers, possibly **a second round** narrowing further,
and only then the substantive answer. This is how Claude behaves.

## Solution overview

Add a new `ClarificationOrchestrator` that runs **before** every user
message reaches Gemma or the web router. It is the single gatekeeper for
"should we ask, or should we answer?". It owns:

1. A fast heuristic to skip orchestration for obviously-specific questions
   (a regex / token-level pre-filter; no LLM cost).
2. An LLM judge call (small focused prompt, ~200 tokens) that returns a
   structured JSON decision.
3. Per-conversation state that tracks how many clarification rounds have
   already been asked, so we cap at 2 rounds and don't loop forever.
4. The composed "refined query" we send onward once the conversation is
   specific enough.

## Flow

```
                  ┌────────────────────┐
user message ───▶ │ ChatViewModel      │
                  │ .sendMessage(text) │
                  └─────────┬──────────┘
                            │
                            ▼
                  ┌────────────────────┐
                  │ Clarification      │   ← reads ClarifyState from
                  │ Orchestrator       │     conversation context
                  │ .analyze(...)      │
                  └─────────┬──────────┘
                            │
        ┌───────────────────┼─────────────────────┐
        ▼                   ▼                     ▼
   AskMore(qs)         Specific(refined)      PassThrough(text)
        │                   │                     │
        │                   │                     │
        ▼                   ▼                     ▼
   render as            send refined          send text as-is
   assistant            query through         (already specific)
   message with         existing
   options              SendMessageUseCase
```

## New types

```kotlin
// app/src/main/java/com/localyze/domain/clarify/ClarificationDecision.kt

data class ClarifyQuestion(
    val text: String,
    val options: List<String>,    // e.g. ["finance", "culture", "sports", "tech"]
)

sealed class ClarificationDecision {
    /** Question is already specific; pass through to normal flow. */
    data object PassThrough : ClarificationDecision()

    /** We need more info. Ask these and stop. */
    data class AskMore(val questions: List<ClarifyQuestion>) : ClarificationDecision()

    /** We've gathered enough; here's the consolidated query to run. */
    data class Specific(val refinedQuery: String) : ClarificationDecision()
}

data class ClarifyState(
    val originalQuery: String,
    val rounds: List<RoundQA> = emptyList(),
    val maxRounds: Int = 2,
) {
    val roundsAsked: Int = rounds.size
    val capReached: Boolean = roundsAsked >= maxRounds
}

data class RoundQA(
    val questions: List<ClarifyQuestion>,
    val userReply: String,
)
```

## Orchestrator

```kotlin
// app/src/main/java/com/localyze/domain/clarify/ClarificationOrchestrator.kt

@Singleton
class ClarificationOrchestrator @Inject constructor(
    private val gemma: GemmaInferenceEngine,
    private val json: Json,
) {
    suspend fun analyze(
        userQuery: String,
        state: ClarifyState?,
    ): ClarificationDecision {
        // 1. Pre-filter: if the query is obviously specific, pass through.
        if (isObviouslySpecific(userQuery, state)) {
            return state?.let {
                ClarificationDecision.Specific(composeRefinedQuery(it, userQuery))
            } ?: ClarificationDecision.PassThrough
        }

        // 2. If we've already asked the max rounds, force-Specific.
        if (state != null && state.capReached) {
            return ClarificationDecision.Specific(composeRefinedQuery(state, userQuery))
        }

        // 3. Run the LLM judge.
        val verdict = askJudge(userQuery, state)
        return when (verdict.action) {
            "specific"  -> ClarificationDecision.Specific(verdict.refinedQuery
                ?: composeRefinedQuery(state, userQuery))
            "ask_more"  -> ClarificationDecision.AskMore(verdict.questions.orEmpty())
            else        -> ClarificationDecision.PassThrough
        }
    }

    private fun isObviouslySpecific(q: String, state: ClarifyState?): Boolean {
        // - Already asked 2 rounds → not "obviously specific" but capped.
        if (state != null && state.capReached) return false
        // - Short factual lookups (capital of, what is X+Y, etc.)
        // - Already has explicit constraints (currency, region, year, budget)
        // - Code blocks / file paths / specific identifiers
        // ...
        return matchesSpecificPatterns(q)
    }

    private suspend fun askJudge(
        userQuery: String,
        state: ClarifyState?,
    ): JudgeVerdict {
        val prompt = buildJudgePrompt(userQuery, state)
        val raw = gemma.generateOnce(prompt, capabilityMode = "code", thinking = false)
        return json.decodeFromString(JudgeVerdict.serializer(), extractJson(raw))
    }

    /** The LLM prompt is small and focused — no system prompt, no tools. */
    private fun buildJudgePrompt(q: String, state: ClarifyState?): String = """
        You are an intent classifier. Decide whether the user's query is
        specific enough to answer well, or needs clarification.

        Already-asked rounds (if any):
        ${state?.rounds?.joinToString("\n") { r ->
            "- asked: ${r.questions.joinToString("; ") { it.text }}\n  user replied: ${r.userReply}"
        } ?: "(none)"}

        Current query:
        ${state?.originalQuery ?: q}
        Latest user reply: ${q}

        Output STRICT JSON with this exact schema:
        {
          "action": "specific" | "ask_more",
          "questions": [
            {"text": "...", "options": ["...","...","...","..."]}
          ],
          "refined_query": "..."
        }

        Rules:
        - If clear → action="specific", refined_query = the consolidated query.
        - If vague → action="ask_more", give 2-3 questions WITH options.
        - Never both.
        - Examples of vague: "top 10 news", "best phone", "help with taxes",
          "recommend a stock", "tell me about X", "what's trending".
    """.trimIndent()
}
```

## Wiring into SendMessageUseCase

```kotlin
class SendMessageUseCase @Inject constructor(
    private val orchestrator: ClarificationOrchestrator,
    /* ... existing deps ... */
) {
    suspend fun send(conversationId: Long, userText: String): Flow<ChatResponseEvent> = flow {
        val state = readClarifyState(conversationId)
        when (val decision = orchestrator.analyze(userText, state)) {
            is ClarificationDecision.AskMore -> {
                writeClarifyState(conversationId, state.appendRound(decision.questions, userText))
                emit(buildClarifyMessageEvent(decision.questions))   // assistant turn
                return@flow
            }
            is ClarificationDecision.Specific -> {
                clearClarifyState(conversationId)
                runRealQuery(decision.refinedQuery).collect { emit(it) }
            }
            is ClarificationDecision.PassThrough -> {
                runRealQuery(userText).collect { emit(it) }
            }
        }
    }
}
```

## Per-conversation state storage

`ClarifyState` is small enough to store either:
- **In memory** keyed by conversationId (simple, lost on app restart — fine
  for a clarification flow that should resolve in seconds), or
- **In the conversation row** as a JSON column (`Conversation.clarifyState`).

V1 uses in-memory. V2 can promote to Room if we want clarification to
survive process death.

## UI rendering

The clarify message is a regular assistant message containing structured
content. Two options:

- **V1 (text-only)**: render as plain markdown:

  > **Quick question first — to give you a useful answer:**
  >
  > 1. About what topic? *(finance / culture / sports / tech)*
  > 2. Which region? *(US / India / UK / global)*

  User types their reply normally. Works with zero UI changes.

- **V2 (chips)**: render each option as a tappable chip that fills the
  input. Better UX but requires a new MessageBubble variant.

V1 ships first, V2 as a follow-up.

## Tests

1. `ClarificationOrchestratorTest` — fakes the Gemma call, verifies:
   - "top 10 news" → AskMore with topic + region
   - "capital of France" → PassThrough
   - After 2 rounds → forced Specific
2. `SendMessageUseCaseClarifyTest` — verifies the use case routes correctly.
3. End-to-end live test on the device with the existing 35-question batch.

## Open questions

1. **Latency**: the judge is a small Gemma call but still adds 1-3s to
   every message. Acceptable? Pre-filter handles the common case.
2. **Web-routed queries**: today the web router intercepts before the
   model. Move the orchestrator earlier so news/sports queries get
   clarified first, then the refined query goes to web search.
3. **Double-asking**: the existing system-prompt CLARIFICATION_POLICY may
   conflict with the orchestrator. After this lands, simplify the prompt
   to just say "answer the refined query directly".
4. **State expiry**: if the user changes topic mid-clarification ("never
   mind, what's the weather"), we should detect the topic shift and reset
   the state. Heuristic: low cosine similarity between old and new query.

## Implementation order

1. New types + orchestrator class with the LLM judge stubbed.
2. `ClarificationOrchestratorTest` with a mocked Gemma.
3. Wire into `SendMessageUseCase`, gated behind a feature flag.
4. Live test on device: "top 10 news" → expect 2 rounds → final answer.
5. Run all 35 questions and capture the round flow.
6. Once stable, simplify the system prompt and remove the flag.
