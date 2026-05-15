package com.localyze.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import com.localyze.ui.theme.OnBackground
import com.localyze.ui.theme.Primary
import com.localyze.ui.theme.SurfaceVariant
import com.localyze.ui.theme.TextSecondary

internal sealed interface MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
    data class BulletList(val items: List<String>) : MarkdownBlock
    data class NumberedList(val items: List<String>) : MarkdownBlock
    data class Table(val headers: List<String>, val rows: List<List<String>>) : MarkdownBlock
    data class CodeBlock(val language: String, val code: String) : MarkdownBlock
    data class Quote(val text: String) : MarkdownBlock
    data object Divider : MarkdownBlock
}

@Composable
fun StructuredMarkdownText(
    markdown: String,
    modifier: Modifier = Modifier
) {
    val blocks = parseMarkdownBlocks(markdown)
    SelectionContainer {
        Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            blocks.forEach { block ->
                when (block) {
                    is MarkdownBlock.Heading -> HeadingBlock(block)
                    is MarkdownBlock.Paragraph -> ParagraphBlock(block.text)
                    is MarkdownBlock.BulletList -> ListBlock(block.items, numbered = false)
                    is MarkdownBlock.NumberedList -> ListBlock(block.items, numbered = true)
                    is MarkdownBlock.Table -> TableBlock(block)
                    is MarkdownBlock.CodeBlock -> CodeBlockView(block)
                    is MarkdownBlock.Quote -> QuoteBlock(block.text)
                    MarkdownBlock.Divider -> DividerBlock()
                }
            }
        }
    }
}

@Composable
private fun HeadingBlock(block: MarkdownBlock.Heading) {
    val style = when (block.level) {
        1 -> MaterialTheme.typography.titleLarge
        2 -> MaterialTheme.typography.titleMedium
        else -> MaterialTheme.typography.titleSmall
    }
    Text(
        text = cleanInlineMarkdown(block.text),
        color = OnBackground,
        style = style.copy(fontWeight = FontWeight.Bold)
    )
}

@Composable
private fun ParagraphBlock(text: String) {
    Text(
        text = buildInlineAnnotated(text),
        color = OnBackground,
        style = MaterialTheme.typography.bodyLarge
    )
}

@Composable
private fun ListBlock(items: List<String>, numbered: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEachIndexed { index, item ->
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = if (numbered) "${index + 1}." else "-",
                    color = Primary,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.width(if (numbered) 30.dp else 20.dp)
                )
                Text(
                    text = buildInlineAnnotated(item),
                    color = OnBackground,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

private val MARKDOWN_LINK_RE = Regex("\\[([^\\]]+)]\\(([^)]+)\\)")
private val BARE_URL_RE = Regex("(?<![(\\[])\\bhttps?://\\S+")

/**
 * Build an AnnotatedString that:
 *  - renders `[label](url)` as a clickable link (label only, no URL shown).
 *  - renders bare `https://...` as clickable text "link" (no URL shown).
 *  - strips bold/italic/code formatting markers like cleanInlineMarkdown did.
 */
private fun buildInlineAnnotated(raw: String): AnnotatedString {
    // Apply the same inline cleanup as cleanInlineMarkdown EXCEPT the
    // bracket→"label (url)" rewrite so we can still see the link tokens.
    val cleaned = raw
        .replace("\\${'$'}", "${'$'}")
        .replace(Regex("\\s*\\\\\\s*"), " ")
        .replace(Regex("(?<=[a-z0-9)])(?=(Year|New Balance|Compound Interest|Simple Interest|As you can see)\\b)"), " ")
        .replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1")
        .replace(Regex("__([^_]+)__"), "$1")
        .replace(Regex("`([^`]+)`"), "$1")
        .trim()

    val linkStyle = TextLinkStyles(
        style = SpanStyle(color = Primary, textDecoration = TextDecoration.Underline)
    )

    return buildAnnotatedString {
        var cursor = 0
        // Combined matches: [label](url) takes precedence; for bare URLs not
        // already inside an md link, fall back to a "link" anchor.
        val mdMatches = MARKDOWN_LINK_RE.findAll(cleaned).toList()
        val mdRanges = mdMatches.map { it.range }
        // Bare URL matches that don't overlap an md match.
        val bareMatches = BARE_URL_RE.findAll(cleaned)
            .filter { m -> mdRanges.none { it.first <= m.range.first && it.last >= m.range.last } }
            .toList()
        val combined = (mdMatches.map { it.range to "md" to it } +
            bareMatches.map { it.range to "bare" to it })
            .sortedBy { (pair, _) -> pair.first.first }

        for ((rangeKind, m) in combined) {
            val (range, kind) = rangeKind
            if (cursor < range.first) append(cleaned.substring(cursor, range.first))
            when (kind) {
                "md" -> {
                    val label = m.groupValues[1].trim()
                    val url = m.groupValues[2].trim()
                    withLink(LinkAnnotation.Url(url = url, styles = linkStyle)) {
                        append(label.ifEmpty { "link" })
                    }
                }
                "bare" -> {
                    val url = m.value
                    withLink(LinkAnnotation.Url(url = url, styles = linkStyle)) {
                        append("link")
                    }
                }
            }
            cursor = range.last + 1
        }
        if (cursor < cleaned.length) append(cleaned.substring(cursor))
    }
}

@Composable
private fun TableBlock(block: MarkdownBlock.Table) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = Color.Transparent,
        border = BorderStroke(0.5.dp, SurfaceVariant)
    ) {
        Column {
            TableRow(cells = block.headers, isHeader = true)
            block.rows.forEach { row ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(0.5.dp)
                        .background(SurfaceVariant)
                )
                TableRow(cells = row, isHeader = false)
            }
        }
    }
}

@Composable
private fun TableRow(cells: List<String>, isHeader: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isHeader) SurfaceVariant else Color.Transparent)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        cells.forEach { cell ->
            Text(
                text = cleanInlineMarkdown(cell),
                color = if (isHeader) TextSecondary else OnBackground,
                style = if (isHeader) {
                    MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                } else {
                    MaterialTheme.typography.bodyMedium
                },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun CodeBlockView(block: MarkdownBlock.CodeBlock) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFF111827),
        border = BorderStroke(1.dp, Color(0xFF243244)),
        shape = MaterialTheme.shapes.small
    ) {
        Column {
            if (block.language.isNotBlank()) {
                Text(
                    text = block.language,
                    color = Color(0xFFB8C4D9),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF172033))
                        .padding(horizontal = 12.dp, vertical = 7.dp)
                )
            }
            Text(
                text = block.code,
                color = Color(0xFFE7EEF8),
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.padding(12.dp)
            )
        }
    }
}

@Composable
private fun QuoteBlock(text: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .background(Primary)
        )
        Text(
            text = cleanInlineMarkdown(text),
            color = TextSecondary,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 10.dp)
        )
    }
}

@Composable
private fun DividerBlock() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceVariant)
            .padding(top = 1.dp)
    )
}

internal fun parseMarkdownBlocks(input: String): List<MarkdownBlock> {
    if (input.isBlank()) return emptyList()

    val blocks = mutableListOf<MarkdownBlock>()
    val paragraph = mutableListOf<String>()
    val bullets = mutableListOf<String>()
    val numbers = mutableListOf<String>()
    val lines = normalizeMarkdownForDisplay(input).lines()
    var index = 0

    fun flushParagraph() {
        if (paragraph.isNotEmpty()) {
            blocks += MarkdownBlock.Paragraph(paragraph.joinToString(" ").trim())
            paragraph.clear()
        }
    }

    fun flushLists() {
        if (bullets.isNotEmpty()) {
            blocks += MarkdownBlock.BulletList(bullets.toList())
            bullets.clear()
        }
        if (numbers.isNotEmpty()) {
            blocks += MarkdownBlock.NumberedList(numbers.toList())
            numbers.clear()
        }
    }

    fun flushAll() {
        flushParagraph()
        flushLists()
    }

    while (index < lines.size) {
        val rawLine = lines[index]
        val line = rawLine.trimEnd()
        val trimmed = line.trim()

        if (trimmed.startsWith("```")) {
            flushAll()
            val language = trimmed.removePrefix("```").trim()
            val codeLines = mutableListOf<String>()
            index++
            while (index < lines.size && !lines[index].trim().startsWith("```")) {
                codeLines += lines[index]
                index++
            }
            blocks += MarkdownBlock.CodeBlock(language, codeLines.joinToString("\n").trimEnd())
        } else if (trimmed.isBlank()) {
            flushAll()
        } else if (trimmed.matches(Regex("^#{1,4}\\s+.+"))) {
            flushAll()
            val level = trimmed.takeWhile { it == '#' }.length.coerceIn(1, 4)
            blocks += MarkdownBlock.Heading(level, trimmed.drop(level).trim())
        } else if (isMarkdownTableStart(lines, index)) {
            flushAll()
            val headers = parseTableCells(trimmed)
            val rows = mutableListOf<List<String>>()
            index += 2
            while (index < lines.size && isTableRow(lines[index].trim())) {
                rows += parseTableCells(lines[index].trim())
                index++
            }
            blocks += MarkdownBlock.Table(headers = headers, rows = rows)
            continue
        } else if (trimmed == "---" || trimmed == "***") {
            flushAll()
            blocks += MarkdownBlock.Divider
        } else if (trimmed.startsWith(">")) {
            flushAll()
            blocks += MarkdownBlock.Quote(trimmed.removePrefix(">").trim())
        } else if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
            flushParagraph()
            if (numbers.isNotEmpty()) flushLists()
            bullets += trimmed.drop(2).trim()
        } else if (trimmed.matches(Regex("^\\d+[.)]\\s+.+"))) {
            flushParagraph()
            if (bullets.isNotEmpty()) flushLists()
            numbers += trimmed.replaceFirst(Regex("^\\d+[.)]\\s+"), "").trim()
        } else {
            flushLists()
            paragraph += trimmed
        }

        index++
    }

    flushAll()
    return blocks
}

private fun isMarkdownTableStart(lines: List<String>, index: Int): Boolean {
    if (index + 1 >= lines.size) return false
    return isTableRow(lines[index].trim()) && isTableSeparator(lines[index + 1].trim())
}

private fun isTableRow(line: String): Boolean {
    return line.startsWith("|") && line.endsWith("|") && line.count { it == '|' } >= 3
}

private fun isTableSeparator(line: String): Boolean {
    if (!isTableRow(line)) return false
    return line
        .trim('|')
        .split('|')
        .all { cell -> cell.trim().matches(Regex(":?-{3,}:?")) }
}

private fun parseTableCells(line: String): List<String> {
    return line
        .trim()
        .trim('|')
        .split('|')
        .map { it.trim() }
}

internal fun normalizeMarkdownForDisplay(input: String): String {
    if (input.isBlank()) return input

    return input
        .replace("\\${'$'}", "${'$'}")
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        // Strip inline italic emphasis BEFORE bullet promotion below.
        // The model frequently italicizes book/play/species names with
        // `*Hamlet*` / `*Balaenoptera musculus*`, and the bullet-promotion
        // step otherwise turned the leading `*` into a `\n- ` and left the
        // closing `*` orphaned (e.g. "the play *Hamlet*." → "Hamlet*").
        // Constraints: opening `*` must be followed by a non-space, non-`*`,
        // and the inner span must not contain another `*` or newline — this
        // avoids matching list-marker `*` (which has a trailing space) and
        // bold `**X**` pairs.
        .replace(Regex("(?<![*\\w])\\*(?=\\S)([^*\\n]+?)(?<=\\S)\\*(?![*\\w])"), "$1")
        .replace(Regex("(?<!\\n)(#{1,4}\\s+)"), "\n\n$1")
        .replace(Regex("([:.!?])\\*(?=[A-Za-z0-9\\[])"), "$1\n- ")
        .replace(Regex("(#{1,4}\\s+[^\\n*]+)\\*(?=[A-Za-z0-9\\[])"), "$1\n- ")
        .replace(Regex("(?<![\\n*])\\*\\s*(?=[A-Za-z0-9\\[])"), "\n- ")
        .replace(Regex("(?<![\\n\\w])-\\s+(?=[A-Za-z0-9\\[])"), "\n- ")
        // Promote run-on numbered list markers to their own line, but ONLY at
        // a true line start. Previously we matched ANYWHERE that wasn't
        // preceded by `\n\w,$`, which caught the trailing digits of URLs
        // like `wikipedia.org/?curid=42400)` and corrupted them into a
        // numbered list, breaking the Markdown link.
        .replace(Regex("(?<=^|\\n)(\\d+[.)])\\s+(?=\\S)"), "$1 ")
        .replace(Regex("(?i)(?<![#\\n])\\bSources\\s*:"), "\n\n## Sources")
        .lines()
        .joinToString("\n") { it.trimEnd() }
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
}

private fun cleanInlineMarkdown(text: String): String {
    return text
        .replace("\\${'$'}", "${'$'}")
        .replace(Regex("\\s*\\\\\\s*"), " ")
        .replace(Regex("(?<=[a-z0-9)])(?=(Year|New Balance|Compound Interest|Simple Interest|As you can see)\\b)"), " ")
        .replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1")
        .replace(Regex("__([^_]+)__"), "$1")
        .replace(Regex("`([^`]+)`"), "$1")
        .replace(Regex("\\[([^\\]]+)]\\(([^)]+)\\)"), "$1 ($2)")
        .trim()
}
