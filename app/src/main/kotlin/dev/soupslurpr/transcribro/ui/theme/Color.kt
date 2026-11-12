package dev.soupslurpr.transcribro.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Verbalo colours: the roles of docs/design-language.md (the Lexona family palette). Change both
 * together. Material components get them through the colour scheme in Theme.kt; the roles
 * Material has no slot for (raised surface, border, muted text, status, coral) come from
 * [VerbaloTheme.colors].
 */
@Immutable
data class VerbaloColors(
    val background: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val border: Color,
    val primary: Color,
    /** Teal for text and icons that must read on the background. */
    val primarySoft: Color,
    val onPrimary: Color,
    /** Coral: at most one element per screen, never a status. */
    val accent: Color,
    val success: Color,
    val warning: Color,
    val error: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val isDark: Boolean,
)

object VerbaloPalette {
    val Dark = VerbaloColors(
        background = Color(0xFF0B132B), // ink
        surface = Color(0xFF111B36),
        surfaceRaised = Color(0xFF17233F),
        border = Color(0xFF22304D),
        primary = Color(0xFF14B8A6), // teal
        primarySoft = Color(0xFF5EEAD4),
        onPrimary = Color(0xFF04211D),
        accent = Color(0xFFFB7185), // coral
        success = Color(0xFF34D399),
        warning = Color(0xFFFBBF24),
        error = Color(0xFFF87171),
        textPrimary = Color(0xFFEEF4FF),
        textSecondary = Color(0xFFA7B4CC),
        textMuted = Color(0xFF7584A3),
        isDark = true,
    )

    val Light = VerbaloColors(
        background = Color(0xFFF5F9F8),
        surface = Color(0xFFFFFFFF),
        surfaceRaised = Color(0xFFEEF5F3),
        border = Color(0xFFD7E3E0),
        primary = Color(0xFF0D9488),
        primarySoft = Color(0xFF0F766E),
        onPrimary = Color(0xFFFFFFFF),
        accent = Color(0xFFE11D48),
        success = Color(0xFF059669),
        warning = Color(0xFFB45309),
        error = Color(0xFFDC2626),
        textPrimary = Color(0xFF0B132B),
        textSecondary = Color(0xFF3D4B66),
        textMuted = Color(0xFF5B6982),
        isDark = false,
    )
}

val LocalVerbaloColors = staticCompositionLocalOf { VerbaloPalette.Dark }
