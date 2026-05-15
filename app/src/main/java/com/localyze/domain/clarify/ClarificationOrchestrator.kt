package com.localyze.domain.clarify

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decides whether a user message needs clarification before being sent
 * to the model or web router.
 *
 * V1 is heuristic-only: a curated pattern table catches the common
 * vague openers (top-N news, recommendations, "help me with X", etc.)
 * with canned options-style follow-ups. The long tail passes through
 * to the LLM, which still has the system-prompt CLARIFICATION_POLICY
 * as a backstop.
 *
 * V2 will add a small Gemma-judge call for the long tail; the pattern
 * table stays as a fast pre-filter.
 *
 * Per-conversation state ([ClarifyState]) lives in-memory keyed by
 * conversation id. Cap is 2 rounds — after that we force [Specific]
 * with whatever the user has supplied so far.
 */
@Singleton
class ClarificationOrchestrator @Inject constructor() {

    /**
     * @param userQuery the message the user just sent
     * @param state any in-progress clarification for this conversation
     *              (null on the first turn)
     */
    fun analyze(userQuery: String, state: ClarifyState?): ClarificationDecision {
        val trimmed = userQuery.trim()

        // 1. Empty / very long messages pass through.
        if (trimmed.isBlank()) return ClarificationDecision.PassThrough
        if (trimmed.length > 240) return ClarificationDecision.PassThrough

        // 2. Mid-flow: there are pending questions awaiting reply.
        if (state != null && state.pendingQuestions.isNotEmpty()) {
            // Topic shift detection: if the new message itself looks like
            // a fresh vague opener, OR starts with a question word, the
            // user is asking something new — abandon the pending round
            // and start over.
            if (isLikelyNewQuestion(trimmed)) {
                val pattern = VaguePatterns.matchOrNull(trimmed)
                return if (pattern != null) {
                    ClarificationDecision.AskMore(pattern.questions, topic = trimmed)
                } else {
                    ClarificationDecision.PassThrough
                }
            }

            val finished = state.finishPending(trimmed)
            // Opt-out → just answer with what we have.
            if (looksLikeOptOut(trimmed)) {
                return ClarificationDecision.Specific(composeRefinedQuery(finished))
            }
            // Cap reached — force specific.
            if (finished.capReached) {
                return ClarificationDecision.Specific(composeRefinedQuery(finished))
            }
            // Substantive reply → specific.
            if (replyIsSubstantive(state.pendingQuestions, trimmed)) {
                return ClarificationDecision.Specific(composeRefinedQuery(finished))
            }
            // Still vague — ask one more narrowing round.
            return askNextRound()
        }

        // 3. First turn (or pending state cleared): classify against
        //    the vague-opener table.
        val pattern = VaguePatterns.matchOrNull(trimmed)
        return if (pattern != null) {
            ClarificationDecision.AskMore(pattern.questions, topic = trimmed)
        } else {
            // Long-tail vague queries fall through to the LLM, which has
            // CLARIFICATION_POLICY in its system prompt as a backstop.
            ClarificationDecision.PassThrough
        }
    }

    /**
     * Heuristic: does this look like a fresh question (topic shift) rather
     * than a reply to the previous clarification round?
     *
     * Triggers:
     *   - matches our vague-opener pattern table (it's clearly a new
     *     ask, not a one-line answer to options).
     *   - starts with a question word (how/what/why/where/when/who/which/
     *     can/could/should/is/are/do/does/did) AND has 4+ tokens.
     *   - ends with a question mark.
     *
     * Skips trigger when:
     *   - reply is short (1-3 words) — likely an option pick.
     */
    private fun isLikelyNewQuestion(reply: String): Boolean {
        if (reply.length < 8) return false
        val tokens = reply.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.size < 4) return false

        if (VaguePatterns.matchOrNull(reply) != null) return true
        if (reply.endsWith("?")) return true

        val first = tokens.first().lowercase().trimStart('"', '\'')
        val questionWords = setOf(
            "how", "what", "why", "where", "when", "who", "which",
            "can", "could", "should", "would", "is", "are", "do", "does",
            "did", "will", "tell", "explain", "describe", "list", "show",
            "give", "recommend", "suggest", "find", "build", "draft", "write",
            "calculate", "compute", "compare", "translate", "summarize",
        )
        return first in questionWords
    }

    private fun looksLikeOptOut(reply: String): Boolean {
        val lower = reply.lowercase()
        return lower in setOf(
            "go ahead", "just answer", "your call", "your choice",
            "doesn't matter", "any", "anything", "whatever",
        )
    }

    private fun replyIsSubstantive(
        pendingQuestions: List<ClarifyQuestion>,
        reply: String,
    ): Boolean {
        val tokens = reply.split(Regex("[\\s,/]+"))
            .filter { it.isNotBlank() }
        if (tokens.size >= 2) return true
        // Single-token reply that matches one of the offered options is
        // valid (e.g., user said "finance" to "topic? finance/sports/tech").
        val askedOptions = pendingQuestions
            .flatMap { it.options.map(String::lowercase) }
        return reply.lowercase() in askedOptions
    }

    private fun askNextRound(): ClarificationDecision {
        // V1: ask a generic narrowing round (timeframe / scope) so the
        // model doesn't loop on the same options.
        return ClarificationDecision.AskMore(
            listOf(
                ClarifyQuestion(
                    text = "Anything more specific to narrow this down?",
                    options = listOf("more recent", "broader scope", "with examples", "concise summary"),
                )
            )
        )
    }

    private fun composeRefinedQuery(state: ClarifyState): String = buildString {
        append(state.originalQuery)
        state.completedRounds.forEach { round ->
            val asked = round.questions.joinToString("; ") { it.text.removeSuffix("?") }
            append(" — ").append(asked).append(": ").append(round.userReply.trim())
        }
    }
}

/**
 * Curated table of vague-opener patterns and the clarifying questions to
 * ask for each. Extending this table is the v1 path for "make the model
 * ask better questions for category X".
 */
internal object VaguePatterns {
    data class Pattern(val regex: Regex, val questions: List<ClarifyQuestion>)

    private val table: List<Pattern> = listOf(
        // ── News / headlines (broad: covers "biggest tech news", "what's
        //     trending", "did the Fed do X", "latest update on Y") ──
        Pattern(
            regex = Regex(
                "(?i)" + listOf(
                    "^\\s*(top\\s+\\d+\\s+)?(news|headlines?)\\b",
                    // "What's the news?", "What's the news today?", "Whats news"
                    // — bare news queries with optional 'the' and apostrophe.
                    "^\\s*what(?:'?s)?\\s+(?:the\\s+|in\\s+the\\s+)?news\\b",
                    "^\\s*what'?s\\s+happening\\b",
                    "^\\s*trending\\s+(news|stories|on\\s+\\w+)\\b",
                    "^\\s*biggest\\s+\\w+\\s+news\\b",
                    // Only fire when the news topic is GENERIC. If a specific
                    // noun precedes "news" (e.g. "cricket news from Australia",
                    // "tech news today", "news about Elon Musk"), the user
                    // has already disambiguated — go straight to web search.
                    "^\\s*(latest|recent)\\s+(news|updates?|stories|headlines)\\s*\\??\\s*$",
                    "^\\s*did\\s+(the\\s+)?(fed|federal\\s+reserve|government|congress|" +
                        "supreme\\s+court|senate)\\b",
                    "^\\s*current\\s+(us\\s+)?(election|polls?|polling)\\b",
                    "^\\s*what'?s?\\s+the\\s+latest\\b",
                    "^\\s*what'?s?\\s+the\\s+(inflation|unemployment|interest)\\s+rate\\b",
                    "^\\s*what\\s+\\w+(\\s+\\w+){0,3}\\s+(are|is)\\s+popular\\s+(this|right\\s+now)\\b",
                ).joinToString("|")
            ),
            questions = listOf(
                ClarifyQuestion("About what topic?", listOf("finance", "tech", "sports", "politics", "culture", "world")),
                ClarifyQuestion("From which region?", listOf("US", "India", "UK", "EU", "global")),
                ClarifyQuestion("What time window?", listOf("today", "this week", "this month")),
            ),
        ),
        // ── Stocks / investment recommendations ──────────────────────
        Pattern(
            regex = Regex(
                "(?i)\\b(recommend|suggest|pick|find|best)\\s+(a\\s+|an\\s+|some\\s+)?(stock|stocks|shares|investment)"
            ),
            questions = listOf(
                ClarifyQuestion("Risk tolerance?", listOf("low", "moderate", "high")),
                ClarifyQuestion("Time horizon?", listOf("short-term (<1yr)", "medium (1-5yr)", "long (5+yr)")),
                ClarifyQuestion("Sector preference?", listOf("tech", "finance", "energy", "healthcare", "any")),
            ),
        ),
        // ── Phone / laptop / car / camera / headphones recommendations
        Pattern(
            regex = Regex(
                "(?i)\\b(best|recommend|suggest|which)\\s+(a\\s+|an\\s+)?(phone|laptop|car|" +
                    "ev|evs|electric\\s+(car|vehicle)|tablet|" +
                    "headphone|headphones|earbuds|camera|tv|monitor|smartwatch|" +
                    "fitness\\s+tracker|bike|scooter|drone|console|gaming\\s+pc)\\b"
            ),
            questions = listOf(
                ClarifyQuestion("Budget range?", listOf("under \$500", "\$500–1000", "\$1000–2000", "\$2000+")),
                ClarifyQuestion("Primary use?", listOf("work", "gaming", "travel", "video editing", "general")),
                ClarifyQuestion("Region?", listOf("US", "UK", "India", "EU", "global")),
            ),
        ),
        // ── "Best X" generic recommendations (instruments, AI tools,
        //     albums, books, restaurants, EVs, software, etc.) — runs
        //     AFTER the phone/laptop pattern above so device-specific
        //     budgets win for those. ────────────────────────────────
        Pattern(
            regex = Regex(
                "(?i)" + listOf(
                    // Entertainment (movies, songs, books, albums, shows) and
                    // games are dropped from this pattern — "Top movies 2026"
                    // is a list request, not a purchase decision that needs
                    // budget/skill follow-ups. They go straight to web search.
                    "^\\s*(top(\\s+\\d+)?|best|trending|hot|favorite|" +
                        "most\\s+(popular|streamed|watched|read|downloaded|played))\\s+" +
                        "(\\w+\\s+){0,3}" +
                        "(instruments?|tools?|apps?|software|services|libraries|frameworks|" +
                        "platforms|" +
                        "restaurants?|brands?|protein|supplements?|vitamins?|exercises?|" +
                        "funds?|cryptos?|coins?|languages?|courses?|classes?|" +
                        "websites?|sites?)\\b",
                    "^\\s*(best|recommend|suggest|which|what'?s\\s+the\\s+best)\\s+" +
                        "(programming\\s+)?(language|framework|library|tool|app|service|" +
                        "platform|stack)\\b",
                    "^\\s*starter\\s+\\w+",  // "starter acoustic guitar"
                ).joinToString("|")
            ),
            questions = listOf(
                ClarifyQuestion("What for?", listOf("personal use", "work", "kids/family", "gift", "specific project")),
                ClarifyQuestion("Budget?", listOf("free", "under \$50", "\$50–200", "\$200+")),
                ClarifyQuestion("Skill level?", listOf("beginner", "intermediate", "advanced", "any")),
            ),
        ),
        // ── "Is X worth it" / "X vs Y" / "X better than Y" / "is X safe" —
        //     discussion questions that benefit from situation context. ─
        Pattern(
            regex = Regex(
                "(?i)" + listOf(
                    "^\\s*is\\s+(an?\\s+|the\\s+)?\\w+(\\s+\\w+){0,4}\\s+(still\\s+)?worth\\s+it\\b",
                    "^\\s*should\\s+i\\s+(get|buy|invest\\s+in|switch\\s+to|upgrade(\\s+(to|from))?)\\b",
                    // "X vs Y" pattern is intentionally narrow now: only
                    // fires when the user is asking for a DECISION ("which
                    // should I get?", "help me choose"), not for a direct
                    // comparison. "Compare iPhone vs Android" / "iPhone vs
                    // Android" goes straight to a real comparison; only
                    // "iPhone vs Android — which should I pick?" clarifies.
                    "^\\s*(which|help\\s+me\\s+(pick|choose|decide))" +
                        "\\s+(should\\s+i\\s+)?\\w+(\\s+\\w+){0,2}\\s+vs\\.?\\s+\\w+(\\s+\\w+){0,2}\\b",
                    "^\\s*is\\s+\\w+(\\s+\\w+){0,3}\\s+(better\\s+than|worse\\s+than|safer\\s+than|cheaper\\s+than)\\b",
                    "^\\s*is\\s+\\w+(\\s+\\w+){0,3}\\s+safe(\\s+to\\s+(visit|travel|use|eat|drink))?\\b",
                    "^\\s*is\\s+\\w+(\\s+\\w+){0,3}\\s+(legal|illegal|allowed|banned)\\b",
                ).joinToString("|")
            ),
            questions = listOf(
                ClarifyQuestion("What's your situation?", listOf("starting out", "comparing options", "considering switching", "doing research")),
                ClarifyQuestion("What matters most?", listOf("cost", "quality", "long-term ROI", "speed", "simplicity")),
                ClarifyQuestion("Region / context?", listOf("US", "India", "UK", "global", "n/a")),
            ),
        ),
        // ── "How to X" catch-all DISABLED — the user feedback north-star
        //     is "answer first, don't ask." For tactical how-to questions
        //     ("how do I get red wine out of a shirt?", "how do I unclog a
        //     sink?") the model can give a generally-useful answer without
        //     interrogating the user. Specific clarifiers still fire from
        //     the more targeted patterns above (laptop / phone / car /
        //     learn-skill etc.) — this catch-all was the over-firing one.
        // (intentionally removed)
        // ── Big life decisions: "should I quit/leave/switch/change …"
        //    Asking-but-not-clarifying makes the model default to an
        //    unhelpful "I'm an AI, can't give personal advice" refusal.
        //    With 2-3 clarifying options we can frame the trade-off and
        //    let the user think out loud. ─────────────────────────────
        Pattern(
            regex = Regex(
                "(?i)" + listOf(
                    "^\\s*should\\s+i\\s+(quit|leave|switch|change|resign\\s+from|" +
                        "stay\\s+at|stay\\s+in|take|accept|reject|decline)\\s+" +
                        "(my\\s+)?(job|role|career|company|position|offer|gig|" +
                        "internship|grad\\s+school|phd|startup|business)\\b",
                    "^\\s*should\\s+i\\s+(go\\s+back\\s+to\\s+school|change\\s+careers?|" +
                        "start\\s+(a\\s+)?(business|startup|company))\\b",
                    "^\\s*i'?m\\s+(thinking\\s+of|considering)\\s+(quitting|leaving|switching|changing)\\b",
                ).joinToString("|")
            ),
            questions = listOf(
                ClarifyQuestion(
                    "What's pushing you to consider this?",
                    listOf("burnout / stress", "comp / pay", "career growth", "team / culture", "personal life", "something else")
                ),
                ClarifyQuestion(
                    "How urgent is the decision?",
                    listOf("right now / weeks", "this quarter", "this year", "exploring")
                ),
                ClarifyQuestion(
                    "What does success look like in 1 year?",
                    listOf("more $", "more meaning", "more time", "more growth", "more stability")
                ),
            ),
        ),
        // ── Salary / cost-of-living / job-market questions ───────────
        Pattern(
            regex = Regex(
                "(?i)" + listOf(
                    "^\\s*what'?s?\\s+the\\s+(salary|pay|compensation|comp)\\s+(for|of)\\b",
                    "^\\s*how\\s+much\\s+do(es)?\\s+\\w+(\\s+\\w+){0,3}\\s+(make|earn|cost|pay)\\b",
                    "^\\s*(average|median|typical)\\s+(salary|pay|compensation)\\b",
                ).joinToString("|")
            ),
            questions = listOf(
                ClarifyQuestion("Experience level?", listOf("entry", "mid", "senior", "staff/principal", "exec")),
                ClarifyQuestion("Region / city?", listOf("San Francisco", "New York", "London", "India", "remote", "other")),
                ClarifyQuestion("Comp components?", listOf("base only", "base + bonus", "total comp incl. equity", "industry benchmark")),
            ),
        ),
        // ── Events / exhibitions / shows in a city this month/year ───
        Pattern(
            regex = Regex(
                "(?i)" + listOf(
                    "\\b(exhibitions?|shows?|concerts?|festivals?|events?|conferences?)\\s+" +
                        "(in|at|near)\\s+\\w+",
                    "^\\s*(must.?see|must.?do|must.?try)\\s+\\w+",
                    "\\b(auction\\s+sales?|auction\\s+results?)\\b",
                ).joinToString("|")
            ),
            questions = listOf(
                ClarifyQuestion("Time window?", listOf("this week", "this month", "this year", "upcoming")),
                ClarifyQuestion("Type / genre?", listOf("art", "music", "tech", "food", "sport", "any")),
                ClarifyQuestion("Vibe?", listOf("popular/mainstream", "underrated", "free / cheap", "kid-friendly", "luxury")),
            ),
        ),
        // ── Live prices: only clarify when the user asks for advice
        //    ("should I buy?", "is it a good time?") — simple lookups
        //    like "current price of Bitcoin" should go straight to web
        //    search and return a number. Asking three follow-ups for a
        //    one-word answer is bad UX (caught by the golden-50 eval).
        Pattern(
            regex = Regex(
                "(?i)" + listOf(
                    "should\\s+i\\s+(buy|sell|invest\\s+in)\\s+(gold|silver|oil|crude|bitcoin|btc|ethereum|eth)\\b",
                    "is\\s+(it|now)\\s+a\\s+good\\s+time\\s+to\\s+(buy|sell)\\s+(gold|silver|oil|crude|bitcoin|btc|ethereum|eth)\\b",
                    "will\\s+(gold|silver|oil|bitcoin|btc|ethereum|eth)\\s+(go\\s+up|go\\s+down|crash|rally)\\b",
                ).joinToString("|")
            ),
            questions = listOf(
                ClarifyQuestion("Why are you asking?", listOf("buying now", "selling now", "investing", "just curious")),
                ClarifyQuestion("Region / market?", listOf("US", "India", "UK", "global spot")),
                ClarifyQuestion("Time horizon?", listOf("today", "next month", "long-term")),
            ),
        ),
        // ── Sports schedule / results ─────────────────────────────
        Pattern(
            regex = Regex(
                "(?i)" + listOf(
                    "^\\s*when\\s+(is|does)\\s+(the\\s+)?next\\s+(\\w+\\s+){0,3}(race|game|match|" +
                        "tournament|season|event|fight)\\b",
                    // "Who won the …" only fires if the target is a generic
                    // sport noun (game/match/race/tournament). Specific named
                    // events like "Super Bowl 2026" or "Nobel Peace Prize"
                    // are already disambiguated — don't pester the user with
                    // "which sport / league?" for prompts that don't need it.
                    "^\\s*who\\s+won\\s+(the\\s+|today'?s\\s+|last\\s+night'?s\\s+)?" +
                        "(\\w+\\s+){0,2}(games?|matches?|races?|tournaments?)\\b",
                    "^\\s*\\w+\\s+(scores?|results?|standings?|schedule)\\s+(today|now|this\\s+week)\\b",
                ).joinToString("|")
            ),
            questions = listOf(
                ClarifyQuestion("Which sport / league?", listOf("F1", "NBA", "NFL", "MLB", "EPL", "tennis", "cricket", "other")),
                ClarifyQuestion("Region?", listOf("US", "UK", "India", "global")),
                ClarifyQuestion("Time window?", listOf("today", "this week", "this season")),
            ),
        ),
        // ── Travel: visas / passports / "good time to visit" ───────
        Pattern(
            regex = Regex(
                "(?i)" + listOf(
                    "^\\s*(visa\\s+free|visa-?free)\\s+countries?\\s+for\\s+\\w+\\s+passport\\b",
                    "^\\s*good\\s+time\\s+to\\s+(visit|travel\\s+to)\\s+\\w+",
                    "^\\s*best\\s+(time|month|season)\\s+to\\s+visit\\s+\\w+",
                    "^\\s*cheapest\\s+flights?\\b",
                ).joinToString("|")
            ),
            questions = listOf(
                ClarifyQuestion("From which country / passport?", listOf("US", "India", "UK", "EU", "other")),
                ClarifyQuestion("When are you traveling?", listOf("this month", "next 3 months", "this year", "flexible")),
                ClarifyQuestion("Trip purpose?", listOf("vacation", "business", "family", "study", "stopover")),
            ),
        ),
        // ── Stocks / market sectors ────────────────────────────────
        Pattern(
            regex = Regex(
                "(?i)" + listOf(
                    "^\\s*where\\s+are\\s+(\\w+\\s+){0,3}(stocks?|markets?)\\s+(today|now)?",
                    "^\\s*how\\s+(are|is)\\s+(\\w+\\s+){0,3}(stocks?|markets?)\\s+(doing|performing)",
                ).joinToString("|")
            ),
            questions = listOf(
                ClarifyQuestion("Which sector / index?", listOf("S&P 500", "NASDAQ", "energy", "tech", "finance", "healthcare", "specific stock")),
                ClarifyQuestion("Region?", listOf("US", "India", "Europe", "Asia", "global")),
                ClarifyQuestion("What do you want to know?", listOf("current price", "today's move", "YTD performance", "outlook")),
            ),
        ),
        // ── Generic "help me with X" without a verb-noun ─────────────
        Pattern(
            regex = Regex(
                "(?i)^\\s*(help|assist)\\s+me\\s+with\\s+(my\\s+)?(taxes|finances|career|" +
                    "diet|fitness|health|relationship|kid|kids|parents|sleep|focus)\\s*\\.?\\s*$"
            ),
            questions = listOf(
                ClarifyQuestion("What specifically?", listOf("a question", "a plan", "review my situation", "general guidance")),
                ClarifyQuestion("Country / region?", listOf("US", "India", "UK", "EU", "other")),
                ClarifyQuestion("How urgent?", listOf("right now", "this week", "this month", "general")),
            ),
        ),
        // ── "Tell me about X" / "What is X" without aspect ──────────
        Pattern(
            regex = Regex(
                "(?i)^\\s*(tell\\s+me\\s+about|give\\s+me\\s+(an?\\s+)?overview\\s+of)\\s+\\w+\\s*\\.?\\s*$"
            ),
            questions = listOf(
                ClarifyQuestion("What aspect?", listOf("history", "current state", "how to use it", "key concepts", "famous examples")),
                ClarifyQuestion("How deep?", listOf("one paragraph", "detailed explanation", "with examples", "step-by-step")),
            ),
        ),
        // ── "Plan my X" without specifics ───────────────────────────
        Pattern(
            regex = Regex(
                "(?i)^\\s*(plan|organize|design|build)\\s+(my\\s+|a\\s+)?(trip|day|week|month|" +
                    "year|workout|diet|meal|wedding|party|career)\\s*\\.?\\s*$"
            ),
            questions = listOf(
                ClarifyQuestion("Where / for who?", listOf("my own use", "for a group", "for someone else")),
                ClarifyQuestion("Budget / time available?", listOf("low", "medium", "high", "flexible")),
                ClarifyQuestion("Goal?", listOf("save money", "save time", "best quality", "novelty")),
            ),
        ),
        // ── "Recommend a movie/book/song/restaurant" ────────────────
        Pattern(
            regex = Regex(
                "(?i)\\b(recommend|suggest)\\s+(a\\s+|some\\s+)?(movie|movies|film|films|" +
                    "book|books|song|songs|album|albums|restaurant|restaurants|show|shows)\\b"
            ),
            questions = listOf(
                ClarifyQuestion("Genre / style?", listOf("drama", "comedy", "thriller", "documentary", "any")),
                ClarifyQuestion("Mood right now?", listOf("light", "thought-provoking", "fun", "intense")),
                ClarifyQuestion("Time / length?", listOf("short", "medium", "long", "any")),
            ),
        ),
        // ── "What should I learn / do / study" ──────────────────────
        Pattern(
            regex = Regex(
                "(?i)^\\s*what\\s+should\\s+i\\s+(learn|study|do|read|try|know|cook|eat|wear|watch)\\s*\\??\\s*$"
            ),
            questions = listOf(
                ClarifyQuestion("Goal?", listOf("career", "hobby", "fitness", "social", "creative")),
                ClarifyQuestion("How much time / effort?", listOf("a few minutes", "an hour", "weeks", "months+")),
                ClarifyQuestion("Current level?", listOf("complete beginner", "some experience", "intermediate", "advanced")),
            ),
        ),
        // ── Generic "give me advice" / "any advice" ──────────────────
        Pattern(
            regex = Regex(
                "(?i)^\\s*(give\\s+me|any|got)\\s+(some\\s+)?advice\\s*\\.?\\s*$"
            ),
            questions = listOf(
                ClarifyQuestion("What domain?", listOf("career", "money", "health", "relationships", "learning")),
                ClarifyQuestion("What's your situation?", listOf("just starting", "stuck", "doing well, want more", "between options")),
            ),
        ),
    )

    fun matchOrNull(query: String): Pattern? = table.firstOrNull { it.regex.containsMatchIn(query) }

    /** Exposed for tests + future tooling. */
    fun all(): List<Pattern> = table
}
