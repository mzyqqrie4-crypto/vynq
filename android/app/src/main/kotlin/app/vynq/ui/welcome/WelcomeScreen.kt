package app.vynq.ui.welcome

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import app.vynq.BuildConfig
import app.vynq.designsystem.component.BrandMark
import app.vynq.designsystem.component.ButtonStyle
import app.vynq.designsystem.component.VynqWideButton
import app.vynq.designsystem.theme.VynqPalette
import app.vynq.designsystem.theme.VynqSpacing
import app.vynq.designsystem.theme.VynqTheme

@Composable
fun WelcomeScreen(onGetStarted: () -> Unit, onSignIn: () -> Unit, onDesignPreview: () -> Unit) {
    val c = VynqTheme.colors
    val motion = VynqTheme.motion
    // One calm entrance: the mark rises and fades in once. Nothing loops.
    val enter = remember { Animatable(if (motion.reduced) 1f else 0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, motion.standard()) }

    Box(
        Modifier
            .fillMaxSize()
            .background(c.background)
            .background(
                Brush.radialGradient(
                    listOf(VynqPalette.Violet500.copy(alpha = if (c.isDark) 0.22f else 0.10f), c.background.copy(alpha = 0f)),
                    radius = 900f,
                ),
            ),
    ) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = VynqSpacing.xxl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))
            BrandMark(
                symbolWidth = 132.dp,
                modifier = Modifier.graphicsLayer {
                    alpha = enter.value
                    translationY = (1f - enter.value) * 24.dp.toPx()
                },
            )
            Spacer(Modifier.height(VynqSpacing.lg))
            Text("REAL CONNECTIONS", style = VynqTheme.type.overline, color = c.textSecondary, modifier = Modifier.graphicsLayer { alpha = enter.value })
            Spacer(Modifier.weight(1.2f))

            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(VynqSpacing.sm)) {
                VynqWideButton("Get started", onGetStarted)
                VynqWideButton("I already have an account", onSignIn, style = ButtonStyle.Ghost)
                if (BuildConfig.DEBUG) {
                    // Debug builds only: lets the design shell be reviewed before accounts exist.
                    // Every screen behind it shows real empty states, never sample data.
                    VynqWideButton("Explore design preview (debug)", onDesignPreview, style = ButtonStyle.Secondary)
                }
            }
            Spacer(Modifier.height(VynqSpacing.xl))
        }
    }
}
