package com.localyze

import com.localyze.ai.KNOWLEDGE_AND_TOOL_INSTRUCTION
import com.localyze.ai.shouldExposeToolToModel
import com.localyze.ui.viewmodels.ChatUiState
import com.localyze.ui.viewmodels.SettingsUiState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Focused regressions for the real-device issue where Gemma answered "hi"
 * but treated stable educational prompts as blocked web-search requests.
 */
class InferencePolicyRegressionTest {

    @Test
    fun stableKnowledgePolicyDoesNotGateGeneralAnswersOnWebSearch() {
        val policy = KNOWLEDGE_AND_TOOL_INSTRUCTION.lowercase(Locale.US)

        assertTrue(policy.contains("stable general-knowledge"))
        assertTrue(policy.contains("from your own model knowledge"))
        assertTrue(policy.contains("do not refuse"))
        assertTrue(policy.contains("web search is disabled"))
    }

    @Test
    fun webSearchPolicyIsLimitedToCurrentOrExplicitSearchNeeds() {
        val policy = KNOWLEDGE_AND_TOOL_INSTRUCTION.lowercase(Locale.US)

        val currentInfoTriggers = listOf(
            "explicitly asks you to search",
            "current",
            "recent",
            "live",
            "location-specific",
            "price",
            "schedule",
            "news"
        )

        currentInfoTriggers.forEach { trigger ->
            assertTrue("Expected web-search policy to mention '$trigger'", policy.contains(trigger))
        }
    }

    @Test
    fun webSearchToolIsHiddenFromNativeModelWhenDisabled() {
        assertFalse(shouldExposeToolToModel("web_search", allowWebSearch = false, memoryEnabled = false))
        assertTrue(shouldExposeToolToModel("web_search", allowWebSearch = true, memoryEnabled = false))
    }

    @Test
    fun memoryToolIsHiddenFromNativeModelUntilOptedIn() {
        assertFalse(shouldExposeToolToModel("memory", allowWebSearch = false, memoryEnabled = false))
        assertTrue(shouldExposeToolToModel("memory", allowWebSearch = false, memoryEnabled = true))
    }

    @Test
    fun nonWebNonMemoryToolsRemainVisibleWhenWebSearchAndMemoryAreDisabled() {
        val nonWebTools = listOf(
            "calendar",
            "contacts_search",
            "system_info",
            "email_draft",
            "sms_draft"
        )

        nonWebTools.forEach { toolName ->
            assertTrue(
                "Expected non-web tool '$toolName' to stay available",
                shouldExposeToolToModel(toolName, allowWebSearch = false, memoryEnabled = false)
            )
        }
    }

    @Test
    fun defaultChatAndSettingsStateAvoidThinkingOnlyBlankResponses() {
        val chatState = ChatUiState()
        val settingsState = SettingsUiState()

        assertFalse(chatState.enableThinking)
        assertFalse(settingsState.thinkingMode)
        assertFalse(chatState.allowWebSearch)
        assertFalse(settingsState.allowWebSearch)
        assertFalse(settingsState.memoryEnabled)
    }
}
