package app.vynq.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.vynq.MainActivity
import app.vynq.R
import app.vynq.data.messaging.Contact
import app.vynq.data.messaging.Message
import app.vynq.data.messaging.MessageKind
import app.vynq.data.prefs.NotificationPreview

/**
 * Message notifications, posted on the phone from decrypted messages. Nothing
 * about a message ever goes through a push provider: notifications are built
 * locally after the device itself has fetched and decrypted the mail.
 *
 * On the lock screen only "New message" shows unless the phone is unlocked.
 */
class Notifier(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)

    init { createChannels() }

    fun canPost(): Boolean =
        (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            manager.areNotificationsEnabled()

    /** One notification per conversation, listing its unread messages (newest last). */
    fun showConversation(contact: Contact?, peer: String, unread: List<Message>, preview: NotificationPreview) {
        if (!canPost() || unread.isEmpty()) return
        val title = when (preview) {
            NotificationPreview.Hidden -> "vynq"
            else -> contact?.label ?: "New message"
        }
        val lines = unread.takeLast(6).map { m ->
            when {
                preview != NotificationPreview.NameAndMessage -> "New message"
                m.kind == MessageKind.Photo -> "Photo"
                else -> m.text.orEmpty()
            }
        }
        val summary = if (unread.size == 1) lines.last() else "${unread.size} new messages"
        val publicVersion = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("vynq")
            .setContentText(if (unread.size == 1) "New message" else "${unread.size} new messages")
            .build()

        val builder = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(if (preview == NotificationPreview.NameAndMessage) lines.last() else summary)
            .setStyle(NotificationCompat.InboxStyle().also { s -> lines.forEach(s::addLine) }.setSummaryText(
                if (unread.size > 1) "${unread.size} new" else null))
            .setNumber(unread.size)
            .setWhen(unread.last().sentAt)
            .setShowWhen(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setContentIntent(openChat(peer))
            .setGroup(GROUP_MESSAGES)
        try {
            manager.notify(TAG_CHAT, peer.hashCode(), builder.build())
        } catch (e: SecurityException) {
            // Permission revoked between the check and the post: nothing to show.
        }
    }

    fun cancelConversation(peer: String) = manager.cancel(TAG_CHAT, peer.hashCode())

    fun cancelAll() = manager.cancelAll()

    /** The ongoing notification a foreground service must show while it keeps the connection. */
    fun connectionNotification() = NotificationCompat.Builder(context, CHANNEL_CONNECTION)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle("Ready for messages")
        .setContentText("vynq keeps a private connection so messages arrive. You can hide this in notification settings.")
        .setPriority(NotificationCompat.PRIORITY_MIN)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setOngoing(true)
        .setShowWhen(false)
        .setContentIntent(openApp())
        .build()

    private fun openChat(peer: String): PendingIntent = PendingIntent.getActivity(
        context, peer.hashCode(),
        Intent(context, MainActivity::class.java)
            .setAction(ACTION_OPEN_CHAT)
            .putExtra(EXTRA_PEER, peer)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun createChannels() {
        val system = context.getSystemService(NotificationManager::class.java)
        system.createNotificationChannel(
            NotificationChannel(CHANNEL_MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "New messages from your contacts"
                lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
                enableVibration(true)
                setShowBadge(true)
            },
        )
        system.createNotificationChannel(
            NotificationChannel(CHANNEL_CONNECTION, "Background connection", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Shown while vynq keeps its connection open so messages arrive. Safe to hide."
                setShowBadge(false)
            },
        )
    }

    companion object {
        const val CHANNEL_MESSAGES = "messages"
        const val CHANNEL_CONNECTION = "connection"
        const val GROUP_MESSAGES = "app.vynq.MESSAGES"
        const val ACTION_OPEN_CHAT = "app.vynq.OPEN_CHAT"
        const val EXTRA_PEER = "peer"
        const val CONNECTION_NOTIFICATION_ID = 1
        private const val TAG_CHAT = "chat"
    }
}
