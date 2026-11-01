package app.vynq.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vynq.BuildConfig
import app.vynq.container
import app.vynq.data.network.NetworkSnapshot
import app.vynq.data.network.Transport
import app.vynq.data.prefs.CallQuality
import app.vynq.designsystem.component.BrandMark
import app.vynq.designsystem.component.ListGroup
import app.vynq.designsystem.component.ListRow
import app.vynq.designsystem.component.RowDivider
import app.vynq.designsystem.component.SectionLabel
import app.vynq.designsystem.component.SegmentedControl
import app.vynq.designsystem.component.StatusPill
import app.vynq.designsystem.icon.VynqIcons
import app.vynq.designsystem.theme.ThemeMode
import app.vynq.designsystem.theme.VynqSpacing
import app.vynq.designsystem.theme.VynqTheme
import app.vynq.ui.common.DetailScreen
import kotlinx.coroutines.launch

@Composable
fun AppearanceScreen(onBack: () -> Unit) {
    val prefs = LocalContext.current.container.preferences
    val mode by prefs.themeMode.collectAsStateWithLifecycle(ThemeMode.System)
    val scope = rememberCoroutineScope()
    DetailScreen("Appearance", onBack) {
        Spacer(Modifier.height(VynqSpacing.lg))
        SegmentedControl(
            options = ThemeMode.entries,
            selected = mode,
            label = { it.label() },
            onSelect = { scope.launch { prefs.setThemeMode(it) } },
        )
        Spacer(Modifier.height(VynqSpacing.md))
        Text(
            "System follows your phone's light or dark setting. Motion follows the system “Remove animations” setting.",
            style = VynqTheme.type.footnote, color = VynqTheme.colors.textTertiary,
        )
    }
}

@Composable
fun CallQualityScreen(onBack: () -> Unit) {
    val prefs = LocalContext.current.container.preferences
    val selected by prefs.callQuality.collectAsStateWithLifecycle(CallQuality.Automatic)
    val scope = rememberCoroutineScope()
    val c = VynqTheme.colors
    DetailScreen("Call quality", onBack) {
        Text("How vynq balances picture quality against data and battery.", style = VynqTheme.type.callout, color = c.textSecondary)
        Spacer(Modifier.height(VynqSpacing.xl))
        ListGroup {
            CallQuality.entries.forEachIndexed { i, q ->
                if (i > 0) RowDivider(inset = false)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(selected = q == selected, role = Role.RadioButton) { scope.launch { prefs.setCallQuality(q) } }
                        .padding(horizontal = VynqSpacing.lg, vertical = VynqSpacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(VynqSpacing.md),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(q.title, style = VynqTheme.type.body, color = c.textPrimary)
                        Text(q.description, style = VynqTheme.type.footnote, color = c.textSecondary)
                    }
                    if (q == selected) Icon(VynqIcons.Check, null, Modifier.size(20.dp), tint = c.accent)
                }
            }
        }
        Spacer(Modifier.height(VynqSpacing.md))
        Text(
            "Saved on this device. It takes effect once calling is available.",
            style = VynqTheme.type.footnote, color = c.textTertiary,
        )
    }
}

@Composable
fun NetworkDiagnosticsScreen(onBack: () -> Unit) {
    val monitor = LocalContext.current.container.networkMonitor
    val flow = remember(monitor) { monitor.observe() }
    val networks by flow.collectAsStateWithLifecycle(emptyList())
    val c = VynqTheme.colors
    DetailScreen("Network", onBack) {
        Text(
            "Live values reported by Android for every connected network. Call statistics such as round-trip time, jitter and packet loss appear here during calls.",
            style = VynqTheme.type.callout, color = c.textSecondary,
        )
        if (networks.isEmpty()) {
            SectionLabel("Status")
            ListGroup { ListRow("No connected network", icon = VynqIcons.Network, iconTint = c.danger) }
        }
        networks.forEach { n -> NetworkCard(n) }
    }
}

@Composable
private fun NetworkCard(n: NetworkSnapshot) {
    val c = VynqTheme.colors
    SectionLabel(if (n.isDefault) "${n.transport.label()} · in use" else n.transport.label())
    ListGroup {
        ListRow("Internet", trailing = {
            StatusPill(if (n.validated) "Verified" else "Not verified", positive = n.validated)
        })
        RowDivider(inset = false)
        ListRow("Metered", value = if (n.metered) "Yes" else "No")
        RowDivider(inset = false)
        ListRow("Link estimate ↓ / ↑", trailing = {
            Text("${n.downstreamKbps.asMbps()} / ${n.upstreamKbps.asMbps()}", style = VynqTheme.type.numeric, color = c.textSecondary)
        })
        n.interfaceName?.let {
            RowDivider(inset = false)
            ListRow("Interface", trailing = { Text(it, style = VynqTheme.type.numeric, color = c.textSecondary) })
        }
    }
}

private fun Transport.label() = when (this) {
    Transport.WiFi -> "Wi-Fi"
    Transport.Cellular -> "Mobile data"
    Transport.Ethernet -> "Ethernet"
    Transport.Vpn -> "VPN"
    Transport.Other -> "Other"
}

internal fun Int.asMbps(): String = if (this <= 0) "—" else "%.1f Mbps".format(this / 1000f)

@Composable
fun PrivacyScreen(onBack: () -> Unit) {
    val c = VynqTheme.colors
    DetailScreen("Privacy & security", onBack) {
        Text(
            "What vynq protects, stated plainly. Each line says whether it is working in this build.",
            style = VynqTheme.type.callout, color = c.textSecondary,
        )
        SectionLabel("In this build")
        ListGroup {
            ListRow("No phone number required", icon = VynqIcons.Shield, iconTint = c.success)
            RowDivider()
            ListRow("No trackers or analytics SDKs", icon = VynqIcons.Shield, iconTint = c.success)
            RowDivider()
            ListRow("Excluded from cloud backup", icon = VynqIcons.Shield, iconTint = c.success, subtitle = "Keys and messages never leave this device through backup")
        }
        SectionLabel("Planned")
        ListGroup {
            ListRow("End-to-end encrypted messages", icon = VynqIcons.Lock, subtitle = "Signal Protocol, per-device keys")
            RowDivider()
            ListRow("End-to-end encrypted calls", icon = VynqIcons.Lock, subtitle = "Frame encryption. Shown only while it is actually active.")
            RowDivider()
            ListRow("Device verification", icon = VynqIcons.Devices, subtitle = "Safety codes and QR")
        }
        Spacer(Modifier.height(VynqSpacing.lg))
        Text(
            "vynq doesn't claim to be anonymous. The server can see account IDs, timing and IP addresses, but never message content.",
            style = VynqTheme.type.footnote, color = c.textTertiary,
        )
    }
}

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val c = VynqTheme.colors
    DetailScreen("About", onBack) {
        Spacer(Modifier.height(VynqSpacing.xxl))
        BrandMark(Modifier.fillMaxWidth(), symbolWidth = 96.dp)
        Spacer(Modifier.height(VynqSpacing.xxl))
        ListGroup {
            ListRow("Version", trailing = { Text(BuildConfig.VERSION_NAME, style = VynqTheme.type.numeric, color = c.textSecondary) })
            RowDivider(inset = false)
            ListRow("Build", trailing = { Text(BuildConfig.VERSION_CODE.toString(), style = VynqTheme.type.numeric, color = c.textSecondary) })
        }
        Spacer(Modifier.height(VynqSpacing.md))
        Text("REAL CONNECTIONS", style = VynqTheme.type.overline, color = c.textTertiary, modifier = Modifier.padding(start = VynqSpacing.lg))
    }
}
