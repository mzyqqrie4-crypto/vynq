package app.vynq.data.messaging

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import app.vynq.data.auth.AccountRepository
import app.vynq.data.auth.AuthRepository
import app.vynq.data.auth.AuthState
import app.vynq.data.auth.BackendApi
import app.vynq.data.auth.DeviceIdentity
import app.vynq.data.prefs.PreferencesRepository
import app.vynq.notify.BackgroundDelivery
import app.vynq.notify.Notifier
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Messaging for the signed-in account on this device, or nothing when signed out. */
class MessagingSession(val messenger: Messenger, val dao: MessagingDao, val userId: String)

/**
 * Opens the encrypted store and starts messaging when someone signs in. On
 * sign-out the store is closed but kept, so the same account finds its
 * history when it signs back in. Data of devices this phone has forgotten
 * ("sign out and erase", deleted account) is deleted.
 *
 * Delivery: while the app is visible, or while the background connection
 * service runs, it listens on Realtime for new mail. Otherwise a periodic job
 * fetches it. New messages are notified locally after decryption.
 */
class MessagingService(
    private val context: Context,
    private val auth: AuthRepository,
    private val account: AccountRepository,
    private val api: BackendApi,
    private val device: DeviceIdentity,
    private val prefs: PreferencesRepository,
    private val scope: CoroutineScope,
) {
    val notifier = Notifier(context)

    private val _session = MutableStateFlow<MessagingSession?>(null)
    val session: StateFlow<MessagingSession?> = _session.asStateFlow()

    /** The conversation on screen right now; it doesn't get notifications. */
    val openChat = MutableStateFlow<String?>(null)

    private var realtime: RealtimeMailbox? = null
    private val syncLock = Mutex()
    private var syncJob: Job? = null
    @Volatile private var foreground = false
    @Volatile private var keepAlive = false
    private var ready: Job? = null

    fun attach() {
        scope.launch {
            auth.state.map { (it as? AuthState.SignedIn)?.userId }.distinctUntilChanged().collect { userId ->
                close()
                if (userId == null) {
                    if (auth.state.value is AuthState.SignedOut) {
                        notifier.cancelAll()
                        BackgroundDelivery.stop(context)
                        withContext(Dispatchers.IO) { wipe(context, keep = device.knownIds()) }
                    }
                } else {
                    open(userId)
                }
            }
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                foreground = true
                updateRealtime()
                syncNow()
                scope.launch { BackgroundDelivery.ensure(context, prefs.backgroundConnection.first(), _session.value != null) }
            }
            override fun onStop(owner: LifecycleOwner) { foreground = false; updateRealtime() }
        })
    }

    /** Called by the background connection service while it runs. */
    fun setKeepAlive(on: Boolean) { keepAlive = on; updateRealtime() }

    private fun updateRealtime() {
        if (foreground || keepAlive) realtime?.start() else realtime?.stop()
    }

    private suspend fun open(userId: String) {
        val deviceId = api.deviceId
        val dao = withContext(Dispatchers.IO) {
            wipe(context, keep = device.knownIds() + deviceId) // only devices this phone has forgotten
            SqlCipherMessagingDao.open(context, deviceId)
        }
        val messenger = Messenger(api, dao, userId, FileAttachmentCache(attachmentsDir(context, deviceId)))
        messenger.onHistoryRequest = { notifier.showHistoryRequest(it.deviceName) }
        _session.value = MessagingSession(messenger, dao, userId)
        realtime = RealtimeMailbox(api.realtimeUrl, deviceId, auth::accessToken, scope, onMail = ::syncNow)
        updateRealtime()
        BackgroundDelivery.ensure(context, prefs.backgroundConnection.first(), signedIn = true)
        ready = scope.launch {
            runCatching {
                account.registerThisDevice().getOrThrow()  // keys can only be published for a registered device
                messenger.start()
                messenger.refreshContacts()
            }
        }
        syncNow()
    }

    private fun close() {
        realtime?.stop(); realtime = null
        syncJob?.cancel(); syncJob = null
        ready?.cancel(); ready = null
        _session.value = null
    }

    /** Fetch now; calls coalesce so at most one sync runs and one waits. */
    fun syncNow() {
        if (_session.value == null) return
        if (syncLock.isLocked && syncJob?.isActive == true) {
            scope.launch { syncJob?.join(); syncOnce() }
            return
        }
        syncJob = scope.launch { syncOnce() }
    }

    /** One fetch-decrypt-notify pass. Used by the app, the service and the periodic job. */
    suspend fun syncOnce(): Boolean = syncLock.withLock {
        val s = _session.value ?: return false
        ready?.join()
        val received = runCatching { s.messenger.sync() }.getOrElse { return false }
        if (received.isNotEmpty()) {
            runCatching { s.messenger.refreshContacts() }
            notify(s, received.map { it.peer }.toSet())
        }
        true
    }

    /** Waits until the stored session has been read and messaging opened (or not, if signed out). */
    suspend fun awaitSession(): MessagingSession? {
        if (auth.state.value is AuthState.Loading) auth.state.first { it !is AuthState.Loading }
        if (auth.state.value !is AuthState.SignedIn) return null
        return _session.first { it != null }
    }

    private suspend fun notify(s: MessagingSession, peers: Set<String>) {
        val preview = prefs.notificationPreview.first()
        for (peer in peers) {
            if (foreground && openChat.value == peer) continue
            val unread = withContext(Dispatchers.IO) {
                s.dao.messages(peer).filter { !it.outgoing && !it.read && it.kind != MessageKind.System }
            }
            notifier.showConversation(s.dao.contact(peer), peer, unread, preview)
        }
    }

    companion object {
        private fun attachmentsDir(context: Context, deviceId: String) = File(context.noBackupFilesDir, "attachments-$deviceId")

        /** Deletes the messaging data of every device on this phone except [keep]. */
        fun wipe(context: Context, keep: Set<String>) {
            SqlCipherMessagingDao.deleteAllExcept(context, keep)
            context.noBackupFilesDir.listFiles().orEmpty()
                .filter { it.name.startsWith("attachments-") && it.name.removePrefix("attachments-") !in keep }
                .forEach { it.deleteRecursively() }
        }
    }
}

/** Encrypted blobs on disk, named by a hash of their storage path. */
class FileAttachmentCache(private val dir: File) : AttachmentCache {
    private fun file(path: String): File {
        val name = MessageDigest.getInstance("SHA-256").digest(path.encodeToByteArray()).joinToString("") { "%02x".format(it) }
        return File(dir, name)
    }
    override fun get(path: String) = file(path).takeIf { it.exists() }?.readBytes()
    override fun put(path: String, blob: ByteArray) {
        dir.mkdirs()
        file(path).writeBytes(blob)
    }
}
