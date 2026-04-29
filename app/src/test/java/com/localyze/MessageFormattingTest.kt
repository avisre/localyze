package com.localyze

import com.localyze.tools.normalizeWebSearchQueries
import com.localyze.tools.requiresFreshSources
import com.localyze.ui.components.MarkdownBlock
import com.localyze.ui.components.normalizeMarkdownForDisplay
import com.localyze.ui.components.parseMarkdownBlocks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageFormattingTest {

    @Test
    fun markdownParserKeepsHeadingsListsAndCodeAsSeparateBlocks() {
        val blocks = parseMarkdownBlocks(
            """
            ## Summary
            - First point
            - Second point

            ```kotlin
            fun answer() = 42
            ```
            """.trimIndent()
        )

        assertTrue(blocks[0] is MarkdownBlock.Heading)
        assertTrue(blocks[1] is MarkdownBlock.BulletList)
        assertTrue(blocks[2] is MarkdownBlock.CodeBlock)
        assertEquals("kotlin", (blocks[2] as MarkdownBlock.CodeBlock).language)
    }

    @Test
    fun searchQueryNormalizerRemovesPromptySearchPhrasesAndAddsYearForCurrentQueries() {
        val queries = normalizeWebSearchQueries(
            query = "Please search the web for latest Android Compose release notes?",
            year = 2026
        )

        assertEquals("latest Android Compose release notes", queries.first())
        assertTrue(queries.any { it.contains("2026") })
    }

    @Test
    fun searchQueryNormalizerTreatsTrendingAndAwardResultsAsCurrentQueries() {
        val movieQueries = normalizeWebSearchQueries(
            query = "Who won the Oscar for Best Picture?",
            year = 2026
        )
        val trendingQueries = normalizeWebSearchQueries(
            query = "What are the top trending movies in India this week?",
            year = 2026
        )

        assertTrue(movieQueries.any { it.contains("2026") })
        assertTrue(trendingQueries.any { it.contains("2026") })
    }

    @Test
    fun freshSourceDetectorCoversCommonLiveQuestionPhrases() {
        val liveQuestions = listOf(
            "What is the current Federal Funds rate?",
            "What are today's technology headlines?",
            "Who won the Oscar for Best Picture in 2026?",
            "What are the top trending movies in India this week?"
        )

        liveQuestions.forEach { question ->
            assertTrue(question, requiresFreshSources(question))
        }
    }

    @Test
    fun markdownNormalizerRecoversCrampedModelLists() {
        val normalized = normalizeMarkdownForDisplay(
            "Summary:*First point*Second point### Sources*Docs (https://example.com)"
        )

        val blocks = parseMarkdownBlocks(normalized)

        assertTrue(blocks.any { it is MarkdownBlock.BulletList })
        assertTrue(blocks.any { it is MarkdownBlock.Heading && it.text == "Sources" })
    }

    @Test
    fun markdownNormalizerRecoversBulletsAfterPunctuation() {
        val normalized = normalizeMarkdownForDisplay(
            "I can explain topics such as:*Science & Technology: AI basics.*Finance: Compound interest."
        )
        val blocks = parseMarkdownBlocks(normalized)

        assertTrue(blocks.any { block ->
            block is MarkdownBlock.BulletList &&
                block.items.any { it.startsWith("Science & Technology") } &&
                block.items.any { it.startsWith("Finance") }
        })
    }

    @Test
    fun markdownRendererCleansEscapedCurrencyInPlainAnswers() {
        val blocks = parseMarkdownBlocks(
            "Example: \\${'$'}1,000 + \\${'$'}100 = \\${'$'}1,100"
        )

        assertTrue(blocks.any { block ->
            block is MarkdownBlock.Paragraph &&
                block.text.contains("${'$'}1,000 + ${'$'}100 = ${'$'}1,100")
        })
    }

    @Test
    fun markdownNormalizerDoesNotTreatCurrencyAsNumberedList() {
        val blocks = parseMarkdownBlocks(
            """
            - After year 1, you earn ${'$'}100, so you have ${'$'}1,100.
            - In year 2, the 10% is calculated on ${'$'}1,100.
            """.trimIndent()
        )

        assertTrue(blocks.none { it is MarkdownBlock.NumberedList })
        assertTrue(blocks.any { block ->
            block is MarkdownBlock.BulletList && block.items.size == 2
        })
    }
}
