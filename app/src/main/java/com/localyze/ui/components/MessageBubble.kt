package com.localyze.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.localyze.ui.theme.Hairline
import com.localyze.ui.theme.OnPrimary
import com.localyze.ui.theme.Primary
import com.localyze.ui.theme.Surface
import com.localyze.ui.theme.SurfaceVariant
import com.localyze.ui.theme.TextSecondary

@Composable
fun UserMessageBubble(
    message: String,
    timestamp: Long,
    imageUris: List<String> = emptyList(),
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.End
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomStart = 18.dp,
                bottomEnd = 6.dp
            ),
            color = Primary,
            modifier = Modifier.widthIn(max = 560.dp)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                if (imageUris.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        horizontalAlignment = Alignment.End
                    ) {
                        for (uri in imageUris) {
                            val bitmap = runCatching {
                                BitmapFactory.decodeFile(uri)?.asImageBitmap()
                            }.getOrNull()
                            if (bitmap != null) {
                                Image(
                                    bitmap = bitmap,
                                    contentDescription = "Attached image",
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 240.dp)
                                        .clip(RoundedCornerShape(8.dp)),
                                    contentScale = ContentScale.Fit
                                )
                            }
                        }
                    }
                }
                Text(
                    text = message,
                    color = OnPrimary,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
        Text(
            text = formatRelativeTime(timestamp),
            color = TextSecondary,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = 3.dp, end = 4.dp)
        )
    }
}

@Composable
fun AssistantMessageBubble(
    message: String,
    timestamp: Long,
    isStreaming: Boolean = false,
    modifier: Modifier = Modifier
) {
    val chartResults = remember(message) { if (!isStreaming) extractChartResults(message) else emptyList() }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomStart = 6.dp,
                bottomEnd = 18.dp
            ),
            color = Surface,
            border = BorderStroke(0.5.dp, Hairline),
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 720.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                // Render the message as-is so its identity is stable and Compose
                // can skip recomposing the (expensive) markdown parse when only
                // the cursor blinks. The blinking caret is overlaid in a
                // separate, end-aligned Box that toggles its own visibility.
                Box(modifier = Modifier.fillMaxWidth()) {
                    StructuredMarkdownText(markdown = message)
                    if (isStreaming) {
                        var cursorVisible by remember { mutableStateOf(true) }
                        LaunchedEffect(Unit) {
                            while (true) {
                                delay(500)
                                cursorVisible = !cursorVisible
                            }
                        }
                        if (cursorVisible) {
                            Text(
                                text = "|",
                                color = TextSecondary,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                            )
                        }
                    }
                }
                if (chartResults.isNotEmpty()) {
                    ErrorBoundary(label = "chart") {
                        chartResults.forEach { chartResult ->
                            when (chartResult.type) {
                                ChartType.LINE -> InlineLineChart(
                                    data = chartResult.data,
                                    modifier = Modifier.padding(top = 8.dp)
                                )
                                ChartType.PIE -> InlinePieChart(
                                    data = chartResult.data,
                                    modifier = Modifier.padding(top = 8.dp)
                                )
                                ChartType.BAR -> InlineBarChart(
                                    data = chartResult.data,
                                    modifier = Modifier.padding(top = 8.dp)
                                )
                            }
                            if (shouldShowCompanionBarChart(chartResult)) {
                                InlineBarChart(
                                    data = chartResult.data,
                                    modifier = Modifier.padding(top = 8.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
        Text(
            text = formatRelativeTime(timestamp),
            color = TextSecondary,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = 3.dp, start = 4.dp)
        )
    }
}
