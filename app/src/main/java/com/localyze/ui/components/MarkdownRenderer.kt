package com.localyze.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
        text = cleanInlineMarkdown(text),
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
                    text = cleanInlineMarkdown(item),
                    color = OnBackground,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f)
                )
            }
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

internal fun normalizeMarkdownForDisplay(input: String): String {
    if (input.isBlank()) return input

    return input
        .replace("\\${'$'}", "${'$'}")
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .replace(Regex("(?<!\\n)(#{1,4}\\s+)"), "\n\n$1")
        .replace(Regex("([:.!?])\\*(?=[A-Za-z0-9\\[])"), "$1\n- ")
        .replace(Regex("(#{1,4}\\s+[^\\n*]+)\\*(?=[A-Za-z0-9\\[])"), "$1\n- ")
        .replace(Regex("(?<![\\n*])\\*\\s*(?=[A-Za-z0-9\\[])"), "\n- ")
        .replace(Regex("(?<![\\n\\w])-\\s+(?=[A-Za-z0-9\\[])"), "\n- ")
        .replace(Regex("(?<![\\n\\d,$])(\\d+[.)])\\s+(?=\\S)"), "\n$1 ")
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
