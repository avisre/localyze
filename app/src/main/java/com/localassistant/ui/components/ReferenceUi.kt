package com.localassistant.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.localassistant.ui.theme.OnBackground
import com.localassistant.ui.theme.OnPrimary
import com.localassistant.ui.theme.Primary
import com.localassistant.ui.theme.SurfaceVariant
import com.localassistant.ui.theme.TextSecondary

@Composable
fun AssistantMark(
    modifier: Modifier = Modifier,
    active: Boolean = true
) {
    val muted = if (active) Primary else SurfaceVariant
    Canvas(modifier = modifier.size(36.dp)) {
        val w = size.width
        val h = size.height
        val darkCutout = Color(0xFF06251B)
        val bright = Color(0xFF94EF37)
        val mid = muted
        val deep = Color(0xFF17951F)

        drawPath(
            color = mid,
            path = Path().apply {
                moveTo(w * 0.18f, h * 0.25f)
                lineTo(w * 0.52f, h * 0.10f)
                lineTo(w * 0.52f, h * 0.62f)
                lineTo(w * 0.18f, h * 0.78f)
                close()
            }
        )
        drawPath(
            color = bright,
            path = Path().apply {
                moveTo(w * 0.56f, h * 0.24f)
                lineTo(w * 0.86f, h * 0.38f)
                lineTo(w * 0.74f, h * 0.56f)
                lineTo(w * 0.56f, h * 0.48f)
                close()
            }
        )
        drawPath(
            color = deep,
            path = Path().apply {
                moveTo(w * 0.18f, h * 0.78f)
                lineTo(w * 0.52f, h * 0.62f)
                lineTo(w * 0.78f, h * 0.80f)
                lineTo(w * 0.50f, h * 0.96f)
                close()
            }
        )
        drawPath(
            color = darkCutout,
            path = Path().apply {
                moveTo(w * 0.66f, h * 0.52f)
                lineTo(w * 0.76f, h * 0.57f)
                lineTo(w * 0.76f, h * 0.78f)
                lineTo(w * 0.66f, h * 0.72f)
                close()
            }
        )
    }
}

@Composable
fun ReferenceHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {}
) {
    val displaySubtitle = subtitle
        ?.replace("\u2022", " ")
        ?.replace("\u00e2\u20ac\u00a2", " ")
        ?.replace("  ", " ")
        ?.trim()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AssistantMark()
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = OnBackground,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!displaySubtitle.isNullOrBlank()) {
                Text(
                    text = displaySubtitle,
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            content = actions
        )
    }
}

@Composable
fun StatusChip(
    text: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false
) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = if (selected) Primary.copy(alpha = 0.12f) else Color.White,
        border = BorderStroke(
            1.dp,
            if (selected) Primary.copy(alpha = 0.32f) else SurfaceVariant
        )
    ) {
        Text(
            text = text,
            color = if (selected) Primary else TextSecondary,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            maxLines = 1
        )
    }
}

@Composable
fun ReferenceSectionTitle(
    title: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    onTrailingClick: (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            color = OnBackground,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.weight(1f)
        )
        if (trailing != null) {
            Text(
                text = trailing,
                color = Primary,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.clickable(enabled = onTrailingClick != null) {
                    onTrailingClick?.invoke()
                }
            )
        }
    }
}

@Composable
fun ReferenceQuickActionCard(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier
            .height(76.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = Color.White,
        border = BorderStroke(1.dp, SurfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(
                modifier = Modifier.size(34.dp),
                shape = RoundedCornerShape(10.dp),
                color = Primary.copy(alpha = 0.11f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = Primary,
                        modifier = Modifier.size(19.dp)
                    )
                }
            }
            Text(
                text = title,
                color = OnBackground,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
fun ReferenceCapabilityCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier
            .height(92.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = Color.White,
        border = BorderStroke(1.dp, SurfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Primary,
                modifier = Modifier.size(21.dp)
            )
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    text = title,
                    color = OnBackground,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun ReferenceSettingsGroup(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = title,
            color = TextSecondary,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 8.dp)
        )
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp),
            color = Color.White,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, SurfaceVariant)
        ) {
            Column(content = { content() })
        }
    }
}

@Composable
fun ReferenceSettingsRow(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    value: String? = null,
    danger: Boolean = false,
    showChevron: Boolean = true,
    checked: Boolean? = null,
    onCheckedChange: ((Boolean) -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    val rowClick = onClick ?: checked?.let { { onCheckedChange?.invoke(!it) } }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(if (subtitle == null) 58.dp else 70.dp)
            .clickable(enabled = rowClick != null) { rowClick?.invoke() }
            .padding(start = 14.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(34.dp),
            shape = RoundedCornerShape(10.dp),
            color = if (danger) MaterialTheme.colorScheme.error.copy(alpha = 0.08f) else Primary.copy(alpha = 0.1f)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (danger) MaterialTheme.colorScheme.error else Primary,
                    modifier = Modifier.size(19.dp)
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = if (danger) MaterialTheme.colorScheme.error else OnBackground,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (value != null) {
            Text(
                text = value,
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
        if (checked != null && onCheckedChange != null) {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = Primary,
                    checkedThumbColor = OnPrimary,
                    uncheckedTrackColor = SurfaceVariant,
                    uncheckedThumbColor = Color.White
                )
            )
        } else if (showChevron) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = TextSecondary,
                modifier = Modifier.size(23.dp)
            )
        }
    }
}

@Composable
fun ReferenceDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier.padding(start = 60.dp, end = 12.dp),
        color = SurfaceVariant,
        thickness = 1.dp
    )
}
