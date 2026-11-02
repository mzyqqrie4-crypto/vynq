package app.vynq.ui.calls

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.vynq.data.messaging.MessageKind
import app.vynq.designsystem.component.ButtonStyle
import app.vynq.designsystem.component.EmptyState
import app.vynq.designsystem.component.VynqButton
import app.vynq.designsystem.component.pressable
import app.vynq.designsystem.icon.VynqIcons
import app.vynq.designsystem.theme.VynqSpacing
import app.vynq.designsystem.theme.VynqTheme
import app.vynq.ui.common.TabHeader
import app.vynq.ui.main.TabBarClearance
import app.vynq.ui.messaging.Avatar
import app.vynq.ui.messaging.messagingSession
import app.vynq.ui.messaging.rememberLocal
import app.vynq.ui.messaging.timeLabel

private data class CallEntry(val peer: String, val label: String, val text: String, val at: Long, val missed: Boolean)

/** Recent calls, from the call notes kept in each conversation on this phone. */
@Composable
fun CallsTab(onFindPeople: () -> Unit, onOpenChat: (String) -> Unit = {}) {
    val session = messagingSession()
    val calls by rememberLocal(session, emptyList()) { s ->
        s.dao.contacts().flatMap { c ->
            s.dao.messages(c.userId).filter { m ->
                m.kind == MessageKind.System && m.text.orEmpty().let { it.contains("call", ignoreCase = true) && !it.contains("safety") }
            }.map { CallEntry(c.userId, c.label, it.text.orEmpty(), it.sentAt, it.text.orEmpty().startsWith("Missed")) }
        }.sortedByDescending { it.at }.take(100)
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item { TabHeader("Calls") }
        items(calls, key = { "${it.peer}-${it.at}" }) { e ->
            Row(
                Modifier.fillMaxWidth().pressable { onOpenChat(e.peer) }.padding(horizontal = VynqSpacing.gutter, vertical = VynqSpacing.md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(VynqSpacing.md),
            ) {
                Avatar(e.label, 44)
                Column(Modifier.weight(1f)) {
                    Text(e.label, style = VynqTheme.type.headline, color = if (e.missed) VynqTheme.colors.danger else VynqTheme.colors.textPrimary)
                    Text(e.text, style = VynqTheme.type.footnote, color = VynqTheme.colors.textSecondary)
                }
                Text(timeLabel(e.at), style = VynqTheme.type.footnote, color = VynqTheme.colors.textTertiary)
            }
        }
        if (calls.isEmpty()) item {
            EmptyState(
                icon = VynqIcons.Video,
                title = "No calls yet",
                message = "Open a conversation and tap the phone or camera button. Calls are end-to-end encrypted.",
                action = { VynqButton("Find people", onFindPeople, style = ButtonStyle.Secondary) },
            )
        }
        item { Spacer(Modifier.height(TabBarClearance)) }
    }
}
