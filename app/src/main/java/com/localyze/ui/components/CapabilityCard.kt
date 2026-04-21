package com.localyze.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.localyze.ui.theme.OnBackground
import com.localyze.ui.theme.Surface
import com.localyze.ui.theme.SurfaceVariant
import com.localyze.ui.theme.TextSecondary

/**
 * Grid card for the capabilities screen.
 *
 * Features a Canvas-drawn icon based on the capability type, with the specified pastel tint color.
 * Spring animation on click (scale down to 0.95f then back).
 */
@Composable
fun CapabilityCard(
    title: String,
    description: String,
    iconTint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessHigh
        ),
        label = "card_scale"
    )

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = Surface
        ),
        border = BorderStroke(1.dp, SurfaceVariant),
        modifier = modifier
            .scale(scale)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(16.dp)
        ) {
            // Icon drawn with Canvas
            Canvas(modifier = Modifier.size(48.dp)) {
                drawCapabilityIcon(title, iconTint)
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = title,
                color = OnBackground,
                style = MaterialTheme.typography.titleSmall
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = description,
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

/**
 * Dispatches to the correct icon drawing function based on the capability title.
 */
private fun DrawScope.drawCapabilityIcon(title: String, tint: Color) {
    when (title) {
        "Chat" -> drawSpeechBubbleIcon(tint)
        "See & Understand" -> drawEyeIcon(tint)
        "Write & Draft" -> drawDocumentIcon(tint)
        "Brainstorm" -> drawLightbulbIcon(tint)
        "Code Help" -> drawCodeBracketsIcon(tint)
        "Data & Charts" -> drawChartIcon(tint)
        "Texts & Email" -> drawDocumentIcon(tint)
        else -> drawSpeechBubbleIcon(tint) // fallback
    }
}

/**
 * Speech bubble: rounded rectangle with triangle pointer at bottom-left.
 */
private fun DrawScope.drawSpeechBubbleIcon(color: Color) {
    val width = size.width
    val height = size.height
    val padding = width * 0.1f
    val cornerRadius = width * 0.15f

    // Bubble body
    val bubbleLeft = padding
    val bubbleTop = padding
    val bubbleWidth = width - padding * 2f
    val bubbleHeight = height * 0.65f

    drawRoundRect(
        color = color,
        topLeft = Offset(bubbleLeft, bubbleTop),
        size = Size(bubbleWidth, bubbleHeight),
        cornerRadius = CornerRadius(cornerRadius, cornerRadius)
    )

    // Triangle pointer at bottom-left
    val path = Path().apply {
        moveTo(bubbleLeft + bubbleWidth * 0.15f, bubbleTop + bubbleHeight)
        lineTo(bubbleLeft + bubbleWidth * 0.05f, bubbleTop + bubbleHeight + height * 0.2f)
        lineTo(bubbleLeft + bubbleWidth * 0.35f, bubbleTop + bubbleHeight)
        close()
    }
    drawPath(path = path, color = color)
}

/**
 * Eye: oval with circle pupil.
 */
private fun DrawScope.drawEyeIcon(color: Color) {
    val width = size.width
    val height = size.height
    val centerX = width / 2f
    val centerY = height / 2f

    // Outer eye shape (almond/oval)
    drawOval(
        color = color,
        topLeft = Offset(width * 0.1f, height * 0.25f),
        size = Size(width * 0.8f, height * 0.5f)
    )

    // Pupil (circle in center)
    drawCircle(
        color = color.copy(alpha = 0.6f),
        radius = width * 0.15f,
        center = Offset(centerX, centerY)
    )

    // Inner pupil highlight
    drawCircle(
        color = Color.White.copy(alpha = 0.7f),
        radius = width * 0.06f,
        center = Offset(centerX - width * 0.04f, centerY - height * 0.04f)
    )
}

/**
 * Document: rectangle with folded corner and lines.
 */
private fun DrawScope.drawDocumentIcon(color: Color) {
    val width = size.width
    val height = size.height
    val padding = width * 0.15f
    val foldSize = width * 0.2f

    // Document body
    val docLeft = padding
    val docTop = padding
    val docWidth = width - padding * 2f
    val docHeight = height - padding * 2f

    // Main rectangle (without folded corner)
    val bodyPath = Path().apply {
        moveTo(docLeft, docTop)
        lineTo(docLeft + docWidth - foldSize, docTop)
        lineTo(docLeft + docWidth, docTop + foldSize)
        lineTo(docLeft + docWidth, docTop + docHeight)
        lineTo(docLeft, docTop + docHeight)
        close()
    }
    drawPath(path = bodyPath, color = color)

    // Folded corner
    val foldPath = Path().apply {
        moveTo(docLeft + docWidth - foldSize, docTop)
        lineTo(docLeft + docWidth - foldSize, docTop + foldSize)
        lineTo(docLeft + docWidth, docTop + foldSize)
        close()
    }
    drawPath(path = foldPath, color = color.copy(alpha = 0.6f))

    // Lines on document
    val lineStartX = docLeft + docWidth * 0.15f
    val lineEndX = docLeft + docWidth * 0.75f
    val lineY1 = docTop + docHeight * 0.35f
    val lineY2 = docTop + docHeight * 0.5f
    val lineY3 = docTop + docHeight * 0.65f
    val lineStroke = 1.5.dp.toPx()

    drawLine(
        color = Color.White.copy(alpha = 0.7f),
        start = Offset(lineStartX, lineY1),
        end = Offset(lineEndX, lineY1),
        strokeWidth = lineStroke,
        cap = StrokeCap.Round
    )
    drawLine(
        color = Color.White.copy(alpha = 0.7f),
        start = Offset(lineStartX, lineY2),
        end = Offset(lineEndX * 0.85f, lineY2),
        strokeWidth = lineStroke,
        cap = StrokeCap.Round
    )
    drawLine(
        color = Color.White.copy(alpha = 0.7f),
        start = Offset(lineStartX, lineY3),
        end = Offset(lineEndX * 0.7f, lineY3),
        strokeWidth = lineStroke,
        cap = StrokeCap.Round
    )
}

/**
 * Lightbulb: circle on top, trapezoid base.
 */
private fun DrawScope.drawLightbulbIcon(color: Color) {
    val width = size.width
    val height = size.height
    val centerX = width / 2f

    // Bulb (circle)
    val bulbRadius = width * 0.28f
    val bulbCenterY = height * 0.35f

    drawCircle(
        color = color,
        radius = bulbRadius,
        center = Offset(centerX, bulbCenterY)
    )

    // Glow rays
    val rayLength = width * 0.08f
    val rayStroke = 1.5.dp.toPx()
    val rayColor = color.copy(alpha = 0.5f)

    // Top ray
    drawLine(
        color = rayColor,
        start = Offset(centerX, bulbCenterY - bulbRadius - rayLength * 0.5f),
        end = Offset(centerX, bulbCenterY - bulbRadius - rayLength * 1.5f),
        strokeWidth = rayStroke,
        cap = StrokeCap.Round
    )
    // Left ray
    drawLine(
        color = rayColor,
        start = Offset(centerX - bulbRadius - rayLength * 0.3f, bulbCenterY - bulbRadius * 0.3f),
        end = Offset(centerX - bulbRadius - rayLength * 1.3f, bulbCenterY - bulbRadius * 0.7f),
        strokeWidth = rayStroke,
        cap = StrokeCap.Round
    )
    // Right ray
    drawLine(
        color = rayColor,
        start = Offset(centerX + bulbRadius + rayLength * 0.3f, bulbCenterY - bulbRadius * 0.3f),
        end = Offset(centerX + bulbRadius + rayLength * 1.3f, bulbCenterY - bulbRadius * 0.7f),
        strokeWidth = rayStroke,
        cap = StrokeCap.Round
    )

    // Base (trapezoid)
    val baseTop = bulbCenterY + bulbRadius * 0.7f
    val baseHeight = height * 0.15f
    val baseTopWidth = width * 0.25f
    val baseBottomWidth = width * 0.18f

    val basePath = Path().apply {
        moveTo(centerX - baseTopWidth / 2f, baseTop)
        lineTo(centerX + baseTopWidth / 2f, baseTop)
        lineTo(centerX + baseBottomWidth / 2f, baseTop + baseHeight)
        lineTo(centerX - baseBottomWidth / 2f, baseTop + baseHeight)
        close()
    }
    drawPath(path = basePath, color = color.copy(alpha = 0.7f))

    // Bottom lines (screw base)
    val lineY1 = baseTop + baseHeight + height * 0.02f
    val lineY2 = lineY1 + height * 0.04f
    val lineWidth = baseBottomWidth * 0.9f
    val lineStroke = 1.5.dp.toPx()

    drawLine(
        color = color.copy(alpha = 0.5f),
        start = Offset(centerX - lineWidth / 2f, lineY1),
        end = Offset(centerX + lineWidth / 2f, lineY1),
        strokeWidth = lineStroke,
        cap = StrokeCap.Round
    )
    drawLine(
        color = color.copy(alpha = 0.5f),
        start = Offset(centerX - lineWidth / 2f, lineY2),
        end = Offset(centerX + lineWidth / 2f, lineY2),
        strokeWidth = lineStroke,
        cap = StrokeCap.Round
    )
}

/**
 * Code brackets: < and > angle shapes.
 */
private fun DrawScope.drawCodeBracketsIcon(color: Color) {
    val width = size.width
    val height = size.height
    val centerX = width / 2f
    val centerY = height / 2f
    val strokeWidth = 2.5.dp.toPx()
    val bracketHeight = height * 0.5f

    // Left angle bracket <
    drawLine(
        color = color,
        start = Offset(centerX - width * 0.15f, centerY),
        end = Offset(centerX - width * 0.3f, centerY - bracketHeight / 2f),
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round
    )
    drawLine(
        color = color,
        start = Offset(centerX - width * 0.15f, centerY),
        end = Offset(centerX - width * 0.3f, centerY + bracketHeight / 2f),
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round
    )

    // Right angle bracket >
    drawLine(
        color = color,
        start = Offset(centerX + width * 0.15f, centerY),
        end = Offset(centerX + width * 0.3f, centerY - bracketHeight / 2f),
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round
    )
    drawLine(
        color = color,
        start = Offset(centerX + width * 0.15f, centerY),
        end = Offset(centerX + width * 0.3f, centerY + bracketHeight / 2f),
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round
    )

    // Slash / in the middle
    drawLine(
        color = color.copy(alpha = 0.5f),
        start = Offset(centerX - width * 0.05f, centerY + bracketHeight * 0.3f),
        end = Offset(centerX + width * 0.05f, centerY - bracketHeight * 0.3f),
        strokeWidth = strokeWidth * 0.7f,
        cap = StrokeCap.Round
    )
}

/**
 * Chart: 3 vertical bars of different heights.
 */
private fun DrawScope.drawChartIcon(color: Color) {
    val width = size.width
    val height = size.height
    val padding = width * 0.15f
    val barWidth = width * 0.18f
    val barSpacing = width * 0.08f
    val maxBarHeight = height * 0.6f
    val baseY = height - padding

    val barHeights = listOf(0.5f, 1.0f, 0.7f)
    val totalBarsWidth = barWidth * 3f + barSpacing * 2f
    val startX = (width - totalBarsWidth) / 2f

    for (i in barHeights.indices) {
        val barHeight = maxBarHeight * barHeights[i]
        val barLeft = startX + i * (barWidth + barSpacing)
        val barTop = baseY - barHeight

        drawRoundRect(
            color = color,
            topLeft = Offset(barLeft, barTop),
            size = Size(barWidth, barHeight),
            cornerRadius = CornerRadius(barWidth * 0.3f, barWidth * 0.3f)
        )
    }

    // Baseline
    drawLine(
        color = color.copy(alpha = 0.4f),
        start = Offset(padding, baseY),
        end = Offset(width - padding, baseY),
        strokeWidth = 1.5.dp.toPx(),
        cap = StrokeCap.Round
    )
}
