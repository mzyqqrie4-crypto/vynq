package app.vynq.ui.chats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import app.vynq.designsystem.component.EmptyState
import app.vynq.designsystem.component.VynqButton
import app.vynq.designsystem.component.VynqIconButton
import app.vynq.designsystem.component.VynqTextField
import app.vynq.designsystem.component.Wordmark
import app.vynq.designsystem.icon.VynqIcons
import app.vynq.designsystem.theme.VynqSpacing
import app.vynq.ui.main.TabBarClearance

/**
 * Conversations. Phase 3 supplies real conversations from the encrypted local
 * store. Until then the list is genuinely empty: no sample data.
 */
@Composable
fun ChatsTab(onFindPeople: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val conversations = emptyList<String>()
    val visible = conversations.filter { it.contains(query.trim(), ignoreCase = true) }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(start = VynqSpacing.gutter, end = VynqSpacing.md, top = VynqSpacing.lg),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Wordmark()
                VynqIconButton(VynqIcons.Plus, "New conversation", onFindPeople, accent = true, size = 38)
            }
            Spacer(Modifier.height(VynqSpacing.lg))
            VynqTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = "Search",
                leadingIcon = VynqIcons.Search,
                compact = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.padding(horizontal = VynqSpacing.gutter),
            )
        }
        if (visible.isEmpty()) {
            item {
                EmptyState(
                    icon = VynqIcons.Chats,
                    title = if (query.isBlank()) "No conversations yet" else "No matches",
                    message = if (query.isBlank()) {
                        "vynq is for the people you trust. Invite someone by their username to start talking."
                    } else {
                        "Nothing matches “${query.trim()}”."
                    },
                    action = if (query.isBlank()) {
                        { VynqButton("Find people", onFindPeople, leadingIcon = VynqIcons.AddContact) }
                    } else null,
                )
            }
        }
        item { Spacer(Modifier.height(TabBarClearance)) }
    }
}
