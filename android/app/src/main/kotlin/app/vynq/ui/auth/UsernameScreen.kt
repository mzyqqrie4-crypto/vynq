package app.vynq.ui.auth

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.vynq.container
import app.vynq.data.auth.AccountError
import app.vynq.data.auth.AccountRepository
import app.vynq.data.auth.UsernameRules
import app.vynq.data.auth.userMessage
import app.vynq.designsystem.component.VynqAlert
import app.vynq.designsystem.component.VynqTextField
import app.vynq.designsystem.component.VynqWideButton
import app.vynq.designsystem.icon.VynqIcons
import app.vynq.designsystem.theme.VynqSpacing
import app.vynq.designsystem.theme.VynqTheme
import app.vynq.ui.common.DetailScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UsernameUiState(
    val username: String = "",
    val fieldError: String? = null,
    val loading: Boolean = false,
    val alert: String? = null,
    val claimed: String? = null,
)

class UsernameViewModel(private val account: AccountRepository) : ViewModel() {
    private val _state = MutableStateFlow(UsernameUiState())
    val state: StateFlow<UsernameUiState> = _state.asStateFlow()

    fun onChange(value: String) = _state.update { it.copy(username = value.lowercase().take(20), fieldError = null) }

    fun submit() {
        val name = _state.value.username
        UsernameRules.problem(name)?.let { problem ->
            _state.update { it.copy(fieldError = problem) }
            return
        }
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            account.claimUsername(name).fold(
                onSuccess = { claimed -> _state.update { it.copy(loading = false, claimed = claimed) } },
                onFailure = { e ->
                    val inline = e is AccountError.UsernameTaken || e is AccountError.UsernameReserved || e is AccountError.InvalidUsername
                    _state.update {
                        if (inline) it.copy(loading = false, fieldError = e.userMessage())
                        else it.copy(loading = false, alert = e.userMessage())
                    }
                },
            )
        }
    }

    fun dismissAlert() = _state.update { it.copy(alert = null) }
}

@Composable
fun UsernameScreen(onDone: () -> Unit, onBack: (() -> Unit)? = null) {
    val container = LocalContext.current.container
    val vm: UsernameViewModel = viewModel { UsernameViewModel(container.accountRepository) }
    val state by vm.state.collectAsStateWithLifecycle()
    val c = VynqTheme.colors

    LaunchedEffect(state.claimed) { if (state.claimed != null) onDone() }

    DetailScreen(title = "Choose a username", onBack = onBack) {
        Text(
            "This is how people find you on vynq. Only exact matches show up in search, and you can turn search off in Privacy.",
            style = VynqTheme.type.callout,
            color = c.textSecondary,
        )
        Spacer(Modifier.height(VynqSpacing.xxl))
        VynqTextField(
            value = state.username,
            onValueChange = vm::onChange,
            placeholder = "username",
            leadingIcon = VynqIcons.Contacts,
            error = state.fieldError,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { vm.submit() }),
        )
        Spacer(Modifier.height(VynqSpacing.sm))
        Text(
            "3–20 characters: letters, numbers, . and _. Names that look like an existing one are blocked to stop impersonation.",
            style = VynqTheme.type.footnote,
            color = c.textTertiary,
        )
        Spacer(Modifier.height(VynqSpacing.xl))
        VynqWideButton("Continue", vm::submit, loading = state.loading, enabled = state.username.length >= 3)
    }

    state.alert?.let { VynqAlert("Couldn't save", it, "OK", vm::dismissAlert) }
}
