package app.vynq.ui.auth

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.vynq.container
import app.vynq.data.auth.AuthNotConfiguredException
import app.vynq.data.auth.AuthRepository
import app.vynq.data.auth.EmailValidator
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

data class AuthUiState(
    val email: String = "",
    val fieldError: String? = null,
    val loading: Boolean = false,
    val blockingError: String? = null,
)

class AuthViewModel(private val auth: AuthRepository) : ViewModel() {
    private val _state = MutableStateFlow(AuthUiState())
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    fun onEmailChange(value: String) = _state.update { it.copy(email = value, fieldError = null) }

    fun submit() {
        val email = _state.value.email
        if (!EmailValidator.isValid(email)) {
            _state.update { it.copy(fieldError = "Enter a valid email address") }
            return
        }
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            val result = auth.requestEmailCode(EmailValidator.normalize(email))
            _state.update { s ->
                result.fold(
                    onSuccess = { s.copy(loading = false) }, // Phase 2: navigate to code entry
                    onFailure = { e ->
                        s.copy(
                            loading = false,
                            blockingError = if (e is AuthNotConfiguredException) {
                                "Accounts arrive in the next build. The vynq account service isn't connected yet, so no email was sent."
                            } else {
                                "We couldn't reach vynq. Check your connection and try again."
                            },
                        )
                    },
                )
            }
        }
    }

    fun dismissError() = _state.update { it.copy(blockingError = null) }
}

@Composable
fun AuthScreen(onBack: () -> Unit) {
    val container = LocalContext.current.container
    val vm: AuthViewModel = viewModel { AuthViewModel(container.authRepository) }
    val state by vm.state.collectAsStateWithLifecycle()
    val c = VynqTheme.colors

    DetailScreen(title = "Your email", onBack = onBack) {
        Text(
            "We'll send you a one-time code. No password and no phone number. Your email stays private and is never shown to other people.",
            style = VynqTheme.type.callout,
            color = c.textSecondary,
        )
        Spacer(Modifier.height(VynqSpacing.xxl))
        VynqTextField(
            value = state.email,
            onValueChange = vm::onEmailChange,
            placeholder = "name@example.com",
            leadingIcon = VynqIcons.Mail,
            error = state.fieldError,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Go, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onGo = { vm.submit() }),
        )
        Spacer(Modifier.height(VynqSpacing.xl))
        VynqWideButton("Continue", vm::submit, loading = state.loading, enabled = state.email.isNotBlank())
    }

    state.blockingError?.let { VynqAlert("Not available yet", it, "OK", vm::dismissError) }
}
