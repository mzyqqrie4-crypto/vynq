package app.vynq.ui

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.vynq.container
import app.vynq.data.auth.AuthState
import app.vynq.designsystem.theme.VynqTheme
import app.vynq.ui.auth.AuthScreen
import app.vynq.ui.auth.UsernameScreen
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
    const val USERNAME = "onboarding/username"
    const val MAIN = "main"
    const val USERNAME_CHANGE = "settings/username"
    const val APPEARANCE = "settings/appearance"
    const val CALL_QUALITY = "settings/call-quality"
    const val NETWORK = "settings/network"
    const val PRIVACY = "settings/privacy"
    const val ABOUT = "settings/about"
}

@Composable
fun VynqNavHost(nav: NavHostController = rememberNavController()) {
    val container = LocalContext.current.container
    val authState by container.authRepository.state.collectAsStateWithLifecycle()
    // The system splash stays up while the stored session is read (MainActivity).
    if (authState is AuthState.Loading) return

    val start = remember { if (authState is AuthState.SignedIn) Routes.MAIN else Routes.WELCOME }
    val motion = VynqTheme.motion

    // Signed out from anywhere (sign-out, expired session, deleted account): back to the start.
    var wasSignedIn by remember { mutableStateOf(authState is AuthState.SignedIn) }
    LaunchedEffect(authState) {
        val signedIn = authState is AuthState.SignedIn
        if (wasSignedIn && !signedIn) nav.navigate(Routes.WELCOME) { popUpTo(0) }
        wasSignedIn = signedIn
    }

    // Push/pop: a short slide with a fade. Reduced motion turns it into an instant cut.
    NavHost(
        navController = nav,
        startDestination = start,
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
        composable(Routes.AUTH) {
            AuthScreen(
                onBack = { nav.popBackStack() },
                onSignedIn = { needsUsername ->
                    nav.navigate(if (needsUsername) Routes.USERNAME else Routes.MAIN) { popUpTo(0) }
                },
            )
        }
        composable(Routes.USERNAME) {
            UsernameScreen(onDone = { nav.navigate(Routes.MAIN) { popUpTo(0) } })
        }
        composable(Routes.MAIN) {
            // Refreshes this device's name and last-seen, and covers an onboarding
            // that was interrupted before a username was chosen.
            if (authState is AuthState.SignedIn) {
                LaunchedEffect(Unit) {
                    container.accountRepository.registerThisDevice()
                    val profile = container.accountRepository.profile().getOrNull()
                    if (profile != null && profile.username == null) nav.navigate(Routes.USERNAME) { popUpTo(0) }
                }
            }
            MainScreen(onOpen = { route -> nav.navigate(route) })
        }
        composable(Routes.USERNAME_CHANGE) {
            UsernameScreen(onDone = { nav.popBackStack() }, onBack = { nav.popBackStack() })
        }
        composable(Routes.APPEARANCE) { AppearanceScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.CALL_QUALITY) { CallQualityScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.NETWORK) { NetworkDiagnosticsScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.PRIVACY) { PrivacyScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.ABOUT) { AboutScreen(onBack = { nav.popBackStack() }) }
    }
}
