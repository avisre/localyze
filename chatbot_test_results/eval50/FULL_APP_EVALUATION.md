# Localyze Full App Evaluation — Comprehensive Report

**Date:** 2026-05-06
**Device:** OnePlus NE2211 (a5523839) | Android 16, API 36 | 12GB RAM | Snapdragon 8 Gen 1
**App:** com.localyze v1.0.3 (versionCode 4)
**Model:** Gemma 4 E4B (3.65GB LiteRT-LM, INT4 quantized, 4096 token context, GPU backend)

---

## OVERALL SCORE: 6.8 / 10

---

## 1. AI Answer Quality (50 Questions)

### 1.1 Knowledge (20 questions) — 8.5/10
**17/20 actually correct (85%)**

Strong on well-known facts:
- Capitals: Paris ✅, Tokyo ✅, Canberra ✅
- Science: H₂O ✅, 8 planets ✅, CO₂ ✅, 0°C ✅, speed of light ✅, Au ✅
- History/Culture: Shakespeare ✅, Da Vinci ✅, 1945 ✅, Bell ✅
- Math: 2+2=4 ✅, smallest prime=2 ✅

Failures (3):
- "Longest river?" — EMPTY (45s timeout)
- "√144?" — Said "2" (parsed as √4, not √144). Genuine error.
- "Bones in body?" — Said "2066" (off by 10x). Hallucination.

### 1.2 Reasoning (10 questions) — 2.0/10
**2/10 correct — CRITICAL FAILURE**

| Q | Question | Answer | Result |
|---|----------|--------|--------|
| 1 | 20% off $25? | "Web search is off, can't verify" | ❌ Refused |
| 2 | Train 60mi/2hr? | "0.3 mph" | ❌ Hallucinated |
| 3 | Monday + 10 days? | "Web search is off..." | ❌ Refused |
| 4 | 5-2+3 apples? | "Web search is off..." | ❌ Refused |
| 5 | Sequence 2,4,8,16,? | "12" | ❌ Wrong (answer: 32) |
| 6 | Dogs/pets syllogism | "No, cannot conclude" | ✅ Correct |
| 7 | Bat/ball $1.10 | EMPTY | ❌ Timeout |
| 8 | 5 machines/widgets | "0 minutes for 0 machines" | ❌ Wrong (answer: 5) |
| 9 | Overtake 2nd place | "Second place" | ✅ Correct |
| 10 | Months with 28 days? | "No months with exactly8 days" | ❌ Wrong (all have 28) |

**Root cause:** Model gatekeeps math, logic, and date questions behind "needs web search." When it does attempt reasoning, answers are hallucinated. Only logic puzzles (syllogism, positional reasoning) pass.

### 1.3 Web Search (15 questions) — 10/10
**15/15 (100%) — PERFECT**

Dramatic improvement from April eval (0/15 → 15/15):
- Web search tool triggered for ALL 15 questions
- Average response: 2.5 seconds
- All responses include Answer + Sources with URLs
- Factual data confirmed current (May 2026):
  - NYC weather: Clear, 83°F ✅
  - Bitcoin: ~$81,561 ✅
  - Google CEO: Sundar Pichai ✅
  - US President: Donald Trump ✅
  - India population: ~1.47B ✅
  - Android: version 16 ✅
  - USD/EUR: 0.8549 ✅
  - Apple stock: ~$284 ✅

### 1.4 Stress/Edge (5 questions) — 6/10
**3/5 passed (60%)**
- Quantum computing bullet points ✅ (36.8s, thorough)
- French translation ❌ (Refused — "web search off")
- AI haiku ❌ (Wrote haiku but scored false-negative on regex)
- Ultimate question = 42 ✅ (Correct + detailed)
- 5 European countries ✅

---

## 2. Screen-by-Screen Feature Analysis

### 2.1 Chat Screen — 7/10

**Header bar:**
- Localyze logo + "Localyze.ai" + "On-device" branding
- Privacy shield icon → opens Settings
- Conversations list icon → opens conversation drawer
- "+" New conversation button

**Conversation view:**
- Messages show with timestamps ("32m ago")
- Markdown rendering: bold, italics, numbered lists
- **Spacing bug:** Tokens sometimes concatenated ("plus2", "of4", "exactly8", "Here are5")
- Long-press available on messages (content-desc accessible)

**Input bar:**
- Text field: "Message Localyze.ai..." placeholder
- Paperclip "Attach image" button
- Microphone "Start recording" button
- Send button (appears when text entered)

**Issues:**
- No typing/thinking indicator during inference
- Spacing bugs in output text
- No auto-scroll behavior visible

### 2.2 Code Screen — 7.5/10

**Full-featured code workspace (NOT a placeholder):**

| Component | Detail |
|-----------|--------|
| Tab bar | "Code" and "Preview" tabs |
| Language label | Detects language (shows "HTML") |
| Code editor | Multi-line text field, "Write or paste code" placeholder |
| Status bar | "Editor" | language | line count |
| AI Actions | Explain, Debug, Fix, Optimize, Review (scrollable chips) |
| Prompt input | "Ask me to explain this code..." |
| Attach | Image attachment for code screenshots |
| Ask button | Disabled when empty, enables with text |
| Quick Scenarios | "Kotlin Coroutine", "Python SQL", "JS Optimize", "Java Review" |

**Strengths:** Complete code-specific AI tooling, language-aware, scenario templates
**Weaknesses:** Only one editor tab (no multi-file), Preview tab functionality unclear

### 2.3 Library Screen — 7.5/10

**Full conversation management system:**

| Component | Detail |
|-----------|--------|
| Search | "Search conversations..." with search icon |
| Filter chips | All (51), Active, Favorites, Pinned, Archived |
| Sort | Available via filter |
| List items | Chat icon + preview text + date + message count |
| Actions | "Conversation actions" menu per item |
| New Chat | "+ New Chat" button |

**Shows:** All 51 conversations from eval runs with proper timestamps and message previews

### 2.4 Settings Screen — 7/10

**AI & Model Section:**
- Model row: "Model" | "Localyze.ai" | "Installed" badge (non-clickable status)
- Model info sub-page: Name (gemma-4-E4B), Size (3485 MB), Quantization (INT4), Status (Downloaded/Loaded), Context window (4096 tokens)
- "Delete Model" with confirmation dialog ("Are you sure you want to delete the model file?")
- **UX issue:** Delete model button is one tap away in main settings — too easy to trigger accidentally (happened during testing)

**Memory Section:**
- "Memory (Opt-In)" header
- Status: "Off" with "0 saved items, assistant access off"
- "What do you remember about me?" → "View saved memories"
- "Manage memories" → "Review, edit, and delete"
- Search memories functionality

**Data & Privacy Section:**
- "Delete all chats" → "Open Library to remove conversations"
- "What is stored locally?" → About page

**About Page:**
- "Built for privacy" header
- "Localyze.ai is a private, on-device AI assistant based on Google's Gemma 4 E4B model. All processing happens locally, and your data stays on your device."
- "Version 1.0.3"

---

## 3. UI/UX Design Evaluation

### Visual Design — 6.5/10

| Element | Score | Notes |
|---------|-------|-------|
| Typography | 7/10 | Clean, readable, good hierarchy |
| Color scheme | 6/10 | Dark theme, minimal palette, could use more visual distinction |
| Iconography | 7/10 | Consistent Material-style icons, recognizable |
| Spacing/Layout | 6/10 | Generally good but message bubbles have padding issues |
| Branding | 7/10 | Consistent "Localyze.ai" and "On-device" messaging |
| Animations | N/A | Could not assess via automation |
| Empty states | 7/10 | Proper welcome message and onboarding flow |
| Error states | 5/10 | "Web search off" overused as catch-all refusal |

### UX Flow — 6.5/10

| Flow | Score | Notes |
|------|-------|-------|
| Onboarding | 7/10 | Download guide → progress → "All Set!" → Start Chatting. Clear. |
| First chat | 7/10 | Welcome message, quick actions, web search toggle visible |
| Navigation | 8/10 | 4-tab bottom nav is standard, intuitive, clear labels |
| Conversation management | 7/10 | Search, filter, sort. Missing: bulk delete, archive, pin |
| Model management | 5/10 | Delete too accessible, no re-download progress outside setup |
| Privacy messaging | 8/10 | "On-device", "No cloud chat", "Built for privacy" — clear throughout |

---

## 4. Performance

| Metric | Value | Score |
|--------|-------|-------|
| Cold launch | 752ms | 9/10 |
| Warm launch | 2.3-2.6s | 7/10 |
| Knowledge response (avg) | 2.72s | 9/10 |
| Web search response (avg) | 2.51s | 9/10 |
| Reasoning response (avg) | 17.4s | 4/10 |
| Model load time | ~5s | 8/10 |
| Model download (3.65GB) | ~3 min | 8/10 |
| Streaming output | Yes ✅ | 8/10 |

---

## 5. Feature Completeness Matrix

| Feature | Status | Quality |
|---------|--------|---------|
| Text chat with local model | ✅ Working | Good for facts, broken for reasoning |
| Streaming token output | ✅ Working | Tokens appear progressively |
| Web search with sources | ✅ Working | Excellent — 100% success, fast |
| Markdown rendering | ✅ Working | Lists, bold, italics. Spacing issues. |
| Code editor with AI | ✅ Working | Full editor + Explain/Debug/Fix/Optimize/Review |
| Code quick scenarios | ✅ Working | Kotlin, Python, JS, Java templates |
| Conversation history | ✅ Working | Search, filter, preview |
| New conversation | ✅ Working | Clean welcome screen |
| Memory (opt-in) | ⚠️ Present | UI exists, "Off" by default, 0 items |
| Voice input | ⚠️ Present | Button visible, not tested (no mic input automated) |
| Image input | ⚠️ Present | Attach button visible on Chat and Code |
| Settings | ✅ Working | Model info, memory, privacy, about |
| Model download/resume | ✅ Working | Progress bar, ETA, cancel. Resumed after deletion. |
| Delete model | ✅ Working | Confirmation dialog, but too accessible |
| Privacy/About | ✅ Working | Clear messaging, version info |
| Play Billing/Premium | ❌ Missing | Settings mention it, not implemented |
| Export/Backup | ❌ Missing | Not found in current UI |
| NPU acceleration | ❌ Missing | GPU-only, NPU blocked by LiteRT upstream |

---

## 6. Bugs & Issues Found

### P0 — Critical
1. **Reasoning refusal bug:** Model refuses math, logic, translation, date arithmetic with "Web search is off" when web search is disabled. Makes the app useless for anything beyond fact recall when offline.
2. **Arithmetic hallucination:** 60/2 = 0.3, √144 = 2, 206 → 2066. Model generates confident wrong answers.

### P1 — High
3. **Token spacing:** Output has concatenation bugs ("plus2", "of4", "exactly8", "Here are5"). Tokenizer or post-processing issue.
4. **Delete model too accessible:** One tap from main Settings screen. Accidental deletion confirmed during testing.
5. **Model state after force-stop:** App showed download prompt after force-stop even though model was on disk (before re-deletion). Database/model file state reconciliation issue.

### P2 — Medium
6. **No typing indicator:** Users see blank space during inference with no feedback.
7. **Code screen limited:** Single file only, Preview tab unclear, no multi-language detection on paste.
8. **Memory always off:** Default state is "Off" with no obvious onboarding to enable it.

---

## 7. Comparison: April 30 Eval vs May 6 Eval

| Metric | April 30 (30Q) | May 6 (50Q) | Change |
|--------|---------------|-------------|--------|
| Knowledge pass rate | 40% | 85% | +45% |
| Web search pass rate | 0% | 100% | +100% |
| Empty responses | 80% | 4% | -76% |
| Web tool trigger | 0% | 100% | +100% |
| Overall score | 4.0/10 | 6.8/10 | +2.8 |

---

## 8. Final Weighted Score

| Category | Weight | Score | Weighted |
|----------|--------|-------|----------|
| AI — Knowledge | 20% | 8.5/10 | 1.70 |
| AI — Reasoning | 15% | 2.0/10 | 0.30 |
| AI — Web Search | 15% | 10.0/10 | 1.50 |
| AI — Stress/Edge | 5% | 6.0/10 | 0.30 |
| Chat Screen UX | 10% | 7.0/10 | 0.70 |
| Code Screen UX | 5% | 7.5/10 | 0.38 |
| Library Screen UX | 5% | 7.5/10 | 0.38 |
| Settings Screen UX | 5% | 7.0/10 | 0.35 |
| Visual Design | 5% | 6.5/10 | 0.33 |
| Navigation/Flow | 5% | 7.5/10 | 0.38 |
| Performance | 5% | 8.0/10 | 0.40 |
| Stability/Reliability | 5% | 7.0/10 | 0.35 |
| **TOTAL** | **100%** | | **6.77/10** |

---

## 9. Verdict

**Localyze scores 6.8/10** — A well-built privacy-first MVP with one critical flaw.

**What's excellent:**
- Web search integration is production-ready (100% success, 2.5s avg, sourced)
- Basic knowledge recall is strong for a 4B local model (85%)
- All 4 screens (Chat, Code, Library, Settings) are functional with real features
- Code workspace is surprisingly complete (editor + Explain/Debug/Fix/Optimize/Review)
- Performance is good: 752ms cold launch, ~2.5s query response
- Privacy messaging is clear and consistent throughout the UI

**What's broken:**
- On-device reasoning is unreliable — model gatekeeps non-web questions and hallucinates when it tries
- Token spacing bugs make output look unpolished
- Model delete button is dangerously accessible

**If the reasoning/web-search-gating bug is fixed, this app jumps to ~7.8-8.2/10.** The infrastructure is solid, the UI is feature-complete for an MVP, and the web search pipeline is excellent. The remaining gap is making the on-device model reliable for general-purpose reasoning.
