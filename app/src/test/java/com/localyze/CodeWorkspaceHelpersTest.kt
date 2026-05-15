package com.localyze

import com.localyze.ui.screens.previewHtmlFromCode
import com.localyze.ui.viewmodels.CodeBlock
import com.localyze.ui.viewmodels.detectLanguageFromCode
import com.localyze.ui.viewmodels.extractAllCodeBlocks
import com.localyze.ui.viewmodels.extractCompleteHtmlDocument
import com.localyze.ui.viewmodels.looksLikeHtml
import com.localyze.ui.viewmodels.mergeBlocksIntoHtml
import com.localyze.ui.viewmodels.stripThoughtSections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeWorkspaceHelpersTest {

    // ── stripThoughtSections ──────────────────────────────────────

    @Test
    fun stripThoughtSectionsRemovesThoughtAndThinkBlocks() {
        val raw = """
            <thought>I should plan first</thought>
            Here is the answer.
            <think>more thinking</think>
            More answer.
        """.trimIndent()

        val cleaned = stripThoughtSections(raw)

        assertFalse(cleaned.contains("<thought"))
        assertFalse(cleaned.contains("<think"))
        assertTrue(cleaned.contains("Here is the answer."))
        assertTrue(cleaned.contains("More answer."))
    }

    @Test
    fun stripThoughtSectionsLeavesPlainTextAlone() {
        val raw = "Just regular markdown without thought tags"
        assertEquals(raw, stripThoughtSections(raw))
    }

    // ── extractCompleteHtmlDocument ───────────────────────────────

    @Test
    fun extractsCompleteHtmlDocumentFromMixedText() {
        val raw = """
            Here's your page:
            <!DOCTYPE html>
            <html>
            <head><title>Test</title></head>
            <body><h1>Hello</h1></body>
            </html>
            Hope that helps!
        """.trimIndent()

        val doc = extractCompleteHtmlDocument(raw)

        assertNotNull(doc)
        // The extractor takes the latest of <!doctype> / <html> — for this
        // single-document input that's <html, so the returned slice starts
        // there and runs to the matching </html>.
        assertTrue(doc!!.lowercase().startsWith("<html"))
        assertTrue(doc.endsWith("</html>"))
        assertTrue(doc.contains("<h1>Hello</h1>"))
        assertFalse(doc.contains("Hope that helps"))
    }

    @Test
    fun extractCompleteHtmlDocumentReturnsNullWhenAbsent() {
        assertNull(extractCompleteHtmlDocument("Just some plain text"))
        assertNull(extractCompleteHtmlDocument("<div>fragment only</div>"))
    }

    // ── extractAllCodeBlocks ──────────────────────────────────────

    @Test
    fun extractsLanguageTaggedAndUntaggedCodeBlocks() {
        val raw = """
            Here are the pieces:
            ```html
            <div>hello</div>
            ```
            And the styles:
            ```css
            div { color: red; }
            ```
            And:
            ```
            untagged content
            ```
        """.trimIndent()

        val blocks = extractAllCodeBlocks(raw)

        assertEquals(3, blocks.size)
        assertEquals("html", blocks[0].language)
        assertEquals("<div>hello</div>", blocks[0].code)
        assertEquals("css", blocks[1].language)
        assertEquals("", blocks[2].language)
        assertEquals("untagged content", blocks[2].code)
    }

    @Test
    fun extractAllCodeBlocksStripsStrikethroughCorrections() {
        val raw = """
            ```js
            const ~~old~~ value = 42;
            ```
        """.trimIndent()

        val blocks = extractAllCodeBlocks(raw)

        assertEquals(1, blocks.size)
        assertFalse(blocks[0].code.contains("~~"))
        assertTrue(blocks[0].code.contains("const old value = 42;"))
    }

    // ── mergeBlocksIntoHtml ───────────────────────────────────────

    @Test
    fun mergesSeparateHtmlCssJsBlocksIntoOneDocument() {
        val blocks = listOf(
            CodeBlock("html", "<div class=\"box\">Hello</div>"),
            CodeBlock("css", ".box { color: tomato; padding: 8px; }"),
            CodeBlock("js", "document.querySelector('.box').addEventListener('click', () => alert('hi'));")
        )

        val merged = mergeBlocksIntoHtml(blocks)

        assertNotNull(merged)
        assertTrue(merged!!.startsWith("<!DOCTYPE html>"))
        assertTrue(merged.contains("<div class=\"box\">Hello</div>"))
        assertTrue(merged.contains("color: tomato"))
        assertTrue(merged.contains("addEventListener"))
        assertTrue(merged.contains("<meta name=\"viewport\""))
    }

    @Test
    fun mergeBlocksReturnsNullForSingleBlock() {
        assertNull(mergeBlocksIntoHtml(listOf(CodeBlock("html", "<div>x</div>"))))
        assertNull(mergeBlocksIntoHtml(emptyList()))
    }

    // ── looksLikeHtml ─────────────────────────────────────────────

    @Test
    fun looksLikeHtmlIdentifiesCommonTags() {
        assertTrue(looksLikeHtml("<!DOCTYPE html><html><body>x</body></html>"))
        assertTrue(looksLikeHtml("<div class=\"x\">y</div>"))
        assertTrue(looksLikeHtml("<main><section>hi</section></main>"))
        assertTrue(looksLikeHtml("<nav><a href='#'>home</a></nav>"))
    }

    @Test
    fun looksLikeHtmlRejectsPlainText() {
        assertFalse(looksLikeHtml("just words"))
        assertFalse(looksLikeHtml("def foo(): pass"))
        assertFalse(looksLikeHtml("# Markdown heading"))
    }

    // ── detectLanguageFromCode ────────────────────────────────────

    @Test
    fun detectsCommonLanguages() {
        assertEquals("HTML", detectLanguageFromCode("<!DOCTYPE html><html><body><div>x</div></body></html>"))
        assertEquals("CSS", detectLanguageFromCode("@media (max-width: 600px) { body { color: red; } }"))
        assertEquals(
            "JavaScript",
            detectLanguageFromCode("const x = 1;\ndocument.body.appendChild(x);")
        )
        assertEquals("Kotlin", detectLanguageFromCode("fun greet(name: String): String = \"hi \$name\""))
        assertEquals("Java", detectLanguageFromCode("public static void main(String[] args) { }"))
        assertEquals("Python", detectLanguageFromCode("def add(a, b):\n    return a + b"))
        assertEquals("Go", detectLanguageFromCode("func main() { fmt.Println(\"hi\") }"))
        assertEquals("C++", detectLanguageFromCode("#include <iostream>\nint main() { return 0; }"))
        assertEquals("SQL", detectLanguageFromCode("SELECT id, name FROM users WHERE active = 1"))
        assertEquals("Plain text", detectLanguageFromCode("just a sentence"))
    }

    // ── previewHtmlFromCode (the screen's preview-pane router) ────

    @Test
    fun previewHtmlPassesCompleteDocumentThrough() {
        val html = "<!DOCTYPE html><html lang=\"en\"><head></head><body><h1>x</h1></body></html>"
        val out = previewHtmlFromCode(html)
        assertTrue(out.contains("<h1>x</h1>"))
        assertTrue(out.lowercase().contains("viewport"))
    }

    @Test
    fun previewHtmlWrapsBodyFragment() {
        val out = previewHtmlFromCode("<body><h1>x</h1></body>")
        assertTrue(out.startsWith("<!DOCTYPE html>"))
        assertTrue(out.contains("<h1>x</h1>"))
    }

    @Test
    fun previewHtmlWrapsTagFragment() {
        val out = previewHtmlFromCode("<div class=\"box\"><h1>Hello</h1></div>")
        assertTrue(out.startsWith("<!DOCTYPE html>"))
        assertTrue(out.contains("<h1>Hello</h1>"))
        assertTrue(out.contains("<body>"))
    }

    @Test
    fun previewHtmlWrapsBareCss() {
        val out = previewHtmlFromCode(".btn { color: red; padding: 8px; }")
        assertTrue(out.startsWith("<!DOCTYPE html>"))
        assertTrue(out.contains("color: red"))
        assertTrue(out.contains("CSS Preview"))
    }

    @Test
    fun previewHtmlReturnsBlankForNonRenderableInput() {
        assertEquals("", previewHtmlFromCode(""))
        assertEquals("", previewHtmlFromCode("just plain text with no tags or rules"))
        assertEquals("", previewHtmlFromCode("def foo(): pass"))
    }

    @Test
    fun previewHtmlInjectsViewportWhenMissing() {
        val html = "<html><head><title>T</title></head><body>x</body></html>"
        val out = previewHtmlFromCode(html)
        assertTrue(out.lowercase().contains("viewport"))
    }
}
