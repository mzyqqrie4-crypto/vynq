package app.vynq.ui.messaging

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vynq.container
import app.vynq.data.auth.userMessage
import app.vynq.data.messaging.HistoryState
import app.vynq.designsystem.component.ButtonStyle
import app.vynq.designsystem.component.ListGroup
import app.vynq.designsystem.component.StatusPill
import app.vynq.designsystem.component.VynqAlert
import app.vynq.designsystem.component.VynqConfirm
import app.vynq.designsystem.component.VynqTextField
import app.vynq.designsystem.component.VynqWideButton
import app.vynq.designsystem.component.pressable
import app.vynq.designsystem.icon.VynqIcons
import app.vynq.designsystem.theme.VynqRadius
import app.vynq.designsystem.theme.VynqSpacing
import app.vynq.designsystem.theme.VynqTheme
import app.vynq.ui.common.DetailScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Banner at the top of Chats when history needs the user: save the recovery key, or unlock on a new phone. */
@Composable
fun HistoryBanner(onRestore: () -> Unit, onSaveKey: () -> Unit) {
    val session = messagingSession() ?: return
    val state by session.messenger.history.collectAsStateWithLifecycle()
    val unseen by session.messenger.recoveryKeyPending.collectAsStateWithLifecycle()
    val (title, text, action) = when {
        state == HistoryState.Locked -> Triple("Restore your message history",
            "Approve this phone on your other phone, or enter your recovery key.", onRestore)
        state == HistoryState.Ready && unseen -> Triple("Save your recovery key",
            "You'll need it to get your messages on a new phone if this one is lost.", onSaveKey)
        else -> return
    }
    val c = VynqTheme.colors
    Row(
        Modifier.padding(horizontal = VynqSpacing.gutter, vertical = VynqSpacing.sm).fillMaxWidth()
            .background(c.accentSoft, RoundedCornerShape(VynqRadius.lg))
            .pressable(onClick = action).padding(VynqSpacing.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(VynqSpacing.md),
    ) {
        Icon(VynqIcons.Lock, null, Modifier.size(22.dp), tint = c.accent)
        Column(Modifier.weight(1f)) {
            Text(title, style = VynqTheme.type.headline, color = c.textPrimary)
            Text(text, style = VynqTheme.type.footnote, color = c.textSecondary)
        }
        Icon(VynqIcons.ChevronRight, null, Modifier.size(18.dp), tint = c.textTertiary)
    }
}

/** Shown on the user's existing phones when a new phone of theirs asks for history. */
@Composable
fun HistoryApprovalDialog() {
    val session = messagingSession() ?: return
    val requests by session.messenger.historyRequests.collectAsStateWithLifecycle()
    val request = requests.firstOrNull() ?: return
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    VynqConfirm(
        title = "Give your messages to ${request.deviceName}?",
        message = "A phone just signed in to your account and asked for your message history. Approve only if it's yours.\n\n" +
            "Not yours? Someone may have access to your email: tap “Not mine”, then secure your email account.",
        confirm = "Approve",
        onConfirm = {
            scope.launch {
                runCatching { session.messenger.approveHistoryRequest(request.deviceId) }
                    .onFailure { error = it.message ?: it.userMessage() }
            }
        },
        secondary = "Not mine",
        onSecondary = { session.messenger.dismissHistoryRequest(request.deviceId) },
        onDismiss = { session.messenger.dismissHistoryRequest(request.deviceId) },
    )
    error?.let { VynqAlert("Couldn't send", it, "OK") { error = null } }
}

@Composable
fun RecoveryKeyScreen(onBack: () -> Unit) {
    val session = messagingSession()
    val key by rememberLocal(session, null) { it.messenger.recoveryKeyText() }
    val c = VynqTheme.colors
    DetailScreen(title = "Recovery key", onBack = onBack) {
        Text(
            "Write this down and keep it somewhere safe, like a password manager or on paper at home. " +
                "If you lose this phone and have no other phone signed in, it's the only way to get your messages back. " +
                "vynq can't recover it for you.",
            style = VynqTheme.type.callout, color = c.textSecondary,
        )
        Spacer(Modifier.height(VynqSpacing.xxl))
        if (key == null) {
            Text("This phone doesn't have the recovery key. Restore your history first.", style = VynqTheme.type.footnote, color = c.textTertiary)
        } else {
            ListGroup {
                Column(
                    Modifier.fillMaxWidth().padding(VynqSpacing.xl).semantics { contentDescription = "Recovery key ${key!!}" },
                    verticalArrangement = Arrangement.spacedBy(VynqSpacing.md),
                ) {
                    key!!.split(" ").chunked(2).forEach { pair ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            pair.forEach { Text(it, style = VynqTheme.type.numeric, color = c.textPrimary) }
                        }
                    }
                }
            }
            Spacer(Modifier.height(VynqSpacing.md))
            Text(
                "Anyone with this key and access to your email could read your message history, so don't share it or post it online.",
                style = VynqTheme.type.footnote, color = c.textTertiary,
            )
            Spacer(Modifier.height(VynqSpacing.xxl))
            VynqWideButton("I've written it down", {
                session?.messenger?.markRecoveryKeySaved()
                onBack()
            })
        }
    }
}

@Composable
fun RestoreHistoryScreen(onBack: () -> Unit) {
    val session = messagingSession()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = VynqTheme.colors
    val state by (session?.messenger?.history ?: remember { MutableStateFlow(HistoryState.Unknown) }).collectAsStateWithLifecycle()
    var asked by remember { mutableStateOf<Int?>(null) }
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(session) {
        session?.let { s -> asked = runCatching { s.messenger.requestHistory() }.getOrDefault(0) }
    }
    LaunchedEffect(state) { if (state == HistoryState.Ready) onBack() }

    DetailScreen(title = "Restore history", onBack = onBack) {
        Text(
            "Your messages are stored encrypted. This phone needs your history key to read them, and vynq doesn't have it.",
            style = VynqTheme.type.callout, color = c.textSecondary,
        )

        Text("On another phone", style = VynqTheme.type.headline, color = c.textPrimary,
            modifier = Modifier.padding(top = VynqSpacing.xxl, bottom = VynqSpacing.sm))
        when (asked) {
            null -> Text("Checking your other phones…", style = VynqTheme.type.footnote, color = c.textTertiary)
            0 -> Text("None of your other phones is signed in. Use your recovery key below.", style = VynqTheme.type.footnote, color = c.textTertiary)
            else -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), color = c.accent, strokeWidth = 2.dp)
                Spacer(Modifier.size(VynqSpacing.sm))
                Text("Open vynq on your other phone and tap Approve. Waiting…", style = VynqTheme.type.footnote, color = c.textSecondary)
            }
        }

        Text("With your recovery key", style = VynqTheme.type.headline, color = c.textPrimary,
            modifier = Modifier.padding(top = VynqSpacing.xxl, bottom = VynqSpacing.sm))
        VynqTextField(
            value = input,
            onValueChange = { input = it.uppercase().take(60); error = null },
            placeholder = "XXXX XXXX XXXX …",
            leadingIcon = VynqIcons.Lock,
            error = error,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {}),
        )
        Spacer(Modifier.height(VynqSpacing.lg))
        VynqWideButton("Restore", {
            val s = session ?: return@VynqWideButton
            busy = true
            scope.launch {
                runCatching { s.messenger.unlockWithRecoveryKey(input) }
                    .onSuccess { ok -> if (!ok) error = "That recovery key doesn't match. Check each group of 4." }
                    .onFailure { error = it.userMessage() }
                busy = false
            }
        }, loading = busy, enabled = input.count { it.isLetterOrDigit() } >= 40)

        Spacer(Modifier.height(VynqSpacing.xxl))
        VynqWideButton("Not now", onBack, style = ButtonStyle.Secondary)
        Spacer(Modifier.height(VynqSpacing.sm))
        Text(
            "New messages work either way. Your old history stays locked, safe on the server, until you restore it.",
            style = VynqTheme.type.footnote, color = c.textTertiary,
        )
    }
    LaunchedEffect(Unit) { context.container.messaging.syncNow() }
}

/** Settings → Message history. */
@Composable
fun HistorySettingsScreen(onBack: () -> Unit, onRecoveryKey: () -> Unit, onRestore: () -> Unit) {
    val session = messagingSession()
    val c = VynqTheme.colors
    val state by (session?.messenger?.history ?: remember { MutableStateFlow(HistoryState.Unknown) }).collectAsStateWithLifecycle()
    DetailScreen(title = "Message history", onBack = onBack) {
        Text(
            "Your messages are kept, end-to-end encrypted, for as long as your account exists, so they're there on every phone you sign in on. " +
                "vynq stores them but can't read them. Deleting your account deletes them.",
            style = VynqTheme.type.callout, color = c.textSecondary,
        )
        Spacer(Modifier.height(VynqSpacing.lg))
        when (state) {
            HistoryState.Ready -> StatusPill("Backed up")
            HistoryState.Locked -> StatusPill("Locked on this phone", positive = false)
            HistoryState.Unknown -> StatusPill("Checking…", positive = false)
        }
        Spacer(Modifier.height(VynqSpacing.xxl))
        if (state == HistoryState.Locked) VynqWideButton("Restore history", onRestore)
        if (state == HistoryState.Ready) VynqWideButton("Show recovery key", onRecoveryKey, style = ButtonStyle.Secondary)
    }
}
