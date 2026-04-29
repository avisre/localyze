# Code Workspace Chatbot - Comprehensive Testing Matrix

## Test Environment Setup

### Before Testing
1. Open Localyze app
2. Navigate to **Code Workspace** tab
3. Ensure you can see: Editor (left), Preview (center), Assistant Panel (right)
4. Check for: Action chips (Explain/Debug/Fix/Optimize/Review), Scenario chips, Image attach button

### Mode Toggling
- **Offline tests**: Turn OFF WiFi/Mobile Data before tapping scenario chip or typing prompt
- **Online tests**: Turn ON WiFi/Mobile Data before testing
- Mock engine is active (yellow banner should show if visible)

---

## Category 1: TECHNOLOGY (7 Offline + 8 Online = 15)

### Offline Tech Tests (No Internet)
| # | Scenario Chip or Prompt | Action | What to Verify |
|---|------------------------|--------|----------------|
| T1 | Tap **"Kotlin Coroutine"** chip | Explain | Response explains suspend, Flow, retry logic, and Channel.CONFLATED |
| T2 | Tap **"Python SQL"** chip | Debug | Response identifies SQL injection risk on line with f-string |
| T3 | Tap **"JS Optimize"** chip | Optimize | Response shows N+1 problem and suggests eager loading or caching |
| T4 | Tap **"Java Review"** chip | Review | Response flags lazy singleton thread-safety issue |
| T5 | Tap **"SQL Fix"** chip | Fix | Response rewrites implicit join to explicit INNER JOIN |
| T6 | Tap **"CSS Debug"** chip | Debug | Response explains width:100% + margin-left causes overflow |
| T7 | Type: "Explain what this C++ code does: `std::unique_ptr<int> p = std::make_unique<int>(5);`" | Explain | Response explains RAII, memory ownership, why it's safer than raw pointers |

### Online Tech Tests (With Internet)
| # | Prompt to Type or Select | Expected Behavior |
|---|--------------------------|-------------------|
| T8 | "What are the latest Android 15 features developers should know about?" | Response mentions any real 2024-2025 Android API changes |
| T9 | "Compare Python 3.11 vs Python 3.12 performance improvements" | Response lists real differences (e.g., PEP 702, faster comprehensions) |
| T10 | "How do I implement OAuth 2.0 in a Kotlin Android app?" | Response outlines real OAuth flow with PKCE |
| T11 | "What are CSS container queries and how do I use them?" | Response explains @container syntax with real examples |
| T12 | "Explain the latest TypeScript 5.7 features" | Response references real TS 5.7 additions |
| T13 | "How do I integrate WebAssembly into a React app?" | Response explains wasm-pack, useEffect loading pattern |
| T14 | "GraphQL vs REST API: which should I choose for a mobile app?" | Response compares real tradeoffs (over-fetching, caching, complexity) |
| T15 | "Show me how to use Jetpack Compose LazyColumn with sticky headers" | Response provides real Compose code with stickyHeader() |

---

## Category 2: FINANCE (4 Offline + 4 Online = 8)

### Offline Finance Tests
| # | Prompt | Action | Verification |
|---|--------|--------|--------------|
| F1 | "Calculate compound interest for $10,000 at 7% over 10 years" | Explain | Response shows formula A = P(1 + r/n)^(nt) and final amount ~$19,671 |
| F2 | "Debug this loan calculator: `payment = principal * rate / (1 - (1 + rate)^-months)`" | Debug | Response spots potential divide-by-zero or negative exponent issues |
| F3 | "Optimize this budget tracking loop: for each transaction, query the database for the category" | Optimize | Response identifies N+1 query and suggests caching categories |
| F4 | "Review this Python stock price fetcher for errors: uses requests.get without timeout" | Review | Response flags missing timeout, no error handling, no rate limiting |

### Online Finance Tests
| # | Prompt | Expected |
|---|--------|----------|
| F5 | "What is the current inflation rate in the US and how does it affect savings?" | Response references real recent inflation data |
| F6 | "Explain how compound interest works with a real-world example" | Response uses realistic numbers and mentions Rule of 72 |
| F7 | "Compare S&P 500 vs Nasdaq performance this year" | Response references real 2025/2026 market trends |
| F8 | "How do I calculate currency conversion with exchange rates in Python?" | Response shows real API pattern (e.g., exchangerate-api or similar) |

---

## Category 3: CULTURE (4 Offline + 3 Online = 7)

### Offline Culture Tests
| # | Prompt | Action | Verification |
|---|--------|--------|--------------|
| C1 | "How do I localize a React app for Japanese users?" | Explain | Response mentions i18next, date-fns-jp, text expansion considerations |
| C2 | "Debug this CSS: Arabic text overlaps when mixed with English" | Debug | Response suggests dir="rtl", unicode-bidi, proper font stacking |
| C3 | "Fix this date formatting code: `new Date().toLocaleString()` for users in Japan and Brazil" | Fix | Response shows locale-specific formatting with proper options |
| C4 | "Review this emoji handling code: `text.length` returns wrong count for emoji" | Review | Response explains surrogate pairs, suggests Array.from or grapheme-splitter |

### Online Culture Tests
| # | Prompt | Expected |
|---|--------|----------|
| C5 | "What are the best practices for RTL language support in Android apps?" | Response mentions real Android RTL APIs (layoutDirection, start/end) |
| C6 | "How do different cultures format dates and what should my app support?" | Response covers ISO 8601, US (MM/DD), EU (DD/MM), JP (YYYY/MM) |
| C7 | "Explain Unicode normalization and why it matters for user input" | Response references NFC/NFD, Korean Hangul, combining characters |

---

## Category 4: GENERAL / OTHER NEWS (4 Offline + 4 Online = 8)

### Offline General Tests
| # | Prompt | Action | Verification |
|---|--------|--------|--------------|
| G1 | "Explain how a Docker Compose file works" | Explain | Response explains services, networks, volumes, dependencies |
| G2 | "Debug this regex: `^[a-z]+$` failing on 'HelloWorld'`" | Debug | Response points out case sensitivity, suggests `i` flag or `[a-zA-Z]` |
| G3 | "Fix this bash script: `rm -rf $DIR/*` when DIR might be empty" | Fix | Response shows `${DIR:?}` or proper guard checks |
| G4 | "Review this Git pre-commit hook for security issues" | Review | Response flags eval usage, missing quoting, arbitrary code execution |

### Online General Tests
| # | Prompt | Expected |
|---|--------|----------|
| G5 | "What are the latest cybersecurity threats developers should know in 2025?" | Response references real recent CVEs or attack patterns |
| G6 | "How do I build a Python script that fetches today's weather for any city?" | Response uses a real weather API (OpenWeatherMap, etc.) |
| G7 | "Explain how LLMs work to a non-technical person" | Response avoids jargon, uses analogies, stays accurate |
| G8 | "What is the current state of AI regulation and how does it affect app developers?" | Response references real regulations (EU AI Act, etc.) |

---

## Scoring Rubric

For each response, rate the following on a scale of 1-10:

### 1. Accuracy (10 points)
- **10**: Factually correct, no hallucinations, cites real APIs/patterns
- **7-9**: Mostly correct, minor inaccuracies
- **4-6**: Partially correct, some misleading info
- **1-3**: Significantly wrong or fabricated

### 2. Clarity (10 points)
- **10**: A beginner could understand; well-structured with headers, bullet points, code blocks
- **7-9**: Mostly clear, some dense sections
- **4-6**: Confusing structure, jargon undefined
- **1-3**: Incoherent or overly verbose

### 3. Actionability (10 points)
- **10**: User can immediately copy/paste and use the code/fix
- **7-9**: Mostly actionable, needs minor tweaks
- **4-6**: Vague suggestions, no concrete code
- **1-3**: No practical value

### 4. Completeness (10 points)
- **10**: Covers edge cases, error handling, alternatives
- **7-9**: Main path covered, missing some edge cases
- **4-6**: Superficial, only surface-level
- **1-3**: Incomplete or truncated

### 5. Language Support (10 points)
- **10**: Correctly identifies language, proper syntax highlighting hint
- **7-9**: Mostly correct language detection
- **4-6**: Wrong language guessed
- **1-3**: No language awareness shown

---

## Total Score Calculation

- **Maximum per test**: 50 points
- **Offline total (15 tests)**: 750 points max
- **Online total (15 tests)**: 750 points max
- **Grand total**: 1500 points max

### Grade Scale
- **1400-1500 (A+)**: Exceptional. Ready to ship.
- **1200-1399 (A)**: Very good. Minor polish needed.
- **1000-1199 (B)**: Good. Some prompt/mock engineering needed.
- **800-999 (C)**: Acceptable. Significant improvements needed.
- **Below 800 (D/F)**: Major rework required.

---

## Instructions for Tester

1. Test **all offline scenarios first** (airplane mode on)
2. For each test, tap the scenario chip OR type the exact prompt shown
3. Wait for streaming to complete
4. Read the response carefully
5. Score using the rubric above
6. Note any issues: wrong answers, confusing explanations, bad formatting
7. After all offline tests, turn on internet and run online tests
8. Report scores back to me for iteration

## What I Will Do With Results

- **If a mock response is wrong**: I will rewrite the hardcoded response in `MockGemmaEngine.kt`
- **If a prompt is unclear**: I will rewrite the prompt builder in `CodeWorkspaceViewModel.kt`
- **If formatting is bad**: I will update the `ResponseMessageBubble` composable or markdown rendering
- **If the response is too generic**: I will add more specific scenario branches to the mock engine

**Goal**: Every response should score at least 9/10 on Accuracy and Clarity, and 8/10 on everything else.
