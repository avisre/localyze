package com.localyze.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.localyze.ui.theme.OnBackground
import com.localyze.ui.theme.SurfaceVariant
import com.localyze.ui.theme.TextSecondary

/**
 * Collapsible thinking trace display.
 *
 * When collapsed: shows "Thinking..." pill with chevron.
 * When expanded: shows the full thinking text in italic, muted brown text.
 * Background: slightly different shade of cream, subtle left border accent in dusty blue.
 */
@Composable
fun ThinkingBubble(
    thinkingContent: String,
    isExpanded: Boolean = false,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val chevronRotation by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        label = "chevron_rotation"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        // Header pill
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(SurfaceVariant.copy(alpha = 0.5f))
                .clickable { onToggle() }
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            // Sparkle/brain icon drawn with Canvas
            Canvas(modifier = Modifier.size(14.dp)) {
                drawSparkleIcon(OnBackground)
            }

            Text(
                text = "Thinking...",
                color = OnBackground,
                style = MaterialTheme.typography.labelMedium
            )

            // Chevron
            Canvas(modifier = Modifier.size(12.dp).rotate(chevronRotation)) {
                drawChevron(OnBackground)
            }
        }

        // Expandable content
        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, start = 8.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                        shape = RoundedCornerShape(8.dp)
                    )
                    .border(
                        width = 1.dp,
                        color = SurfaceVariant.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(8.dp)
                    )
                    .padding(12.dp)
            ) {
                Text(
                    text = thinkingContent,
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontStyle = FontStyle.Italic
                    )
                )
            }
        }
    }
}

/**
 * Draws a simple sparkle/lightning icon.
 */
private fun DrawScope.drawSparkleIcon(color: Color) {
    val size = this.size
    val center = Offset(size.width / 2f, size.height / 2f)

    // Simple 4-point sparkle
    val armLength = size.width * 0.4f

    // Vertical line
    drawLine(
        color = color,
        start = Offset(center.x, center.y - armLength),
        end = Offset(center.x, center.y + armLength),
        strokeWidth = 1.5.dp.toPx(),
        cap = StrokeCap.Round
    )

    // Horizontal line
    drawLine(
        color = color,
        start = Offset(center.x - armLength, center.y),
        end = Offset(center.x + armLength, center.y),
        strokeWidth = 1.5.dp.toPx(),
        cap = StrokeCap.Round
    )

    // Diagonal lines (shorter)
    val diagLength = armLength * 0.5f
    drawLine(
        color = color,
        start = Offset(center.x - diagLength, center.y - diagLength),
        end = Offset(center.x + diagLength, center.y + diagLength),
        strokeWidth = 1.dp.toPx(),
        cap = StrokeCap.Round
    )
    drawLine(
        color = color,
        start = Offset(center.x + diagLength, center.y - diagLength),
        end = Offset(center.x - diagLength, center.y + diagLength),
        strokeWidth = 1.dp.toPx(),
        cap = StrokeCap.Round
    )
}

/**
 * Draws a simple chevron (down-pointing arrow).
 */
private fun DrawScope.drawChevron(color: Color) {
    val size = this.size
    val strokeWidth = 1.5.dp.toPx()

    drawLine(
        color = color,
        start = Offset(size.width * 0.25f, size.height * 0.35f),
        end = Offset(size.width * 0.5f, size.height * 0.65f),
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round
    )
    drawLine(
        color = color,
        start = Offset(size.width * 0.5f, size.height * 0.65f),
        end = Offset(size.width * 0.75f, size.height * 0.35f),
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round
    )
}