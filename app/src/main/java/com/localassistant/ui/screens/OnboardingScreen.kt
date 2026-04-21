package com.localassistant.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.localassistant.data.repository.DownloadProgress
import com.localassistant.ui.components.RobotMascot
import com.localassistant.ui.theme.Background
import com.localassistant.ui.theme.OnBackground
import com.localassistant.ui.theme.OnPrimary
import com.localassistant.ui.theme.PastelBlue
import com.localassistant.ui.theme.PastelGreen
import com.localassistant.ui.theme.PastelOrange
import com.localassistant.ui.theme.PastelPurple
import com.localassistant.ui.theme.Primary
import com.localassistant.ui.theme.PrimaryVariant
import com.localassistant.ui.theme.Surface
import com.localassistant.ui.theme.SurfaceVariant
import com.localassistant.ui.theme.TextSecondary
import com.localassistant.ui.viewmodels.OnboardingUiState
import com.localassistant.ui.viewmodels.OnboardingViewModel

@Composable
fun OnboardingScreen(
    onModelReady: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Surface(
        color = Background,
        modifier = Modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 400.dp)
                    .padding(horizontal = 24.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // State-driven content with animated transitions
                when (val state = uiState) {
                    is OnboardingUiState.Welcome -> {
                        WelcomeContent(
                            onGetStarted = { viewModel.checkPrerequisites() }
                        )
                    }
                    is OnboardingUiState.CheckingModel -> {
                        CheckingContent(isChecking = state.isChecking)
                    }
                    is OnboardingUiState.ReadyToDownload -> {
                        ReadyToDownloadContent(
                            onStartDownload = { viewModel.checkNetworkAndStartDownload() }
                        )
                    }
                    is OnboardingUiState.NetworkWarning -> {
                        NetworkWarningContent(
                            networkType = state.networkType,
                            dataSize = state.dataSize,
                            onConfirmDownload = { viewModel.confirmCellularDownload() },
                            onCancel = { viewModel.navigateBack() }
                        )
                    }
                    is OnboardingUiState.Downloading -> {
                        DownloadingContent(
                            progress = state.progress,
                            onCancel = { viewModel.cancelDownload() }
                        )
                    }
                    is OnboardingUiState.Verifying -> {
                        VerifyingContent(percent = state.percent)
                    }
                    is OnboardingUiState.ReadyToChat -> {
                        ReadyToChatContent(
                            onStartChatting = onModelReady
                        )
                    }
                    is OnboardingUiState.Error -> {
                        ErrorContent(
                            message = state.message,
                            isRetryable = state.isRetryable,
                            onRetry = { viewModel.retryDownload() },
                            onGoBack = { viewModel.navigateBack() }
                        )
                    }
                    is OnboardingUiState.InsufficientRam -> {
                        InsufficientRamContent(
                            onContinueAnyway = { viewModel.continueWithInsufficientRam() },
                            onGoBack = { viewModel.navigateBack() }
                        )
                    }
                    is OnboardingUiState.InsufficientStorage -> {
                        InsufficientStorageContent(
                            onGoBack = { viewModel.dismissStorageError() }
                        )
                    }
                }
            }
        }
    }
}

// ── Welcome State ──────────────────────────────────────────────────────────────

@Composable
private fun WelcomeContent(onGetStarted: () -> Unit) {
    AnimatedVisibility(
        visible = true,
        enter = fadeIn() + slideInVertically(),
        exit = fadeOut() + slideOutVertically()
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Robot mascot
            RobotMascot(
                isThinking = false,
                modifier = Modifier.size(200.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Title
            Text(
                text = "Localyze",
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 32.sp
                ),
                color = OnBackground
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Subtitle
            Text(
                text = "Your private AI, running entirely on your device",
                style = MaterialTheme.typography.bodyLarge,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Feature cards
            FeatureCard(
                emoji = "🔒",
                title = "Private by Design",
                description = "Your data never leaves your device"
            )

            Spacer(modifier = Modifier.height(12.dp))

            FeatureCard(
                emoji = "⚡",
                title = "Lightning Fast",
                description = "No internet needed for AI responses"
            )

            Spacer(modifier = Modifier.height(12.dp))

            FeatureCard(
                emoji = "🧠",
                title = "Gemma 4 E4B",
                description = "Google's latest on-device AI model"
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Get Started button
            Button(
                onClick = onGetStarted,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Primary,
                    contentColor = OnPrimary
                )
            ) {
                Text(
                    text = "Get Started",
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = FontWeight.SemiBold
                    )
                )
            }
        }
    }
}

@Composable
private fun FeatureCard(
    emoji: String,
    title: String,
    description: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Surface
        ),
        border = CardDefaults.outlinedCardBorder(enabled = true).copy(
            brush = androidx.compose.ui.graphics.SolidColor(SurfaceVariant)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Emoji icon in a pastel circle
            Surface(
                modifier = Modifier.size(44.dp),
                shape = RoundedCornerShape(12.dp),
                color = when (emoji) {
                    "🔒" -> PastelBlue
                    "⚡" -> PastelOrange
                    "🧠" -> PastelPurple
                    else -> PastelGreen
                }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = emoji,
                        fontSize = 20.sp
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = OnBackground
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        }
    }
}

// ── Checking / Prerequisites State ──────────────────────────────────────────────

@Composable
private fun CheckingContent(isChecking: Boolean) {
    AnimatedVisibility(
        visible = true,
        enter = fadeIn() + slideInVertically(),
        exit = fadeOut() + slideOutVertically()
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(48.dp),
                color = Primary,
                strokeWidth = 4.dp
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Checking your device...",
                style = MaterialTheme.typography.titleMedium,
                color = OnBackground
            )
        }
    }
}

// ── Ready to Download State ─────────────────────────────────────────────────────

@Composable
private fun ReadyToDownloadContent(onStartDownload: () -> Unit) {
    AnimatedVisibility(
        visible = true,
        enter = fadeIn() + slideInVertically(),
        exit = fadeOut() + slideOutVertically()
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            RobotMascot(
                isThinking = false,
                modifier = Modifier.size(160.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Ready to Download",
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.Bold
                ),
                color = OnBackground
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "The Gemma 4 E4B model needs to be downloaded to your device.\n" +
                        "This is approximately 3.6 GB and requires a stable Wi-Fi connection.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = onStartDownload,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Primary,
                    contentColor = OnPrimary
                )
            ) {
                Text(
                    text = "Download Model",
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = FontWeight.SemiBold
                    )
                )
            }
        }
    }
}

// ── Network Warning State ────────────────────────────────────────────────────────

@Composable
private fun NetworkWarningContent(
    networkType: String,
    dataSize: String,
    onConfirmDownload: () -> Unit,
    onCancel: () -> Unit
) {
    AnimatedVisibility(
        visible = true,
        enter = fadeIn() + slideInVertically(),
        exit = fadeOut() + slideOutVertically()
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = "Warning",
                modifier = Modifier.size(80.dp),
                tint = PastelOrange
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Mobile Data Warning",
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.Bold
                ),
                color = OnBackground
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "You appear to be on a $networkType connection. " +
                        "The model download requires $dataSize of data.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = PastelOrange.copy(alpha = 0.2f)
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = "This may result in significant data charges from your carrier. " +
                            "We recommend using Wi-Fi for this download.",
                    style = MaterialTheme.typography.bodySmall,
                    color = PastelOrange,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(12.dp)
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = onConfirmDownload,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = PastelOrange,
                    contentColor = OnPrimary
                )
            ) {
                Text(
                    text = "Download Anyway",
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = FontWeight.SemiBold
                    )
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(24.dp)
            ) {
                Text(
                    text = "Cancel",
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = FontWeight.SemiBold
                    )
                )
            }
        }
    }
}

// ── Downloading State ───────────────────────────────────────────────────────────

@Composable
private fun DownloadingContent(
    progress: DownloadProgress,
    onCancel: () -> Unit
) {
    AnimatedVisibility(
        visible = true,
        enter = fadeIn() + slideInVertically(),
        exit = fadeOut() + slideOutVertically()
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Thinking robot mascot
            RobotMascot(
                isThinking = true,
                modifier = Modifier.size(160.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Downloading AI Model",
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.Bold
                ),
                color = OnBackground
            )

            Spacer(modifier = Modifier.height(24.dp))

            when (progress) {
                is DownloadProgress.Downloading -> {
                    DownloadProgressIndicator(
                        bytesDownloaded = progress.bytesDownloaded,
                        totalBytes = progress.totalBytes,
                        percent = progress.percent,
                        estimatedSecondsRemaining = progress.estimatedSecondsRemaining
                    )
                }
                else -> {
                    // Fallback: show indeterminate progress
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp),
                        color = Primary,
                        trackColor = SurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            TextButton(onClick = onCancel) {
                Text(
                    text = "Cancel",
                    style = MaterialTheme.typography.labelLarge,
                    color = TextSecondary
                )
            }
        }
    }
}

@Composable
private fun DownloadProgressIndicator(
    bytesDownloaded: Long,
    totalBytes: Long,
    percent: Float,
    estimatedSecondsRemaining: Long
) {
    // Progress bar
    LinearProgressIndicator(
        progress = { percent.coerceIn(0f, 1f) },
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp),
        color = Primary,
        trackColor = SurfaceVariant,
        strokeCap = StrokeCap.Round
    )

    Spacer(modifier = Modifier.height(12.dp))

    // Progress text: "1.2 GB / 2.5 GB"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = formatFileSize(bytesDownloaded) + " / " + formatFileSize(totalBytes),
            style = MaterialTheme.typography.bodyMedium,
            color = OnBackground
        )
        Text(
            text = "${(percent * 100).toInt()}%",
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = FontWeight.SemiBold
            ),
            color = Primary
        )
    }

    Spacer(modifier = Modifier.height(4.dp))

    // Estimated time remaining
    if (estimatedSecondsRemaining > 0) {
        Text(
            text = formatTimeRemaining(estimatedSecondsRemaining),
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )
    }

    // Download speed
    if (bytesDownloaded > 0 && totalBytes > 0) {
        val percentValue = percent * 100
        if (percentValue > 0) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Download in progress...",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }
    }
}

// ── Verifying State ─────────────────────────────────────────────────────────────

@Composable
private fun VerifyingContent(percent: Float) {
    AnimatedVisibility(
        visible = true,
        enter = fadeIn() + slideInVertically(),
        exit = fadeOut() + slideOutVertically()
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(40.dp),
                color = Primary,
                strokeWidth = 3.dp
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Verifying download...",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold
                ),
                color = OnBackground
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Checking file integrity",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )

            if (percent > 0f) {
                Spacer(modifier = Modifier.height(12.dp))

                LinearProgressIndicator(
                    progress = { percent.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp),
                    color = Primary,
                    trackColor = SurfaceVariant,
                    strokeCap = StrokeCap.Round
                )
            }
        }
    }
}

// ── Ready to Chat State ─────────────────────────────────────────────────────────

@Composable
private fun ReadyToChatContent(onStartChatting: () -> Unit) {
    AnimatedVisibility(
        visible = true,
        enter = fadeIn() + slideInVertically(),
        exit = fadeOut() + slideOutVertically()
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Robot mascot with checkmark overlay
            Box(contentAlignment = Alignment.BottomEnd) {
                RobotMascot(
                    isThinking = false,
                    modifier = Modifier.size(160.dp)
                )
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = "Ready",
                    tint = PastelGreen,
                    modifier = Modifier.size(36.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "All Set!",
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.Bold
                ),
                color = Primary
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Your AI assistant is ready to help",
                style = MaterialTheme.typography.bodyLarge,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = onStartChatting,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Primary,
                    contentColor = OnPrimary
                )
            ) {
                Text(
                    text = "Start Chatting",
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = FontWeight.SemiBold
                    )
                )
            }
        }
    }
}

// ── Error State ─────────────────────────────────────────────────────────────────

@Composable
private fun ErrorContent(
    message: String,
    isRetryable: Boolean,
    onRetry: () -> Unit,
    onGoBack: () -> Unit
) {
    AnimatedVisibility(
        visible = true,
        enter = fadeIn() + slideInVertically(),
        exit = fadeOut() + slideOutVertically()
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Sad robot mascot
            RobotMascot(
                isThinking = false,
                modifier = Modifier.size(160.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Something went wrong",
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.Bold
                ),
                color = OnBackground
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = if (!isRetryable) {
                    "Download failed. Please check your connection and try again."
                } else {
                    message
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            if (isRetryable) {
                Button(
                    onClick = onRetry,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Primary,
                        contentColor = OnPrimary
                    )
                ) {
                    Text(
                        text = "Retry",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
            }

            OutlinedButton(
                onClick = onGoBack,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = TextSecondary
                )
            ) {
                Text(
                    text = "Go Back",
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

// ── Insufficient RAM State ──────────────────────────────────────────────────────

@Composable
private fun InsufficientRamContent(
    onContinueAnyway: () -> Unit,
    onGoBack: () -> Unit
) {
    AnimatedVisibility(
        visible = true,
        enter = fadeIn() + slideInVertically(),
        exit = fadeOut() + slideOutVertically()
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = "Warning",
                tint = PastelOrange,
                modifier = Modifier.size(64.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Low Memory Warning",
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.Bold
                ),
                color = OnBackground
            )

            Spacer(modifier = Modifier.height(12.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Surface),
                border = CardDefaults.outlinedCardBorder(enabled = true).copy(
                    brush = androidx.compose.ui.graphics.SolidColor(PastelOrange.copy(alpha = 0.5f))
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "This device may not have enough RAM (8 GB required). " +
                                "The app may run slowly or crash.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = OnBackground
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = onContinueAnyway,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Primary,
                    contentColor = OnPrimary
                )
            ) {
                Text(
                    text = "Continue Anyway",
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = FontWeight.SemiBold
                    )
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = onGoBack,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = TextSecondary
                )
            ) {
                Text(
                    text = "Go Back",
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

// ── Insufficient Storage State ──────────────────────────────────────────────────

@Composable
private fun InsufficientStorageContent(onGoBack: () -> Unit) {
    AnimatedVisibility(
        visible = true,
        enter = fadeIn() + slideInVertically(),
        exit = fadeOut() + slideOutVertically()
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = "Error",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(64.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Not Enough Storage",
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.Bold
                ),
                color = OnBackground
            )

            Spacer(modifier = Modifier.height(12.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Surface),
                border = CardDefaults.outlinedCardBorder(enabled = true).copy(
                    brush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Not enough storage. Need approximately 4 GB free space to " +
                                "download and install the AI model.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = OnBackground
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = onGoBack,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Primary,
                    contentColor = OnPrimary
                )
            ) {
                Text(
                    text = "Free Up Space",
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = FontWeight.SemiBold
                    )
                )
            }
        }
    }
}

// ── Utility Functions ───────────────────────────────────────────────────────────

private fun formatFileSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    val gb = mb / 1024.0
    return "%.1f GB".format(gb)
}

private fun formatTimeRemaining(seconds: Long): String {
    if (seconds <= 0) return ""
    if (seconds < 60) return "About $seconds second${if (seconds != 1L) "s" else ""} remaining"
    val minutes = seconds / 60
    val remainingSeconds = seconds % 60
    if (minutes < 60) {
        return if (remainingSeconds > 0) {
            "About $minutes min $remainingSeconds sec remaining"
        } else {
            "About $minutes minute${if (minutes != 1L) "s" else ""} remaining"
        }
    }
    val hours = minutes / 60
    val remainingMinutes = minutes % 60
    return "About $hours hr $remainingMinutes min remaining"
}
