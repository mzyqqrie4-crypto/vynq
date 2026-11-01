package app.vynq.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.vynq.designsystem.icon.VynqIcons
import app.vynq.designsystem.theme.VynqRadius
import app.vynq.designsystem.theme.VynqSpacing
import app.vynq.designsystem.theme.VynqTheme

/**
 * A settings-style row. When [onClick] is null the row is static. A disabled row
 * shows [disabledReason] instead of a chevron, so nothing looks tappable that isn't.
 */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconTint: Color? = null,
    subtitle: String? = null,
    value: String? = null,
    enabled: Boolean = true,
    disabledReason: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = VynqTheme.colors
    val clickable = enabled && onClick != null
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 54.dp)
            .then(if (clickable) Modifier.pressable(onClick = onClick) else Modifier)
            .alpha(if (enabled) 1f else 0.45f)
            .padding(horizontal = VynqSpacing.lg, vertical = VynqSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(VynqSpacing.md),
    ) {
        if (icon != null) {
            Box(
                Modifier.size(30.dp).background((iconTint ?: c.accent).copy(alpha = 0.14f), RoundedCornerShape(VynqRadius.sm)),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, Modifier.size(18.dp), tint = iconTint ?: c.accent) }
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = VynqTheme.type.body, color = c.textPrimary)
            if (subtitle != null) Text(subtitle, style = VynqTheme.type.footnote, color = c.textSecondary)
        }
        when {
            trailing != null -> trailing()
            !enabled && disabledReason != null ->
                Text(disabledReason, style = VynqTheme.type.footnote, color = c.textTertiary)
            else -> {
                if (value != null) Text(value, style = VynqTheme.type.callout, color = c.textSecondary)
                if (clickable) Icon(VynqIcons.ChevronRight, null, Modifier.size(18.dp), tint = c.textTertiary)
            }
        }
    }
}

@Composable
fun RowDivider(inset: Boolean = true) {
    HorizontalDivider(
        Modifier.padding(start = if (inset) 58.dp else 0.dp),
        thickness = 0.5.dp,
        color = VynqTheme.colors.hairline,
    )
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = VynqTheme.type.overline,
        color = VynqTheme.colors.textTertiary,
        modifier = modifier.padding(start = VynqSpacing.lg, bottom = VynqSpacing.sm, top = VynqSpacing.xxl),
    )
}
