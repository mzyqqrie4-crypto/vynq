package app.vynq.ui.main

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.vynq.designsystem.component.GlassSurface
import app.vynq.designsystem.component.pressable
import app.vynq.designsystem.icon.VynqIcons
import app.vynq.designsystem.theme.VynqSpacing
import app.vynq.designsystem.theme.VynqTheme
import app.vynq.ui.calls.CallsTab
import app.vynq.ui.chats.ChatsTab
import app.vynq.ui.contacts.ContactsTab
import app.vynq.ui.settings.SettingsTab

enum class Tab(val label: String, val icon: ImageVector) {
    Chats("Chats", VynqIcons.Chats),
    Calls("Calls", VynqIcons.Phone),
    Contacts("Contacts", VynqIcons.Contacts),
    Settings("Settings", VynqIcons.Settings),
}

/** Height reserved beneath scrolling content so the floating tab bar never covers it. */
val TabBarClearance = 96.dp

@Composable
fun MainScreen(onOpen: (String) -> Unit) {
    var tab by rememberSaveable { mutableStateOf(Tab.Chats) }
    val motion = VynqTheme.motion

    Box(Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = tab,
            transitionSpec = { fadeIn(motion.fade()) togetherWith fadeOut(motion.fade()) },
            modifier = Modifier.fillMaxSize().statusBarsPadding(),
            label = "tab",
        ) { current ->
            when (current) {
                Tab.Chats -> ChatsTab(onFindPeople = { tab = Tab.Contacts })
                Tab.Calls -> CallsTab(onFindPeople = { tab = Tab.Contacts })
                Tab.Contacts -> ContactsTab()
                Tab.Settings -> SettingsTab(onOpen = onOpen)
            }
        }
        TabBar(
            selected = tab,
            onSelect = { tab = it },
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = VynqSpacing.sm),
        )
    }
}

@Composable
private fun TabBar(selected: Tab, onSelect: (Tab) -> Unit, modifier: Modifier = Modifier) {
    val c = VynqTheme.colors
    GlassSurface(modifier.padding(horizontal = VynqSpacing.xxl).fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(VynqSpacing.xs), horizontalArrangement = Arrangement.SpaceEvenly) {
            Tab.entries.forEach { t ->
                val isSel = t == selected
                val tint by animateColorAsState(if (isSel) c.accent else c.textTertiary, VynqTheme.motion.quick(), label = "tabTint")
                Column(
                    Modifier
                        .weight(1f)
                        .pressable(role = Role.Tab) { onSelect(t) }
                        .semantics { this.selected = isSel }
                        .padding(vertical = VynqSpacing.sm),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(t.icon, null, Modifier.size(24.dp), tint = tint)
                    Spacer(Modifier.height(3.dp))
                    Text(t.label, style = VynqTheme.type.caption, color = tint)
                }
            }
        }
    }
}
