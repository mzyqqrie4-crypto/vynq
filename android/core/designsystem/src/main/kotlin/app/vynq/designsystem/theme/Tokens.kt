package app.vynq.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Raw brand palette. Mirrors brand/tokens.json. UI code uses [VynqColors], never these directly. */
object VynqPalette {
    val Blue300 = Color(0xFF7CC0FF)
    val Blue500 = Color(0xFF3D7BFF)
    val Blue600 = Color(0xFF2F5FE8)
    val Violet500 = Color(0xFF6A4BFF)
    val Violet700 = Color(0xFF3B1FB8)
    val Purple400 = Color(0xFFB15CFF)
    val Pink400 = Color(0xFFFF6FD2)

    val Ink950 = Color(0xFF07070C)
    val Ink900 = Color(0xFF0E0E16)
    val Graphite850 = Color(0xFF14151D)
    val Graphite800 = Color(0xFF1A1B24)
    val Graphite700 = Color(0xFF262733)
    val Grey500 = Color(0xFF8E8E9A)
    val Grey400 = Color(0xFFA6A6B2)
    val OffWhite = Color(0xFFF7F6F3)
    val SoftWhite = Color(0xFFF5F5F7)

    val Red = Color(0xFFFF5A6E)
    val Green = Color(0xFF34D399)
    val Amber = Color(0xFFFFB547)
}

/** Semantic colour roles. */
@Immutable
data class VynqColors(
    val isDark: Boolean,
    val background: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val glassFill: Color,
    val glassEdgeTop: Color,
    val glassEdgeBottom: Color,
    val hairline: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val accent: Color,
    val onAccent: Color,
    val accentSoft: Color,
    val danger: Color,
    val success: Color,
    val warning: Color,
) {
    /** Iridescent brand sweep. Reserved for primary actions and brand moments only. */
    val brandSweep: Brush
        get() = Brush.linearGradient(listOf(VynqPalette.Blue500, VynqPalette.Violet500, VynqPalette.Purple400))
}

val DarkColors = VynqColors(
    isDark = true,
    background = VynqPalette.Ink950,
    surface = VynqPalette.Graphite850,
    surfaceRaised = VynqPalette.Graphite800,
    glassFill = VynqPalette.Graphite800.copy(alpha = 0.82f),
    glassEdgeTop = Color.White.copy(alpha = 0.14f),
    glassEdgeBottom = Color.White.copy(alpha = 0.04f),
    hairline = Color.White.copy(alpha = 0.08f),
    textPrimary = VynqPalette.SoftWhite,
    textSecondary = VynqPalette.Grey400,
    textTertiary = Color(0xFF6B6B78),
    accent = Color(0xFF5B7CFF),
    onAccent = Color.White,
    accentSoft = Color(0xFF5B7CFF).copy(alpha = 0.16f),
    danger = VynqPalette.Red,
    success = VynqPalette.Green,
    warning = VynqPalette.Amber,
)

val LightColors = VynqColors(
    isDark = false,
    background = VynqPalette.OffWhite,
    surface = Color.White,
    surfaceRaised = Color.White,
    glassFill = Color.White.copy(alpha = 0.86f),
    glassEdgeTop = Color.White,
    glassEdgeBottom = Color.Black.copy(alpha = 0.06f),
    hairline = Color.Black.copy(alpha = 0.08f),
    textPrimary = Color(0xFF0B0B12),
    textSecondary = Color(0xFF5E5E6B),
    textTertiary = Color(0xFF9A9AA6),
    accent = Color(0xFF3D5BFF),
    onAccent = Color.White,
    accentSoft = Color(0xFF3D5BFF).copy(alpha = 0.10f),
    danger = Color(0xFFE5394F),
    success = Color(0xFF12A774),
    warning = Color(0xFFD98A00),
)

/** 4-pt spacing scale. */
object VynqSpacing {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
    val xxxl = 32.dp
    val huge = 48.dp
    /** Horizontal screen gutter. */
    val gutter = 20.dp
}

/** Corner radius scale. Continuous-feeling radii, never sharp corners on surfaces. */
object VynqRadius {
    val xs = 6.dp
    val sm = 10.dp
    val md = 14.dp
    val lg = 20.dp
    val xl = 28.dp
    val pill = 999.dp
}
