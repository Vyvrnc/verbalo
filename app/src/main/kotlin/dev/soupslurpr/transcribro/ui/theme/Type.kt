package dev.soupslurpr.transcribro.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.soupslurpr.transcribro.R

/** UI text. Bundled (res/font, SIL OFL 1.1), so the app looks the same on every phone. */
val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
    Font(R.font.inter_extrabold, FontWeight.ExtraBold),
)

/** Logs, paths, versions, timings. */
val JetBrainsMono = FontFamily(Font(R.font.jetbrains_mono_regular, FontWeight.Normal))

/**
 * The type scale of docs/design-language.md on Material's slots:
 * display -> displaySmall, title -> headlineSmall, heading -> titleMedium,
 * subheading -> titleSmall, body -> bodyMedium, caption -> bodySmall, label -> labelSmall.
 */
val Typography = Typography(
    displaySmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.ExtraBold, fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-1).sp),
    headlineSmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = (-0.3).sp),
    titleLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp),
    titleSmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 1.5.em),
    bodyMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 1.5.em),
    bodySmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 1.4.em),
    labelLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 1.2.sp),
)

object VerbaloType {
    /** Commands, values, timings. */
    val mono = TextStyle(fontFamily = JetBrainsMono, fontSize = 13.sp, lineHeight = 18.sp)
    /** Log lines, hashes. */
    val monoSmall = TextStyle(fontFamily = JetBrainsMono, fontSize = 11.sp, lineHeight = 15.sp)
}
