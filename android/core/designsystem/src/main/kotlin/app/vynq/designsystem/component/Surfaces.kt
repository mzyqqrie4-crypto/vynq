package app.vynq.designsystem.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.vynq.designsystem.theme.VynqMotion
import app.vynq.designsystem.theme.VynqRadius
import app.vynq.designsystem.theme.VynqTheme

/**
 * Glass material: translucent fill plus a fine top-lit edge. Used only for
 * floating chrome (tab bar, call controls, overlays), never for content cards.
 *
 * No backdrop blur in Phase 1. Compose has no cheap backdrop blur below API 31,
 * and blur over live video costs GPU time the call needs. The high-opacity fill
 * keeps text contrast stable. Real blur is evaluated in Phase 5 with measurements.
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(VynqRadius.xl),
    content: @Composable BoxScope.() -> Unit,
) {
    val c = VynqTheme.colors
    Box(
        modifier
            .clip(shape)
            .background(c.glassFill, shape)
            .border(0.75.dp, Brush.verticalGradient(listOf(c.glassEdgeTop, c.glassEdgeBottom)), shape),
        content = content,
    )
}

/** Inset grouped container for list rows (settings, profile). */
@Composable
fun ListGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = VynqTheme.colors
    val shape = RoundedCornerShape(VynqRadius.lg)
    Column(
        modifier
            .clip(shape)
            .background(c.surface, shape)
            .border(0.5.dp, c.hairline, shape),
        content = content,
    )
}

/** Clickable with a restrained scale-down on press. Respects reduced motion. */
@Composable
fun Modifier.pressable(
    enabled: Boolean = true,
    role: Role = Role.Button,
    onClick: () -> Unit,
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) VynqMotion.PRESSED_SCALE else 1f,
        animationSpec = VynqTheme.motion.quick(),
        label = "press",
    )
    return this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = role, onClick = onClick)
}
