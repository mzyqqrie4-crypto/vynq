package app.vynq

import android.app.Application
import android.content.Context
import app.vynq.data.auth.AccountRepository
import app.vynq.data.auth.AccountService
import app.vynq.data.auth.AuthRepository
import app.vynq.data.auth.InstallDeviceIdentity
import app.vynq.data.auth.KeystoreSessionStore
import app.vynq.data.auth.SupabaseAuthRepository
import app.vynq.data.auth.UnconfiguredAuthRepository
import app.vynq.call.CallManager
import app.vynq.data.messaging.MessagingService
import app.vynq.data.network.NetworkMonitor
import app.vynq.data.prefs.PreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class VynqApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}

/**
 * Manual dependency container. Small enough that a DI framework would cost more
 * (build time, startup) than it saves. Revisit when feature modules land.
 */
class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val preferences = PreferencesRepository(context)
    val networkMonitor = NetworkMonitor(context)

    val backendConfigured: Boolean =
        BuildConfig.SUPABASE_URL.startsWith("https://") && BuildConfig.SUPABASE_ANON_KEY.isNotEmpty()

    private val deviceIdentity = InstallDeviceIdentity(context)

    private val accounts: AccountService = if (backendConfigured) {
        SupabaseAuthRepository(
            baseUrl = BuildConfig.SUPABASE_URL,
            anonKey = BuildConfig.SUPABASE_ANON_KEY,
            store = KeystoreSessionStore(context),
            device = deviceIdentity,
        ).also { repo -> appScope.launch { repo.restore() } }
    } else {
        UnconfiguredAuthRepository()
    }

    val authRepository: AuthRepository = accounts
    val accountRepository: AccountRepository = accounts

    /** End-to-end encrypted messaging for whoever is signed in on this device. */
    val messaging = MessagingService(context, accounts, accounts, accounts, deviceIdentity, preferences, appScope)
        .also { if (backendConfigured) it.attach() }

    /** 1:1 calls over LiveKit (created on first use; signals arrive through messaging). */
    val calls: CallManager by lazy { CallManager(context, accounts, messaging, appScope) }

    init { messaging.onCallSignal = { calls.onSignal(it) } }
}

val Context.container: AppContainer get() = (applicationContext as VynqApplication).container
