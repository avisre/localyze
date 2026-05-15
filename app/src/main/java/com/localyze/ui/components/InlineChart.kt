package com.localyze.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localyze.ui.theme.Hairline
import com.localyze.ui.theme.OnBackground
import androidx.compose.ui.graphics.drawscope.Fill
import com.localyze.ui.theme.Surface
import com.localyze.ui.theme.SurfaceVariant
import com.localyze.ui.theme.TextSecondary
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val chartColors = listOf(
    Color(0xFF34C759), Color(0xFFFF6B6B), Color(0xFF6BD888),
    Color(0xFFFFCC00), Color(0xFFAF52DE), Color(0xFFFF9500),
    Color(0xFF5AC8FA), Color(0xFFFF2D55)
)

enum class ChartType { BAR, LINE, PIE }

data class ChartData(val label: String, val value: Float, val color: Color = Color.Unspecified)

data class ChartResult(val data: List<ChartData>, val type: ChartType)

internal fun shouldShowCompanionBarChart(chartResult: ChartResult): Boolean {
    return chartResult.type == ChartType.LINE && chartResult.data.size >= 2
}

fun extractChartData(markdown: String): ChartResult? {
    return extractChartResults(markdown).firstOrNull()
}

fun extractChartResults(markdown: String): List<ChartResult> {
    val tableResults = extractChartDataFromTables(markdown)
    if (tableResults.isNotEmpty()) return tableResults

    // Pattern: "X: 10", "Y: 20" type data (key-value pairs with numbers)
    val kvPattern = Regex("""[-•*]\s*\*{0,2}([A-Za-z\s&]+)\*{0,2}[:–-]+\s*\$?([\d,.]+)\s*(million|billion|thousand|k|m|b|t|%|percent)?\*{0,2}""", RegexOption.IGNORE_CASE)
    val matches = kvPattern.findAll(markdown).toList()
    if (matches.size >= 2) {
        val data = matches.mapIndexed { i, match ->
            val label = match.groupValues[1].trim().take(20)
            var value = match.groupValues[2].replace(",", "").toFloatOrNull() ?: 0f
            val multiplier = match.groupValues[3].lowercase()
            value *= when { "billion" in multiplier -> 1_000_000_000f; "million" in multiplier -> 1_000_000f; "thousand" in multiplier -> 1_000f; else -> 1f }
            ChartData(label, value, chartColors[i % chartColors.size])
        }
        val type = if (data.all { it.value <= 100f && it.value >= 0f } && matches.any { it.groupValues[3].contains("%", ignoreCase = true) }) ChartType.PIE else ChartType.BAR
        return listOf(ChartResult(data, type))
    }

    return emptyList()
}

private fun extractChartDataFromTables(markdown: String): List<ChartResult> {
    val tableBlocks = mutableListOf<List<String>>()
    val current = mutableListOf<String>()
    markdown.lines().forEach { line ->
        if (line.trim().startsWith("|") && line.contains("|")) {
            current += line
        } else if (current.isNotEmpty()) {
            tableBlocks += current.toList()
            current.clear()
        }
    }
    if (current.isNotEmpty()) tableBlocks += current.toList()

    return tableBlocks.mapNotNull { tableLines ->
        val rows = tableLines.mapNotNull { line ->
            val cells = line.trim().trim('|').split("|").map { it.trim() }
            if (cells.size < 2) return@mapNotNull null
            val label = cells[0]
            if (label.lowercase() in listOf("", "category", "item", "name", "label", "metric", "---", "year", "month", "quarter", "date", "period")) return@mapNotNull null
            val numStr = cells[1]
                .replace("$", "")
                .replace(",", "")
                .replace("%", "")
                .trim()
            val value = numStr.toFloatOrNull() ?: return@mapNotNull null
            ChartData(label.take(15), value)
        }
        if (rows.size >= 2) {
            val typedRows = rows.mapIndexed { i, d -> d.copy(color = chartColors[i % chartColors.size]) }
            val headers = tableLines.firstOrNull()
                ?.trim()
                ?.trim('|')
                ?.split("|")
                ?.map { it.trim() }
                .orEmpty()
            val timeHeaders = listOf("year", "month", "quarter", "date", "period", "time", "fiscal")
            // Metrics that the user prefers as BAR even when the X axis is
            // time: amounts you compare across periods (revenue, profit,
            // net income, earnings, sales, EPS, dividends). Stock price /
            // ratio metrics stay as LINE because they're continuous flows.
            val barMetricKeywords = listOf(
                "revenue", "revenues", "sales", "net sales", "turnover",
                "net income", "income", "profit", "profits", "earnings",
                "ebitda", "operating income", "operating profit",
                "gross profit", "free cash flow", "fcf", "eps",
                "dividend", "dividends"
            )
            val headerJoined = headers.joinToString(" ").lowercase()
            val isBarMetric = barMetricKeywords.any { headerJoined.contains(it) }
            val isTimeAxis = headers.any { h ->
                timeHeaders.any { h.lowercase().contains(it) }
            }
            val type = when {
                isTimeAxis && isBarMetric -> ChartType.BAR
                isTimeAxis -> ChartType.LINE
                rows.all { it.value <= 100f && it.value >= 0f } &&
                    tableLines.any { it.contains("%") } -> ChartType.PIE
                else -> ChartType.BAR
            }
            ChartResult(typedRows, type)
        } else {
            null
        }
    }
}

@Composable
fun InlineBarChart(
    data: List<ChartData>,
    modifier: Modifier = Modifier,
    title: String? = null
) {
    if (data.isEmpty()) return
    val maxVal = data.maxOf { it.value }.coerceAtLeast(1f)
    val barColors = listOf(
        Color(0xFF34C759), Color(0xFFFF6B6B), Color(0xFF6BD888),
        Color(0xFFFFCC00), Color(0xFFAF52DE), Color(0xFFFF9500)
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .semantics { contentDescription = "Inline bar chart" },
        shape = RoundedCornerShape(8.dp),
        color = Surface,
        tonalElevation = 0.dp
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            if (title != null) {
                Text(
                    text = title,
                    color = TextSecondary,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                )
                Spacer(modifier = Modifier.height(10.dp))
            }
            data.forEach { item ->
                val fraction = (item.value / maxVal).coerceIn(0.01f, 1f)
                val barColor = if (item.color != Color.Unspecified) item.color else barColors[data.indexOf(item) % barColors.size]
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = item.label,
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.width(72.dp),
                        maxLines = 1
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(20.dp)
                            .background(SurfaceVariant, RoundedCornerShape(4.dp))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(fraction)
                                .height(20.dp)
                                .background(barColor, RoundedCornerShape(4.dp))
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = formatNumber(item.value),
                        color = OnBackground,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.width(48.dp),
                        textAlign = TextAlign.End
                    )
                }
            }
        }
    }
}

@Composable
fun InlineLineChart(
    data: List<ChartData>,
    modifier: Modifier = Modifier,
    title: String? = null
) {
    if (data.size < 2) return
    val maxVal = data.maxOf { it.value }.coerceAtLeast(1f)
    val minVal = data.minOf { it.value }.coerceAtLeast(0f)
    val range = (maxVal - minVal).coerceAtLeast(1f)
    val lineColor = Color(0xFF6BD888)
    val pointColor = Color(0xFF34C759)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .semantics { contentDescription = "Inline line chart" },
        shape = RoundedCornerShape(8.dp),
        color = Surface,
        tonalElevation = 0.dp
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            if (title != null) {
                Text(
                    text = title,
                    color = TextSecondary,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                )
                Spacer(modifier = Modifier.height(10.dp))
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .padding(horizontal = 4.dp)
            ) {
                Canvas(modifier = Modifier.fillMaxWidth().height(160.dp)) {
                    val paddingX = 40f
                    val paddingY = 24f
                    val chartWidth = size.width - paddingX * 2
                    val chartHeight = size.height - paddingY * 2
                    val stepX = chartWidth / (data.size - 1).coerceAtLeast(1)

                    // Draw grid lines
                    for (i in 0..4) {
                        val y = paddingY + (chartHeight * i / 4f)
                        drawLine(
                            color = SurfaceVariant,
                            start = Offset(paddingX, y),
                            end = Offset(size.width - paddingX, y),
                            strokeWidth = 1f
                        )
                    }

                    // Build path
                    val path = Path()
                    val points = data.mapIndexed { index, item ->
                        val x = paddingX + stepX * index
                        val y = paddingY + chartHeight - ((item.value - minVal) / range) * chartHeight
                        Offset(x, y)
                    }
                    if (points.isNotEmpty()) {
                        path.moveTo(points.first().x, points.first().y)
                        for (i in 1 until points.size) {
                            path.lineTo(points[i].x, points[i].y)
                        }
                    }

                    // Draw line
                    drawPath(
                        path = path,
                        color = lineColor,
                        style = Stroke(width = 3f, cap = StrokeCap.Round)
                    )

                    // Draw points and labels
                    points.forEachIndexed { index, point ->
                        drawCircle(
                            color = pointColor,
                            radius = 5f,
                            center = point
                        )
                        // X label
                        val label = data[index].label
                        drawContext.canvas.nativeCanvas.drawText(
                            label,
                            point.x,
                            size.height - 4f,
                            android.graphics.Paint().apply {
                                color = android.graphics.Color.parseColor("#6E6E73")
                                textSize = 22f
                                textAlign = android.graphics.Paint.Align.CENTER
                            }
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                data.forEach { item ->
                    Text(
                        text = formatNumber(item.value),
                        color = OnBackground,
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.width(56.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun InlinePieChart(
    data: List<ChartData>,
    modifier: Modifier = Modifier,
    title: String? = null
) {
    if (data.isEmpty()) return
    val total = data.sumOf { it.value.toDouble() }.toFloat().coerceAtLeast(1f)
    val pieColors = chartColors

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .semantics { contentDescription = "Inline pie chart" },
        shape = RoundedCornerShape(8.dp),
        color = Surface,
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(100.dp),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.size(100.dp)) {
                    var startAngle = 0f
                    data.forEachIndexed { index, item ->
                        val sweep = (item.value / total) * 360f
                        val color = if (item.color != Color.Unspecified) item.color else pieColors[index % pieColors.size]
                        drawArc(
                            color = color,
                            startAngle = startAngle,
                            sweepAngle = sweep,
                            useCenter = true,
                            style = Fill
                        )
                        startAngle += sweep
                    }
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                if (title != null) {
                    Text(
                        text = title,
                        color = TextSecondary,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                }
                data.forEachIndexed { index, item ->
                    val percentage = ((item.value / total) * 100).let { "%d%%".format(it.toInt()) }
                    val color = if (item.color != Color.Unspecified) item.color else pieColors[index % pieColors.size]
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(color, CircleShape)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = item.label,
                            color = TextSecondary,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                            maxLines = 1
                        )
                        Text(
                            text = percentage,
                            color = OnBackground,
                            style = MaterialTheme.typography.labelSmall,
                            textAlign = TextAlign.End
                        )
                    }
                }
            }
        }
    }
}

private fun formatNumber(value: Float): String {
    return when {
        value >= 1_000_000_000f -> "%.1fB".format(value / 1_000_000_000f)
        value >= 1_000_000f -> "%.1fM".format(value / 1_000_000f)
        value >= 1_000f -> "%.1fK".format(value / 1_000f)
        value == value.toLong().toFloat() -> value.toLong().toString()
        else -> "%.1f".format(value)
    }
}
