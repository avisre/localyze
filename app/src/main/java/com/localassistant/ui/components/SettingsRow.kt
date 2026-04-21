package com.localassistant.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localassistant.ui.theme.Nunito
import com.localassistant.ui.theme.OnBackground
import com.localassistant.ui.theme.Primary
import com.localassistant.ui.theme.SurfaceVariant
import com.localassistant.ui.theme.TextSecondary

/**
 * A settings row with an emoji icon, title, subtitle, and a toggle switch.
 *
 * @param icon    Emoji character to display as the row icon (e.g. "🌙").
 * @param title   Primary label text (Nunito SemiBold, 16sp).
 * @param subtitle Secondary description text (Nunito Regular, 13sp).
 * @param checked Whether the switch is currently on.
 * @param onCheckedChange Callback invoked when the switch is toggled.
 * @param modifier Optional modifier for the row.
 */
@Composable
fun SettingsToggleRow(
    icon: String,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Emoji icon
            Text(
                text = icon,
                fontSize = 24.sp,
                modifier = Modifier.padding(end = 12.dp)
            )

            // Title and subtitle column
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp)
            ) {
                Text(
                    text = title,
                    fontFamily = Nunito,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    color = OnBackground,
                    lineHeight = 22.sp
                )
                Text(
                    text = subtitle,
                    fontFamily = Nunito,
                    fontWeight = FontWeight.Normal,
                    fontSize = 13.sp,
                    color = TextSecondary,
                    lineHeight = 18.sp
                )
            }

            // Switch
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = Primary,
                    checkedThumbColor = Primary,
                    checkedBorderColor = Primary,
                    uncheckedTrackColor = SurfaceVariant,
                    uncheckedThumbColor = TextSecondary
                )
            )
        }

        // Divider
        HorizontalDivider(
            color = SurfaceVariant,
            thickness = 1.dp,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
    }
}

/**
 * A settings row with an emoji icon, title, and a chevron ">" indicator.
 * Tapping the row invokes [onClick].
 *
 * @param icon    Emoji character to display as the row icon (e.g. "📱").
 * @param title   Primary label text (Nunito SemiBold, 16sp).
 * @param subtitle Optional secondary description text.
 * @param onClick Callback invoked when the row is tapped.
 * @param modifier Optional modifier for the row.
 */
@Composable
fun SettingsChevronRow(
    icon: String,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (subtitle != null) 72.dp else 56.dp)
                .clickable { onClick() }
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Emoji icon
            Text(
                text = icon,
                fontSize = 24.sp,
                modifier = Modifier.padding(end = 12.dp)
            )

            // Title and optional subtitle column
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = title,
                    fontFamily = Nunito,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    color = OnBackground
                )

                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        fontFamily = Nunito,
                        fontWeight = FontWeight.Normal,
                        fontSize = 13.sp,
                        color = TextSecondary,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }

            // Chevron
            Text(
                text = ">",
                fontFamily = Nunito,
                fontWeight = FontWeight.Normal,
                fontSize = 18.sp,
                color = TextSecondary
            )
        }

        // Divider
        HorizontalDivider(
            color = SurfaceVariant,
            thickness = 1.dp,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
    }
}