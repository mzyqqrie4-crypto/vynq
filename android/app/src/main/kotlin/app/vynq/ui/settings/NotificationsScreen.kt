package app.vynq.ui.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vynq.container
import app.vynq.data.messaging.Contact
import app.vynq.data.messaging.ContactState
import app.vynq.data.messaging.Message
import app.vynq.data.messaging.MessageKind
import app.vynq.data.messaging.MessageStatus
import app.vynq.data.prefs.NotificationPreview
import app.vynq.designsystem.component.ListGroup
import app.vynq.designsystem.component.ListRow
import app.vynq.designsystem.component.RowDivider
import app.vynq.designsystem.component.SectionLabel
import app.vynq.designsystem.component.SegmentedControl
import app.vynq.designsystem.component.StatusPill
import app.vynq.designsystem.component.VynqButton
import app.vynq.designsystem.component.ButtonStyle
import app.vynq.designsystem.icon.VynqIcons
import app.vynq.designsystem.theme.VynqSpacing
import app.vynq.designsystem.theme.VynqTheme
import app.vynq.notify.BackgroundDelivery
import app.vynq.notify.ReliabilityGuide
import app.vynq.ui.common.DetailScreen
import kotlinx.coroutines.launch

/**
 * Everything that decides whether a message notification actually shows up,
 * as a checklist. Statuses that Android lets apps read are live; vendor
 * switches like Autostart can't be read, so those just open the right screen.
 */
@Composable
fun NotificationsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val scope = rememberCoroutineScope()
    val c = VynqTheme.colors
    val preview by container.preferences.notificationPreview.collectAsStateWithLifecycle(NotificationPreview.NameAndMessage)
    val background by container.preferences.backgroundConnection.collectAsStateWithLifecycle(true)
    val session by container.messaging.session.collectAsStateWithLifecycle()

    var refresh by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { refresh++; onPauseOrDispose { } }
    val notificationsOn = remember(refresh) { container.messaging.notifier.canPost() }
    val batteryOk = remember(refresh) { BackgroundDelivery.ignoresBatteryOptimizations(context) }
    val fullScreenOk = remember(refresh) { ReliabilityGuide.canUseFullScreenIntent(context) }

    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    val maker = ReliabilityGuide.maker

    DetailScreen(title = "Notifications", onBack = onBack) {
        Text(
            "vynq doesn't use Google's or any phone maker's push service. Your phone fetches and decrypts messages itself, then shows the notification. For that to work while vynq is closed, your phone has to let it run in the background.",
            style = VynqTheme.type.callout, color = c.textSecondary,
        )

        SectionLabel("Make sure messages arrive")
        ListGroup {
            ListRow(
                "Allow notifications", icon = VynqIcons.Bell,
                subtitle = if (notificationsOn) "On" else "Off — you won't see new messages",
                onClick = {
                    if (!notificationsOn && Build.VERSION.SDK_INT >= 33) askPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else ReliabilityGuide.openNotificationSettings(context)
                },
                trailing = { StatusPill(if (notificationsOn) "On" else "Off", positive = notificationsOn) },
            )
            RowDivider()
            ListRow(
                "Battery: no restrictions", icon = VynqIcons.Gauge,
                subtitle = if (batteryOk) "vynq may run in the background"
                else if (maker == ReliabilityGuide.Maker.Xiaomi) "Battery saver → No restrictions"
                else "Let vynq run in the background",
                onClick = { ReliabilityGuide.openBatterySettings(context) },
                trailing = { StatusPill(if (batteryOk) "Allowed" else "Restricted", positive = batteryOk) },
            )
            if (ReliabilityGuide.hasAutostart) {
                RowDivider()
                ListRow(
                    maker.autostartName, icon = VynqIcons.Network,
                    subtitle = "Turn on for vynq. ${maker.label} phones block apps from starting by themselves unless you allow it. Apps can't check this, so please look.",
                    onClick = { ReliabilityGuide.openAutostart(context) },
                )
            }
            if (maker == ReliabilityGuide.Maker.Xiaomi) {
                RowDivider()
                ListRow(
                    "Lock screen & pop-ups", icon = VynqIcons.Lock,
                    subtitle = "Optional: “Show on lock screen” and “Display pop-up windows while running in the background”",
                    onClick = { ReliabilityGuide.openXiaomiOtherPermissions(context) },
                )
            }
        }

        SectionLabel("Calls")
        ListGroup {
            ListRow(
                "Full-screen incoming calls", icon = VynqIcons.Phone,
                subtitle = if (fullScreenOk) "Calls take over the screen, even when locked"
                else "Off: calls only show as a notification. Turn on so the call screen appears.",
                onClick = { ReliabilityGuide.openFullScreenIntentSettings(context) },
                trailing = { StatusPill(if (fullScreenOk) "On" else "Off", positive = fullScreenOk) },
            )
            if (maker == ReliabilityGuide.Maker.Xiaomi) {
                RowDivider()
                ListRow(
                    "Show calls on the lock screen", icon = VynqIcons.Lock,
                    subtitle = "Xiaomi: allow “Show on lock screen” and “Display pop-up windows while running in the background”",
                    onClick = { ReliabilityGuide.openXiaomiOtherPermissions(context) },
                )
            }
            RowDivider()
            ListRow(
                "Test the ringtone", icon = VynqIcons.Bell,
                subtitle = "Rings for 3 seconds the way a call would (silent mode stays silent)",
                onClick = {
                    val ringer = app.vynq.call.Ringer(context)
                    ringer.start()
                    scope.launch { kotlinx.coroutines.delay(3_000); ringer.stop() }
                },
            )
        }

        SectionLabel("Background connection")
        ListGroup {
            ListRow(
                "Keep connected", icon = VynqIcons.Network,
                subtitle = "Messages arrive within seconds while vynq is closed. Shows a quiet “Ready for messages” notification you can hide. Off: vynq checks about every 15 minutes, or less often when your phone saves battery.",
                trailing = {
                    Switch(
                        checked = background,
                        onCheckedChange = { on ->
                            scope.launch {
                                container.preferences.setBackgroundConnection(on)
                                BackgroundDelivery.ensure(context, on, session != null)
                            }
                        },
                        colors = SwitchDefaults.colors(checkedTrackColor = c.accent),
                    )
                },
            )
        }

        SectionLabel("Show in notifications")
        SegmentedControl(
            options = NotificationPreview.entries.toList(),
            selected = preview,
            label = { it.title },
            onSelect = { scope.launch { container.preferences.setNotificationPreview(it) } },
        )
        Spacer(Modifier.height(VynqSpacing.sm))
        Text(
            "On the lock screen only “New message” shows until the phone is unlocked.",
            style = VynqTheme.type.footnote, color = c.textTertiary,
        )

        Spacer(Modifier.height(VynqSpacing.xxl))
        VynqButton("Send a test notification", {
            val sample = Message("test", "test", false, MessageKind.Text, "This is how a message will look.", null,
                System.currentTimeMillis(), MessageStatus.Received, read = false)
            container.messaging.notifier.showConversation(
                Contact("test", "vynq", "vynq", ContactState.Accepted), "test", listOf(sample), preview,
            )
        }, style = ButtonStyle.Secondary, enabled = notificationsOn)
    }
}
