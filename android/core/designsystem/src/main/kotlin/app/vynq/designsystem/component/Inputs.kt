package app.vynq.designsystem.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.vynq.designsystem.theme.VynqRadius
import app.vynq.designsystem.theme.VynqSpacing
import app.vynq.designsystem.theme.VynqTheme

/** Single-line text field. The border picks up the accent on focus and danger on error. */
@Composable
fun VynqTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    error: String? = null,
    enabled: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    compact: Boolean = false,
) {
    val c = VynqTheme.colors
    var focused by remember { mutableStateOf(false) }
    val border by animateColorAsState(
        when {
            error != null -> c.danger
            focused -> c.accent.copy(alpha = 0.7f)
            else -> c.hairline
        },
        VynqTheme.motion.quick(), label = "border",
    )
    val shape = RoundedCornerShape(if (compact) VynqRadius.md else VynqRadius.lg)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(VynqSpacing.xs)) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            textStyle = VynqTheme.type.body.copy(color = c.textPrimary),
            cursorBrush = SolidColor(c.accent),
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused }
                .then(if (error != null) Modifier.semantics { error(error) } else Modifier),
            decorationBox = { inner ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = if (compact) 40.dp else 52.dp)
                        .background(c.surfaceRaised, shape)
                        .border(0.75.dp, border, shape)
                        .padding(horizontal = VynqSpacing.lg),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(VynqSpacing.sm),
                ) {
                    if (leadingIcon != null) Icon(leadingIcon, null, Modifier.size(18.dp), tint = c.textTertiary)
                    Box(Modifier.weight(1f)) {
                        if (value.isEmpty()) Text(placeholder, style = VynqTheme.type.body, color = c.textTertiary)
                        inner()
                    }
                }
            },
        )
        if (error != null) {
            Text(error, style = VynqTheme.type.footnote, color = c.danger, modifier = Modifier.padding(start = VynqSpacing.xs))
        }
    }
}
