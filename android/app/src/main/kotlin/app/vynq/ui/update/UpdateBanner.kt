package app.vynq.ui.update

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.vynq.data.update.UpdateCheck
import app.vynq.designsystem.component.pressable
import app.vynq.designsystem.icon.VynqIcons
import app.vynq.designsystem.theme.VynqRadius
import app.vynq.designsystem.theme.VynqSpacing
import app.vynq.designsystem.theme.VynqTheme

/** "A new version is available" on the Chats tab; opens the GitHub release in the browser. */
@Composable
fun UpdateBanner() {
    val context = LocalContext.current
    val release by produceState<UpdateCheck.Release?>(null) { value = UpdateCheck.newer(context) }
    val r = release ?: return
    val c = VynqTheme.colors
    Row(
        Modifier.padding(horizontal = VynqSpacing.gutter, vertical = VynqSpacing.sm).fillMaxWidth()
            .background(c.accentSoft, RoundedCornerShape(VynqRadius.lg))
            .pressable {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(r.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            .padding(VynqSpacing.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(VynqSpacing.md),
    ) {
        Icon(VynqIcons.Info, null, Modifier.size(22.dp), tint = c.accent)
        Column(Modifier.weight(1f)) {
            Text("vynq ${r.version} is available", style = VynqTheme.type.headline, color = c.textPrimary)
            Text("Tap to download it from GitHub. Your messages stay on this phone.", style = VynqTheme.type.footnote, color = c.textSecondary)
        }
        Icon(VynqIcons.ChevronRight, null, Modifier.size(18.dp), tint = c.textTertiary)
    }
}
