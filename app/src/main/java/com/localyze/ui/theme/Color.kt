package com.localyze.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// â”€â”€ Light Palette â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

val Background = Color(0xFFF8FAF5)
val Surface = Color(0xFFFFFFFF)
val SurfaceVariant = Color(0xFFE4E9DF)
val Primary = Color(0xFF3FCB45)
val PrimaryVariant = Color(0xFF1F7A2F)
val Secondary = Color(0xFF2D5C38)
val SecondaryVariant = Color(0xFFDCEFD6)
val OnPrimary = Color(0xFFFFFFFF)
val OnSecondary = Color(0xFFFFFFFF)
val OnBackground = Color(0xFF151A14)
val OnSurface = Color(0xFF151A14)
val TextSecondary = Color(0xFF6D756B)
val Error = Color(0xFFDC2626)
val OnError = Color(0xFFFFFFFF)

// â”€â”€ Dark Palette â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

val DarkBackground = Color(0xFF0E130D)
val DarkSurface = Color(0xFF171D16)
val DarkSurfaceVariant = Color(0xFF2C342A)
val DarkPrimary = Color(0xFF68E66B)
val DarkPrimaryVariant = Color(0xFF2A8C37)
val DarkSecondary = Color(0xFFA8D9A1)
val DarkSecondaryVariant = Color(0xFF244D2D)
val DarkOnPrimary = Color(0xFFFFFFFF)
val DarkOnSecondary = Color(0xFFFFFFFF)
val DarkOnBackground = Color(0xFFF9FAFB)
val DarkOnSurface = Color(0xFFF9FAFB)
val DarkTextSecondary = Color(0xFFAEB6C2)
val DarkError = Color(0xFFF87171)
val DarkOnError = Color(0xFFFFFFFF)

// â”€â”€ Icon Tints (capability cards, etc.) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

val PastelBlue = Color(0xFFA8C8E8)
val PastelOrange = Color(0xFFF0B090)
val PastelGreen = Color(0xFFA8D4B0)
val PastelYellow = Color(0xFFF0D890)
val PastelPurple = Color(0xFFC8B8E8)
val PastelTeal = Color(0xFFA8D4D0)

// â”€â”€ Material 3 Color Schemes â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

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
