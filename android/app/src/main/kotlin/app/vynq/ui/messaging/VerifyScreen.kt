package app.vynq.ui.messaging

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import app.vynq.data.auth.userMessage
import app.vynq.designsystem.component.ButtonStyle
import app.vynq.designsystem.component.ListGroup
import app.vynq.designsystem.component.StatusPill
import app.vynq.designsystem.component.VynqAlert
import app.vynq.designsystem.component.VynqWideButton
import app.vynq.designsystem.theme.VynqSpacing
import app.vynq.designsystem.theme.VynqTheme
import app.vynq.ui.common.DetailScreen
import kotlinx.coroutines.launch

/**
 * Safety numbers: both phones show the same 60 digits only if no one has
 * swapped keys in between. Compare them in person or on a call you trust,
 * then mark the contact verified. A new device clears verification.
 */
@Composable
fun VerifyScreen(peer: String, onBack: () -> Unit) {
    val session = messagingSession()
    val scope = rememberCoroutineScope()
    val c = VynqTheme.colors
    var refreshed by remember { mutableStateOf(false) }
    var alert by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(session) {
        session?.let { s -> runCatching { s.messenger.refreshDevices(peer) }.onFailure { alert = it.userMessage() } }
        refreshed = true
    }
    val contact by rememberLocal(session, null, peer) { it.dao.contact(peer) }
    val numbers by rememberLocal(session, emptyList(), peer, refreshed) { s ->
        s.messenger.safetyNumbers(peer).map { (device, digits) -> device.deviceId to digits }
    }
    val verified by rememberLocal(session, false, peer, refreshed) { it.messenger.isVerified(peer) }
    val name = contact?.label ?: "this contact"

    DetailScreen(title = "Safety number", onBack = onBack) {
        Text(
            "If these digits match what $name sees on their phone, your messages can't be read by anyone in between, including vynq. Compare them in person or on a call where you recognise their voice.",
            style = VynqTheme.type.callout,
            color = c.textSecondary,
        )
        Spacer(Modifier.height(VynqSpacing.lg))
        if (verified) StatusPill("Verified")
        Spacer(Modifier.height(VynqSpacing.lg))

        when {
            !refreshed -> Text("Checking their devices…", style = VynqTheme.type.footnote, color = c.textTertiary)
            numbers.isEmpty() -> Text(
                "$name hasn't set up encryption on any device yet, so there's nothing to compare. This happens until they open vynq once after accepting you.",
                style = VynqTheme.type.footnote, color = c.textTertiary,
            )
        }

        numbers.forEachIndexed { i, (_, digits) ->
            if (numbers.size > 1) {
                Text("Device ${i + 1}", style = VynqTheme.type.subhead, color = c.textSecondary,
                    modifier = Modifier.padding(top = VynqSpacing.md, bottom = VynqSpacing.sm))
            }
            ListGroup {
                Column(
                    Modifier.fillMaxWidth().padding(VynqSpacing.lg).semantics { contentDescription = "Safety number ${digits.chunked(5).joinToString(" ")}" },
                    verticalArrangement = Arrangement.spacedBy(VynqSpacing.sm),
                ) {
                    digits.chunked(5).chunked(4).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            row.forEach { group -> Text(group, style = VynqTheme.type.numeric, color = c.textPrimary, textAlign = TextAlign.Center) }
                        }
                    }
                }
            }
        }

        if (numbers.isNotEmpty()) {
            Spacer(Modifier.height(VynqSpacing.xxl))
            if (verified) {
                VynqWideButton("Clear verification", {
                    scope.launch { session?.messenger?.setVerified(peer, false) }
                }, style = ButtonStyle.Secondary)
            } else {
                VynqWideButton("They match — mark as verified", {
                    scope.launch { session?.messenger?.setVerified(peer, true) }
                })
            }
            Spacer(Modifier.height(VynqSpacing.md))
            Text(
                "If the numbers don't match, don't mark them verified. It can mean someone is intercepting, or simply that one of you is on a new device.",
                style = VynqTheme.type.footnote, color = c.textTertiary,
            )
        }
    }

    alert?.let { VynqAlert("Couldn't check devices", it, "OK") { alert = null } }
}
