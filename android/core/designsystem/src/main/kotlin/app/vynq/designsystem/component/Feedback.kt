package app.vynq.designsystem.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.vynq.designsystem.R
import app.vynq.designsystem.theme.VynqRadius
import app.vynq.designsystem.theme.VynqSpacing
import app.vynq.designsystem.theme.VynqTheme

/** The vynq symbol, optionally with the wordmark beneath. */
@Composable
fun BrandMark(modifier: Modifier = Modifier, symbolWidth: Dp = 96.dp, wordmark: Boolean = true) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Image(painterResource(R.drawable.vynq_symbol), contentDescription = "vynq", modifier = Modifier.width(symbolWidth))
        if (wordmark) {
            Spacer(Modifier.height(symbolWidth * 0.28f))
            Image(
                painterResource(R.drawable.vynq_wordmark), contentDescription = null,
                modifier = Modifier.width(symbolWidth * 1.15f),
                colorFilter = ColorFilter.tint(VynqTheme.colors.textPrimary),
            )
        }
    }
}

/** Compact wordmark for top bars. */
@Composable
fun Wordmark(modifier: Modifier = Modifier, width: Dp = 76.dp) {
    Image(
        painterResource(R.drawable.vynq_wordmark), contentDescription = "vynq",
        modifier = modifier.width(width), colorFilter = ColorFilter.tint(VynqTheme.colors.textPrimary),
    )
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    val c = VynqTheme.colors
    Column(
        modifier.fillMaxWidth().padding(horizontal = VynqSpacing.xxxl, vertical = VynqSpacing.huge),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(64.dp).background(c.accentSoft, RoundedCornerShape(VynqRadius.lg)), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(28.dp), tint = c.accent)
        }
        Spacer(Modifier.height(VynqSpacing.xl))
        Text(title, style = VynqTheme.type.title2, color = c.textPrimary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(VynqSpacing.sm))
        Text(message, style = VynqTheme.type.callout, color = c.textSecondary, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(VynqSpacing.xxl))
            action()
        }
    }
}

/** iOS-style segmented control, built on vynq tokens. */
@Composable
fun <T> SegmentedControl(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = VynqTheme.colors
    Row(
        modifier.fillMaxWidth().background(c.surfaceRaised, RoundedCornerShape(VynqRadius.md)).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            val bg by animateColorAsState(if (isSelected) c.accent else c.surfaceRaised, VynqTheme.motion.quick(), label = "seg")
            Box(
                Modifier
                    .weight(1f)
                    .background(bg, RoundedCornerShape(VynqRadius.sm))
                    .pressable(role = Role.Tab) { onSelect(option) }
                    .semantics { this.selected = isSelected; role = Role.Tab }
                    .padding(vertical = VynqSpacing.sm),
                contentAlignment = Alignment.Center,
            ) {
                Text(label(option), style = VynqTheme.type.subhead, color = if (isSelected) c.onAccent else c.textSecondary)
            }
        }
    }
}

@Composable
fun StatusPill(text: String, modifier: Modifier = Modifier, positive: Boolean = true) {
    val c = VynqTheme.colors
    val tint = if (positive) c.success else c.textTertiary
    Text(
        text,
        style = VynqTheme.type.caption,
        color = tint,
        modifier = modifier.background(tint.copy(alpha = 0.14f), RoundedCornerShape(VynqRadius.pill))
            .padding(horizontal = VynqSpacing.sm, vertical = 3.dp),
    )
}

@Composable
fun VynqAlert(title: String, message: String, confirm: String, onDismiss: () -> Unit) {
    val c = VynqTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surfaceRaised,
        shape = RoundedCornerShape(VynqRadius.xl),
        title = { Text(title, style = VynqTheme.type.title2, color = c.textPrimary) },
        text = { Text(message, style = VynqTheme.type.callout, color = c.textSecondary) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(confirm, style = VynqTheme.type.headline, color = c.accent) } },
    )
}

/**
 * Confirmation. [destructive] colours the action red; Cancel is always the safe
 * default. An optional [secondary] action (always red) sits under the main one.
 */
@Composable
fun VynqConfirm(
    title: String,
    message: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
    secondary: String? = null,
    onSecondary: () -> Unit = {},
) {
    val c = VynqTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surfaceRaised,
        shape = RoundedCornerShape(VynqRadius.xl),
        title = { Text(title, style = VynqTheme.type.title2, color = c.textPrimary) },
        text = { Text(message, style = VynqTheme.type.callout, color = c.textSecondary) },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", style = VynqTheme.type.headline, color = c.accent) } },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = onConfirm) {
                    Text(confirm, style = VynqTheme.type.headline, color = if (destructive) c.danger else c.accent)
                }
                if (secondary != null) TextButton(onClick = onSecondary) {
                    Text(secondary, style = VynqTheme.type.headline, color = c.danger)
                }
            }
        },
    )
}
