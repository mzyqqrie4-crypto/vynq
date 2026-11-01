package app.vynq.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Type scale. Uses the platform system face (Roboto / MiSans on HyperOS) so text
 * renders with the OEM's hinting and weight axes, at zero APK cost.
 */
@Immutable
data class VynqTypography(
    val largeTitle: TextStyle,
    val title1: TextStyle,
    val title2: TextStyle,
    val headline: TextStyle,
    val body: TextStyle,
    val callout: TextStyle,
    val subhead: TextStyle,
    val footnote: TextStyle,
    val caption: TextStyle,
    val overline: TextStyle,
    /** Tabular figures for timers, stats and timestamps. */
    val numeric: TextStyle,
)

private val base = TextStyle(fontFamily = FontFamily.Default)

val DefaultTypography = VynqTypography(
    largeTitle = base.copy(fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.02).em),
    title1 = base.copy(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.015).em),
    title2 = base.copy(fontSize = 21.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.01).em),
    headline = base.copy(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    body = base.copy(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal),
    callout = base.copy(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal),
    subhead = base.copy(fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.Normal),
    footnote = base.copy(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal),
    caption = base.copy(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    overline = base.copy(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.18.em),
    numeric = base.copy(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum"),
)
