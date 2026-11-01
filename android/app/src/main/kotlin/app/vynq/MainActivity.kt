package app.vynq

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vynq.data.auth.AuthState
import app.vynq.designsystem.theme.ThemeMode
import app.vynq.designsystem.theme.VynqTheme
import app.vynq.notify.Notifier
import app.vynq.ui.VynqNavHost
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    /** Conversation to open, from a tapped message notification. */
    private val openChat = MutableStateFlow<String?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        if (intent?.action == Notifier.ACTION_OPEN_CHAT) openChat.value = intent.getStringExtra(Notifier.EXTRA_PEER)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        val prefs = container.preferences
        val auth = container.authRepository
        splash.setKeepOnScreenCondition { auth.state.value is AuthState.Loading }
        if (savedInstanceState == null) handle(intent)

        setContent {
            val mode by prefs.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.System)
            val dark = when (mode) {
                ThemeMode.System -> isSystemInDarkTheme()
                ThemeMode.Light -> false
                ThemeMode.Dark -> true
            }
            DisposableEffect(dark) {
                val bars = if (dark) SystemBarStyle.dark(Color.TRANSPARENT)
                else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
                onDispose {}
            }
            VynqTheme(mode) { VynqNavHost(openChat = openChat, onChatOpened = { openChat.value = null }) }
        }
    }
}
