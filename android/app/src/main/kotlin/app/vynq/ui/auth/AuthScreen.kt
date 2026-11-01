package app.vynq.ui.auth

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.vynq.container
import app.vynq.data.auth.AccountError
import app.vynq.data.auth.AccountRepository
import app.vynq.data.auth.AuthRepository
import app.vynq.data.auth.EmailValidator
import app.vynq.data.auth.userMessage
import app.vynq.designsystem.component.ButtonStyle
import app.vynq.designsystem.component.VynqAlert
import app.vynq.designsystem.component.VynqTextField
import app.vynq.designsystem.component.VynqWideButton
import app.vynq.designsystem.icon.VynqIcons
import app.vynq.designsystem.theme.VynqSpacing
import app.vynq.designsystem.theme.VynqTheme
import app.vynq.ui.common.DetailScreen
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AuthStep { Email, Code }

data class AuthUiState(
    val step: AuthStep = AuthStep.Email,
    val email: String = "",
    val code: String = "",
    val fieldError: String? = null,
    val loading: Boolean = false,
    val resendInSeconds: Int = 0,
    val alert: Pair<String, String>? = null,
    /** Set once signed in; true when the account still needs a username. */
    val done: Boolean? = null,
)

class AuthViewModel(
    private val auth: AuthRepository,
    private val account: AccountRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(AuthUiState())
    val state: StateFlow<AuthUiState> = _state.asStateFlow()
    private var countdown: Job? = null

    fun onEmailChange(value: String) = _state.update { it.copy(email = value, fieldError = null) }

    fun onCodeChange(value: String) {
        val digits = value.filter(Char::isDigit).take(CODE_LENGTH)
        _state.update { it.copy(code = digits, fieldError = null) }
        if (digits.length == CODE_LENGTH && !_state.value.loading) verify()
    }

    fun sendCode() {
        val email = _state.value.email
        if (!EmailValidator.isValid(email)) {
            _state.update { it.copy(fieldError = "Enter a valid email address") }
            return
        }
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            auth.requestEmailCode(EmailValidator.normalize(email)).fold(
                onSuccess = {
                    _state.update { it.copy(loading = false, step = AuthStep.Code, code = "", fieldError = null) }
                    startCountdown()
                },
                onFailure = { e -> fail(e) },
            )
        }
    }

    fun verify() {
        val s = _state.value
        if (s.code.length != CODE_LENGTH) {
            _state.update { it.copy(fieldError = "Enter the $CODE_LENGTH-digit code") }
            return
        }
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            auth.verifyEmailCode(EmailValidator.normalize(s.email), s.code).fold(
                onSuccess = { finishSignIn() },
                onFailure = { e ->
                    if (e is AccountError.InvalidCode) {
                        _state.update { it.copy(loading = false, code = "", fieldError = e.userMessage()) }
                    } else {
                        fail(e)
                    }
                },
            )
        }
    }

    fun changeEmail() {
        countdown?.cancel()
        _state.update { it.copy(step = AuthStep.Email, code = "", fieldError = null, resendInSeconds = 0) }
    }

    fun dismissAlert() = _state.update { it.copy(alert = null) }

    private suspend fun finishSignIn() {
        // Device registration failing (e.g. the 5-device limit) must not trap the
        // user on this screen; Settings shows the problem and they can fix it there.
        account.registerThisDevice()
        val needsUsername = account.profile().getOrNull()?.username == null
        _state.update { it.copy(loading = false, done = needsUsername) }
    }

    private fun fail(e: Throwable) {
        val title = if (e is AccountError.NotConfigured) "Not available yet" else "Couldn't continue"
        _state.update { it.copy(loading = false, alert = title to e.userMessage()) }
    }

    private fun startCountdown() {
        countdown?.cancel()
        countdown = viewModelScope.launch {
            for (left in RESEND_SECONDS downTo 0) {
                _state.update { it.copy(resendInSeconds = left) }
                if (left > 0) delay(1_000)
            }
        }
    }

    companion object {
        const val CODE_LENGTH = 6
        const val RESEND_SECONDS = 60
    }
}

@Composable
fun AuthScreen(onBack: () -> Unit, onSignedIn: (needsUsername: Boolean) -> Unit = {}) {
    val container = LocalContext.current.container
    val vm: AuthViewModel = viewModel { AuthViewModel(container.authRepository, container.accountRepository) }
    val state by vm.state.collectAsStateWithLifecycle()
    val c = VynqTheme.colors

    LaunchedEffect(state.done) { state.done?.let(onSignedIn) }
    BackHandler(enabled = state.step == AuthStep.Code, onBack = vm::changeEmail)

    when (state.step) {
        AuthStep.Email -> DetailScreen(title = "Your email", onBack = onBack) {
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
                keyboardActions = KeyboardActions(onGo = { vm.sendCode() }),
            )
            Spacer(Modifier.height(VynqSpacing.xl))
            VynqWideButton("Send code", vm::sendCode, loading = state.loading, enabled = state.email.isNotBlank())
        }

        AuthStep.Code -> DetailScreen(title = "Enter the code", onBack = vm::changeEmail) {
            Text(
                "We sent a ${AuthViewModel.CODE_LENGTH}-digit code to ${EmailValidator.normalize(state.email)}. It expires in 10 minutes.",
                style = VynqTheme.type.callout,
                color = c.textSecondary,
            )
            Spacer(Modifier.height(VynqSpacing.xxl))
            VynqTextField(
                value = state.code,
                onValueChange = vm::onCodeChange,
                placeholder = "123456",
                leadingIcon = VynqIcons.Lock,
                error = state.fieldError,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onDone = { vm.verify() }),
            )
            Spacer(Modifier.height(VynqSpacing.xl))
            VynqWideButton("Sign in", vm::verify, loading = state.loading, enabled = state.code.length == AuthViewModel.CODE_LENGTH)
            Spacer(Modifier.height(VynqSpacing.md))
            VynqWideButton(
                if (state.resendInSeconds > 0) "Send a new code in ${state.resendInSeconds}s" else "Send a new code",
                vm::sendCode,
                style = ButtonStyle.Ghost,
                enabled = state.resendInSeconds == 0 && !state.loading,
            )
            VynqWideButton("Use a different email", vm::changeEmail, style = ButtonStyle.Ghost, enabled = !state.loading)
        }
    }

    state.alert?.let { (title, message) -> VynqAlert(title, message, "OK", vm::dismissAlert) }
}
