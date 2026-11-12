package dev.soupslurpr.transcribro.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.soupslurpr.transcribro.preferences.PreferencesViewModel

/** Radii 8 / 12 / 16 (docs/design-language.md); dialogs and sheets use 16, not Material's 28. */
val VerbaloShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(16.dp),
)

/**
 * The Verbalo roles on Material's slots. Every container is a solid colour from the palette and
 * there is no tonal tint, so cards, sheets and dialogs stay opaque and flat (no shadows on dark).
 */
private fun scheme(c: VerbaloColors): ColorScheme {
    val base = if (c.isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = c.primary,
        onPrimary = c.onPrimary,
        primaryContainer = c.surfaceRaised,
        onPrimaryContainer = c.primarySoft,
        inversePrimary = c.primarySoft,
        secondary = c.primarySoft,
        onSecondary = c.onPrimary,
        secondaryContainer = c.surfaceRaised,
        onSecondaryContainer = c.primarySoft,
        tertiary = c.accent,
        onTertiary = c.onPrimary,
        tertiaryContainer = c.surfaceRaised,
        onTertiaryContainer = c.accent,
        background = c.background,
        onBackground = c.textPrimary,
        surface = c.background,
        onSurface = c.textPrimary,
        surfaceVariant = c.surfaceRaised,
        onSurfaceVariant = c.textSecondary,
        surfaceTint = Color.Transparent,
        surfaceBright = c.surfaceRaised,
        surfaceDim = c.background,
        surfaceContainerLowest = c.surface,
        surfaceContainerLow = c.surface,
        surfaceContainer = c.surface,
        surfaceContainerHigh = c.surfaceRaised,
        surfaceContainerHighest = c.surfaceRaised,
        inverseSurface = c.surfaceRaised,
        inverseOnSurface = c.textPrimary,
        outline = c.border,
        outlineVariant = c.border,
        error = c.error,
        onError = if (c.isDark) Color(0xFF2A0A0A) else Color.White,
        errorContainer = c.surfaceRaised,
        onErrorContainer = c.error,
        scrim = Color.Black,
    )
}

/** The palette, type and shapes of Verbalo; follows the system dark/light setting. */
@Composable
fun VerbaloTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    preferencesViewModel: PreferencesViewModel,
    content: @Composable () -> Unit,
) {
    val settingsUiState by preferencesViewModel.uiState.collectAsState()
    val pitchBlack = settingsUiState.pitchBlackBackground.second.value && darkTheme
    val colors = when {
        pitchBlack -> VerbaloPalette.Dark.copy(background = Color.Black)
        darkTheme -> VerbaloPalette.Dark
        else -> VerbaloPalette.Light
    }
    CompositionLocalProvider(LocalVerbaloColors provides colors) {
        MaterialTheme(
            colorScheme = scheme(colors),
            typography = Typography,
            shapes = VerbaloShapes,
            content = content,
        )
    }
}

object VerbaloTheme {
    val colors: VerbaloColors
        @Composable @ReadOnlyComposable get() = LocalVerbaloColors.current
}
