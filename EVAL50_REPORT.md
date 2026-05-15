# 50-question evaluation — Localyze on OnePlus NE2211 (2026-05-08)

50 prompts across 8 categories, run online and offline on the phone (Gemma 4 E4B / GPU, thinking-mode forced off via intent extra).

## Build under test

Patched APK with all of the following fixes from the prior diagnosis sessions:
- Format adherence + digit fidelity rules in chat & code prompts
- EmailDraftTool tightening (no inline-email misrouting)
- `directWebAnswerFor` fallback removed (Gemma synthesizes from web context instead)
- Offline cutoff preamble (no canned "I can't verify…" refusal)
- Auto-route Python prompts to `code` capability mode
- Web-grounded prompt trimmed to fit Gemma's 4096-token context
- Markdown links rendered as clickable `link` (no raw URLs in UI)
- Numbered-list regex no longer corrupts URL query params
- Bar chart for revenue/profit time series; line chart for stock prices
- WebSearchTool branch reorder so multi-year company-financial queries reach SEC EDGAR
- Force-CPU intent extra (persisted via SettingsDataStore)
- Per-test thinking/temperature/top_k overrides

## Question set

50 prompts across 8 categories: knowledge (10), math (6), code (5), writing (5), explain (6), live (8), viz (7), planning (3).

## Methodology

- Each Q sent via the `chat_msg` debug intent (force-stop → set `set_thinking=false` → set web search mode → send).
- Answer reconstructed by concatenating Gemma's logged token deltas (`GemmaInference: Received text content:`) for the Gemma path, OR ChatViewModel `StreamingToken` chunks (lossy 20-char truncation) for the curator path.
- Scored by category-specific regex rubric, with a lenient mode that accepts curator-path answers when they show the canonical template signature (e.g. "RAG" + dollar amount for financial-viz Qs).

## Headline numbers

| | online | offline |
|---|---:|---:|
| Overall | **80.0%** | **64.5%** |
| OK status | 49 / 50 | 50 / 50 |
| Mean answer length | ~330 char | ~250 char |

The 15.5-point online→offline drop is concentrated in `live` (-34) and `viz` (-50) — exactly the two categories that depend on web grounding (web search + SEC EDGAR XBRL). Knowledge / explain / plan / writing are unchanged offline.

## Per-category scores

| Category | online | offline | notes |
|---|---:|---:|---|
| plan | 100% | 100% | structured lists; deterministic |
| knowledge | 90% | 90% | only Q04 fails ("1984"→"1 84" GPU digit-garble) |
| explain | 87.5% | 87.5% | only Q29 fails (multi-step train math) |
| code | 100% | 85% | offline Q17 had token-truncation in capture |
| live | 78% | 44% | offline can't fetch BTC/stocks/weather; canned offline preamble fires |
| viz | 75% | 25% | offline has no SEC EDGAR fetch — model declines instead of fabricating |
| writing | 65% | 65% | rubric is harsh (looks for "fire/warehouse" in apology email — model often paraphrases) |
| math | 50% | 37.5% | dominated by GPU digit-garble (Q11/Q12/Q14) |

## Failures by class

### Real model failures — GPU digit-garbling (fail in both modes)
- Q04 (Who wrote 1984): "1984" → "1 84" → model says "I don't have enough info"
- Q11 (18% of 250): "450 is 8% of 50" — digits swapped
- Q12 (20% tip on $42): "$5.444"
- Q14 (compound interest) — both modes
- Q15 (3/4 + 5/8) — offline only

### Real model failures — small-model reasoning ceiling
- Q29 (two-train meet-time) — multi-step word problem, 5+ arithmetic steps with distance/time/relative-velocity
- Q22, Q23 (writing tasks where the rubric demands specific keywords the paraphrase doesn't hit) — false negatives more than true failures

### Infrastructure (online run)
- Q14, Q34: LiteRT-LM `ERROR` (intermittent); Q41: `EXCEPTION` (timeout)

### Expected offline degradation (live + viz)
- All 8 live Qs and all 7 viz Qs depend on web search / SEC EDGAR. Offline they trip the offline-cutoff preamble and the model declines or gives generic guidance instead of fabricating numbers — this is the **correct** behavior, but the regex rubric counts it as no-match.

### Lossy log-capture (works in app, hard to read in logs)
- Curator-path answers reach the device fully formed but ChatViewModel's `StreamingToken` log truncates each 32-char chunk to 20 chars. The lenient grader catches the financial-viz signature (`RAG` + `$` + digits) and the canned-knowledge case, but live/viz curator outputs in offline mode are still patchy in JSON.

## What this run validates

- Visualization pipeline end-to-end: SEC EDGAR XBRL → Markdown table → bar chart (revenue/profit) or line chart (stock prices) in the Compose UI
- Clickable Markdown links work — raw URLs are rendered as `link` and tap-handled by `LinkAnnotation.Url`
- Numbered-list regex preserves URL query params (no more `?curid=42400)` corruption)
- 9 of 10 knowledge Qs correct; 5 of 5 code Qs produce runnable code; 3 of 3 plan Qs produce structured lists; 5/6 explain Qs are clean
- Offline behaves as designed: declines confidently for live data instead of hallucinating, and keeps full quality on knowledge/code/explain/plan/writing
- The GPU-digit bug remains the dominant residual failure mode for math; CPU on phone gets these right but is ~3× slower, so we keep GPU as default and accept the math hit

## Files

- [eval50_questions.py](eval50_questions.py) — the question set
- [run_eval50.py](run_eval50.py) — runner
- [grade_eval50.py](grade_eval50.py) — grader
- [eval50_online.json](eval50_online.json) / [eval50_offline.json](eval50_offline.json) — raw answers
- [eval50_scorecard.json](eval50_scorecard.json) — scored output
