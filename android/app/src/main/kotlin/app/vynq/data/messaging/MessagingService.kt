package app.vynq.data.messaging

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import app.vynq.data.auth.AccountRepository
import app.vynq.data.auth.AuthRepository
import app.vynq.data.auth.AuthState
import app.vynq.data.auth.BackendApi
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Messaging for the signed-in account on this device, or nothing when signed out. */
class MessagingSession(val messenger: Messenger, val dao: MessagingDao, val userId: String)

/**
 * Opens the encrypted store and starts messaging when someone signs in, and
 * wipes all local messaging data when they sign out (the next sign-in is a
 * new device). While the app is visible it listens for new mail.
 */
class MessagingService(
    private val context: Context,
    private val auth: AuthRepository,
    private val account: AccountRepository,
    private val api: BackendApi,
    private val scope: CoroutineScope,
) {
    private val _session = MutableStateFlow<MessagingSession?>(null)
    val session: StateFlow<MessagingSession?> = _session.asStateFlow()

    /** True while the first key upload / sync is running or last failed. */
    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    private var realtime: RealtimeMailbox? = null
    private var syncJob: Job? = null
    private var foreground = false

    fun attach() {
        scope.launch {
            auth.state.map { (it as? AuthState.SignedIn)?.userId }.distinctUntilChanged().collect { userId ->
                close()
                if (userId == null) {
                    if (auth.state.value is AuthState.SignedOut) withContext(Dispatchers.IO) { wipe(context, keep = null) }
                } else {
                    open(userId)
                }
            }
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { foreground = true; realtime?.start(); syncNow() }
            override fun onStop(owner: LifecycleOwner) { foreground = false; realtime?.stop() }
        })
    }

    private suspend fun open(userId: String) {
        val deviceId = api.deviceId
        val dao = withContext(Dispatchers.IO) {
            wipe(context, keep = deviceId) // older devices' data on this phone
            SqlCipherMessagingDao.open(context, deviceId)
        }
        val messenger = Messenger(api, dao, userId, FileAttachmentCache(attachmentsDir(context, deviceId)))
        _session.value = MessagingSession(messenger, dao, userId)
        realtime = RealtimeMailbox(api.realtimeUrl, deviceId, auth::accessToken, scope, onMail = ::syncNow)
            .also { if (foreground) it.start() }
        scope.launch {
            _syncing.value = true
            runCatching {
                account.registerThisDevice().getOrThrow()  // keys can only be published for a registered device
                messenger.start()
                messenger.refreshContacts()
                messenger.sync()
            }
            _syncing.value = false
        }
    }

    private fun close() {
        realtime?.stop(); realtime = null
        syncJob?.cancel(); syncJob = null
        _session.value = null
    }

    /** Fetch now (coalesced: one sync at a time, one queued). */
    fun syncNow() {
        val s = _session.value ?: return
        if (syncJob?.isActive == true) {
            scope.launch { syncJob?.join(); runCatching { s.messenger.sync() } }
            return
        }
        syncJob = scope.launch { runCatching { s.messenger.refreshContacts(); s.messenger.sync() } }
    }

    companion object {
        private fun attachmentsDir(context: Context, deviceId: String) = File(context.noBackupFilesDir, "attachments-$deviceId")

        fun wipe(context: Context, keep: String?) {
            SqlCipherMessagingDao.deleteAllExcept(context, keep)
            context.noBackupFilesDir.listFiles().orEmpty()
                .filter { it.name.startsWith("attachments-") && it.name != "attachments-$keep" }
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
