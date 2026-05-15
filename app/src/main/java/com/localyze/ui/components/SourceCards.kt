package com.localyze.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.localyze.ui.theme.Hairline
import com.localyze.ui.theme.OnBackground
import com.localyze.ui.theme.Surface
import com.localyze.ui.theme.TextSecondary
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class SourceCard(
    val title: String,
    val url: String,
    val snippet: String,
    val source: String
)

private val lenientJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

/**
 * Best-effort parse of a [WebSearchTool] JSON result into source cards.
 * Returns an empty list for non-web-search results, errors, or empty payloads.
 */
fun parseSourceCards(rawJson: String?): List<SourceCard> {
    if (rawJson.isNullOrBlank()) return emptyList()
    return runCatching {
        val obj = lenientJson.parseToJsonElement(rawJson).jsonObject
        val results = obj["results"]?.jsonArray ?: return@runCatching emptyList()
        results.mapNotNull { element ->
            val r = element.jsonObject
            val url = (r["url"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            SourceCard(
                title = (r["title"] as? JsonPrimitive)?.content.orEmpty().ifBlank { hostnameOf(url) },
                url = url,
                snippet = (r["snippet"] as? JsonPrimitive)?.content.orEmpty(),
                source = (r["source"] as? JsonPrimitive)?.content.orEmpty()
            )
        }
    }.getOrDefault(emptyList())
}

private fun hostnameOf(url: String): String = runCatching {
    Uri.parse(url).host?.removePrefix("www.") ?: url
}.getOrDefault(url)

/**
 * Horizontally-scrolling list of source cards that appears below the
 * assistant's answer when the model used `web_search`. Tapping a card
 * opens the URL in the system browser.
 */
@Composable
fun SourceCardsRow(
    cards: List<SourceCard>,
    modifier: Modifier = Modifier
) {
    if (cards.isEmpty()) return
    val context = LocalContext.current
    Column(
        modifier = modifier.fillMaxWidth().padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = "Sources",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = TextSecondary,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(cards) { card ->
                SourceCardItem(card = card, onClick = {
                    runCatching {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(card.url))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    }
                })
            }
        }
    }
}

@Composable
private fun SourceCardItem(card: SourceCard, onClick: () -> Unit) {
    val host = hostnameOf(card.url)
    Column(
        modifier = Modifier
            .width(220.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Surface)
            .border(width = 1.dp, color = Hairline, shape = RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FaviconStub(host)
            Text(
                text = host,
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = card.title,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = OnBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (card.snippet.isNotBlank()) {
            Text(
                text = card.snippet,
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun FaviconStub(host: String) {
    val seed = host.fold(0) { acc, c -> acc * 31 + c.code }
    val palette = listOf(
        Color(0xFF34C759), Color(0xFF5AC8FA), Color(0xFFFF9500),
        Color(0xFFAF52DE), Color(0xFFFF2D55), Color(0xFF30B0C7)
    )
    val color = palette[((seed % palette.size) + palette.size) % palette.size]
    val letter = host.firstOrNull()?.uppercaseChar()?.toString() ?: "•"
    Row(
        modifier = Modifier.size(16.dp).clip(CircleShape).background(color),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Text(
            text = letter,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
    }
}
