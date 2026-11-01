package app.vynq.ui

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.vynq.designsystem.theme.VynqTheme
import app.vynq.ui.auth.AuthScreen
import app.vynq.ui.main.MainScreen
import app.vynq.ui.settings.AboutScreen
import app.vynq.ui.settings.AppearanceScreen
import app.vynq.ui.settings.CallQualityScreen
import app.vynq.ui.settings.NetworkDiagnosticsScreen
import app.vynq.ui.settings.PrivacyScreen
import app.vynq.ui.welcome.WelcomeScreen

object Routes {
    const val WELCOME = "welcome"
    const val AUTH = "auth"
    const val MAIN = "main"
    const val APPEARANCE = "settings/appearance"
    const val CALL_QUALITY = "settings/call-quality"
    const val NETWORK = "settings/network"
    const val PRIVACY = "settings/privacy"
    const val ABOUT = "settings/about"
}

@Composable
fun VynqNavHost(nav: NavHostController = rememberNavController()) {
    val motion = VynqTheme.motion
    // Push/pop: a short slide with a fade. Reduced motion turns it into an instant cut.
    NavHost(
        navController = nav,
        startDestination = Routes.WELCOME,
        modifier = Modifier.fillMaxSize().background(VynqTheme.colors.background),
        enterTransition = { slideInHorizontally(motion.standard()) { it / 6 } + fadeIn(motion.fade()) },
        exitTransition = { fadeOut(motion.fade()) },
        popEnterTransition = { fadeIn(motion.fade()) },
        popExitTransition = { slideOutHorizontally(motion.standard()) { it / 6 } + fadeOut(motion.fade()) },
    ) {
        composable(Routes.WELCOME) {
            WelcomeScreen(
                onGetStarted = { nav.navigate(Routes.AUTH) },
                onSignIn = { nav.navigate(Routes.AUTH) },
                onDesignPreview = {
                    nav.navigate(Routes.MAIN) { popUpTo(Routes.WELCOME) { inclusive = true } }
                },
            )
        }
        composable(Routes.AUTH) { AuthScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.MAIN) { MainScreen(onOpen = { route -> nav.navigate(route) }) }
        composable(Routes.APPEARANCE) { AppearanceScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.CALL_QUALITY) { CallQualityScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.NETWORK) { NetworkDiagnosticsScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.PRIVACY) { PrivacyScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.ABOUT) { AboutScreen(onBack = { nav.popBackStack() }) }
    }
}
