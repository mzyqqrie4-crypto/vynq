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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vynq.BuildConfig
import app.vynq.container
import app.vynq.data.auth.AuthState
import app.vynq.data.auth.Profile
import app.vynq.data.auth.userMessage
import app.vynq.data.prefs.CallQuality
import app.vynq.designsystem.component.ListGroup
import app.vynq.designsystem.component.ListRow
import app.vynq.designsystem.component.RowDivider
import app.vynq.designsystem.component.SectionLabel
import app.vynq.designsystem.component.VynqAlert
import app.vynq.designsystem.component.VynqConfirm
import kotlinx.coroutines.launch
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
    val auth = context.container.authRepository
    val account = context.container.accountRepository
    val authState by auth.state.collectAsStateWithLifecycle()
    val signedIn = authState as? AuthState.SignedIn
    val profile by produceState<Profile?>(null, signedIn) {
        value = if (signedIn != null) account.profile().getOrNull() else null
    }
    var confirm by remember { mutableStateOf<AccountAction?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    val c = VynqTheme.colors

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = VynqSpacing.gutter),
    ) {
        Spacer(Modifier.height(VynqSpacing.md))
        LargeTitle("Settings")

        Row(
            Modifier.fillMaxWidth().padding(vertical = VynqSpacing.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(VynqSpacing.lg),
        ) {
            Box(Modifier.size(60.dp).background(c.brandSweep, CircleShape), contentAlignment = Alignment.Center) {
                Icon(VynqIcons.Contacts, null, Modifier.size(28.dp), tint = VynqPalette.SoftWhite)
            }
            Column {
                val (title, subtitle) = when {
                    signedIn == null -> "Not signed in" to "Your profile and username appear here"
                    profile?.username != null -> "@${profile?.username}" to signedIn.email
                    else -> signedIn.email to "Signed in"
                }
                Text(title, style = VynqTheme.type.title2, color = c.textPrimary)
                Text(subtitle, style = VynqTheme.type.footnote, color = c.textSecondary)
            }
        }

        SectionLabel("Account")
        ListGroup {
            if (signedIn == null) {
                ListRow("Profile & username", icon = VynqIcons.Contacts, enabled = false, disabledReason = "Needs sign-in")
            } else {
                ListRow(
                    "Username", icon = VynqIcons.Contacts,
                    value = profile?.username?.let { "@$it" } ?: "Not set",
                    onClick = { onOpen(Routes.USERNAME_CHANGE) },
                )
            }
            RowDivider()
            ListRow("Connected devices", icon = VynqIcons.Devices, enabled = false, disabledReason = "Coming soon")
            if (signedIn != null) {
                RowDivider()
                ListRow("Sign out", icon = VynqIcons.Back, iconTint = c.textSecondary, onClick = { confirm = AccountAction.SignOut })
                RowDivider()
                ListRow(
                    "Delete account", icon = VynqIcons.Shield, iconTint = c.danger,
                    subtitle = "Removes your profile and devices",
                    onClick = { confirm = AccountAction.Delete },
                )
            }
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

    // Account actions run in the app scope so leaving the screen can't cancel them halfway.
    val scope = context.container.appScope
    when (confirm) {
        AccountAction.SignOut -> VynqConfirm(
            title = "Sign out?",
            message = "This device's session is revoked. You can sign back in with a new email code.",
            confirm = "Sign out",
            onConfirm = { confirm = null; scope.launch { auth.signOut() } },
            onDismiss = { confirm = null },
        )
        AccountAction.Delete -> VynqConfirm(
            title = "Delete your account?",
            message = "Your username, profile and registered devices are permanently removed. This can't be undone.",
            confirm = "Delete",
            destructive = true,
            onConfirm = {
                confirm = null
                scope.launch { account.deleteAccount().onFailure { failure = it.userMessage() } }
            },
            onDismiss = { confirm = null },
        )
        null -> Unit
    }
    failure?.let { VynqAlert("Account not deleted", it, "OK") { failure = null } }
}

private enum class AccountAction { SignOut, Delete }

fun ThemeMode.label() = when (this) {
    ThemeMode.System -> "System"
    ThemeMode.Light -> "Light"
    ThemeMode.Dark -> "Dark"
}
