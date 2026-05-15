package com.localyze.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Light-first, Apple-inspired neutrals with one clear action color.
val Background = Color(0xFFF5F5F7)
val Surface = Color(0xFFFFFFFF)
val SurfaceVariant = Color(0xFFE5E5EA)
val Primary = Color(0xFF34C759)
val OnPrimary = Color(0xFFFFFFFF)
val OnBackground = Color(0xFF1D1D1F)
val OnSurface = Color(0xFF1D1D1F)
val TextSecondary = Color(0xFF6E6E73)
val Error = Color(0xFFFF3B30)
val OnError = Color(0xFFFFFFFF)
val Success = Color(0xFF34C759)
val Ink = Color(0xFF1D1D1F)
val Hairline = Color(0xFFD2D2D7)

// Dark palette
val DarkBackground = Color(0xFF000000)
val DarkSurface = Color(0xFF1C1C1E)
val DarkSurfaceVariant = Color(0xFF2C2C2E)
val DarkPrimary = Color(0xFF30D158)
val DarkOnPrimary = Color(0xFFFFFFFF)
val DarkOnBackground = Color(0xFFF5F5F7)
val DarkOnSurface = Color(0xFFF5F5F7)
val DarkTextSecondary = Color(0xFF98989D)
val DarkError = Color(0xFFFF453A)
val DarkOnError = Color(0xFFFFFFFF)

// Material 3 Color Schemes
val DarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkSurfaceVariant,
    onPrimaryContainer = DarkOnBackground,
    secondary = DarkSurfaceVariant,
    onSecondary = DarkOnBackground,
    secondaryContainer = DarkSurfaceVariant,
    onSecondaryContainer = DarkOnBackground,
    tertiary = DarkSurfaceVariant,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkTextSecondary,
    error = DarkError,
    onError = DarkOnError,
    outline = DarkSurfaceVariant,
    outlineVariant = DarkSurfaceVariant
)

val LightColorScheme = lightColorScheme(
    primary = Primary,
    onPrimary = OnPrimary,
    primaryContainer = SurfaceVariant,
    onPrimaryContainer = OnBackground,
    secondary = SurfaceVariant,
    onSecondary = OnBackground,
    secondaryContainer = SurfaceVariant,
    onSecondaryContainer = OnBackground,
    tertiary = SurfaceVariant,
    background = Background,
    onBackground = OnBackground,
    surface = Surface,
    onSurface = OnSurface,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = TextSecondary,
    error = Error,
    onError = OnError,
    outline = Hairline,
    outlineVariant = SurfaceVariant
)
