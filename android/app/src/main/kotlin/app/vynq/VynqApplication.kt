package app.vynq

import android.app.Application
import android.content.Context
import app.vynq.data.auth.AuthRepository
import app.vynq.data.auth.UnconfiguredAuthRepository
import app.vynq.data.network.NetworkMonitor
import app.vynq.data.prefs.PreferencesRepository

class VynqApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}

/**
 * Manual dependency container. Small enough that a DI framework would cost more
 * (build time, startup) than it saves. Revisit when feature modules land.
 */
class AppContainer(context: Context) {
    val preferences = PreferencesRepository(context)
    val networkMonitor = NetworkMonitor(context)

    /** Swapped for the Supabase implementation in Phase 2. */
    val authRepository: AuthRepository = UnconfiguredAuthRepository()
}

val Context.container: AppContainer get() = (applicationContext as VynqApplication).container
