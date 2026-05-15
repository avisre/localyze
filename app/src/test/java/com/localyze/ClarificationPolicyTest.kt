package com.localyze

import com.localyze.ai.CLARIFICATION_POLICY
import com.localyze.ui.viewmodels.CodeAssistAction
import com.localyze.ui.viewmodels.buildCodeWorkspacePrompt
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The clarification policy tells Gemma to ask 1-3 follow-up questions
 * before answering when the user's request is genuinely ambiguous, instead
 * of guessing. These tests lock in the policy text and confirm it gets
 * threaded into both the chat-side system prompt (via the constant) and
 * the workspace-side prompt (via buildCodeWorkspacePrompt).
 */
class ClarificationPolicyTest {

    @Test
    fun chatPolicyConstantContainsKeyDirectives() {
        val text = CLARIFICATION_POLICY
        assertTrue("policy mentions vague", text.contains("vague", ignoreCase = true))
        assertTrue(
            "policy requires options in follow-ups",
            text.contains("offer concrete options", ignoreCase = true) ||
                text.contains("Always offer options", ignoreCase = true)
        )
        assertTrue(
            "policy requires multi-round (≥2 rounds)",
            text.contains("2 rounds") || text.contains("≥2 rounds") ||
                text.contains("ANOTHER round")
        )
        assertTrue(
            "policy lists when NOT to ask",
            text.contains("Do NOT")
        )
        assertTrue(
            "policy includes the canonical follow-up preamble",
            text.contains("Quick question first")
        )
        assertTrue(
            "policy gives the user a 'go ahead' opt-out",
            text.contains("go ahead")
        )
    }

    @Test
    fun chatPolicyIncludesVagueOpenerExamples() {
        val text = CLARIFICATION_POLICY
        assertTrue(
            "policy must show 'top 10 news' as a vague-opener example",
            text.contains("top 10 news", ignoreCase = true)
        )
        assertTrue(
            "policy must show 'recommend' as a vague-opener example",
            text.contains("recommend a stock", ignoreCase = true) ||
                text.contains("recommend a laptop", ignoreCase = true)
        )
    }

    @Test
    fun chatPolicySaysToStopAfterAskingNotToGuess() {
        val text = CLARIFICATION_POLICY
        assertTrue(
            "policy must forbid drafting a speculative answer alongside questions",
            text.contains("NEVER draft a speculative") ||
                (text.contains("speculative") && text.contains("NEVER"))
        )
    }

    @Test
    fun chatPolicyPushesAnotherRoundIfReplyIsPartial() {
        val text = CLARIFICATION_POLICY
        assertTrue(
            "policy must instruct: if user reply is partial, ask another round",
            text.contains("ANOTHER round") ||
                text.contains("another round", ignoreCase = true)
        )
        assertTrue(
            "policy must reject 'answer with assumptions' shortcut",
            text.contains("do NOT give up and answer with assumptions", ignoreCase = true) ||
                text.contains("NOT give up", ignoreCase = true)
        )
    }

    @Test
    fun codeWorkspacePromptIncludesClarificationPolicyForExplainAction() {
        val prompt = buildCodeWorkspacePrompt(
            language = "Kotlin",
            action = CodeAssistAction.Explain,
            code = "fun add(a: Int, b: Int) = a + b",
            instruction = ""
        )
        assertTrue(
            "code-workspace prompt should carry the clarification policy",
            prompt.contains("CLARIFICATION POLICY", ignoreCase = false)
        )
        assertTrue(
            "policy should give vague-instruction examples",
            prompt.contains("\"refactor\"") && prompt.contains("\"fix this\"")
        )
    }

    @Test
    fun codeWorkspacePromptKeepsClarificationOutOfWebsiteBranch() {
        // Website builds use a deterministic template path; clarification
        // would just slow that down. The website branch is a different
        // contract — output the HTML directly.
        val prompt = buildCodeWorkspacePrompt(
            language = "HTML",
            action = CodeAssistAction.WebsiteRequest,
            code = "",
            instruction = "Build a simple landing page for a coffee shop"
        )
        assertFalse(
            "website branch should not include CLARIFICATION POLICY",
            prompt.contains("CLARIFICATION POLICY")
        )
    }
}
