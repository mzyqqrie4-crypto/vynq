package app.vynq.call

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.vynq.R
import app.vynq.container

/** Incoming-call and ongoing-call notifications. */
class CallNotifier(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)

    init {
        val system = context.getSystemService(NotificationManager::class.java)
        // vynq rings by itself (Ringer), so the notification stays silent: no double ringing,
        // and calls still ring where the phone mutes or blocks the call notification.
        system.deleteNotificationChannel("calls_incoming")
        system.createNotificationChannel(
            NotificationChannel(CHANNEL_INCOMING, "Incoming calls", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Shows calls from your contacts. vynq plays the ringtone itself."
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
        system.createNotificationChannel(
            NotificationChannel(CHANNEL_ONGOING, "Ongoing call", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown during a call"
                setShowBadge(false)
            },
        )
    }

    /** Rings (repeating until answered, declined or timed out) and opens the call screen over the lock screen. */
    fun showIncoming(callId: String, caller: String, video: Boolean) {
        val person = Person.Builder().setName(caller).setImportant(true).build()
        val full = PendingIntent.getActivity(context, 1, CallManager.callIntent(context), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val answer = PendingIntent.getActivity(context, 2, CallManager.callIntent(context, CallActivity.ACTION_ANSWER),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val decline = PendingIntent.getBroadcast(context, 3, Intent(context, CallActionReceiver::class.java).setAction(CallActionReceiver.DECLINE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, CHANNEL_INCOMING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(caller)
            .setContentText(if (video) "Incoming video call · end-to-end encrypted" else "Incoming voice call · end-to-end encrypted")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setTimeoutAfter(CallManager.RING_TIMEOUT_MS)
            .setFullScreenIntent(full, true)
            .setContentIntent(full)
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(person, decline, answer).setIsVideo(video))
            .build()
        try { manager.notify(ID_INCOMING, n) } catch (e: SecurityException) { }
    }

    fun cancelIncoming() = manager.cancel(ID_INCOMING)

    fun ongoing(caller: String, video: Boolean, connectedAt: Long?): Notification {
        val person = Person.Builder().setName(caller).build()
        val open = PendingIntent.getActivity(context, 4, CallManager.callIntent(context), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val hangUp = PendingIntent.getBroadcast(context, 5, Intent(context, CallActionReceiver::class.java).setAction(CallActionReceiver.HANG_UP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(context, CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(caller)
            .setContentText(if (video) "Video call" else "Voice call")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setContentIntent(open)
            .apply { if (connectedAt != null) setWhen(connectedAt).setUsesChronometer(true) }
            .setStyle(NotificationCompat.CallStyle.forOngoingCall(person, hangUp).setIsVideo(video))
            .build()
    }

    companion object {
        const val CHANNEL_INCOMING = "calls_incoming_v2"
        const val CHANNEL_ONGOING = "calls_ongoing"
        const val ID_INCOMING = 100
        const val ID_ONGOING = 101
    }
}

/**
 * Foreground service for the length of a call: keeps camera and microphone
 * allowed with the screen off or the app in the background, and shows the
 * ongoing-call notification with a hang-up button.
 */
class CallService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val calls = container.calls
        val s = calls.state.value
        if (s == null) { stopSelf(); return START_NOT_STICKY }
        val video = intent?.getBooleanExtra(EXTRA_VIDEO, s.video) ?: s.video
        var type = 0
        if (Build.VERSION.SDK_INT >= 30) {
            type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
            if (video) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        }
        try {
            ServiceCompat.startForeground(this, CallNotifier.ID_ONGOING, calls.notifier.ongoing(s.peerLabel, video, s.connectedAt), type)
        } catch (e: Exception) {
            // Camera/mic types need the app in the foreground; the call screen starts us from there.
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val EXTRA_VIDEO = "video"

        fun start(context: Context, video: Boolean) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, CallService::class.java).putExtra(EXTRA_VIDEO, video))
            }
        }

        /** Refreshes the notification (e.g. to start the call timer). */
        fun update(context: Context) {
            val s = context.container.calls.state.value ?: return
            runCatching { start(context, s.video) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CallService::class.java))
        }
    }
}

/** Decline / hang up from a notification. */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val calls = context.container.calls
        when (intent.action) {
            DECLINE -> calls.decline()
            HANG_UP -> calls.hangUp()
        }
    }

    companion object {
        const val DECLINE = "app.vynq.call.DECLINE"
        const val HANG_UP = "app.vynq.call.HANG_UP"
    }
}
