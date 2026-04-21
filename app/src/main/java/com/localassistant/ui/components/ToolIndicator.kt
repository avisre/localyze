package com.localassistant.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import com.localassistant.ui.theme.OnBackground
import com.localassistant.ui.theme.PastelBlue

/**
 * Animated pill showing tool usage.
 *
 * When isExecuting=true: animated "Calling [tool name]..." with a tiny spinning indicator.
 * When isExecuting=false: static "Used: [tool name]" with a checkmark.
 * Pill background: pastel blue with low alpha, rounded corners 16dp.
 * Subtle scale-in animation when appearing.
 */
@Composable
fun ToolIndicator(
    toolName: String,
    isExecuting: Boolean = true,
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

    Box(
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(16.dp))
            .background(PastelBlue.copy(alpha = 0.3f))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (isExecuting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 1.5.dp,
                    color = OnBackground
                )
                Text(
                    text = "Calling $toolName...",
                    color = OnBackground,
                    style = MaterialTheme.typography.labelSmall
                )
            } else {
                // Checkmark drawn with Canvas
                Canvas(modifier = Modifier.size(12.dp)) {
                    val strokeWidth = 1.5.dp.toPx()
                    val width = size.width
                    val height = size.height

                    drawLine(
                        color = OnBackground,
                        start = Offset(width * 0.2f, height * 0.5f),
                        end = Offset(width * 0.45f, height * 0.75f),
                        strokeWidth = strokeWidth,
                        cap = StrokeCap.Round
                    )
                    drawLine(
                        color = OnBackground,
                        start = Offset(width * 0.45f, height * 0.75f),
                        end = Offset(width * 0.8f, height * 0.25f),
                        strokeWidth = strokeWidth,
                        cap = StrokeCap.Round
                    )
                }
                Text(
                    text = "Used: $toolName",
                    color = OnBackground,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}