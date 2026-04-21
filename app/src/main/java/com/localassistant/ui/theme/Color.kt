package com.localassistant.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// ── Light Palette ──────────────────────────────────────────────────────────────

val Background = Color(0xFFF7F8FA)
val Surface = Color(0xFFFFFFFF)
val SurfaceVariant = Color(0xFFE5E7EB)
val Primary = Color(0xFF0F766E)
val PrimaryVariant = Color(0xFF115E59)
val Secondary = Color(0xFFE11D48)
val SecondaryVariant = Color(0xFFBE123C)
val OnPrimary = Color(0xFFFFFFFF)
val OnSecondary = Color(0xFFFFFFFF)
val OnBackground = Color(0xFF111827)
val OnSurface = Color(0xFF111827)
val TextSecondary = Color(0xFF6B7280)
val Error = Color(0xFFDC2626)
val OnError = Color(0xFFFFFFFF)

// ── Dark Palette ──────────────────────────────────────────────────────────────

val DarkBackground = Color(0xFF0F1216)
val DarkSurface = Color(0xFF171B21)
val DarkSurfaceVariant = Color(0xFF2A3038)
val DarkPrimary = Color(0xFF2DD4BF)
val DarkPrimaryVariant = Color(0xFF0F766E)
val DarkSecondary = Color(0xFFFB7185)
val DarkSecondaryVariant = Color(0xFFE11D48)
val DarkOnPrimary = Color(0xFFFFFFFF)
val DarkOnSecondary = Color(0xFFFFFFFF)
val DarkOnBackground = Color(0xFFF9FAFB)
val DarkOnSurface = Color(0xFFF9FAFB)
val DarkTextSecondary = Color(0xFFAEB6C2)
val DarkError = Color(0xFFF87171)
val DarkOnError = Color(0xFFFFFFFF)

// ── Icon Tints (capability cards, etc.) ────────────────────────────────────────

val PastelBlue = Color(0xFFA8C8E8)
val PastelOrange = Color(0xFFF0B090)
val PastelGreen = Color(0xFFA8D4B0)
val PastelYellow = Color(0xFFF0D890)
val PastelPurple = Color(0xFFC8B8E8)
val PastelTeal = Color(0xFFA8D4D0)

// ── Material 3 Color Schemes ──────────────────────────────────────────────────

val LightColorScheme = lightColorScheme(
    primary = Primary,
    onPrimary = OnPrimary,
    primaryContainer = PrimaryVariant,
    onPrimaryContainer = OnPrimary,
    secondary = Secondary,
    onSecondary = OnSecondary,
    secondaryContainer = SecondaryVariant,
    onSecondaryContainer = OnSecondary,
    tertiary = PastelBlue,
    background = Background,
    onBackground = OnBackground,
    surface = Surface,
    onSurface = OnSurface,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = TextSecondary,
    error = Error,
    onError = OnError,
    outline = SurfaceVariant,
    outlineVariant = SurfaceVariant
)

val DarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryVariant,
    onPrimaryContainer = DarkOnPrimary,
    secondary = DarkSecondary,
    onSecondary = DarkOnSecondary,
    secondaryContainer = DarkSecondaryVariant,
    onSecondaryContainer = DarkOnSecondary,
    tertiary = PastelBlue,
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
