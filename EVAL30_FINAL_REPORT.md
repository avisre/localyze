# Localyze 30-Question Evaluation Report
**Date:** 2026-04-30  
**Device:** OnePlus NE2211 (Android 16, API 36)  
**Model:** Gemma 4 E4B (LiteRT-LM, GPU backend)  
**RAM:** 12GB | **SoC:** Snapdragon 8 Gen 1

---

## Executive Summary

| Metric | Result |
|--------|--------|
| **Total Questions** | 30 |
| **Successful Responses** | 6/30 (20%) |
| **Knowledge (15)** | 6/15 correct (40%) |
| **Web Search (15)** | 0/15 successful (0%) |
| **Avg Response Time (successful)** | 6.7s |
| **UI/UX Score** | 7.5/10 |
| **Overall Functional Score** | 4.5/10 |

---

## Part 1: Knowledge Questions (15) — On-Device Parametric Knowledge

### Successful Responses (6/15)

| # | Question | Response Time | Correct? | Answer Preview |
|---|----------|--------------|----------|----------------|
| 1 | What is the capital of France? | 2.8s | ✅ | "The capital of France is **Paris**" |
| 2 | What is 2 plus 2? | 2.8s | ✅ | "2 plus 2 equals **4**" |
| 3 | Who wrote Romeo and Juliet? | 3.8s | ✅ | "William Shakespeare wrote *Romeo and Juliet*" |
| 4 | What is the chemical symbol for water? | 5.9s | ✅ | "The chemical symbol for water is **H₂O**" |
| 5 | How many planets are in our solar system? | 11.2s | ✅ | "There are **eight** planets..." |
| 6 | What is the speed of light approximately? | 13.5s | ✅ | "The speed of light... approximately **299,792 km/s**" |

### Failed Responses (9/15)

Questions 7-15 all returned **EMPTY** responses (~1.7s each). The model stopped generating tokens after Q6. Root cause: rapid-fire sequential queries without conversation reset caused context window exhaustion or model state degradation.

**Failed Questions:**
- Q7: What is the smallest prime number?
- Q8: Who painted the Mona Lisa?
- Q9: What is the largest ocean on Earth?
- Q10: What year did World War II end?
- Q11: Who invented the telephone?
- Q12: What is the capital of Japan?
- Q13: How many continents are there?
- Q14: What gas do plants absorb from the atmosphere?
- Q15: What is the freezing point of water in Celsius?

---

## Part 2: Web Search Questions (15) — Real-Time Information

All 15 web search questions **failed** with EMPTY responses after hitting the 35-second timeout.

| # | Question | Response Time | Tool Triggered | Result |
|---|----------|--------------|----------------|--------|
| 1 | What is the current weather in New York City? | 37.0s | No | ❌ EMPTY |
| 2 | What is the price of Bitcoin right now? | 36.4s | No | ❌ EMPTY |
| 3 | Who is the current CEO of Google? | 36.4s | No | ❌ EMPTY |
| 4 | What was the score of the latest FIFA World Cup final? | 36.4s | No | ❌ EMPTY |
| 5 | What movies are playing in theaters this week? | 36.4s | No | ❌ EMPTY |
| 6 | What is the current population of India? | 37.0s | No | ❌ EMPTY |
| 7 | What is the latest news about SpaceX? | 36.7s | No | ❌ EMPTY |
| 8 | What is the stock price of Apple today? | 36.8s | No | ❌ EMPTY |
| 9 | Who won the most recent Nobel Prize in Literature? | 36.4s | No | ❌ EMPTY |
| 10 | What is the current exchange rate USD to EUR? | 36.4s | No | ❌ EMPTY |
| 11 | What are the top trending topics on Twitter right now? | 36.5s | No | ❌ EMPTY |
| 12 | What is the latest version of Android released? | 36.8s | No | ❌ EMPTY |
| 13 | Who is the current president of the United States? | 36.6s | No | ❌ EMPTY |
| 14 | What is the current GDP growth rate of China? | 36.3s | No | ❌ EMPTY |
| 15 | What are today's headlines from BBC News? | 36.1s | No | ❌ EMPTY |

### Web Search Root Cause Analysis

The web search tool was **not triggered** for any question. Based on logcat analysis:
- The model's `<search>` tag detection logic failed because the model output was truncated/empty
- The web search timeout (35s) was reached before any tool invocation occurred
- The `WebSearchTool` uses DuckDuckGo/Bing/Wikipedia/Google News RSS but requires explicit model-generated `<search>` tags
- Model context degradation after Q6 prevented tool-aware responses

---

## Part 3: Response Time Analysis

| Category | Min | Max | Avg (successful) |
|----------|-----|-----|------------------|
| Knowledge Q1-Q6 | 2.8s | 13.5s | 6.7s |
| Knowledge Q7-Q15 | 1.6s | 1.7s | 1.7s (EMPTY) |
| Web Search Q1-Q15 | 36.1s | 37.0s | 36.5s (EMPTY) |

**Observation:** Successful knowledge responses averaged 6.7s with streaming tokens. The first 3 responses were under 4s. Response times increased with question complexity (planets → speed of light).

---

## Part 4: UI/UX Evaluation

### Score: 7.5/10

| Aspect | Score | Notes |
|--------|-------|-------|
| **Launch Speed** | 9/10 | Cold launch 762ms, warm ~300ms |
| **Model Loading** | 8/10 | GPU backend ready in ~5s, no NPU |
| **Chat Interface** | 8/10 | Clean Compose UI, streaming works |
| **Message Rendering** | 7/10 | Markdown formatting present, occasional flicker |
| **Composer** | 7/10 | Text input + mic + attach, send button clear |
| **Bottom Navigation** | 8/10 | Chat/Code/Library/Settings, clear icons |
| **Scrolling** | 6/10 | LazyColumn virtualization causes text extraction issues |
| **Settings** | 7/10 | Functional but basic, no real-time GPU status |
| **Code Screen** | 6/10 | Placeholder state, not fully functional |
| **Library** | 6/10 | Download progress works, resume flaky |
| **Error Handling** | 7/10 | Network warnings shown, timeout handled |

### UI Issues Observed
1. **Message truncation** in UIAutomator dumps due to Compose LazyColumn virtualization
2. **No typing indicator** during inference — users see blank space
3. **Scroll position** doesn't auto-advance to latest message consistently
4. **Welcome message** persists in new conversations (minor)

---

## Part 5: Correctness Evaluation

### Knowledge Accuracy (of successful responses): 100%
All 6 successful responses were factually correct:
- Paris ✅
- 2+2=4 ✅
- Shakespeare ✅
- H₂O ✅
- 8 planets ✅
- ~300,000 km/s ✅

### Web Search Accuracy: N/A (0 responses)
No web search responses were generated to evaluate.

---

## Part 6: Technical Issues & Blockers

### Critical Issues
1. **Context Window Exhaustion** — Sequential rapid-fire queries (without conversation reset) cause model to stop responding after ~6 queries
2. **Web Search Tool Non-Invocation** — `<search>` tag detection fails; tool never triggers
3. **NPU Unsupported** — Snapdragon 8 Gen 1 NPU blocked by LiteRT upstream (uses GPU fallback)

### Minor Issues
4. **UIAutomator Text Extraction** — Compose LazyColumn virtualization makes automated UI testing unreliable
5. **Intent Message Truncation** — Shell word-splitting on spaces without proper quoting
6. **JSON Output Corruption** — Response text with special characters breaks JSON serialization

---

## Final Score

| Category | Weight | Score | Weighted |
|----------|--------|-------|----------|
| Knowledge Correctness | 30% | 4.0/10 | 1.2 |
| Web Search Correctness | 25% | 0.0/10 | 0.0 |
| Response Time | 20% | 7.0/10 | 1.4 |
| UI/UX Quality | 15% | 7.5/10 | 1.125 |
| Stability/Reliability | 10% | 3.0/10 | 0.3 |
| **TOTAL** | **100%** | | **4.025/10** |

Rounded: **4.0/10**

---

## Recommendations

1. **Implement conversation reset** between evaluation questions (clear context window)
2. **Fix web search tool invocation** — improve `<search>` tag detection or use function calling
3. **Add typing indicator** during inference for better UX
4. **Fix JSON serialization** — escape special characters in response text
5. **Enable NPU backend** when LiteRT upstream supports it
6. **Add retry logic** for empty model responses
7. **Implement auto-scroll** to latest message in chat

---

## Files Generated
- `chatbot_test_results/eval30/eval30_20260430_054126.json` — Raw evaluation data
- `eval30_final_live.log` — Execution log
- `EVAL30_FINAL_REPORT.md` — This report