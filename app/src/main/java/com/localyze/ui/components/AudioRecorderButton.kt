package com.localyze.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.localyze.ai.AudioRecordingState
import com.localyze.ui.theme.Error
import com.localyze.ui.theme.OnPrimary
import com.localyze.ui.theme.Primary
import com.localyze.ui.theme.SurfaceVariant
import com.localyze.ui.theme.TextSecondary

@Composable
fun AudioRecorderButton(
    recordingState: AudioRecordingState,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onCancelRecording: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hapticFeedback = LocalHapticFeedback.current
    val isRecording = recordingState is AudioRecordingState.Recording
    val isReady = recordingState is AudioRecordingState.Ready
    val isError = recordingState is AudioRecordingState.Error

    val pulseTransition = rememberInfiniteTransition(label = "voice_pulse")
    val pulseScale by pulseTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.22f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "voice_pulse_scale"
    )
    val pulseAlpha by pulseTransition.animateFloat(
        initialValue = 0.28f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "voice_pulse_alpha"
    )

    Box(
        modifier = modifier.size(48.dp),
        contentAlignment = Alignment.Center
    ) {
        if (isRecording) {
            Surface(
                modifier = Modifier
                    .size(48.dp)
                    .scale(pulseScale),
                shape = CircleShape,
                color = Error.copy(alpha = pulseAlpha)
            ) {}
        }

        Surface(
            modifier = Modifier.size(44.dp),
            shape = CircleShape,
            color = when {
                isRecording -> Error
                isReady -> Color(0xFF16A34A)
                isError -> SurfaceVariant
                else -> Primary
            }
        ) {
            IconButton(
                modifier = Modifier.size(44.dp),
                colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Transparent),
                onClick = {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    when (recordingState) {
                        is AudioRecordingState.Idle -> onStartRecording()
                        is AudioRecordingState.Recording -> onStopRecording()
                        is AudioRecordingState.Error -> onStartRecording()
                        is AudioRecordingState.Ready -> onCancelRecording()
                    }
                }
            ) {
                when (recordingState) {
                    is AudioRecordingState.Recording -> Icon(
                        imageVector = Icons.Filled.Stop,
                        contentDescription = "Stop recording",
                        tint = OnPrimary,
                        modifier = Modifier.size(22.dp)
                    )
                    is AudioRecordingState.Ready -> Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = "Recording ready",
                        tint = OnPrimary,
                        modifier = Modifier.size(22.dp)
                    )
                    is AudioRecordingState.Error -> Icon(
                        imageVector = Icons.Filled.Mic,
                        contentDescription = "Retry recording",
                        tint = TextSecondary,
                        modifier = Modifier.size(22.dp)
                    )
                    is AudioRecordingState.Idle -> Icon(
                        imageVector = Icons.Filled.Mic,
                        contentDescription = "Start recording",
                        tint = OnPrimary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}
