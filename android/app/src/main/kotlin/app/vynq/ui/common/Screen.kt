package app.vynq.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.vynq.designsystem.component.VynqIconButton
import app.vynq.designsystem.icon.VynqIcons
import app.vynq.designsystem.theme.VynqSpacing
import app.vynq.designsystem.theme.VynqTheme

/** Pushed detail screen: back button, large title, scrolling content. */
@Composable
fun DetailScreen(
    title: String,
    onBack: (() -> Unit)?,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = VynqSpacing.sm, vertical = VynqSpacing.xs)) {
            if (onBack != null) VynqIconButton(VynqIcons.Back, "Back", onBack, size = 44)
            else Spacer(Modifier.height(44.dp))
        }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = VynqSpacing.gutter)
                .navigationBarsPadding(),
        ) {
            LargeTitle(title)
            content()
            Spacer(Modifier.height(VynqSpacing.huge))
        }
    }
}

@Composable
fun LargeTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = VynqTheme.type.largeTitle,
        color = VynqTheme.colors.textPrimary,
        modifier = modifier.padding(top = VynqSpacing.xs, bottom = VynqSpacing.sm).semantics { heading() },
    )
}

/** Top bar for root tabs: large title on the left, actions on the right. */
@Composable
fun TabHeader(title: String, actions: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(start = VynqSpacing.gutter, end = VynqSpacing.md, top = VynqSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        LargeTitle(title)
        Row(horizontalArrangement = Arrangement.spacedBy(VynqSpacing.xs)) { actions() }
    }
}
