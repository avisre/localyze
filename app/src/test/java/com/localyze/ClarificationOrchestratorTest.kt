package com.localyze

import com.localyze.domain.clarify.ClarificationDecision
import com.localyze.domain.clarify.ClarificationOrchestrator
import com.localyze.domain.clarify.ClarifyQuestion
import com.localyze.domain.clarify.ClarifyState
import com.localyze.domain.clarify.RoundQA
import com.localyze.domain.clarify.toMarkdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClarificationOrchestratorTest {

    private val orchestrator = ClarificationOrchestrator()

    // ── First-turn vague openers should trigger AskMore ───────────────

    @Test
    fun topNNewsTriggersAskMoreWithTopicRegionAndTimeOptions() {
        val decision = orchestrator.analyze("top 10 news", state = null)
        assertTrue(decision is ClarificationDecision.AskMore)
        val ask = decision as ClarificationDecision.AskMore
        assertTrue("at least 2 questions", ask.questions.size >= 2)
        // Topic question must include common option pool
        assertTrue(
            ask.questions.any { it.options.containsAll(listOf("finance", "tech", "sports")) }
        )
        // Region question must include US and global
        assertTrue(
            ask.questions.any { it.options.containsAll(listOf("US", "global")) }
        )
    }

    @Test
    fun recommendStockTriggersRiskHorizonAndSectorQuestions() {
        val decision = orchestrator.analyze("recommend a stock", state = null)
        assertTrue(decision is ClarificationDecision.AskMore)
        val ask = decision as ClarificationDecision.AskMore
        val labels = ask.questions.map { it.text.lowercase() }
        assertTrue("must ask about risk", labels.any { "risk" in it })
        assertTrue("must ask about horizon", labels.any { "horizon" in it })
        assertTrue("must ask about sector", labels.any { "sector" in it })
    }

    @Test
    fun bestPhoneTriggersBudgetUseAndRegionQuestions() {
        val decision = orchestrator.analyze("best phone", state = null)
        assertTrue(decision is ClarificationDecision.AskMore)
        val ask = decision as ClarificationDecision.AskMore
        val labels = ask.questions.map { it.text.lowercase() }
        assertTrue("must ask about budget", labels.any { "budget" in it })
        assertTrue("must ask about use", labels.any { "use" in it })
        assertTrue("must ask about region", labels.any { "region" in it })
    }

    @Test
    fun helpMeWithMyTaxesTriggersClarification() {
        val decision = orchestrator.analyze("help me with my taxes", state = null)
        assertTrue(decision is ClarificationDecision.AskMore)
    }

    @Test
    fun tellMeAboutXTriggersAspectQuestion() {
        val decision = orchestrator.analyze("tell me about Bitcoin", state = null)
        assertTrue(decision is ClarificationDecision.AskMore)
        val ask = decision as ClarificationDecision.AskMore
        assertTrue(ask.questions.any { "aspect" in it.text.lowercase() })
    }

    @Test
    fun planMyTripTriggersClarification() {
        val decision = orchestrator.analyze("plan my trip", state = null)
        assertTrue(decision is ClarificationDecision.AskMore)
    }

    // ── Broadened pattern coverage (V1.1) ─────────────────────────────

    @Test
    fun biggestNewsByCategoryTriggersClarification() {
        listOf(
            "biggest tech news this week",
            "biggest finance news today",
            "did the Fed change rates this week",
            "current US election polls",
            "latest update on Ukraine",
            "what's the latest on the supreme court",
        ).forEach { q ->
            val d = orchestrator.analyze(q, state = null)
            assertTrue("\"$q\" must trigger clarify", d is ClarificationDecision.AskMore)
        }
    }

    @Test
    fun bestEvAndBestInstrumentTriggerClarification() {
        listOf(
            "best EVs in 2026 under \$40K",
            "best instruments for a 10 year old to start",
            "best AI coding tools right now",
            "best programming language",
            "starter acoustic guitar under \$400",
        ).forEach { q ->
            val d = orchestrator.analyze(q, state = null)
            assertTrue("\"$q\" must trigger clarify", d is ClarificationDecision.AskMore)
        }
    }

    @Test
    fun topNAlbumsAndBooksTriggerClarification() {
        listOf(
            "top 10 albums released this year",
            "top nonfiction books this year",
            "most streamed songs on Spotify right now",
            "trending restaurants in San Francisco right now",
        ).forEach { q ->
            val d = orchestrator.analyze(q, state = null)
            assertTrue("\"$q\" must trigger clarify", d is ClarificationDecision.AskMore)
        }
    }

    @Test
    fun isXWorthItTriggersClarification() {
        listOf(
            "is an MBA still worth it in 2026",
            "should I upgrade to the iPhone 16",
            "should I buy a Tesla",
        ).forEach { q ->
            val d = orchestrator.analyze(q, state = null)
            assertTrue("\"$q\" must trigger clarify", d is ClarificationDecision.AskMore)
        }
    }

    @Test
    fun livePriceQueriesTriggerClarification() {
        listOf(
            "gold price today and is it a good time to buy",
            "30-year mortgage rates this month",
            "Bitcoin price now",
        ).forEach { q ->
            val d = orchestrator.analyze(q, state = null)
            assertTrue("\"$q\" must trigger clarify", d is ClarificationDecision.AskMore)
        }
    }

    @Test
    fun sportsScheduleAndResultsTriggerClarification() {
        listOf(
            "when is the next F1 race",
            "who won the NBA games last night",
            "when does the next Premier League season start",
        ).forEach { q ->
            val d = orchestrator.analyze(q, state = null)
            assertTrue("\"$q\" must trigger clarify", d is ClarificationDecision.AskMore)
        }
    }

    @Test
    fun travelVisaAndTimingTriggerClarification() {
        listOf(
            "visa free countries for Indian passport right now",
            "good time to visit Japan now and cherry blossom forecast",
            "best time to visit Tokyo",
            "cheapest flights New York to Tokyo next 3 months",
        ).forEach { q ->
            val d = orchestrator.analyze(q, state = null)
            assertTrue("\"$q\" must trigger clarify", d is ClarificationDecision.AskMore)
        }
    }

    @Test
    fun howToCatchAllTriggersClarification() {
        listOf(
            "how to lose 10 pounds in 3 months",
            "how to write a cover letter that gets noticed",
            "how to choose between two job offers",
            "how to potty train a 2 year old",
            "how to teach kids about money",
            "how to start composting at home",
            "how to choose a mattress",
            "how to learn React in 2026",
            "how to pair wine with steak",
            "how to start an Instagram account that grows",
            "how to get a US tourist visa from India",
            "how do I back up my iPhone",
            "how can I get better sleep",
        ).forEach { q ->
            val d = orchestrator.analyze(q, state = null)
            assertTrue("\"$q\" must trigger clarify", d is ClarificationDecision.AskMore)
        }
    }

    @Test
    fun isXBetterOrSafeTriggersClarification() {
        listOf(
            "is Mac better than Windows for coding",
            "is Costa Rica safe to visit",
            "is Python better than Rust for AI",
            "is weed legal in Texas",
        ).forEach { q ->
            val d = orchestrator.analyze(q, state = null)
            assertTrue("\"$q\" must trigger clarify", d is ClarificationDecision.AskMore)
        }
    }

    @Test
    fun salaryQuestionsTriggerClarification() {
        listOf(
            "what's the salary for a senior software engineer in San Francisco",
            "how much does a senior PM make in New York",
            "average salary for a data scientist",
        ).forEach { q ->
            val d = orchestrator.analyze(q, state = null)
            assertTrue("\"$q\" must trigger clarify", d is ClarificationDecision.AskMore)
        }
    }

    @Test
    fun eventsExhibitionsTriggerClarification() {
        listOf(
            "art exhibitions in New York and London this month",
            "concerts in San Francisco this week",
            "must-see Cannes films this year",
            "Banksy auction sales last year",
        ).forEach { q ->
            val d = orchestrator.analyze(q, state = null)
            assertTrue("\"$q\" must trigger clarify", d is ClarificationDecision.AskMore)
        }
    }

    @Test
    fun broadenedNewsAndPopularTriggerClarification() {
        listOf(
            "latest oil prices news",
            "what's the inflation rate in the US right now",
            "what board games are popular this year",
            "what books are popular right now",
        ).forEach { q ->
            val d = orchestrator.analyze(q, state = null)
            assertTrue("\"$q\" must trigger clarify", d is ClarificationDecision.AskMore)
        }
    }

    @Test
    fun marketSectorTriggersClarification() {
        listOf(
            "where are energy stocks today",
            "how is the tech market doing",
        ).forEach { q ->
            val d = orchestrator.analyze(q, state = null)
            assertTrue("\"$q\" must trigger clarify", d is ClarificationDecision.AskMore)
        }
    }

    // ── Concrete questions should pass through ────────────────────────

    @Test
    fun capitalOfFrancePassesThrough() {
        val decision = orchestrator.analyze("What is the capital of France?", state = null)
        assertEquals(ClarificationDecision.PassThrough, decision)
    }

    @Test
    fun specificMathQuestionPassesThrough() {
        val decision = orchestrator.analyze("What is 17 percent of 240?", state = null)
        assertEquals(ClarificationDecision.PassThrough, decision)
    }

    @Test
    fun longDetailedRequestPassesThrough() {
        // 240+ char fully-specified request goes straight through.
        val q = "I'm 38 years old, married with 2 kids, earning \$180K, with " +
            "a \$750K mortgage and \$200K saved. How much term life insurance " +
            "do I actually need, and 20- vs 30-year? Show the math you used " +
            "to arrive at the recommended coverage amount."
        val decision = orchestrator.analyze(q, state = null)
        assertEquals(ClarificationDecision.PassThrough, decision)
    }

    @Test
    fun emptyQueryPassesThrough() {
        val decision = orchestrator.analyze("   ", state = null)
        assertEquals(ClarificationDecision.PassThrough, decision)
    }

    // ── Multi-round behaviour ─────────────────────────────────────────

    @Test
    fun substantiveReplyAfterRound1ReturnsSpecific() {
        val state = ClarifyState(
            originalQuery = "top 10 news",
            pendingQuestions = listOf(
                ClarifyQuestion("About what topic?", listOf("finance", "tech", "sports")),
                ClarifyQuestion("From which region?", listOf("US", "India", "global")),
            ),
        )
        val decision = orchestrator.analyze("finance, US, today", state = state)
        assertTrue("multi-token reply should be specific", decision is ClarificationDecision.Specific)
        val refined = (decision as ClarificationDecision.Specific).refinedQuery
        assertTrue(refined.contains("top 10 news"))
        assertTrue(refined.contains("finance, US, today"))
    }

    @Test
    fun singleTokenOptionMatchAfterRound1ReturnsSpecific() {
        val state = ClarifyState(
            originalQuery = "recommend a stock",
            pendingQuestions = listOf(
                ClarifyQuestion("Risk?", listOf("low", "moderate", "high")),
            ),
        )
        val decision = orchestrator.analyze("moderate", state = state)
        assertTrue(decision is ClarificationDecision.Specific)
    }

    @Test
    fun capReachedForcesSpecific() {
        // 2 completed rounds + a 3rd round of pending questions about to be
        // answered → must force Specific regardless of the new reply.
        val state = ClarifyState(
            originalQuery = "top 10 news",
            pendingQuestions = listOf(ClarifyQuestion("More?", listOf("a", "b"))),
            completedRounds = listOf(
                RoundQA(emptyList(), "finance"),
                RoundQA(emptyList(), "US"),
            ),
        )
        val decision = orchestrator.analyze("ok", state = state)
        assertTrue(decision is ClarificationDecision.Specific)
    }

    // ── Topic-shift detection (mid-flow, user starts a new question) ─

    @Test
    fun newVagueQuestionWhilePendingTriggersFreshClarification() {
        // Pending state from a previous AskMore round.
        val state = ClarifyState(
            originalQuery = "top 10 news",
            pendingQuestions = listOf(
                ClarifyQuestion("Topic?", listOf("finance", "tech", "sports")),
                ClarifyQuestion("Region?", listOf("US", "global")),
            ),
        )
        // User abandons the previous round and asks something new.
        val decision = orchestrator.analyze("best laptop for video editing under \$2000", state = state)
        assertTrue(
            "topic-shift to a new vague opener should restart clarify, not refine the old one",
            decision is ClarificationDecision.AskMore
        )
        // It should be the laptop pattern, not the news pattern.
        val ask = decision as ClarificationDecision.AskMore
        assertTrue(
            "must use the new question's pattern (laptop budget)",
            ask.questions.any { "budget" in it.text.lowercase() }
        )
    }

    @Test
    fun questionStarterMidFlowAbandonsPendingState() {
        val state = ClarifyState(
            originalQuery = "top 10 news",
            pendingQuestions = listOf(
                ClarifyQuestion("Topic?", listOf("finance", "tech")),
            ),
        )
        // 4+ tokens starting with a question word — clearly a new question.
        val decision = orchestrator.analyze("how do I back up my iPhone", state = state)
        // Either a clarify (if this matches a pattern) or pass-through —
        // but NOT Specific composing the old originalQuery.
        assertTrue(
            "must not refine the stale originalQuery",
            decision is ClarificationDecision.AskMore ||
                decision is ClarificationDecision.PassThrough
        )
    }

    @Test
    fun shortOptionPickStillCountsAsReply() {
        val state = ClarifyState(
            originalQuery = "recommend a stock",
            pendingQuestions = listOf(
                ClarifyQuestion("Risk?", listOf("low", "moderate", "high")),
            ),
        )
        // Short single-token reply → still treated as a reply, returns Specific.
        val decision = orchestrator.analyze("moderate", state = state)
        assertTrue(decision is ClarificationDecision.Specific)
    }

    @Test
    fun goAheadOptOutForcesSpecific() {
        val state = ClarifyState(
            originalQuery = "top 10 news",
            pendingQuestions = listOf(ClarifyQuestion("Topic?", listOf("a", "b"))),
        )
        val decision = orchestrator.analyze("go ahead", state = state)
        assertTrue(decision is ClarificationDecision.Specific)
    }

    // ── Markdown rendering ────────────────────────────────────────────

    @Test
    fun askMoreRendersAsMarkdownWithOptions() {
        val ask = ClarificationDecision.AskMore(
            listOf(
                ClarifyQuestion("About what topic?", listOf("finance", "tech")),
                ClarifyQuestion("Region?", listOf("US", "global")),
            )
        )
        val md = ask.toMarkdown()
        assertTrue(md.contains("Quick question first"))
        assertTrue(md.contains("1. About what topic? *(finance / tech)*"))
        assertTrue(md.contains("2. Region? *(US / global)*"))
        assertTrue(md.contains("\"go ahead\""))
    }
}
