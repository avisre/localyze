package com.localyze

import com.localyze.ui.viewmodels.CodeAssistAction
import com.localyze.ui.viewmodels.MAX_CODE_WORKSPACE_PROMPT_CODE_CHARS
import com.localyze.ui.viewmodels.buildEcommerceLandingPageTemplate
import com.localyze.ui.viewmodels.buildCodeWorkspacePrompt
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

@Ignore("Code workspace is hidden while chatbot quality work is in focus.")
class CodeWorkspacePromptTest {

    @Test
    fun promptContainsStrictOutputRules() {
        val prompt = buildCodeWorkspacePrompt(
            language = "HTML",
            action = CodeAssistAction.Explain,
            code = "",
            instruction = "Build a portfolio website"
        )

        // Must enforce single code block
        assertTrue(prompt.contains("ONE fenced code block"))
        // Must enforce complete HTML document
        assertTrue(prompt.contains("<!DOCTYPE html>"))
        // Must enforce inline CSS
        assertTrue(prompt.contains("<style>"))
        // Must enforce inline JS
        assertTrue(prompt.contains("<script>"))
        // Must forbid CDNs
        assertTrue(prompt.contains("external CDN"))
        // Must forbid API calls
        assertTrue(prompt.contains("fetch()"))
        assertTrue(prompt.contains("API calls"))
        // Must enforce viewport meta
        assertTrue(prompt.contains("viewport"))
        // Must prevent generic low-quality website output
        assertTrue(prompt.contains("real shipped ecommerce landing page"))
        assertTrue(prompt.contains("Never use lorem ipsum"))
        assertTrue(prompt.contains("meaningful JavaScript interaction"))
        assertTrue(prompt.contains("Do not output <thought>"))
    }

    @Test
    fun ecommercePromptAddsStorefrontRequirements() {
        val prompt = buildCodeWorkspacePrompt(
            language = "HTML",
            action = CodeAssistAction.Explain,
            code = "",
            instruction = "Create a landing page for an ecommerce website selling trail shoes"
        )

        assertTrue(prompt.contains("ECOMMERCE LANDING PAGE REQUIREMENTS"))
        assertTrue(prompt.contains("at least four products"))
        assertTrue(prompt.contains("cart count"))
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
    fun promptIncludesUserInstruction() {
        val prompt = buildCodeWorkspacePrompt(
            language = "HTML",
            action = CodeAssistAction.Explain,
            code = "",
            instruction = "Build a todo list app"
        )

        assertTrue(prompt.contains("todo list app"))
        assertTrue(prompt.contains("USER REQUEST"))
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
        assertTrue(prompt.contains("EXISTING CODE"))
    }

    @Test
    fun promptSaysFromScratchWhenNoCode() {
        val prompt = buildCodeWorkspacePrompt(
            language = "HTML",
            action = CodeAssistAction.Explain,
            code = "",
            instruction = "Build a dashboard"
        )

        assertTrue(prompt.contains("from scratch"))
        assertFalse(prompt.contains("EXISTING CODE"))
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
