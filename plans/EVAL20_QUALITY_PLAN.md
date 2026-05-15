# Localyze 20-Question Quality Evaluation Plan

## Objective
Run a targeted evaluation of the Localyze Android app with 10 knowledge questions (offline) and 10 web-search questions (online), scoring each response for quality on a 0-10 scale.

## Device Under Test
- **Device**: OnePlus NE2211 (Android 16, API 36)
- **Model**: Gemma 4 E4B (LiteRT-LM, GPU backend)
- **ADB Serial**: a5523839

## Question Set

### Part 1: Knowledge (10 questions — web search OFF)
| # | Question | Expected Pattern |
|---|----------|-----------------|
| 1 | What is the capital of France? | paris |
| 2 | What is 2 + 2? | 4 |
| 3 | Who wrote Romeo and Juliet? | shakespeare |
| 4 | What is the chemical symbol for water? | H2O |
| 5 | How many planets are in our solar system? | 8 / eight |
| 6 | What is the speed of light approximately? | 299,792,458 or 300,000 |
| 7 | Who painted the Mona Lisa? | leonardo / da vinci |
| 8 | What is the largest ocean on Earth? | pacific |
| 9 | What year did World War II end? | 1945 |
| 10 | What is the smallest prime number? | 2 / two |

### Part 2: Web Search (10 questions — web search ON)
| # | Question | Expected Pattern |
|---|----------|-----------------|
| 1 | Who is the current CEO of Google? | sundar pichai |
| 2 | What was the score of the latest FIFA World Cup final? | argentina, france, 3-3, penalties |
| 3 | What is the current population of India? | india, population, billion |
| 4 | What is the latest version of Android released? | android, 16/17 |
| 5 | Who is the current president of the United States? | donald trump |
| 6 | What is the current GDP growth rate of China? | china, gdp, 5% |
| 7 | Who won the most recent Nobel Prize in Literature? | krasznahorkai, nobel |
| 8 | What is the latest news about SpaceX? | spacex |
| 9 | What is the stock price of Apple today? | apple, stock, price |
| 10 | What are today's headlines from BBC News? | bbc, headline |

## Quality Scoring Rubric (0-10)

### Knowledge Questions
| Criterion | Points | Description |
|-----------|--------|-------------|
| Has content | 2 | Response is non-empty |
| Correctness | 3 | Contains expected factual patterns |
| Length | 2 | >=80 chars (2), >=40 chars (1) |
| Formatting | 3 | Has structure/sources (3), markdown (2), plain long text (1) |

### Web Search Questions
| Criterion | Points | Description |
|-----------|--------|-------------|
| Has content | 1 | Response is non-empty |
| Tool triggered | 2 | web_search tool was invoked |
| Has URLs | 2 | Answer or tool result contains URLs |
| Correctness | 3 | Contains expected factual patterns |
| Formatting | 2 | Has sources section + markdown (2), markdown only (1) |

## Execution Approach
1. Use `run_device_eval30.py` as the base infrastructure (Room DB polling, ADB control)
2. Modify question list to 10+10
3. Add quality scoring logic
4. Run evaluation and generate report

## Expected Output
- JSON file with per-question results
- Markdown report with quality scores
- Overall quality score out of 10
