# Localyze Chatbot Test Plan - Finance, Tech, Culture & News

## Overview
This document defines 15 test questions to evaluate the Localyze AI chatbot across different news/knowledge categories. Each question is tested both **WITH internet** (web search enabled) and **WITHOUT internet** (offline/on-device only). Answers are evaluated for quality, clarity, and accuracy.

## Evaluation Criteria
| Criterion | Score 1-5 | Description |
|-----------|-----------|-------------|
| **Accuracy** | 1-5 | Is the information factually correct? |
| **Clarity** | 1-5 | Is the answer easy to understand? |
| **Completeness** | 1-5 | Does it cover the key aspects of the question? |
| **Formatting** | 1-5 | Is the Markdown formatting clean and readable? |
| **Source Citation** | 1-5 | (Online only) Are sources/URLs provided and accurate? |
| **Offline Grace** | 1-5 | (Offline only) Does it acknowledge limitations gracefully? |

**Pass threshold**: Average score ≥ 3.5/5 across all criteria

---

## Test Questions

### FINANCE (Questions 1-4)

#### Q1: What is the current Federal Funds rate set by the US Federal Reserve?
- **Category**: Finance / Current Data
- **Needs Internet**: YES (current rates change frequently)
- **Offline expectation**: Should state last known rate and note it may be outdated
- **Type**: Factual / Price-like data

#### Q2: Explain what a yield curve inversion means and why it matters for the economy
- **Category**: Finance / Educational
- **Needs Internet**: NO (stable knowledge)
- **Offline expectation**: Should provide a clear, complete explanation
- **Type**: Educational

#### Q3: What are the latest trends in cryptocurrency regulation in 2025?
- **Category**: Finance / Current News
- **Needs Internet**: YES (regulatory landscape changes rapidly)
- **Offline expectation**: Should provide general framework and note limitations
- **Type**: News / Current events

#### Q4: How does compound interest work, and what is the difference between APR and APY?
- **Category**: Finance / Educational
- **Needs Internet**: NO (stable knowledge)
- **Offline expectation**: Should give a clear, well-structured explanation
- **Type**: Educational

---

### TECHNOLOGY (Questions 5-8)

#### Q5: What are the latest features in Android 16?
- **Category**: Technology / Current News
- **Needs Internet**: YES (new OS features, recent release)
- **Offline expectation**: Should mention known features and note limitations
- **Type**: News / Current events

#### Q6: Explain how large language models work in simple terms
- **Category**: Technology / Educational
- **Needs Internet**: NO (stable knowledge)
- **Offline expectation**: Should give a clear, accessible explanation
- **Type**: Educational

#### Q7: What is the current state of quantum computing in 2025?
- **Category**: Technology / Current News
- **Needs Internet**: YES (rapidly evolving field)
- **Offline expectation**: Should provide general context and note limitations
- **Type**: News / Current events

#### Q8: What is the difference between REST and GraphQL APIs, and when should you use each?
- **Category**: Technology / Educational
- **Needs Internet**: NO (stable knowledge)
- **Offline expectation**: Should give a clear comparison with examples
- **Type**: Educational

---

### CULTURE (Questions 9-11)

#### Q9: What movies won the major categories at the 2025 Oscars?
- **Category**: Culture / Current News
- **Needs Internet**: YES (recent event results)
- **Offline expectation**: Should note limitations, may mention known nominations
- **Type**: News / Current events

#### Q10: Explain the cultural significance of Diwali and how it is celebrated
- **Category**: Culture / Educational
- **Needs Internet**: NO (stable knowledge)
- **Offline expectation**: Should give a thorough, respectful explanation
- **Type**: Educational

#### Q11: What are the biggest music trends shaping pop culture in 2025?
- **Category**: Culture / Current News
- **Needs Internet**: YES (trends change rapidly)
- **Offline expectation**: Should note limitations, provide general context
- **Type**: News / Current events

---

### OTHER NEWS (Questions 12-15)

#### Q12: What is the current status of the India-UK free trade agreement negotiations?
- **Category**: World News / Current Affairs
- **Needs Internet**: YES (ongoing negotiations)
- **Offline expectation**: Should provide background context and note limitations
- **Type**: News / Current events

#### Q13: What are the main climate change initiatives announced at recent global summits?
- **Category**: Science & Environment / Current News
- **Needs Internet**: YES (new announcements at summits)
- **Offline expectation**: Should mention known frameworks (Paris Agreement etc.) and note limitations
- **Type**: News / Current events

#### Q14: Explain what the Nobel Prize is and how laureates are selected
- **Category**: General Knowledge / Educational
- **Needs Internet**: NO (stable knowledge)
- **Offline expectation**: Should give a clear, complete explanation
- **Type**: Educational

#### Q15: What are the latest developments in AI regulation and the EU AI Act?
- **Category**: Tech Policy / Current News
- **Needs Internet**: YES (regulatory landscape evolving)
- **Offline expectation**: Should provide known context and note limitations
- **Type**: News / Current events

---

## Summary Matrix

| Q# | Category | Internet Required | Type |
|----|----------|-------------------|------|
| 1 | Finance | YES | Current Data |
| 2 | Finance | NO | Educational |
| 3 | Finance | YES | Current News |
| 4 | Finance | NO | Educational |
| 5 | Technology | YES | Current News |
| 6 | Technology | NO | Educational |
| 7 | Technology | YES | Current News |
| 8 | Technology | NO | Educational |
| 9 | Culture | YES | Current News |
| 10 | Culture | NO | Educational |
| 11 | Culture | YES | Current News |
| 12 | World News | YES | Current Affairs |
| 13 | Environment | YES | Current News |
| 14 | General | NO | Educational |
| 15 | Tech Policy | YES | Current News |

**Internet Required**: 9 questions (Q1, Q3, Q5, Q7, Q9, Q11, Q12, Q13, Q15)
**Offline OK**: 6 questions (Q2, Q4, Q6, Q8, Q10, Q14)

---

## Test Execution Instructions

### Phase 1: Online Testing (WiFi ON, Web Search Enabled)
1. Ensure device is connected to WiFi
2. Open Localyze app → Settings → Enable "Allow web search"
3. Create a new chat conversation for each question
4. Type the question exactly as written above
5. Wait for full response (streaming to complete)
6. Capture the response (screenshot + copy text)
7. Rate each response on the 6 criteria

### Phase 2: Offline Testing (WiFi OFF, Web Search Disabled)
1. Turn OFF WiFi on device (Settings → WiFi → Off)
2. Open Localyze app → Settings → Disable "Allow web search"
3. Create a new chat conversation for each question
4. Type the question exactly as written above
5. Wait for full response
6. Capture the response
7. Rate each response on the 6 criteria
8. Turn WiFi back ON after testing

### Phase 3: Evaluation & Fixes
1. Compile all scores into the results table below
2. Identify any responses scoring below 3.5/5 average
3. Improve SystemPromptBuilder.kt prompts for failing categories
4. Re-test failed questions
5. Document final results