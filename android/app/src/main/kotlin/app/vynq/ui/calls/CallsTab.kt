package app.vynq.ui.calls

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.vynq.designsystem.component.ButtonStyle
import app.vynq.designsystem.component.EmptyState
import app.vynq.designsystem.component.VynqButton
import app.vynq.designsystem.icon.VynqIcons
import app.vynq.ui.common.TabHeader

/** Call history. Populated by the calling stack in Phase 4. */
@Composable
fun CallsTab(onFindPeople: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TabHeader("Calls")
        EmptyState(
            icon = VynqIcons.Video,
            title = "No calls yet",
            message = "Video and voice calls with your contacts will appear here.",
            action = { VynqButton("Find people", onFindPeople, style = ButtonStyle.Secondary) },
        )
    }
}
