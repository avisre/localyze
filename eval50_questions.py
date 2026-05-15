"""
50-question evaluation set covering common user prompts.
Each entry: (tag, category, question)
"""

QUESTIONS = [
    # ── Knowledge / facts (10) ────────────────────────────────────────
    ("Q01_KNOW_CAPITAL",     "knowledge", "What is the capital of Australia?"),
    ("Q02_KNOW_PLANETS",     "knowledge", "How many planets are in our solar system?"),
    ("Q03_KNOW_ELEMENT",     "knowledge", "What is the chemical symbol for gold?"),
    ("Q04_KNOW_AUTHOR",      "knowledge", "Who wrote 1984?"),
    ("Q05_KNOW_WAR",         "knowledge", "In what year did World War II end?"),
    ("Q06_KNOW_DEFINE_AI",   "knowledge", "Define machine learning in two sentences."),
    ("Q07_KNOW_TALLEST",     "knowledge", "What is the tallest mountain in the world?"),
    ("Q08_KNOW_LANG",        "knowledge", "What language is spoken in Brazil?"),
    ("Q09_KNOW_OCEAN",       "knowledge", "Which ocean is the largest?"),
    ("Q10_KNOW_CURRENCY",    "knowledge", "What is the currency of Japan?"),

    # ── Math / reasoning (6) ──────────────────────────────────────────
    ("Q11_MATH_PCT",         "math",      "What is 18% of 250?"),
    ("Q12_MATH_TIP",         "math",      "If a meal costs $42 and I want to leave a 20% tip, what is the total?"),
    ("Q13_MATH_DISTANCE",    "math",      "If I drive 240 miles in 4 hours, what is my average speed?"),
    ("Q14_MATH_COMPOUND",    "math",      "If I invest $1000 at 5% annual interest compounded yearly, how much do I have after 10 years?"),
    ("Q15_MATH_FRACTION",    "math",      "What is 3/4 plus 5/8?"),
    ("Q16_MATH_LOGIC",       "math",      "I have 3 apples. I give you 2. Then I eat 1 of mine. Then you give me back 1. How many apples does each of us have?"),

    # ── Code (5) ──────────────────────────────────────────────────────
    ("Q17_CODE_PYTHON",      "code",      "Write a Python function reverse_words(s) that reverses the order of words in a string. Include one example call with output."),
    ("Q18_CODE_DEBUG",       "code",      "What's wrong with this Python code: `def avg(xs): return sum(xs)/len(xs)` if I pass an empty list?"),
    ("Q19_CODE_REGEX",       "code",      "Write a regex that matches a US phone number in formats like (123) 456-7890 or 123-456-7890."),
    ("Q20_CODE_SQL",         "code",      "Write a SQL query to find the second highest salary from an Employee table."),
    ("Q21_CODE_EXPLAIN",     "code",      "Explain what a closure is in JavaScript with a 3-line example."),

    # ── Writing (5) ───────────────────────────────────────────────────
    ("Q22_WRITE_EMAIL",      "writing",   "Draft a 3-sentence apology email to a customer whose order was delayed by 5 days due to a warehouse fire."),
    ("Q23_WRITE_TWEET",      "writing",   "Write a 280-character tweet announcing a new productivity app launch."),
    ("Q24_WRITE_SUMMARY",    "writing",   "Summarize the plot of Romeo and Juliet in 4 sentences."),
    ("Q25_WRITE_RESIGNATION","writing",   "Draft a polite 4-sentence resignation letter giving 2 weeks notice."),
    ("Q26_WRITE_LIST",       "writing",   "List 5 short tips for improving sleep quality, one bullet each."),

    # ── Reasoning / explanation (6) ───────────────────────────────────
    ("Q27_EXP_HASH",         "explain",   "Explain what a hash table is to a beginner with one analogy."),
    ("Q28_EXP_TCPUDP",       "explain",   "Explain the difference between TCP and UDP and when to use each, in 4 short bullets."),
    ("Q29_EXP_TRAIN",        "explain",   "A train leaves City A at 9:00 AM going 60 mph east. Another leaves City B at 9:30 AM going 75 mph west. The cities are 300 miles apart. When do they meet?"),
    ("Q30_EXP_QUANTUM",      "explain",   "Explain quantum entanglement in plain English in under 80 words."),
    ("Q31_EXP_MOON",         "explain",   "Why does the moon look bigger when it is near the horizon?"),
    ("Q32_EXP_DNS",          "explain",   "How does DNS resolve a domain name? Walk through it in 5 short steps."),

    # ── Live data (8) ─────────────────────────────────────────────────
    ("Q33_LIVE_BTC",         "live",      "What is the current Bitcoin price in USD?"),
    ("Q34_LIVE_AAPL",        "live",      "What is Apple's current stock price?"),
    ("Q35_LIVE_PYVER",       "live",      "What is the latest stable version of Python and when was it released?"),
    ("Q36_LIVE_WEATHER",     "live",      "What is the current weather in New York City?"),
    ("Q37_LIVE_USDEUR",      "live",      "What is the current exchange rate from USD to EUR?"),
    ("Q38_LIVE_PRES",        "live",      "Who is the current president of the United States?"),
    ("Q39_LIVE_INDIA_POP",   "live",      "What is the current population of India?"),
    ("Q40_LIVE_NEWS",        "live",      "Give me one current top headline from BBC News."),

    # ── Visualization (7) — should produce charts ─────────────────────
    ("Q41_VIZ_AAPL_REV",     "viz",       "Show me Apple's annual revenue for the last 3 years."),
    ("Q42_VIZ_MSFT_REV",     "viz",       "Show me Microsoft revenue for the last 5 years."),
    ("Q43_VIZ_NVDA_REV",     "viz",       "Plot NVIDIA revenue over the last 4 years."),
    ("Q44_VIZ_TSLA_NETINC",  "viz",       "Tesla net income last 3 fiscal years as a chart."),
    ("Q45_VIZ_AAPL_GOOG",    "viz",       "Compare Apple and Google revenue over the last 3 years."),
    ("Q46_VIZ_AMZN_PROFIT",  "viz",       "Plot Amazon's net income over the last 4 years."),
    ("Q47_VIZ_META_REV",     "viz",       "Show me Meta's annual revenue for the past 4 years."),

    # ── Personal / planning (3) ───────────────────────────────────────
    ("Q48_PLAN_DAY",         "plan",      "Suggest a 30-minute morning routine for a busy professional, in numbered steps."),
    ("Q49_PLAN_MEALS",       "plan",      "Give me a simple 3-meal plan for one day under 1500 calories."),
    ("Q50_PLAN_PACK",        "plan",      "I'm going to Tokyo for 5 days in winter. List 8 essential items to pack."),
]
