package app.vynq.notify

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.vynq.container
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * How messages reach a phone while vynq is closed, without depending on
 * Google push (many phones sold in China don't have it, and phones that do
 * often delay it for apps that aren't on a vendor allow-list):
 *
 * 1. A foreground service keeps the Realtime connection open (on by default,
 *    can be turned off). This is the same approach Signal uses without Google
 *    services. It needs the phone to allow vynq to run in the background, which
 *    [ReliabilityGuide] walks the user through; we never try to get around
 *    the phone's restrictions.
 * 2. A periodic job (about every 15 minutes, when the phone allows) fetches
 *    mail and restarts the service if it was killed, as a safety net.
 * 3. After a reboot or an app update the service starts again.
 */
object BackgroundDelivery {
    private const val PERIODIC = "vynq.sync.periodic"

    fun ensure(context: Context, connectionEnabled: Boolean, signedIn: Boolean) {
        if (!signedIn) { stop(context); return }
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build(),
        )
        if (connectionEnabled) startService(context) else context.stopService(Intent(context, MessageConnectionService::class.java))
    }

    fun stop(context: Context) {
        context.stopService(Intent(context, MessageConnectionService::class.java))
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
    }

    /** May be refused while vynq is in the background (Android 12+), unless it's exempt from battery optimisation. */
    fun startService(context: Context): Boolean = try {
        ContextCompat.startForegroundService(context, Intent(context, MessageConnectionService::class.java))
        true
    } catch (e: IllegalStateException) {
        false // ForegroundServiceStartNotAllowedException: the periodic job and next app start retry
    } catch (e: SecurityException) {
        false
    }

    fun ignoresBatteryOptimizations(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
}

/** Holds the process (and with it the Realtime connection) while vynq isn't on screen. */
class MessageConnectionService : Service() {
    override fun onCreate() {
        super.onCreate()
        val notification = container.messaging.notifier.connectionNotification()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        try {
            ServiceCompat.startForeground(this, Notifier.CONNECTION_NOTIFICATION_ID, notification, type)
        } catch (e: Exception) {
            stopSelf(); return
        }
        container.messaging.setKeepAlive(true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        container.messaging.syncNow()
        return START_STICKY
    }

    override fun onDestroy() {
        container.messaging.setKeepAlive(false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

/** Safety net: fetch mail and bring the connection service back if it was stopped. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.container
        val session = withTimeoutOrNull(20_000) { c.messaging.awaitSession() } ?: return Result.success()
        c.messaging.syncOnce()
        if (c.preferences.backgroundConnection.first()) BackgroundDelivery.startService(applicationContext)
        return if (session.userId.isNotEmpty()) Result.success() else Result.failure()
    }
}

/** Restarts delivery after a reboot or an app update (both may start a foreground service). */
class DeliveryRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        val c = context.container
        c.appScope.launch {
            try {
                val session = withTimeoutOrNull(8_000) { c.messaging.awaitSession() }
                BackgroundDelivery.ensure(context, c.preferences.backgroundConnection.first(), session != null)
            } finally {
                pending.finish()
            }
        }
    }
}
