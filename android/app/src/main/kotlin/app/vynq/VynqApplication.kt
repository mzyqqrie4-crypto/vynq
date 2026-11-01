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

    private val accounts: AccountService = if (backendConfigured) {
        SupabaseAuthRepository(
            baseUrl = BuildConfig.SUPABASE_URL,
            anonKey = BuildConfig.SUPABASE_ANON_KEY,
            store = KeystoreSessionStore(context),
            device = InstallDeviceIdentity(context),
        ).also { repo -> appScope.launch { repo.restore() } }
    } else {
        UnconfiguredAuthRepository()
    }

    val authRepository: AuthRepository = accounts
    val accountRepository: AccountRepository = accounts
}

val Context.container: AppContainer get() = (applicationContext as VynqApplication).container
