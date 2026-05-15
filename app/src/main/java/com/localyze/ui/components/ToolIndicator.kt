package com.localyze.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import com.localyze.ui.theme.OnBackground
import com.localyze.ui.theme.Primary
import com.localyze.ui.theme.SurfaceVariant
import com.localyze.ui.theme.TextSecondary

/**
 * Pill showing tool usage in a Claude-Code-like style.
 *
 * - executing: spinner in primary color + a friendly label like "Searching the web…"
 * - completed: checkmark in primary color + "Used: <tool>" + optional one-line detail.
 */
@Composable
fun ToolIndicator(
    toolName: String,
    isExecuting: Boolean = true,
    detail: String? = null,
    modifier: Modifier = Modifier
) {
    val scale by animateFloatAsState(
        targetValue = 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "tool_scale"
    )

    val primaryLabel = if (isExecuting) executingLabel(toolName) else completedLabel(toolName)

    Box(
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceVariant.copy(alpha = 0.6f))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (isExecuting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 1.5.dp,
                    color = Primary
                )
            } else {
                Canvas(modifier = Modifier.size(12.dp)) {
                    val strokeWidth = 1.6.dp.toPx()
                    val w = size.width
                    val h = size.height
                    drawLine(
                        color = Primary,
                        start = Offset(w * 0.2f, h * 0.5f),
                        end = Offset(w * 0.45f, h * 0.75f),
                        strokeWidth = strokeWidth,
                        cap = StrokeCap.Round
                    )
                    drawLine(
                        color = Primary,
                        start = Offset(w * 0.45f, h * 0.75f),
                        end = Offset(w * 0.8f, h * 0.25f),
                        strokeWidth = strokeWidth,
                        cap = StrokeCap.Round
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = primaryLabel,
                    color = OnBackground,
                    style = MaterialTheme.typography.labelSmall
                )
                if (!detail.isNullOrBlank()) {
                    Text(
                        text = detail,
                        color = TextSecondary,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}

private fun executingLabel(toolName: String): String = when (toolName) {
    "web_search" -> "Searching the web…"
    "calculator" -> "Calculating…"
    "memory" -> "Checking memory…"
    "file_reader" -> "Reading file…"
    "calendar" -> "Reading calendar…"
    "contacts" -> "Looking up contact…"
    "alarm" -> "Setting alarm…"
    "clipboard" -> "Reading clipboard…"
    "system_info" -> "Reading system info…"
    "task" -> "Updating tasks…"
    "email_draft" -> "Drafting email…"
    "sms_draft" -> "Drafting SMS…"
    else -> "Calling $toolName…"
}

private fun completedLabel(toolName: String): String = when (toolName) {
    "web_search" -> "Web search complete"
    "calculator" -> "Calculated"
    "memory" -> "Memory checked"
    "file_reader" -> "File read"
    "calendar" -> "Calendar checked"
    "contacts" -> "Contact found"
    "alarm" -> "Alarm set"
    "clipboard" -> "Clipboard ready"
    "system_info" -> "System info read"
    "task" -> "Task updated"
    "email_draft" -> "Email drafted"
    "sms_draft" -> "SMS drafted"
    else -> "Used $toolName"
}
