package app.vynq

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import app.vynq.designsystem.theme.ThemeMode
import app.vynq.designsystem.theme.VynqTheme
import app.vynq.ui.auth.AuthScreen
import app.vynq.ui.auth.UsernameScreen
import app.vynq.ui.main.MainScreen
import app.vynq.ui.settings.CallQualityScreen
import app.vynq.ui.settings.PrivacyScreen
import app.vynq.ui.settings.SettingsTab
import app.vynq.ui.welcome.WelcomeScreen
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders key screens on the JVM so design changes are reviewable without a device.
 * Output: PNGs in app/build/outputs/roborazzi (record with `gradlew recordRoborazziDebug`).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi")
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private fun shot(name: String, mode: ThemeMode = ThemeMode.Dark, content: @Composable () -> Unit) {
        compose.setContent {
            VynqTheme(mode) { Box(Modifier.fillMaxSize().background(VynqTheme.colors.background)) { content() } }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test fun welcomeDark() = shot("01-welcome-dark") { WelcomeScreen({}, {}, {}) }
    @Test fun welcomeLight() = shot("02-welcome-light", ThemeMode.Light) { WelcomeScreen({}, {}, {}) }
    @Test fun chatsDark() = shot("03-chats-dark") { MainScreen(onOpen = {}) }
    @Test fun chatsLight() = shot("04-chats-light", ThemeMode.Light) { MainScreen(onOpen = {}) }
    @Test fun authDark() = shot("05-auth-dark") { AuthScreen(onBack = {}) }
    @Test fun settingsDark() = shot("06-settings-dark") { SettingsTab(onOpen = {}) }
    @Test fun callQualityDark() = shot("07-call-quality-dark") { CallQualityScreen(onBack = {}) }
    @Test fun privacyLight() = shot("08-privacy-light", ThemeMode.Light) { PrivacyScreen(onBack = {}) }
    @Test fun usernameDark() = shot("09-username-dark") { UsernameScreen(onDone = {}) }
}
