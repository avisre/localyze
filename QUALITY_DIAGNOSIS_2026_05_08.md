# Localyze quality vs Claude — diagnosis (2026-05-08)

Test: 8 prompts × 2 modes (online, offline) on OnePlus NE2211 / Android 16, Gemma 4 E4B / GPU.

Logs reconstructed via logcat; raw answers in `quality_8q_online.json` / `quality_8q_offline.json`. Reference Claude-quality answers in `reference_answers.md`.

## Score summary (vs Claude)

| Q | Topic | Online | Offline | Notes |
|---|---|---|---|---|
| Q1 | TCP vs UDP, "4 short bullets" | 3/5 | 4/5 | Verbose; user wanted 4 bullets, got 2-level nested list with 8+ bullets |
| Q2 | Train math word problem | **1/5** | **0/5** | Online: digits garbled ("0 miles", "6 mph", "7.5 mph", final 10:30 AM vs correct 11:30 AM). Offline: response timeout (zero output). |
| Q3 | longest_run() Python | 3/5 | 2/5 | Function body correct, but example call uses `longestrun()` (missing `_`); offline also has `currentrun` typo inside the loop → NameError if copied |
| Q4 | 3-sentence apology email | **0/5** | **0/5** | Online: deterministic LiteRT-LM crash at ~31s. Offline: tool fired (`email_draft`) → stub message instead of the email body the user asked for |
| Q5 | Hash table for beginner | 5/5 | 4/5 | Excellent — close to Claude quality. Library/ISBN analogy is clear and accurate |
| Q6 | Latest Python version | **1/5** | **0/5** | Online: curator dumped URL fragments, no Python 3.x stated. Offline: canned refusal "I can't verify live updates" — never even attempts cutoff knowledge |
| Q7 | Current Bitcoin price | 4/5 | **0/5** | Online: hardcoded BTC pattern returned "around $80,039.00 USD" + sources (works). Offline: same canned refusal as Q6 |
| Q8 | Apple state-tracking | 4/5 | 4/5 | Correct (1, 1). Slightly odd inline LaTeX (`$3-2=1$`) but readable |
| **Avg** | | **52%** | **35%** | |

## How the system actually answers (architecture I had to discover)

`SendMessageUseCase.generateWithToolLoop` runs this gate before Gemma ever sees a message:

1. `buildPreflightCalculatorCallIfNeeded` — regex-matches a math expression in the prompt; if matched, dispatches `calculator` tool and injects result into the model prompt. Q2's word problem is too complex for these regexes (they only catch patterns like `N+M`, `N times M`, `N miles in M hours`), so it never fires.
2. `curatedOfflineCurrentAnswerFor` — *only when web search is OFF*. If `hasCurrentIntent(text)` matches (regex fires on `latest|recent|today|current|now|version|price|...`), returns a hardcoded refusal: "I can't verify live updates right now… Turn on Web search…". **Q6 and Q7 hit this offline.**
3. `curatedStableAnswerFor` — hardcoded canned strings for ~15 specific questions ("capital of france", "speed of light", "wrote romeo and juliet"…).
4. `buildPreflightWebSearchCallIfNeeded` (online only) — heuristic decision to fetch web results.
5. `curatedWebSummaryAnswer` (online + web results) — calls `directWebAnswerFor` which **pattern-matches on ~15 specific question phrasings** ("ceo of google", "bitcoin", "fifa world cup final", "stock price of apple"…). If no pattern matches, falls back to `conciseWebAnswerFromResults` which just stitches snippets together. **Q6 hits this fallback online.**
6. Only if none of the above produced an answer does Gemma actually generate.

So the user often isn't talking to Gemma — they're talking to a regex/template layer that bypasses the model.

## Failure modes — root causes and fixes

### CRITICAL 1 — Q2 number garbling (online)

**Observed**: prompt has "60 mph", "75 mph", "300 miles", "9:30 AM"; the response says "0 miles apart", "6 mph", "7.5 mph", "9:300 AM"; final answer 10:30 AM (correct: 11:30 AM).

**Likely cause**: thinking mode is on (logs show `enableThinking=true` even though datastore default is `false`, so the user toggled it). Internal `<thought>` reasoning is leaking digit confusion into output. The system prompt also tells the model to call `calculator` for any arithmetic — the model can't formalize this multi-step word problem into a single expression, gets stuck, and produces garbled numeric reasoning.

**Fixes**:
- Add to chat prompt: "When the user gives you specific numbers in a word problem, copy each digit exactly. Do not abbreviate, round, or drop trailing zeros."
- Make thinking mode opt-in *per-message*, not a sticky setting (or default off and surface a clearer toggle).
- Soften the calculator-required instruction: "use calculator for `N op M` expressions; for word problems, reason step-by-step and use calculator only on the resulting expression."
- File: [SystemPromptBuilder.kt:9-35](app/src/main/java/com/localyze/ai/SystemPromptBuilder.kt#L9-L35)

### CRITICAL 2 — Q4 deterministic crash + tool misrouting

**Observed**:
- Online: LiteRT-LM JNI error after ~31 s, zero tokens emitted, on both runs.
- Offline: returns `"The email draft has been opened for your review. You must tap Send manually."` — model emitted an `email_draft` tool call instead of writing the email body.

**Likely cause**: `EmailDraftTool` is in the global tool registry and described as "Open an email compose draft for user review." For the prompt "Draft a 3-sentence apology email…" the model classifies this as an email_draft intent. Online, the tool-call path through LiteRT-LM hits a JNI bug. Offline, the tool fires and the user gets a stub.

**Fixes**:
- Tighten `EmailDraftTool.description`: "Open the system Email composer with a pre-filled draft. Use ONLY when the user provides a specific recipient address (e.g. `bob@example.com`) or has already drafted body text and wants it sent."
- Or, in chat-mode system prompt, explicitly instruct: "When the user asks you to *write* an email body inline, output the email text directly — do not call email_draft. Use email_draft ONLY when the user wants to send to a specific address."
- File: [EmailDraftTool.kt:19](app/src/main/java/com/localyze/tools/EmailDraftTool.kt#L19), [SystemPromptBuilder.kt](app/src/main/java/com/localyze/ai/SystemPromptBuilder.kt)
- Separately, the JNI crash should be reported upstream — but the prompt fix avoids hitting it.

### HIGH 3 — Curator over-routing for live questions

**Observed**:
- Q6 online: curator returned a stitched-snippet dump that read like raw URL fragments, no actual "Python 3.13" answer.
- Q7 online: lucky pattern match (`question.contains("bitcoin") && Regex("\\$\\s*\\d{2,3}(?:,\\d{3})+...")`) gave a usable answer.

**Root cause**: `directWebAnswerFor` in [SendMessageUseCase.kt:1372-1469](app/src/main/java/com/localyze/domain/usecases/SendMessageUseCase.kt#L1372-L1469) is a hand-coded `when` block of ~15 phrasings. Anything outside that list falls through to `conciseWebAnswerFromResults` which just concatenates snippets verbatim. Claude reads search results in context and synthesizes an answer; Localyze does pattern-matching.

**Fix**: Stop calling `curatedWebSummaryAnswer` for questions that don't hit a confidence-bearing curator. Always feed the web results into Gemma via `buildWebGroundedPrompt` (already exists at [line 994](app/src/main/java/com/localyze/domain/usecases/SendMessageUseCase.kt#L994)) and let it synthesize. Keep the curator only for: BTC price, stock price, weather — places where regex extraction beats model output.

Concrete change: in [line 1340-1342](app/src/main/java/com/localyze/domain/usecases/SendMessageUseCase.kt#L1340-L1342), if `directWebAnswerFor` returns null, **return null from `curatedWebSummaryAnswer`** (don't fall back to the snippet-stitcher) — the caller will then route through Gemma with web context, which is the right path.

### HIGH 4 — Offline current-intent refusal is too aggressive

**Observed**: Q6 and Q7 OFFLINE both return the canned refusal "I can't verify live updates right now… Turn on Web search…". Claude would attempt an answer with a knowledge-cutoff caveat.

**Root cause**: `hasCurrentIntent` regex at [line 1586](app/src/main/java/com/localyze/domain/usecases/SendMessageUseCase.kt#L1586) matches `version|current|now|latest|...` — these terms apply to almost any question with mild recency, including "What is the latest stable version of Python" (where the model knows the answer to within a year of cutoff).

**Fix**: Replace the canned refusal with a Gemma path that injects a "no web access — answer from training cutoff and disclose it" preamble. Pseudocode:

```kotlin
private fun curatedOfflineCurrentAnswerFor(...): String? = null  // disable

// Instead, in generateWithToolLoop, when allowWebSearch == false and
// hasCurrentIntent(userMessage), prepend to userMessage:
val offlineHint = "(Web search is OFF, so answer from your training data. " +
    "If your knowledge of this topic could be stale (prices, scores, " +
    "current officeholders, version numbers), state your cutoff date and " +
    "recommend the user enable web search.)"
```

### MEDIUM 5 — Code identifier corruption

**Observed**: `longestrun(nums_list)` (online example call), `currentrun` instead of `current_run` (offline body) — both would `NameError`.

**Root cause**: capability mode is `chat` for these prompts (intent extra defaults to "chat"). The chat prompt doesn't have the code-mode instruction. Gemma's tokenizer + thinking-mode reasoning loses the underscore on rare snake_case identifiers.

**Fix**:
- Heuristic: if user prompt mentions "Python", "function", "def", "class", "code" — auto-switch to `code` capability mode in the SendMessageUseCase entry point.
- Or in the chat prompt, add: "When writing code, use exactly the identifier names you defined. Reread your function name before calling it."
- File: [SystemPromptBuilder.kt:80-91](app/src/main/java/com/localyze/ai/SystemPromptBuilder.kt#L80-L91), [ChatViewModel.kt](app/src/main/java/com/localyze/ui/viewmodels/ChatViewModel.kt)

### MEDIUM 6 — Length/format non-adherence

**Observed**: Q1 asked for "4 short bullets" → got nested list with 8+ bullets, 1100+ chars.

**Fix**: in chat prompt, add a strict line: "If the user specifies a length or format ('3 sentences', '4 bullets', 'one paragraph'), match it exactly. Do not add sub-bullets or extra commentary."

### LOW 7 — Thinking mode is sticky-on

**Observed**: `SettingsDataStore.thinkingMode` defaults to `false` but logs show `enableThinking=true` for every question. The toggle is in Settings; once on it stays on.

This nearly doubles latency (thinking pass + answer pass) and contributes to verbosity. Recommend: hide the toggle behind an "advanced" section and gate it per-message rather than globally — or default to a "smart" mode that enables thinking only for math/multi-step reasoning prompts.

## Why Q5 was good — what to copy

Q5 (hash table) hit the pure Gemma path with no curator interference, no tool-routing, no math. The chat system prompt is fine for that shape of question. The fixes above are essentially: get more questions to look like Q5 — by removing the regex/template gates that intercept Gemma when it would do better on its own.

## Recommended order of fixes

1. **EmailDraftTool description tightening** + chat-prompt rule (fixes Q4, ~5 min, low risk)
2. **Disable `curatedWebSummaryAnswer` fallback** when no specific pattern matches (fixes Q6 online, ~10 min, low risk)
3. **Replace `curatedOfflineCurrentAnswerFor` with a Gemma path + cutoff preamble** (fixes Q6/Q7 offline, ~20 min)
4. **Add length-adherence + digit-fidelity rules to chat prompt** (helps Q1, Q2, ~5 min)
5. **Auto-route code prompts to `code` capability mode** (fixes Q3 corruption, ~15 min)
6. Investigate Q2 number garbling — likely needs thinking-mode-off A/B test on the same prompt
7. Make thinking mode per-message instead of global

The first four together should lift the average score from ~44% to ~70% without touching the model itself.
