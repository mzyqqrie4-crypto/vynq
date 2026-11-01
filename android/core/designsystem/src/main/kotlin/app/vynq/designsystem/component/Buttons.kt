package app.vynq.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.vynq.designsystem.theme.VynqRadius
import app.vynq.designsystem.theme.VynqSpacing
import app.vynq.designsystem.theme.VynqTheme

enum class ButtonStyle { Primary, Secondary, Ghost, Destructive }

/**
 * Primary carries the brand sweep, so use one per screen at most.
 * Secondary is a quiet filled surface, Ghost is text-only.
 */
@Composable
fun VynqButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: ButtonStyle = ButtonStyle.Primary,
    enabled: Boolean = true,
    loading: Boolean = false,
    leadingIcon: ImageVector? = null,
) {
    val c = VynqTheme.colors
    val shape = RoundedCornerShape(VynqRadius.pill)
    val content = when (style) {
        ButtonStyle.Primary -> c.onAccent
        ButtonStyle.Secondary -> c.textPrimary
        ButtonStyle.Ghost -> c.accent
        ButtonStyle.Destructive -> c.danger
    }
    var m = modifier
        .heightIn(min = 52.dp)
        .alpha(if (enabled) 1f else 0.4f)
        .clip(shape)
    m = when (style) {
        ButtonStyle.Primary -> m.background(c.brandSweep, shape)
        ButtonStyle.Secondary, ButtonStyle.Destructive -> m.background(c.surfaceRaised, shape).border(0.5.dp, c.hairline, shape)
        ButtonStyle.Ghost -> m
    }
    Box(
        m.pressable(enabled = enabled && !loading, onClick = onClick).padding(horizontal = VynqSpacing.xxl),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(20.dp), color = content, strokeWidth = 2.dp)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(VynqSpacing.sm)) {
                if (leadingIcon != null) Icon(leadingIcon, null, Modifier.size(20.dp), tint = content)
                Text(text, style = VynqTheme.type.headline, color = content)
            }
        }
    }
}

/** Full-width convenience. */
@Composable
fun VynqWideButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: ButtonStyle = ButtonStyle.Primary,
    enabled: Boolean = true,
    loading: Boolean = false,
) = VynqButton(text, onClick, modifier.fillMaxWidth(), style, enabled, loading)

/** Circular icon button. [accent] fills it with the brand sweep (e.g. "new chat"). */
@Composable
fun VynqIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    enabled: Boolean = true,
    size: Int = 40,
) {
    val c = VynqTheme.colors
    val bg = if (accent) Modifier.background(c.brandSweep, CircleShape) else Modifier.background(Color.Transparent)
    Box(
        modifier
            .size(size.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(CircleShape)
            .then(bg)
            .pressable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, Modifier.size(22.dp), tint = if (accent) c.onAccent else c.textPrimary)
    }
}
