package app.vynq.ui.messaging

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import app.vynq.data.auth.userMessage
import app.vynq.data.messaging.ContactState
import app.vynq.designsystem.component.ListGroup
import app.vynq.designsystem.component.ListRow
import app.vynq.designsystem.component.RowDivider
import app.vynq.designsystem.component.SectionLabel
import app.vynq.designsystem.component.StatusPill
import app.vynq.designsystem.component.VynqAlert
import app.vynq.designsystem.component.VynqConfirm
import app.vynq.designsystem.component.VynqTextField
import app.vynq.designsystem.icon.VynqIcons
import app.vynq.designsystem.theme.VynqRadius
import app.vynq.designsystem.theme.VynqSpacing
import app.vynq.designsystem.theme.VynqTheme
import app.vynq.ui.common.DetailScreen
import kotlinx.coroutines.launch

/** A contact's details: picture, private nickname, safety number, and removing them. */
@Composable
fun ContactInfoScreen(peer: String, onBack: () -> Unit, onVerify: () -> Unit, onGone: () -> Unit, onMessage: () -> Unit) {
    val session = messagingSession()
    val scope = rememberCoroutineScope()
    val c = VynqTheme.colors
    val contact by rememberLocal(session, null, peer) { it.dao.contact(peer) }
    val verified by rememberLocal(session, false, peer) { it.messenger.isVerified(peer) }
    var editing by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }

    fun act(block: suspend () -> Unit, then: () -> Unit = {}) {
        scope.launch { runCatching { block() }.onSuccess { then() }.onFailure { failure = it.userMessage() } }
    }

    DetailScreen("Contact", onBack) {
        val ct = contact
        Spacer(Modifier.height(VynqSpacing.xl))
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(VynqSpacing.xs)) {
            Avatar(ct?.label ?: "?", 96, peer)
            Spacer(Modifier.height(VynqSpacing.sm))
            Text(ct?.label ?: "", style = VynqTheme.type.title2, color = c.textPrimary, textAlign = TextAlign.Center)
            ct?.username?.let { u -> if (ct.label != "@$u") Text("@$u", style = VynqTheme.type.subhead, color = c.textSecondary) }
            if (ct?.state == ContactState.Removed) StatusPill("No longer a contact")
        }
        Spacer(Modifier.height(VynqSpacing.xl))

        ListGroup {
            ListRow("Message", icon = VynqIcons.Chats, onClick = onMessage)
            RowDivider()
            ListRow(
                "Nickname", icon = VynqIcons.Contacts,
                subtitle = "Only you see it, on all your phones",
                value = ct?.nickname ?: "Add",
                onClick = { editing = true },
            )
            RowDivider()
            ListRow(
                "Safety number", icon = if (verified) VynqIcons.Shield else VynqIcons.Lock,
                subtitle = if (verified) "Verified" else "Compare to make sure no one is in the middle",
                onClick = onVerify,
            )
        }

        Spacer(Modifier.height(VynqSpacing.lg))
        SectionLabel("Manage")
        ListGroup {
            if (ct?.state == ContactState.Removed) {
                ListRow("Delete chat", icon = VynqIcons.Warning, iconTint = c.danger,
                    subtitle = "From this phone, your other phones and your encrypted history",
                    onClick = { confirmDelete = true })
            } else {
                ListRow("Remove contact", icon = VynqIcons.Warning, iconTint = c.danger,
                    subtitle = "They can't message or call you until one of you adds the other again",
                    onClick = { confirmRemove = true })
            }
        }
        Spacer(Modifier.height(VynqSpacing.xxl))
    }

    if (editing) {
        NicknameDialog(
            initial = contact?.nickname.orEmpty(),
            username = contact?.username,
            onSave = { name -> editing = false; act({ session!!.messenger.setNickname(peer, name) }) },
            onDismiss = { editing = false },
        )
    }
    if (confirmRemove) {
        val name = contact?.label ?: "this contact"
        VynqConfirm(
            title = "Remove $name?",
            message = "They won't be able to message or call you until one of you adds the other again. You can keep your chat, read-only, or delete it from all your phones.",
            confirm = "Remove and delete chat",
            destructive = true,
            onConfirm = { confirmRemove = false; act({ session!!.messenger.removeContact(peer, deleteChat = true) }, onGone) },
            secondary = "Remove, keep chat",
            onSecondary = { confirmRemove = false; act({ session!!.messenger.removeContact(peer, deleteChat = false) }) },
            onDismiss = { confirmRemove = false },
        )
    }
    if (confirmDelete) {
        VynqConfirm(
            title = "Delete this chat?",
            message = "It's deleted from this phone, your other phones and your encrypted history. This can't be undone.",
            confirm = "Delete chat",
            destructive = true,
            onConfirm = { confirmDelete = false; act({ session!!.messenger.deleteChat(peer) }, onGone) },
            onDismiss = { confirmDelete = false },
        )
    }
    failure?.let { VynqAlert("Couldn't do that", it, "OK") { failure = null } }
}

@Composable
private fun NicknameDialog(initial: String, username: String?, onSave: (String?) -> Unit, onDismiss: () -> Unit) {
    val c = VynqTheme.colors
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surfaceRaised,
        shape = RoundedCornerShape(VynqRadius.xl),
        title = { Text("Nickname", style = VynqTheme.type.headline, color = c.textPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(VynqSpacing.sm)) {
                Text(
                    "A name only you see${username?.let { " instead of @$it" } ?: ""}. They're never told.",
                    style = VynqTheme.type.footnote, color = c.textSecondary,
                )
                VynqTextField(
                    value = text,
                    onValueChange = { text = it.take(40) },
                    placeholder = username?.let { "@$it" } ?: "Nickname",
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onSave(text) }),
                    modifier = Modifier.padding(top = VynqSpacing.xs),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("Save", color = c.accent) } },
        dismissButton = {
            if (initial.isNotEmpty()) TextButton(onClick = { onSave(null) }) { Text("Remove", color = c.danger) }
            else TextButton(onClick = onDismiss) { Text("Cancel", color = c.textSecondary) }
        },
    )
}
