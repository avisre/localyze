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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.localyze.ui.theme.OnBackground
import com.localyze.ui.theme.OnPrimary
import com.localyze.ui.theme.Primary
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
            shape = RoundedCornerShape(12.dp),
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
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, SurfaceVariant),
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 720.dp)
        ) {
            Box(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                Text(
                    text = if (isStreaming) "$message|" else message,
                    color = OnBackground,
                    style = MaterialTheme.typography.bodyLarge
                )
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
