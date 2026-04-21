package com.localyze.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.ui.platform.LocalDensity
import com.localyze.ui.theme.OnBackground
import com.localyze.ui.theme.Primary
import com.localyze.ui.theme.Surface
import com.localyze.ui.theme.SurfaceVariant

/**
 * Animated robot mascot drawn entirely with Compose Canvas DrawScope.
 *
 * @param isThinking When true, the mascot bobs up and down; when false, it has a subtle idle breathing animation.
 * @param modifier Optional modifier.
 */
@Composable
fun RobotMascot(
    isThinking: Boolean = false,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "mascot_transition")

    // Bob animation for thinking state: 4dp amplitude, ~1.5s period
    val thinkingBob by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = { it }),
            repeatMode = RepeatMode.Reverse
        ),
        label = "thinking_bob"
    )

    // Idle breathing animation: 1dp amplitude, ~3s period
    val idleBreath by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3000, easing = { it }),
            repeatMode = RepeatMode.Reverse
        ),
        label = "idle_breath"
    )

    // Blink animation: eyes close every ~3.5 seconds
    val blinkCycle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3500),
            repeatMode = RepeatMode.Restart
        ),
        label = "blink_cycle"
    )

    // Calculate vertical offset based on state (in composable scope, so LocalDensity is available)
    val density = LocalDensity.current
    val verticalOffsetPx = if (isThinking) {
        // Sinusoidal bob: 4dp amplitude
        val sinValue = kotlin.math.sin(thinkingBob * Math.PI).toFloat()
        sinValue * with(density) { 4.dp.toPx() }
    } else {
        // Subtle breathing: 1dp amplitude
        val sinValue = kotlin.math.sin(idleBreath * Math.PI).toFloat()
        sinValue * with(density) { 1.dp.toPx() }
    }

    // Calculate blink: close eyes briefly near the end of each cycle
    // Eyes are open for most of the cycle, close briefly (last ~6% of cycle = ~200ms)
    val blinkOpenFraction = if (blinkCycle > 0.94f) {
        // During blink: interpolate from 1 (open) to 0 (closed) and back
        val blinkProgress = (blinkCycle - 0.94f) / 0.06f // 0..1 during blink
        if (blinkProgress < 0.5f) {
            1f - (blinkProgress * 2f) // closing
        } else {
            (blinkProgress - 0.5f) * 2f // opening
        }
    } else {
        1f // eyes open
    }

    Canvas(
        modifier = modifier.size(200.dp)
    ) {
        val canvasWidth = size.width
        val canvasHeight = size.height

        // Center the mascot in the canvas
        // DrawScope implements Density, so dp.toPx() is directly available
        val bodyWidth = 120.dp.toPx()
        val bodyHeight = 140.dp.toPx()
        val cornerRadius = 32.dp.toPx()

        val bodyLeft = (canvasWidth - bodyWidth) / 2f
        val bodyTop = (canvasHeight - bodyHeight) / 2f + verticalOffsetPx

        // â”€â”€ Ears â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        drawEars(bodyLeft, bodyTop, bodyWidth, bodyHeight)

        // â”€â”€ Antenna â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        drawAntenna(bodyLeft, bodyTop, bodyWidth)

        // â”€â”€ Body â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        drawBody(bodyLeft, bodyTop, bodyWidth, bodyHeight, cornerRadius)

        // â”€â”€ Eyes â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        drawEyes(bodyLeft, bodyTop, bodyWidth, bodyHeight, blinkOpenFraction)

        // â”€â”€ Smile â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        drawSmile(bodyLeft, bodyTop, bodyWidth, bodyHeight)
    }
}

private fun DrawScope.drawEars(
    bodyLeft: Float,
    bodyTop: Float,
    bodyWidth: Float,
    bodyHeight: Float
) {
    val earWidth = 16.dp.toPx()
    val earHeight = 24.dp.toPx()
    val bodyCenterY = bodyTop + bodyHeight * 0.35f

    // Left ear
    drawOval(
        color = SurfaceVariant,
        topLeft = Offset(bodyLeft - earWidth * 0.6f, bodyCenterY - earHeight / 2f),
        size = Size(earWidth, earHeight)
    )

    // Right ear
    drawOval(
        color = SurfaceVariant,
        topLeft = Offset(bodyLeft + bodyWidth - earWidth * 0.4f, bodyCenterY - earHeight / 2f),
        size = Size(earWidth, earHeight)
    )
}

private fun DrawScope.drawAntenna(
    bodyLeft: Float,
    bodyTop: Float,
    bodyWidth: Float
) {
    val antennaBaseX = bodyLeft + bodyWidth / 2f
    val antennaTopY = bodyTop - 28.dp.toPx()
    val antennaBallRadius = 6.dp.toPx()
    val antennaStickTop = antennaTopY + antennaBallRadius

    // Antenna stick (line from top of body to ball)
    drawLine(
        color = Primary,
        start = Offset(antennaBaseX, bodyTop),
        end = Offset(antennaBaseX, antennaStickTop),
        strokeWidth = 2.dp.toPx(),
        cap = StrokeCap.Round
    )

    // Antenna ball
    drawCircle(
        color = Primary,
        radius = antennaBallRadius,
        center = Offset(antennaBaseX, antennaTopY)
    )
}

private fun DrawScope.drawBody(
    bodyLeft: Float,
    bodyTop: Float,
    bodyWidth: Float,
    bodyHeight: Float,
    cornerRadius: Float
) {
    // Body fill
    drawRoundRect(
        color = Surface,
        topLeft = Offset(bodyLeft, bodyTop),
        size = Size(bodyWidth, bodyHeight),
        cornerRadius = CornerRadius(cornerRadius, cornerRadius)
    )

    // Body border (warm subtle border)
    drawRoundRect(
        color = SurfaceVariant,
        topLeft = Offset(bodyLeft, bodyTop),
        size = Size(bodyWidth, bodyHeight),
        cornerRadius = CornerRadius(cornerRadius, cornerRadius),
        style = Stroke(width = 2.dp.toPx())
    )
}

private fun DrawScope.drawEyes(
    bodyLeft: Float,
    bodyTop: Float,
    bodyWidth: Float,
    bodyHeight: Float,
    blinkOpenFraction: Float
) {
    val eyeRadius = 8.dp.toPx()
    val eyeSpacing = 20.dp.toPx()
    val eyeCenterY = bodyTop + bodyHeight * 0.35f
    val bodyCenterX = bodyLeft + bodyWidth / 2f

    val leftEyeCenter = Offset(bodyCenterX - eyeSpacing, eyeCenterY)
    val rightEyeCenter = Offset(bodyCenterX + eyeSpacing, eyeCenterY)

    if (blinkOpenFraction > 0.15f) {
        // Eyes partially or fully open â€” draw as ovals with height scaled by blink fraction
        val eyeHeight = eyeRadius * 2f * blinkOpenFraction
        val eyeWidth = eyeRadius * 2f

        // Left eye
        drawOval(
            color = OnBackground,
            topLeft = Offset(
                leftEyeCenter.x - eyeWidth / 2f,
                leftEyeCenter.y - eyeHeight / 2f
            ),
            size = Size(eyeWidth, eyeHeight)
        )

        // Right eye
        drawOval(
            color = OnBackground,
            topLeft = Offset(
                rightEyeCenter.x - eyeWidth / 2f,
                rightEyeCenter.y - eyeHeight / 2f
            ),
            size = Size(eyeWidth, eyeHeight)
        )
    } else {
        // Eyes fully closed â€” draw as thin horizontal lines
        val lineHalfWidth = eyeRadius * 0.8f
        val lineStrokeWidth = 2.dp.toPx()

        drawLine(
            color = OnBackground,
            start = Offset(leftEyeCenter.x - lineHalfWidth, leftEyeCenter.y),
            end = Offset(leftEyeCenter.x + lineHalfWidth, leftEyeCenter.y),
            strokeWidth = lineStrokeWidth,
            cap = StrokeCap.Round
        )

        drawLine(
            color = OnBackground,
            start = Offset(rightEyeCenter.x - lineHalfWidth, rightEyeCenter.y),
            end = Offset(rightEyeCenter.x + lineHalfWidth, rightEyeCenter.y),
            strokeWidth = lineStrokeWidth,
            cap = StrokeCap.Round
        )
    }
}

private fun DrawScope.drawSmile(
    bodyLeft: Float,
    bodyTop: Float,
    bodyWidth: Float,
    bodyHeight: Float
) {
    val bodyCenterX = bodyLeft + bodyWidth / 2f
    val smileCenterY = bodyTop + bodyHeight * 0.58f
    val smileWidth = 24.dp.toPx()
    val smileHeight = 10.dp.toPx()

    val smilePath = Path().apply {
        // Draw an arc for the smile
        val oval = androidx.compose.ui.geometry.Rect(
            left = bodyCenterX - smileWidth,
            top = smileCenterY - smileHeight,
            right = bodyCenterX + smileWidth,
            bottom = smileCenterY + smileHeight
        )
        addArc(
            oval,
            20f,
            140f
        )
    }

    drawPath(
        path = smilePath,
        color = Primary,
        style = Stroke(
            width = 2.5.dp.toPx(),
            cap = StrokeCap.Round
        )
    )
}