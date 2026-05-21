pragma Singleton
import QtQuick

// Central design tokens for Localyze — synced with the Android sibling app's
// Compose color scheme (see app/src/main/java/com/localyze/ui/theme/Color.kt).
// Apple-inspired neutrals with a single clear action color (system green).
// Manual override (themeBridge.darkMode) wins over the system palette hint.
QtObject {
    id: theme

    // Manual override flag — written by ThemeBridge (persisted via QSettings).
    // If the user hasn't chosen, we fall back to Qt.application.palette.
    readonly property bool _systemDark: {
        // Heuristic: a dark system palette has a window color whose value
        // (HSV "v") is below 0.5. Qt.application.palette is not exposed on
        // every Qt build, so guard it.
        try {
            const pal = Qt.application && Qt.application.palette
            if (!pal) return false
            const w = pal.window
            if (!w) return false
            return (w.r + w.g + w.b) / 3.0 < 0.5
        } catch (e) {
            return false
        }
    }
    property bool dark: (typeof themeBridge !== "undefined" && themeBridge && themeBridge.hasUserPreference)
                        ? themeBridge.darkMode
                        : _systemDark

    // ---- Light palette (mobile parity — Color.kt) ----
    readonly property color _lBg:            "#F5F5F7"  // Background
    readonly property color _lSurface:       "#FFFFFF"  // Surface
    readonly property color _lSurfaceSubtle: "#E5E5EA"  // SurfaceVariant
    readonly property color _lBorder:        "#D2D2D7"  // Hairline
    readonly property color _lBorderStrong:  "#A1A1A6"  // darker step from hairline
    readonly property color _lTextPrimary:   "#1D1D1F"  // OnBackground / OnSurface / Ink
    readonly property color _lTextSecondary: "#6E6E73"  // TextSecondary
    readonly property color _lTextMuted:     "#8E8E93"  // muted step (iOS gray)
    readonly property color _lAccent:        "#34C759"  // Primary (system green)
    readonly property color _lAccentHover:   "#2EB350"  // ~10% darker primary
    readonly property color _lCodeBg:        "#F2F2F7"  // light code surface
    readonly property color _lStop:          "#FF3B30"  // Error red (stop button)
    readonly property color _lTrace:         "#6E6E73"  // muted trace text
    readonly property color _lPass:          "#34C759"  // success = primary green
    readonly property color _lPartial:       "#FF9500"  // iOS orange
    readonly property color _lFail:          "#FF3B30"  // Error

    // ---- Dark palette (mobile parity — Color.kt) ----
    readonly property color _dBg:            "#000000"  // DarkBackground
    readonly property color _dSurface:       "#1C1C1E"  // DarkSurface
    readonly property color _dSurfaceSubtle: "#2C2C2E"  // DarkSurfaceVariant
    readonly property color _dBorder:        "#2C2C2E"  // outline = surfaceVariant
    readonly property color _dBorderStrong:  "#48484A"  // separator
    readonly property color _dTextPrimary:   "#F5F5F7"  // DarkOnBackground / OnSurface
    readonly property color _dTextSecondary: "#98989D"  // DarkTextSecondary
    readonly property color _dTextMuted:     "#636366"  // muted step
    readonly property color _dAccent:        "#30D158"  // DarkPrimary (system green dark)
    readonly property color _dAccentHover:   "#28B84C"  // slightly darker
    readonly property color _dCodeBg:        "#1C1C1E"  // dark code surface
    readonly property color _dStop:          "#FF453A"  // DarkError
    readonly property color _dTrace:         "#98989D"
    readonly property color _dPass:          "#30D158"
    readonly property color _dPartial:       "#FF9F0A"  // iOS dark orange
    readonly property color _dFail:          "#FF453A"

    // ---- Resolved tokens (these are what the rest of the app reads) ----
    readonly property color bgColor:       dark ? _dBg            : _lBg
    readonly property color surface:       dark ? _dSurface       : _lSurface
    readonly property color surfaceSubtle: dark ? _dSurfaceSubtle : _lSurfaceSubtle
    readonly property color border:        dark ? _dBorder        : _lBorder
    readonly property color borderStrong:  dark ? _dBorderStrong  : _lBorderStrong
    readonly property color textPrimary:   dark ? _dTextPrimary   : _lTextPrimary
    readonly property color textSecondary: dark ? _dTextSecondary : _lTextSecondary
    readonly property color textMuted:     dark ? _dTextMuted     : _lTextMuted
    readonly property color accent:        dark ? _dAccent        : _lAccent
    readonly property color accentHover:   dark ? _dAccentHover   : _lAccentHover
    readonly property color codeBg:        dark ? _dCodeBg        : _lCodeBg
    readonly property color stopColor:     dark ? _dStop          : _lStop
    readonly property color traceColor:    dark ? _dTrace         : _lTrace
    readonly property color passColor:     dark ? _dPass          : _lPass
    readonly property color partialColor:  dark ? _dPartial       : _lPartial
    readonly property color failColor:     dark ? _dFail          : _lFail

    // Manual toggle from QML — writes through to ThemeBridge so it persists.
    function applyTheme(d) {
        if (typeof themeBridge !== "undefined" && themeBridge) {
            themeBridge.darkMode = d
        } else {
            dark = d
        }
    }
}
