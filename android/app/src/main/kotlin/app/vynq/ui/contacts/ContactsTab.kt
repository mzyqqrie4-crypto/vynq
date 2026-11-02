package app.vynq.ui.contacts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import app.vynq.container
import app.vynq.data.auth.UsernameRules
import app.vynq.data.auth.userMessage
import app.vynq.data.messaging.Contact
import app.vynq.data.messaging.ContactState
import app.vynq.designsystem.component.ButtonStyle
import app.vynq.designsystem.component.EmptyState
import app.vynq.designsystem.component.ListGroup
import app.vynq.designsystem.component.ListRow
import app.vynq.designsystem.component.RowDivider
import app.vynq.designsystem.component.SectionLabel
import app.vynq.designsystem.component.StatusPill
import app.vynq.designsystem.component.VynqAlert
import app.vynq.designsystem.component.VynqButton
import app.vynq.designsystem.component.VynqConfirm
import app.vynq.designsystem.component.VynqTextField
import app.vynq.designsystem.icon.VynqIcons
import app.vynq.designsystem.theme.VynqSpacing
import app.vynq.designsystem.theme.VynqTheme
import app.vynq.ui.common.TabHeader
import app.vynq.ui.main.TabBarClearance
import app.vynq.ui.messaging.messagingSession
import app.vynq.ui.messaging.rememberLocal
import kotlinx.coroutines.launch

/**
 * Contacts are invite-only and found by exact username, with no address-book
 * upload (threat model T12/T13). Both sides must accept before anything can
 * be sent.
 */
@Composable
fun ContactsTab(onOpenContact: (String) -> Unit) {
    val session = messagingSession()
    val container = LocalContext.current.container
    val scope = rememberCoroutineScope()
    val contacts by rememberLocal(session, emptyList()) { it.dao.contacts() }
    val verified by rememberLocal(session, emptySet<String>()) { s ->
        s.dao.contacts().filter { s.messenger.isVerified(it.userId) }.map { it.userId }.toSet()
    }

    var username by rememberSaveable { mutableStateOf("") }
    var fieldError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var alert by remember { mutableStateOf<Pair<String, String>?>(null) }
    var removing by remember { mutableStateOf<Contact?>(null) }

    fun add() {
        val s = session ?: return
        UsernameRules.problem(username)?.let { fieldError = it; return }
        busy = true
        scope.launch {
            runCatching { s.messenger.requestContact(username) }
                .onSuccess { state ->
                    alert = if (state == ContactState.Accepted) "Connected" to "You and @${username.trim().lowercase()} can now message each other."
                    else "Request sent" to "@${username.trim().lowercase()} needs to accept before you can message each other."
                    username = ""
                }
                .onFailure { fieldError = it.userMessage() }
            busy = false
        }
    }

    fun act(block: suspend () -> Unit) {
        scope.launch { runCatching { block() }.onFailure { alert = "Couldn't update" to it.userMessage() } }
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            TabHeader("Contacts")
            Row(Modifier.padding(horizontal = VynqSpacing.gutter), horizontalArrangement = Arrangement.spacedBy(VynqSpacing.sm)) {
                VynqTextField(
                    value = username,
                    onValueChange = { username = it.lowercase().take(20); fieldError = null },
                    placeholder = "Add by exact username",
                    leadingIcon = VynqIcons.AddContact,
                    enabled = session != null,
                    compact = true,
                    error = fieldError,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Send,
                    ),
                    keyboardActions = KeyboardActions(onSend = { add() }),
                    modifier = Modifier.weight(1f),
                )
                if (username.length >= 3) VynqButton("Add", ::add, loading = busy, style = ButtonStyle.Secondary)
            }
            Spacer(Modifier.height(VynqSpacing.sm))
            Text(
                if (session == null) "Sign in to find people. vynq never reads your phone's contacts."
                else "Only exact usernames match. vynq never reads your phone's contacts.",
                style = VynqTheme.type.footnote,
                color = VynqTheme.colors.textTertiary,
                modifier = Modifier.padding(horizontal = VynqSpacing.gutter),
            )
        }

        val incoming = contacts.filter { it.state == ContactState.Incoming }
        val accepted = contacts.filter { it.state == ContactState.Accepted }
        val outgoing = contacts.filter { it.state == ContactState.Outgoing }

        if (incoming.isNotEmpty()) item {
            SectionLabel("Requests", Modifier.padding(horizontal = VynqSpacing.gutter - VynqSpacing.lg))
            ListGroup(Modifier.padding(horizontal = VynqSpacing.gutter)) {
                incoming.forEachIndexed { i, c ->
                    if (i > 0) RowDivider()
                    ListRow(c.label, icon = VynqIcons.AddContact, subtitle = "Wants to connect", trailing = {
                        Row(horizontalArrangement = Arrangement.spacedBy(VynqSpacing.xs)) {
                            VynqButton("Decline", { act { session!!.messenger.removeContact(c.userId) } }, style = ButtonStyle.Ghost)
                            VynqButton("Accept", { act { session!!.messenger.acceptContact(c.userId) } }, style = ButtonStyle.Secondary)
                        }
                    })
                }
            }
        }

        if (accepted.isNotEmpty()) item {
            SectionLabel("Your circle", Modifier.padding(horizontal = VynqSpacing.gutter - VynqSpacing.lg))
            ListGroup(Modifier.padding(horizontal = VynqSpacing.gutter)) {
                accepted.forEachIndexed { i, c ->
                    if (i > 0) RowDivider()
                    ListRow(
                        c.label,
                        icon = VynqIcons.Contacts,
                        subtitle = c.username?.let { "@$it" }?.takeIf { c.displayName != null },
                        onClick = { onOpenContact(c.userId) },
                        trailing = if (c.userId in verified) ({ StatusPill("Verified") }) else null,
                    )
                }
            }
        }

        if (outgoing.isNotEmpty()) item {
            SectionLabel("Waiting for them", Modifier.padding(horizontal = VynqSpacing.gutter - VynqSpacing.lg))
            ListGroup(Modifier.padding(horizontal = VynqSpacing.gutter)) {
                outgoing.forEachIndexed { i, c ->
                    if (i > 0) RowDivider()
                    ListRow(c.label, icon = VynqIcons.Mail, subtitle = "Request sent", trailing = {
                        VynqButton("Cancel", { removing = c }, style = ButtonStyle.Ghost)
                    })
                }
            }
        }

        if (contacts.none { it.state != ContactState.Removed }) item {
            EmptyState(
                icon = VynqIcons.Contacts,
                title = "Your circle starts here",
                message = "Add someone by their username. Once they accept, you can message each other end-to-end encrypted and verify each other's safety number.",
            )
        }
        item { Spacer(Modifier.height(TabBarClearance)) }
    }

    removing?.let { c ->
        VynqConfirm(
            title = "Cancel request?",
            message = "${c.label} won't see your request anymore.",
            confirm = "Cancel request",
            destructive = true,
            onConfirm = { removing = null; act { session!!.messenger.removeContact(c.userId) } },
            onDismiss = { removing = null },
        )
    }
    alert?.let { (title, message) -> VynqAlert(title, message, "OK") { alert = null } }
    // Pull the latest list whenever the tab opens.
    androidx.compose.runtime.LaunchedEffect(session) { if (session != null) container.messaging.syncNow() }
}
