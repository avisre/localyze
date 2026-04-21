package com.localassistant.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import com.localassistant.ui.theme.Primary
import com.localassistant.ui.theme.PrimaryVariant

/**
 * Waveform visualizer for audio recording.
 *
 * Draws a series of vertical bars representing audio amplitude.
 * The number of bars adapts to the available width so it never bleeds into
 * neighboring controls in compact rows.
 */
@Composable
fun AudioWaveform(
    amplitudes: List<Float>,
    modifier: Modifier = Modifier
) {
    val maxBars = 40
    val barWidth = 3.dp
    val barSpacing = 2.dp
    val maxHeight = 48.dp
    val minHeight = 4.dp

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(maxHeight)
    ) {
        val barWidthPx = barWidth.toPx()
        val barSpacingPx = barSpacing.toPx()
        val maxHeightPx = maxHeight.toPx()
        val minHeightPx = minHeight.toPx()
        val totalBarWidth = barWidthPx + barSpacingPx

        val visibleBars = ((size.width + barSpacingPx) / totalBarWidth)
            .toInt()
            .coerceIn(1, maxBars)
        val displayedAmplitudes = amplitudes.takeLast(visibleBars)
        val totalWidth = totalBarWidth * visibleBars - barSpacingPx
        val startX = ((size.width - totalWidth) / 2f).coerceAtLeast(0f)

        val gradientBrush = Brush.verticalGradient(
            colors = listOf(PrimaryVariant, Primary),
            startY = 0f,
            endY = maxHeightPx
        )

        for (i in 0 until visibleBars) {
            val amplitude = if (i < displayedAmplitudes.size) {
                displayedAmplitudes[i].coerceIn(0f, 1f)
            } else {
                0.05f // minimal bar for empty slots
            }

            val barHeight = minHeightPx + (maxHeightPx - minHeightPx) * amplitude
            val barStartX = startX + i * totalBarWidth
            val barStartY = (size.height - barHeight) / 2f

            drawRoundRect(
                brush = gradientBrush,
                topLeft = Offset(barStartX, barStartY),
                size = Size(barWidthPx, barHeight),
                cornerRadius = CornerRadius(barWidthPx / 2f, barWidthPx / 2f)
            )
        }
    }
}
