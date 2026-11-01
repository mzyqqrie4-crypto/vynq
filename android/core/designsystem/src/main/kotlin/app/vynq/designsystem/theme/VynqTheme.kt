package app.vynq.designsystem.theme

import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

private val LocalColors = staticCompositionLocalOf { DarkColors }
private val LocalTypography = staticCompositionLocalOf { DefaultTypography }
private val LocalMotion = staticCompositionLocalOf { VynqMotion(reduced = false) }

enum class ThemeMode { System, Light, Dark }

@Composable
fun VynqTheme(mode: ThemeMode = ThemeMode.System, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val colors = if (dark) DarkColors else LightColors
    val context = LocalContext.current
    val reduced = remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }

    // Material components read from MaterialTheme; map vynq roles onto it so
    // text fields, ripples and dialogs inherit the brand instead of defaults.
    val scheme = if (dark) {
        darkColorScheme(
            primary = colors.accent, onPrimary = colors.onAccent,
            background = colors.background, onBackground = colors.textPrimary,
            surface = colors.surface, onSurface = colors.textPrimary,
            surfaceVariant = colors.surfaceRaised, onSurfaceVariant = colors.textSecondary,
            surfaceContainerHigh = colors.surfaceRaised,
            outline = colors.hairline, outlineVariant = colors.hairline, error = colors.danger,
        )
    } else {
        lightColorScheme(
            primary = colors.accent, onPrimary = colors.onAccent,
            background = colors.background, onBackground = colors.textPrimary,
            surface = colors.surface, onSurface = colors.textPrimary,
            surfaceVariant = colors.surfaceRaised, onSurfaceVariant = colors.textSecondary,
            surfaceContainerHigh = colors.surfaceRaised,
            outline = colors.hairline, outlineVariant = colors.hairline, error = colors.danger,
        )
    }

    CompositionLocalProvider(
        LocalColors provides colors,
        LocalTypography provides DefaultTypography,
        LocalMotion provides VynqMotion(reduced),
    ) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

object VynqTheme {
    val colors: VynqColors
        @Composable @ReadOnlyComposable get() = LocalColors.current
    val type: VynqTypography
        @Composable @ReadOnlyComposable get() = LocalTypography.current
    val motion: VynqMotion
        @Composable @ReadOnlyComposable get() = LocalMotion.current
}
