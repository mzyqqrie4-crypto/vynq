package app.vynq.ui.chats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.vynq.data.messaging.Conversation
import app.vynq.data.messaging.MessageKind
import app.vynq.designsystem.component.EmptyState
import app.vynq.designsystem.component.VynqButton
import app.vynq.designsystem.component.VynqIconButton
import app.vynq.designsystem.component.VynqTextField
import app.vynq.designsystem.component.Wordmark
import app.vynq.designsystem.component.pressable
import app.vynq.designsystem.icon.VynqIcons
import app.vynq.designsystem.theme.VynqSpacing
import app.vynq.designsystem.theme.VynqTheme
import app.vynq.ui.Routes
import app.vynq.ui.main.TabBarClearance
import app.vynq.ui.messaging.HistoryBanner
import app.vynq.ui.messaging.Avatar
import app.vynq.ui.messaging.messagingSession
import app.vynq.ui.messaging.rememberLocal
import app.vynq.ui.messaging.timeLabel

/** Conversations from the encrypted local store. No sample data: empty means empty. */
@Composable
fun ChatsTab(onFindPeople: () -> Unit, onOpenChat: (String) -> Unit, onOpen: (String) -> Unit = {}) {
    val session = messagingSession()
    var query by rememberSaveable { mutableStateOf("") }
    val conversations by rememberLocal(session, emptyList()) { it.dao.conversations() }
    val visible = conversations.filter { query.isBlank() || it.contact.label.contains(query.trim(), ignoreCase = true) }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(start = VynqSpacing.gutter, end = VynqSpacing.md, top = VynqSpacing.lg),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Wordmark()
                VynqIconButton(VynqIcons.Plus, "New conversation", onFindPeople, accent = true, size = 38)
            }
            Spacer(Modifier.height(VynqSpacing.lg))
            VynqTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = "Search",
                leadingIcon = VynqIcons.Search,
                compact = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.padding(horizontal = VynqSpacing.gutter),
            )
            Spacer(Modifier.height(VynqSpacing.sm))
            HistoryBanner(onRestore = { onOpen(Routes.RESTORE_HISTORY) }, onSaveKey = { onOpen(Routes.RECOVERY_KEY) })
            app.vynq.ui.update.UpdateBanner()
        }
        items(visible, key = { it.contact.userId }) { ConversationRow(it) { onOpenChat(it.contact.userId) } }
        if (visible.isEmpty()) {
            item {
                EmptyState(
                    icon = VynqIcons.Chats,
                    title = if (query.isBlank()) "No conversations yet" else "No matches",
                    message = if (query.isBlank()) {
                        "vynq is for the people you trust. Add someone by their username, then say hello."
                    } else {
                        "Nothing matches “${query.trim()}”."
                    },
                    action = if (query.isBlank()) {
                        { VynqButton("Find people", onFindPeople, leadingIcon = VynqIcons.AddContact) }
                    } else null,
                )
            }
        }
        item { Spacer(Modifier.height(TabBarClearance)) }
    }
}

@Composable
private fun ConversationRow(conversation: Conversation, onClick: () -> Unit) {
    val c = VynqTheme.colors
    val last = conversation.last
    val preview = when {
        last == null -> ""
        last.kind == MessageKind.Photo -> if (last.outgoing) "You: Photo" else "Photo"
        last.kind == MessageKind.System -> last.text.orEmpty()
        last.outgoing -> "You: ${last.text.orEmpty()}"
        else -> last.text.orEmpty()
    }
    Row(
        Modifier.fillMaxWidth().pressable(onClick = onClick).padding(horizontal = VynqSpacing.gutter, vertical = VynqSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(VynqSpacing.md),
    ) {
        Avatar(conversation.contact.label, 48, conversation.contact.userId)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(conversation.contact.label, style = VynqTheme.type.headline, color = c.textPrimary, modifier = Modifier.weight(1f), maxLines = 1)
                last?.let { Text(timeLabel(it.sentAt), style = VynqTheme.type.footnote, color = c.textTertiary) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(preview, style = VynqTheme.type.callout, color = c.textSecondary, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (conversation.unread > 0) {
                    Box(
                        Modifier.padding(start = VynqSpacing.sm).widthIn(min = 20.dp).background(c.accent, CircleShape)
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text("${conversation.unread}", style = VynqTheme.type.caption, color = c.onAccent) }
                }
            }
        }
    }
}
