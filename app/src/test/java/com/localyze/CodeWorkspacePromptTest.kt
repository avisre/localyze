package com.localyze

import com.localyze.ui.viewmodels.CodeAssistAction
import com.localyze.ui.viewmodels.MAX_CODE_WORKSPACE_PROMPT_CODE_CHARS
import com.localyze.ui.viewmodels.buildCodeWorkspacePrompt
import com.localyze.ui.viewmodels.buildEcommerceLandingPageTemplate
import com.localyze.ui.viewmodels.buildWebsiteTemplateForInstruction
import com.localyze.ui.viewmodels.resolveCodeAssistAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeWorkspacePromptTest {

    @Test
    fun promptContainsIdentityAndCodeWorkspaceRules() {
        val prompt = buildCodeWorkspacePrompt(
            language = "HTML",
            action = CodeAssistAction.Fix,
            code = "<button>Save</button>",
            instruction = "Fix the button alignment"
        )

        assertTrue(prompt.contains("Localyze.ai"))
        assertTrue(prompt.contains("based on Gemma 4 E4B"))
        assertTrue(prompt.contains("Detected language: HTML"))
        assertTrue(prompt.contains("Action: Fix"))
        assertTrue(prompt.contains("corrected full code in one fenced code block"))
        assertTrue(prompt.contains("Do not output <thought>"))
        assertTrue(prompt.contains("User instruction: Fix the button alignment"))
    }

    @Test
    fun resolveActionInfersFixAndDebugRequests() {
        assertEquals(
            CodeAssistAction.Fix,
            resolveCodeAssistAction("please fix this code and make it work", CodeAssistAction.Explain)
        )
        assertEquals(
            CodeAssistAction.Debug,
            resolveCodeAssistAction("why does this throw an exception?", CodeAssistAction.Explain)
        )
        assertEquals(
            CodeAssistAction.Optimize,
            resolveCodeAssistAction("make this faster and use less memory", CodeAssistAction.Explain)
        )
        assertEquals(
            CodeAssistAction.Review,
            resolveCodeAssistAction("review this for production ready security", CodeAssistAction.Explain)
        )
    }

    @Test
    fun ecommerceTemplateIsCompleteAndInteractive() {
        val html = buildEcommerceLandingPageTemplate(
            "Create a premium ecommerce landing page for a trail running shoe brand"
        )

        assertTrue(html.startsWith("<!DOCTYPE html>"))
        assertTrue(html.endsWith("</html>"))
        assertTrue(html.contains("<style>"))
        assertTrue(html.contains("<script>"))
        assertTrue(html.contains("RidgeRun Supply"))
        assertTrue(html.contains("add-cart"))
        assertTrue(html.contains("filter-chip"))
        assertTrue(html.contains("quick-view"))
        assertFalse(html.contains("Lorem ipsum"))
        assertFalse(html.contains("Product 1"))
    }

    @Test
    fun ecommerceTemplateUsesPromptSpecificStorefronts() {
        val cases = listOf(
            "trail running shoe brand" to "RidgeRun Supply",
            "clean skincare serum shop" to "Luma Ritual",
            "modern desk lamp and home decor store" to "AuraFlow Home",
            "boutique pet accessories shop" to "PawMarket",
            "wireless headphones and audio electronics" to "VoltHaus Audio",
            "sustainable fashion clothing brand" to "North Loom",
            "small batch coffee beans online store" to "Roast House",
            "baby stroller gear with product cards" to "Nestling Co",
            "handmade jewelry shop" to "Atelier Gleam",
            "compact home fitness equipment store" to "FormLab Fitness"
        )

        cases.forEach { (prompt, brand) ->
            val html = buildEcommerceLandingPageTemplate("Create an ecommerce landing page for $prompt")

            assertTrue("Expected $brand for $prompt", html.contains(brand))
            assertTrue(html.contains("Add to cart"))
            assertTrue(html.contains("cart.count"))
        }
    }

    @Test
    fun ecommerceTemplateUsesExplicitBrandAndCartSidebar() {
        val html = buildEcommerceLandingPageTemplate(
            "Build a complete ecommerce landing page for KickX sneakers with 6 product cards and cart sidebar"
        )

        assertTrue(html.contains("KickX"))
        assertTrue(html.contains("cart-sidebar"))
        assertTrue(html.contains("cart-items"))
        assertTrue(html.contains("Checkout preview"))
        assertTrue(Regex("""class="product-card"""").findAll(html).count() >= 6)
    }

    @Test
    fun genericWebsiteTemplateIsCompleteResponsiveAndPreviewable() {
        val html = buildWebsiteTemplateForInstruction("Create a portfolio website for an app designer")

        assertTrue(html.startsWith("<!DOCTYPE html>"))
        assertTrue(html.endsWith("</html>"))
        assertTrue(html.contains("<meta name=\"viewport\""))
        assertTrue(html.contains("<style>"))
        assertTrue(html.contains("<script>"))
        assertTrue(html.contains("Nova Hart"))
        assertTrue(html.contains("lead-form"))
        assertFalse(html.contains("Lorem ipsum"))
    }

    @Test
    fun promptIncludesUserInstruction() {
        val prompt = buildCodeWorkspacePrompt(
            language = "HTML",
            action = CodeAssistAction.Explain,
            code = "",
            instruction = "Build a todo list app"
        )

        assertTrue(prompt.contains("Build a todo list app"))
        assertTrue(prompt.contains("USER'S SPECIFIC REQUEST"))
    }

    @Test
    fun promptIncludesExistingCodeWhenProvided() {
        val prompt = buildCodeWorkspacePrompt(
            language = "Kotlin",
            action = CodeAssistAction.Explain,
            code = "fun answer() = 41",
            instruction = "Fix this function"
        )

        assertTrue(prompt.contains("fun answer() = 41"))
        assertTrue(prompt.contains("CODE TO ANALYZE"))
    }

    @Test
    fun promptSaysNoCodeWasProvidedWhenEditorIsEmpty() {
        val prompt = buildCodeWorkspacePrompt(
            language = "HTML",
            action = CodeAssistAction.Explain,
            code = "",
            instruction = "Build a dashboard"
        )

        assertTrue(prompt.contains("No code was provided"))
        assertFalse(prompt.contains("CODE TO ANALYZE"))
    }

    @Test
    fun promptTruncatesVeryLargeCodeInputs() {
        val code = "a".repeat(MAX_CODE_WORKSPACE_PROMPT_CODE_CHARS + 1_000)
        val prompt = buildCodeWorkspacePrompt(
            language = "Python",
            action = CodeAssistAction.Explain,
            code = code,
            instruction = "Explain this"
        )

        assertFalse(prompt.contains("a".repeat(MAX_CODE_WORKSPACE_PROMPT_CODE_CHARS + 1)))
    }
}
