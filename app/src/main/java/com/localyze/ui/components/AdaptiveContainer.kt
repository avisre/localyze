package com.localyze.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Three width buckets that mirror Material 3 WindowWidthSizeClass without
 * pulling in the full window-size-class artifact (one less dep on the
 * release path). Cutoffs match the official 600dp / 840dp breakpoints.
 */
enum class AdaptiveWidth { Compact, Medium, Expanded }

/**
 * Current device width bucket, computed from [LocalConfiguration.screenWidthDp].
 */
@Composable
fun rememberAdaptiveWidth(): AdaptiveWidth {
    val widthDp = LocalConfiguration.current.screenWidthDp
    return when {
        widthDp < 600 -> AdaptiveWidth.Compact
        widthDp < 840 -> AdaptiveWidth.Medium
        else -> AdaptiveWidth.Expanded
    }
}

/**
 * Content max-width per bucket. Compact stays full-width (no cap). Medium
 * caps at 560dp so foldable/small-tablet content has comfortable measure
 * without sliding to the edges. Expanded caps at 760dp so 10" tablet and
 * desktop don't show 12-word lines.
 */
fun AdaptiveWidth.contentMaxWidth(): Dp = when (this) {
    AdaptiveWidth.Compact -> Dp.Unspecified
    AdaptiveWidth.Medium -> 560.dp
    AdaptiveWidth.Expanded -> 760.dp
}

/**
 * Wrap a screen body so that on Medium/Expanded the content is centered
 * with a sensible max-width, and on Compact it fills the screen unchanged.
 * Use this once per top-level screen.
 */
@Composable
fun AdaptiveContainer(
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.TopCenter,
    content: @Composable () -> Unit
) {
    val bucket = rememberAdaptiveWidth()
    val maxWidth = bucket.contentMaxWidth()
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = contentAlignment
    ) {
        if (maxWidth == Dp.Unspecified) {
            content()
        } else {
            Box(
                modifier = Modifier.widthIn(max = maxWidth),
                contentAlignment = contentAlignment,
                content = { content() }
            )
        }
    }
}
