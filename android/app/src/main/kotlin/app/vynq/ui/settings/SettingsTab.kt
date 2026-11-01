package app.vynq.ui.settings

import android.content.Intent
import android.provider.Settings
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vynq.BuildConfig
import app.vynq.container
import app.vynq.data.prefs.CallQuality
import app.vynq.designsystem.component.ListGroup
import app.vynq.designsystem.component.ListRow
import app.vynq.designsystem.component.RowDivider
import app.vynq.designsystem.component.SectionLabel
import app.vynq.designsystem.icon.VynqIcons
import app.vynq.designsystem.theme.ThemeMode
import app.vynq.designsystem.theme.VynqPalette
import app.vynq.designsystem.theme.VynqSpacing
import app.vynq.designsystem.theme.VynqTheme
import app.vynq.ui.Routes
import app.vynq.ui.common.LargeTitle
import app.vynq.ui.main.TabBarClearance

@Composable
fun SettingsTab(onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val prefs = context.container.preferences
    val theme by prefs.themeMode.collectAsStateWithLifecycle(ThemeMode.System)
    val quality by prefs.callQuality.collectAsStateWithLifecycle(CallQuality.Automatic)
    val c = VynqTheme.colors

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = VynqSpacing.gutter),
    ) {
        Spacer(Modifier.height(VynqSpacing.md))
        LargeTitle("Settings")

        // Profile header: signed-out state, stated plainly.
        Row(
            Modifier.fillMaxWidth().padding(vertical = VynqSpacing.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(VynqSpacing.lg),
        ) {
            Box(Modifier.size(60.dp).background(c.brandSweep, CircleShape), contentAlignment = Alignment.Center) {
                Icon(VynqIcons.Contacts, null, Modifier.size(28.dp), tint = VynqPalette.SoftWhite)
            }
            Column {
                Text("Not signed in", style = VynqTheme.type.title2, color = c.textPrimary)
                Text("Your profile and username appear here", style = VynqTheme.type.footnote, color = c.textSecondary)
            }
        }

        SectionLabel("Account")
        ListGroup {
            ListRow("Profile & username", icon = VynqIcons.Contacts, enabled = false, disabledReason = "Needs sign-in")
            RowDivider()
            ListRow("Connected devices", icon = VynqIcons.Devices, enabled = false, disabledReason = "Needs sign-in")
        }

        SectionLabel("Preferences")
        ListGroup {
            ListRow("Appearance", icon = VynqIcons.Appearance, value = theme.label(), onClick = { onOpen(Routes.APPEARANCE) })
            RowDivider()
            ListRow("Call quality", icon = VynqIcons.Gauge, value = quality.title, onClick = { onOpen(Routes.CALL_QUALITY) })
            RowDivider()
            ListRow(
                "Notifications", icon = VynqIcons.Bell, iconTint = VynqPalette.Pink400,
                subtitle = "Opens system settings",
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                    )
                },
            )
        }

        SectionLabel("Privacy & connection")
        ListGroup {
            ListRow("Privacy & security", icon = VynqIcons.Shield, iconTint = c.success, onClick = { onOpen(Routes.PRIVACY) })
            RowDivider()
            ListRow("Network diagnostics", icon = VynqIcons.Network, onClick = { onOpen(Routes.NETWORK) })
        }

        SectionLabel("vynq")
        ListGroup {
            ListRow("About", icon = VynqIcons.Info, iconTint = c.textSecondary, value = BuildConfig.VERSION_NAME, onClick = { onOpen(Routes.ABOUT) })
        }
        Spacer(Modifier.height(TabBarClearance))
    }
}

fun ThemeMode.label() = when (this) {
    ThemeMode.System -> "System"
    ThemeMode.Light -> "Light"
    ThemeMode.Dark -> "Dark"
}
