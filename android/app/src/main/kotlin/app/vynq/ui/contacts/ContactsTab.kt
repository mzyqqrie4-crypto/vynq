package app.vynq.ui.contacts

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import app.vynq.designsystem.component.EmptyState
import app.vynq.designsystem.component.VynqTextField
import app.vynq.designsystem.icon.VynqIcons
import app.vynq.designsystem.theme.VynqSpacing
import app.vynq.designsystem.theme.VynqTheme
import app.vynq.ui.common.TabHeader

/**
 * Contacts are invite-only and found by exact username, with no address-book
 * upload (threat model T12/T13). The lookup needs an account, so the field is
 * disabled and says why until Phase 2/3.
 */
@Composable
fun ContactsTab() {
    var username by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        TabHeader("Contacts")
        Column(Modifier.padding(horizontal = VynqSpacing.gutter)) {
            VynqTextField(
                value = username,
                onValueChange = { username = it },
                placeholder = "Add by exact username",
                leadingIcon = VynqIcons.AddContact,
                enabled = false,
                compact = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Search),
            )
            Spacer(Modifier.height(VynqSpacing.sm))
            Text(
                "Sign in to find people. vynq never reads your phone's contacts.",
                style = VynqTheme.type.footnote,
                color = VynqTheme.colors.textTertiary,
            )
        }
        EmptyState(
            icon = VynqIcons.Contacts,
            title = "Your circle starts here",
            message = "People you invite and accept show up here. You can verify each other's devices with a safety code.",
        )
    }
}
