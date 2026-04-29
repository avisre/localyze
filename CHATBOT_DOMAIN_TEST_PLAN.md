# Localyze Chatbot Domain Test Plan

> **Purpose**: Evaluate the Localyze chatbot's ability to answer questions across **Finance**, **Technology**, **Culture**, and **News** domains — both with internet (web search) and offline — across all three response paths: Real Engine, Mock Engine, and Fallback.

---

## Table of Contents

1. [Test Questions](#1-test-questions)
2. [Evaluation Criteria](#2-evaluation-criteria)
3. [Expected vs Actual Answer Analysis](#3-expected-vs-actual-answer-analysis)
4. [Identified Improvements](#4-identified-improvements)
5. [Test Execution Instructions](#5-test-execution-instructions)

---

## Response Paths Under Test

The Localyze app has **3 response paths**, each producing fundamentally different output:

| Path | Source | Behavior |
|------|--------|----------|
| **Real Engine** | Gemma 4 E4B via LiteRT-LM | Produces real AI responses. Web search preflight injects DuckDuckGo results into the prompt. Offline protocol prefixes with *I'm offline* and marks knowledge with *As of my last knowledge update…*. |
| **Mock Engine** | `MockGemmaEngine` | Cycles through 5 generic responses for non-code modes. Only `code` mode gets keyword-aware responses (explain, debug, fix, optimize, review). **No domain awareness for finance, tech, culture, or news.** |
| **Fallback** | `GemmaInferenceEngine` mode-specific fallbacks | Model not loaded. Returns per-mode generic messages like *The AI model is currently loading. Please try again in a moment.* |

---

## 1. Test Questions

### Finance (4 Questions)

| # | Question | Internet Required | Category | Expected Behavior |
|---|----------|-------------------|----------|-------------------|
| 1 | "What is the current repo rate set by RBI?" | YES | Finance / Current | Preflight search triggers on **current**. Model should cite source with date. |
| 2 | "Explain what compound interest means with a simple example" | NO | Finance / Educational | Model answers from knowledge. Simple example first, then technical details. |
| 3 | "How is the Indian stock market performing today?" | YES | Finance / Current | Preflight triggers on **today**. Should provide market summary with sources. |
| 4 | "What is the difference between mutual funds and fixed deposits?" | NO | Finance / Educational | Model explains from knowledge. Should compare with pros/cons. |

### Technology (4 Questions)

| # | Question | Internet Required | Category | Expected Behavior |
|---|----------|-------------------|----------|-------------------|
| 5 | "What are the latest features in Android 16?" | YES | Tech / Current | Preflight triggers on **latest**. Should list features with sources. |
| 6 | "Explain the difference between REST and GraphQL APIs" | NO | Tech / Educational | Model explains from knowledge. Plain language first, then comparison table. |
| 7 | "What is the current price of the iPhone 16 Pro in India?" | YES | Tech / Current | Preflight triggers on **current** and **price**. Should cite source. |
| 8 | "What is machine learning and how does it work?" | NO | Tech / Educational | Model explains from knowledge. Simple explanation, then technical depth. |

### Culture (4 Questions)

| # | Question | Internet Required | Category | Expected Behavior |
|---|----------|-------------------|----------|-------------------|
| 9 | "Who won the Oscar for Best Picture in 2026?" | YES | Culture / Current | Preflight may **NOT** trigger — **won** is not in the current-need keyword list. Model should call `web_search` natively via tool calling. |
| 10 | "What is the significance of Diwali in Indian culture?" | NO | Culture / Educational | Model explains from knowledge. Cover significance, history, modern practice. |
| 11 | "What are the top trending movies in India this week?" | YES | Culture / Current | Preflight may **NOT** trigger — **trending** is not in the current-need keyword list. Model should call `web_search`. |
| 12 | "Explain the history and meaning of yoga in Indian tradition" | NO | Culture / Educational | Model explains from knowledge. Comprehensive coverage. |

### News / Other (3 Questions)

| # | Question | Internet Required | Category | Expected Behavior |
|---|----------|-------------------|----------|-------------------|
| 13 | "What are the top news headlines in India today?" | YES | News / Current | Preflight triggers on **news** and **today**. Should provide headlines with sources. |
| 14 | "What is the latest on the India-UK trade agreement?" | YES | News / Current | Preflight triggers on **latest**. Should provide update with sources. |
| 15 | "What is climate change and why does it matter?" | NO | General / Educational | Model explains from knowledge. Well-structured with headers and bullets. |

### Summary Matrix

| # | Domain | Internet | Type | Preflight Keyword Match |
|---|--------|----------|------|------------------------|
| 1 | Finance | YES | Current | **current** ✅ |
| 2 | Finance | NO | Educational | N/A |
| 3 | Finance | YES | Current | **today** ✅ |
| 4 | Finance | NO | Educational | N/A |
| 5 | Technology | YES | Current | **latest** ✅ |
| 6 | Technology | NO | Educational | N/A |
| 7 | Technology | YES | Current | **current**, **price** ✅ |
| 8 | Technology | NO | Educational | N/A |
| 9 | Culture | YES | Current | **won** ❌ NOT in keyword list |
| 10 | Culture | NO | Educational | N/A |
| 11 | Culture | YES | Current | **trending** ❌ NOT in keyword list |
| 12 | Culture | NO | Educational | N/A |
| 13 | News | YES | Current | **news**, **today** ✅ |
| 14 | News | YES | Current | **latest** ✅ |
| 15 | General | NO | Educational | N/A |

**Internet Required**: 9 questions (Q1, Q3, Q5, Q7, Q9, Q11, Q13, Q14)
**Offline OK**: 6 questions (Q2, Q4, Q6, Q8, Q10, Q12, Q15)
**Preflight Gaps**: 2 questions (Q9, Q11) where the preflight keyword list does not match the question intent

---

## 2. Evaluation Criteria

For each question, evaluate on these dimensions using a **1–5 scale**:

| Criterion | Description | Score 1 | Score 5 |
|-----------|-------------|---------|---------|
| **Accuracy** | Is the information correct? | Completely wrong | Fully accurate with sources |
| **Clarity** | Is it easy to understand for a non-expert? | Confusing, jargon-heavy | Crystal clear, simple language |
| **Completeness** | Does it cover the key aspects? | Missing major points | Comprehensive coverage |
| **Source Citation** | *(Online only)* Are sources cited with URLs? | No sources | Sources with exact URLs from search results |
| **Offline Handling** | *(Offline only)* Is the limitation clearly stated? | Refuses to answer | Clear offline prefix + knowledge caveat |
| **Formatting** | Is the answer well-structured? | Wall of text | Clean markdown with sections/bullets |

### Scoring Rules

- **Online questions** are scored on: Accuracy, Clarity, Completeness, Source Citation, Formatting (5 criteria)
- **Offline questions** are scored on: Accuracy, Clarity, Completeness, Offline Handling, Formatting (5 criteria)
- **Pass threshold**: Average score ≥ 3.5/5 across all applicable criteria for a given question
- **Overall pass**: ≥ 12 of 15 questions must pass individually

### Score Interpretation Per Criterion

| Score | Meaning |
|-------|---------|
| 5 | Excellent — exceeds expectations |
| 4 | Good — meets expectations with minor gaps |
| 3 | Adequate — acceptable but could improve |
| 2 | Below average — notable deficiency |
| 1 | Failing — critical gap |

---

## 3. Expected vs Actual Answer Analysis

For each of the 15 questions, this section documents what each response path should produce and identifies gaps.

---

### Q1: "What is the current repo rate set by RBI?"

**Real Engine (with internet):**
- Preflight triggers on **current** → `web_search` called with query
- Model receives DuckDuckGo results with current RBI repo rate
- Should answer with: exact rate, date of last RBI monetary policy meeting, source URL
- Example: *"The current repo rate set by RBI is 6.50% as of [date]. Source: [URL]"*

**Real Engine (offline):**
- No preflight search (web search disabled)
- Model should prefix: *"I'm offline, so I can't search for the latest information."*
- Then: *"As of my last knowledge update, the RBI repo rate was 6.50% (as of [training cutoff]). This may have changed."*

**Mock Engine:**
- ⚠️ **CRITICAL GAP**: Cycles through 5 generic responses — no finance domain awareness
- Returns one of: greeting, code block, productivity tips, thinking response, or tool call
- User asking about RBI repo rate gets a completely irrelevant answer

**Fallback:**
- Returns: *"Hi! I'm your on-device AI assistant. The model is currently loading. Please try sending your message again in a moment."*
- ⚠️ **NEEDS IMPROVEMENT**: Does not acknowledge the user asked about finance/RBI

---

### Q2: "Explain what compound interest means with a simple example"

**Real Engine (with internet):**
- No preflight (no current-need keyword) — model answers from knowledge
- Should: define compound interest in plain language, give a simple numerical example, then explain the formula
- System prompt guidance: *"explain clearly with simple examples first, then add technical details"*

**Real Engine (offline):**
- Same as online — this is stable knowledge, no web search needed
- Should produce equivalent quality answer

**Mock Engine:**
- ⚠️ **CRITICAL GAP**: Returns generic cycling response, not a compound interest explanation
- No keyword matching for finance/educational content in non-code modes

**Fallback:**
- Returns: *"Hi! I'm your on-device AI assistant. The model is currently loading…"*
- ⚠️ **NEEDS IMPROVEMENT**: Does not acknowledge the user asked about finance

---

### Q3: "How is the Indian stock market performing today?"

**Real Engine (with internet):**
- Preflight triggers on **today** → `web_search` called
- Model receives current market data from search results
- Should: summarize Sensex/Nifty levels, daily change, notable movers, cite sources with dates

**Real Engine (offline):**
- Should prefix offline disclaimer
- Should provide general context about Indian stock market structure
- Should caveat: *"As of my last knowledge update…"*

**Mock Engine:**
- ⚠️ **CRITICAL GAP**: Generic cycling response, no market data awareness

**Fallback:**
- ⚠️ **NEEDS IMPROVEMENT**: Generic loading message, no topic acknowledgment

---

### Q4: "What is the difference between mutual funds and fixed deposits?"

**Real Engine (with internet):**
- No preflight needed — stable educational knowledge
- Should: define both, compare with pros/cons table, mention risk/return/liquidity differences
- System prompt guidance: *"Be precise with numbers, rates, and dates"*

**Real Engine (offline):**
- Same quality expected — stable knowledge

**Mock Engine:**
- ⚠️ **CRITICAL GAP**: Generic cycling response

**Fallback:**
- ⚠️ **NEEDS IMPROVEMENT**: Generic loading message

---

### Q5: "What are the latest features in Android 16?"

**Real Engine (with internet):**
- Preflight triggers on **latest** → `web_search` called
- Model receives search results about Android 16 features
- Should: list key features with brief descriptions, cite sources
- `normalizeWebSearchQueries` will append current year to query

**Real Engine (offline):**
- Should prefix offline disclaimer
- Should share what it knows about Android 16 from training data with caveat

**Mock Engine:**
- ⚠️ **CRITICAL GAP**: Generic cycling response, no tech domain awareness

**Fallback:**
- ⚠️ **NEEDS IMPROVEMENT**: Generic loading message

---

### Q6: "Explain the difference between REST and GraphQL APIs"

**Real Engine (with internet):**
- No preflight needed — stable educational knowledge
- Should: explain both in plain language, provide comparison table, give pros/cons
- System prompt guidance: *"Compare options with clear pros/cons tables"*

**Real Engine (offline):**
- Same quality expected

**Mock Engine:**
- ⚠️ **CRITICAL GAP**: Generic cycling response (only `code` mode gets tech-aware responses)

**Fallback:**
- ⚠️ **NEEDS IMPROVEMENT**: Generic loading message

---

### Q7: "What is the current price of the iPhone 16 Pro in India?"

**Real Engine (with internet):**
- Preflight triggers on **current** and **price** → `web_search` called
- Model receives pricing data from search results
- Should: state price range, cite source with URL, note price may vary by retailer

**Real Engine (offline):**
- Should prefix offline disclaimer
- Should share launch price from training data with caveat about current pricing

**Mock Engine:**
- ⚠️ **CRITICAL GAP**: Generic cycling response

**Fallback:**
- ⚠️ **NEEDS IMPROVEMENT**: Generic loading message

---

### Q8: "What is machine learning and how does it work?"

**Real Engine (with internet):**
- No preflight needed — stable educational knowledge
- Should: simple explanation first, then technical depth
- System prompt guidance: *"Explain concepts in plain language before adding technical depth"*

**Real Engine (offline):**
- Same quality expected

**Mock Engine:**
- ⚠️ **CRITICAL GAP**: Generic cycling response

**Fallback:**
- ⚠️ **NEEDS IMPROVEMENT**: Generic loading message

---

### Q9: "Who won the Oscar for Best Picture in 2026?"

**Real Engine (with internet):**
- ⚠️ **PREFLIGHT GAP**: The word **won** is NOT in the `currentNeed` regex pattern in `SendMessageUseCase.webSearchQueryForPrompt()`
- Current pattern: `latest|recent|today|current|now|news|price|weather|schedule|release notes?|version|docs?|api changes?|breaking changes?|stock|score`
- Preflight will NOT trigger automatically
- Model must call `web_search` natively via tool calling — this depends on the model recognizing the need
- If model does call web_search: should provide winner with source
- If model does NOT call web_search: may provide outdated or hallucinated answer

**Real Engine (offline):**
- Should prefix offline disclaimer
- May share known nominees or past winners with caveat

**Mock Engine:**
- ⚠️ **CRITICAL GAP**: Generic cycling response

**Fallback:**
- ⚠️ **NEEDS IMPROVEMENT**: Generic loading message

---

### Q10: "What is the significance of Diwali in Indian culture?"

**Real Engine (with internet):**
- No preflight needed — stable cultural knowledge
- Should: cover significance, history, modern practice comprehensively
- System prompt guidance: *"cover significance, history, and modern practice comprehensively"*

**Real Engine (offline):**
- Same quality expected — this is stable cultural knowledge

**Mock Engine:**
- ⚠️ **CRITICAL GAP**: Generic cycling response

**Fallback:**
- ⚠️ **NEEDS IMPROVEMENT**: Generic loading message

---

### Q11: "What are the top trending movies in India this week?"

**Real Engine (with internet):**
- ⚠️ **PREFLIGHT GAP**: The word **trending** is NOT in the `currentNeed` regex pattern
- Preflight will NOT trigger automatically
- Model must call `web_search` natively — depends on model recognizing the need
- If model calls web_search: should list current trending movies with sources
- If model does NOT call web_search: may provide outdated or hallucinated answer

**Real Engine (offline):**
- Should prefix offline disclaimer
- Should share what it knows about popular Indian cinema with caveat

**Mock Engine:**
- ⚠️ **CRITICAL GAP**: Generic cycling response

**Fallback:**
- ⚠️ **NEEDS IMPROVEMENT**: Generic loading message

---

### Q12: "Explain the history and meaning of yoga in Indian tradition"

**Real Engine (with internet):**
- No preflight needed — stable cultural knowledge
- Should: comprehensive coverage of origins, philosophical traditions, modern practice

**Real Engine (offline):**
- Same quality expected

**Mock Engine:**
- ⚠️ **CRITICAL GAP**: Generic cycling response

**Fallback:**
- ⚠️ **NEEDS IMPROVEMENT**: Generic loading message

---

### Q13: "What are the top news headlines in India today?"

**Real Engine (with internet):**
- Preflight triggers on **news** and **today** → `web_search` called
- Model receives current headlines from search results
- Should: list 3–5 top headlines with brief summaries, cite sources with URLs

**Real Engine (offline):**
- Should prefix offline disclaimer
- Should explain it cannot provide current headlines, offer general context

**Mock Engine:**
- ⚠️ **CRITICAL GAP**: Generic cycling response

**Fallback:**
- ⚠️ **NEEDS IMPROVEMENT**: Generic loading message

---

### Q14: "What is the latest on the India-UK trade agreement?"

**Real Engine (with internet):**
- Preflight triggers on **latest** → `web_search` called
- Model receives search results about trade agreement status
- Should: summarize current status, key sticking points, timeline, cite sources

**Real Engine (offline):**
- Should prefix offline disclaimer
- Should share background context about the agreement with caveat

**Mock Engine:**
- ⚠️ **CRITICAL GAP**: Generic cycling response

**Fallback:**
- ⚠️ **NEEDS IMPROVEMENT**: Generic loading message

---

### Q15: "What is climate change and why does it matter?"

**Real Engine (with internet):**
- No preflight needed — stable educational knowledge
- Should: well-structured answer with headers, bullets, clear explanation
- System prompt guidance: *"Use headers, bullet points, and numbered lists for clarity"*

**Real Engine (offline):**
- Same quality expected

**Mock Engine:**
- ⚠️ **CRITICAL GAP**: Generic cycling response

**Fallback:**
- ⚠️ **NEEDS IMPROVEMENT**: Generic loading message

---

### Gap Summary Across All Questions

| Response Path | Q1 | Q2 | Q3 | Q4 | Q5 | Q6 | Q7 | Q8 | Q9 | Q10 | Q11 | Q12 | Q13 | Q14 | Q15 |
|---------------|----|----|----|----|----|----|----|----|----|----|-----|-----|-----|-----|-----|
| Real + Internet | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ⚠️ | ✅ | ⚠️ | ✅ | ✅ | ✅ | ✅ |
| Real + Offline | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Mock Engine | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| Fallback | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |

**Legend**: ✅ = Expected to work correctly, ⚠️ = Preflight gap (model may still succeed via native tool call), ❌ = Critical gap

---

## 4. Identified Improvements

### 4.1 MockGemmaEngine Needs Domain-Aware Responses

**Severity**: CRITICAL
**Current behavior**: `MockGemmaEngine` cycles through 5 generic responses for non-code modes. Only `code` mode has keyword-based matching (explain, debug, fix, optimize, review).
**Impact**: All 15 test questions produce irrelevant responses in mock mode. This makes mock mode useless for domain testing, UI/UX validation of domain-specific content, and CI/CD domain tests.
**Fix**: Add keyword-based matching for finance, tech, culture, and news questions in `MockGemmaEngine.generateResponse()`. Match on domain keywords (repo rate, stock market, compound interest, mutual funds, Android, iPhone, machine learning, REST, GraphQL, Oscar, Diwali, trending, yoga, headlines, trade agreement, climate change) and produce realistic, domain-specific mock responses.

**Example implementation pattern** (matching existing code style):
```kotlin
// In MockGemmaEngine.generateResponse(), before the else -> fakeResponses branch:
capabilityMode != "code" && userMessage.contains(Regex("\\b(repo rate|RBI|interest rate|inflation)\\b", ignoreCase = true)) -> {
    // Finance mock response about RBI rates
}
capabilityMode != "code" && userMessage.contains(Regex("\\b(stock market|sensex|nifty)\\b", ignoreCase = true)) -> {
    // Finance mock response about stock market
}
// ... etc for each domain
```

---

### 4.2 Fallback Responses Need Topic Acknowledgment

**Severity**: HIGH
**Current behavior**: Fallback responses in `GemmaInferenceEngine` (lines 877–883) return per-mode generic messages like *"The AI model is currently loading. Please try again in a moment."*
**Impact**: User's question topic is completely ignored. No indication the app understood what was asked.
**Fix**: Parse the user message for topic keywords and include them in the fallback response.

**Example**:
```
// Current:
"Hi! I'm your on-device AI assistant. The model is currently loading. Please try sending your message again in a moment."

// Improved:
"Hi! I can see you're asking about [finance/technology/culture/news]. The AI model is currently loading — please try again in a moment and I'll give you a proper answer."
```

---

### 4.3 Missing Preflight Search Keywords

**Severity**: HIGH
**Current behavior**: The `currentNeed` regex in `SendMessageUseCase.webSearchQueryForPrompt()` (line 601) matches:
```
latest|recent|today|current|now|news|price|weather|schedule|release notes?|version|docs?|api changes?|breaking changes?|stock|score
```
**Missing keywords that indicate current-need intent**:
- `trending` — Q11 "top trending movies" would not trigger preflight
- `won` / `winner` — Q9 "who won the Oscar" would not trigger preflight
- `performing` — Q3 "how is the stock market performing" would not trigger preflight (though "today" saves it here)
- `headlines` — Q13 "top news headlines" would not trigger preflight (though "news" and "today" save it)
- `update` / `updates` — Q14 "latest on the India-UK trade agreement" would not trigger preflight (though "latest" saves it)
- `top` — Q11 "top trending" and Q13 "top news headlines" would not trigger preflight

**Fix**: Add these keywords to the `currentNeed` regex pattern:
```kotlin
val currentNeed = Regex(
    "\\b(latest|recent|today|current|now|news|price|weather|schedule|release notes?|version|docs?|api changes?|breaking changes?|stock|score|trending|won|winner|performing|headlines|updates?|top)\\b",
    RegexOption.IGNORE_CASE
)
```

**Note**: `top` is very common in English and may cause false positives. Consider only adding it when combined with other signals (e.g., "top news", "top trending") rather than as a standalone keyword.

---

### 4.4 Offline Protocol Could Be Clearer

**Severity**: MEDIUM
**Current behavior**: The `KNOWLEDGE_AND_TOOL_INSTRUCTION` in `SystemPromptBuilder` says:
> *"When answering offline, start with: 'I'm offline, so I can't search for the latest information.' Then provide what you know, clearly marking it with 'As of my last knowledge update…'"*

**Problem**: The instruction does not specify the model's training cutoff date. The model may say "As of my last knowledge update" without providing a concrete date, leaving the user uncertain about how stale the information might be.

**Fix**: Include the model's training cutoff date in the system prompt. For Gemma 4 E4B IT, the training data has a known cutoff. Add it to the system prompt:
```
Your training data has a knowledge cutoff of [DATE]. When answering offline,
mention this date so users know how current your information is.
```

This should be appended in `SystemPromptBuilder.buildSystemPrompt()` after the `KNOWLEDGE_AND_TOOL_INSTRUCTION`.

---

### 4.5 Response Format Instructions Need Stronger Plain-Language Emphasis

**Severity**: MEDIUM
**Current behavior**: The `RESPONSE_FORMAT_INSTRUCTION` in `SystemPromptBuilder` says:
> *"Format final answers in clean Markdown. Avoid walls of text. Start with the direct answer, then use short sections, bullets, numbered steps, or compact tables when they improve scanning."*

**Problem**: For non-technical users asking finance or culture questions, the model may still use jargon or technical language. The system prompt's domain guidelines say "explain clearly with simple examples first" for finance and "Explain concepts in plain language before adding technical depth" for tech, but there is no general instruction to avoid jargon and use analogies.

**Fix**: Add a plain-language emphasis to the `RESPONSE_FORMAT_INSTRUCTION` or to the chat mode prompt:
```
Always start with a simple, jargon-free explanation that a non-expert can
understand. Use analogies from everyday life when helpful. Only add
technical terms after the plain-language explanation, and define them
when you use them.
```

---

### 4.6 Web Search Result Formatting for General Audiences

**Severity**: LOW
**Current behavior**: The `buildWebGroundedPrompt()` in `SendMessageUseCase` (lines 615–633) instructs the model:
> *"Answer the original request using the web results when relevant. If the results are thin, say what is uncertain, but do not claim you cannot browse. Format the answer in clean Markdown with short sections or bullets. Include a concise Sources section."*

**Problem**: Web search results often contain technical or dense information. The prompt does not explicitly ask the model to simplify complex information from web results for general audiences.

**Fix**: Add a simplification instruction to `buildWebGroundedPrompt()`:
```
Simplify complex information from the web results so a general audience
can understand it. Translate technical jargon into plain language. If
the web results contain conflicting information, note the disagreement
and present the most reliable consensus.
```

---

### Improvement Priority Matrix

| # | Improvement | Severity | Affected Questions | Effort |
|---|-------------|----------|--------------------|--------|
| 4.1 | MockGemmaEngine domain-aware responses | CRITICAL | All 15 | Medium |
| 4.2 | Fallback topic acknowledgment | HIGH | All 15 | Low |
| 4.3 | Missing preflight search keywords | HIGH | Q9, Q11 (directly); Q3, Q13, Q14 (partially) | Low |
| 4.4 | Offline protocol training cutoff | MEDIUM | All offline scenarios | Low |
| 4.5 | Plain-language emphasis | MEDIUM | Q2, Q4, Q6, Q8, Q10, Q12, Q15 | Low |
| 4.6 | Web search simplification | LOW | Q1, Q3, Q5, Q7, Q9, Q11, Q13, Q14 | Low |

---

## 5. Test Execution Instructions

### Prerequisites

- Localyze app installed on device or emulator
- Emulator configured: 16 GB RAM, 32 GB storage, API 34
- For real engine tests: model downloaded (~3.65 GB)

### Test Matrix

| Test Phase | Engine | Web Search | Network | Build Config |
|------------|--------|------------|---------|--------------|
| Phase A | Real | ON | WiFi ON | `USE_MOCK_ENGINE=false` |
| Phase B | Real | OFF | WiFi OFF | `USE_MOCK_ENGINE=false` |
| Phase C | Mock | ON | Any | `USE_MOCK_ENGINE=true` |
| Phase D | Fallback | N/A | Any | Model not loaded |

---

### Phase A: Real Engine + Internet (Web Search Enabled)

1. Set `USE_MOCK_ENGINE=false` in `app/build.gradle.kts`
2. Build and install: `gradlew.bat assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk`
3. Ensure device is connected to WiFi
4. Open Localyze → **Settings** → Enable **Allow web search**
5. For each of the 15 questions:
   a. Create a **new chat** conversation
   b. Type the question **exactly as written** in the table above
   c. Wait for the full response (streaming to complete)
   d. Capture the response: **screenshot** + **copy text**
   e. Record whether preflight web search was triggered (look for "Calling web_search…" tool indicator)
   f. For Q9 and Q11: additionally note whether the model called `web_search` natively via tool calling
   g. Rate the response on applicable criteria (Accuracy, Clarity, Completeness, Source Citation, Formatting)

### Phase B: Real Engine + Offline (Web Search Disabled)

1. Turn **OFF WiFi** on device (Android Settings → WiFi → Off)
2. Open Localyze → **Settings** → Disable **Allow web search**
3. For each of the 15 questions:
   a. Create a **new chat** conversation
   b. Type the question exactly as written
   c. Wait for the full response
   d. Capture the response: screenshot + copy text
   e. Verify the offline prefix is present: *"I'm offline, so I can't search for the latest information."*
   f. Verify the knowledge caveat is present: *"As of my last knowledge update…"*
   g. Rate the response on applicable criteria (Accuracy, Clarity, Completeness, Offline Handling, Formatting)
4. Turn WiFi back ON after testing

### Phase C: Mock Engine

1. Set `USE_MOCK_ENGINE=true` in `app/build.gradle.kts` debug build config
2. Build and install: `gradlew.bat assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk`
3. Look for the **⚡ MOCK MODE** yellow banner in the chat screen
4. For each of the 15 questions:
   a. Create a new chat conversation
   b. Type the question exactly as written
   c. Wait for the full response
   d. Capture the response
   e. Document which of the 5 generic responses was returned
   f. Rate the response — **expect low scores** for all domain questions (this documents the gap)
5. After testing, set `USE_MOCK_ENGINE=false` for production builds

### Phase D: Fallback (Model Not Loaded)

1. Ensure model is **not downloaded** (delete from Settings if needed, or use fresh install)
2. Set `USE_MOCK_ENGINE=false` in build config
3. Build and install
4. For each of the 15 questions:
   a. Attempt to send the question in chat
   b. Capture the fallback response
   c. Document which per-mode fallback message was returned
   d. Rate the response — **expect low scores** for topic acknowledgment (this documents the gap)

---

### Results Recording Template

For each question, record results in this format:

```
### Q#: [Question Text]

**Phase A (Real + Internet):**
- Preflight triggered: YES/NO
- Model called web_search natively: YES/NO/N/A
- Response text: [paste]
- Scores: Accuracy=_/5, Clarity=_/5, Completeness=_/5, Source Citation=_/5, Formatting=_/5
- Average: _/5
- Pass: YES/NO

**Phase B (Real + Offline):**
- Offline prefix present: YES/NO
- Knowledge caveat present: YES/NO
- Response text: [paste]
- Scores: Accuracy=_/5, Clarity=_/5, Completeness=_/5, Offline Handling=_/5, Formatting=_/5
- Average: _/5
- Pass: YES/NO

**Phase C (Mock Engine):**
- Generic response type: [1-5]
- Response text: [paste]
- Scores: Accuracy=_/5, Clarity=_/5, Completeness=_/5, [Source Citation or Offline Handling]=_/5, Formatting=_/5
- Average: _/5
- Pass: YES/NO

**Phase D (Fallback):**
- Fallback message: [paste]
- Topic acknowledged: YES/NO
- Scores: Accuracy=_/5, Clarity=_/5, Completeness=_/5, Offline Handling=_/5, Formatting=_/5
- Average: _/5
- Pass: YES/NO
```

---

### Post-Test Actions

1. **Compile all scores** into a summary table across all 4 phases
2. **Identify failing questions** (average < 3.5/5) and categorize failures by root cause:
   - Preflight keyword gap → Improvement 4.3
   - Mock engine gap → Improvement 4.1
   - Fallback gap → Improvement 4.2
   - Offline protocol gap → Improvement 4.4
   - Clarity/formatting gap → Improvement 4.5 or 4.6
3. **Implement fixes** for identified improvements (switch to Code mode)
4. **Re-test** failed questions after fixes
5. **Document final results** in `CHATBOT_TEST_RESULTS.md`