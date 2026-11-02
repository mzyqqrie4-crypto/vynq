package app.vynq.data.messaging

import app.vynq.data.crypto.ProfileCrypto
import android.util.Log
import app.vynq.data.auth.AccountError
import app.vynq.data.auth.BackendApi
import app.vynq.data.crypto.AttachmentCrypto
import app.vynq.data.crypto.Envelope
import app.vynq.data.crypto.HistoryCrypto
import app.vynq.data.crypto.KeyUpload
import app.vynq.data.crypto.MessageCrypto
import app.vynq.data.crypto.OneTimeKey
import app.vynq.data.crypto.RemoteBundle
import app.vynq.data.crypto.SignalStore
import app.vynq.data.crypto.SignedKey
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.signal.libsignal.protocol.DuplicateMessageException
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.UntrustedIdentityException

/** Encrypted attachment blobs kept on this device (still encrypted). */
interface AttachmentCache {
    fun get(path: String): ByteArray?
    fun put(path: String, blob: ByteArray)
}

class MemoryAttachmentCache : AttachmentCache {
    private val map = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
    override fun get(path: String) = map[path]
    override fun put(path: String, blob: ByteArray) { map[path] = blob }
}

/**
 * End-to-end encrypted messaging for one signed-in device.
 *
 * Every message is encrypted separately for each of the recipient's devices
 * and for our own other devices, and the server only relays ciphertext. Plain
 * text exists only in memory and in the local SQLCipher database.
 *
 * Never logs message content, keys or identities.
 */
class Messenger(
    private val api: BackendApi,
    private val dao: MessagingDao,
    private val userId: String,
    private val cache: AttachmentCache,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val crypto = MessageCrypto(dao, now)
    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val b64 = Base64.getEncoder()
    private val unb64 = Base64.getDecoder()
    private val deviceId get() = api.deviceId

    /** Whether this phone can read and extend the account's encrypted history. */
    val history = MutableStateFlow(HistoryState.Unknown)
    /** Other phones of this account waiting for approval to receive the history key. */
    val historyRequests = MutableStateFlow(loadHistoryRequests())
    /** True until the user confirms they've written the recovery key down. */
    val recoveryKeyPending = MutableStateFlow(dao.get(NS_HISTORY, "recovery_unseen") != null)
    /** Called for every call signal that arrives (see CallManager). Runs inside sync. */
    var onCallSignal: ((CallSignal) -> Unit)? = null
    /** Called when another of the user's phones asks for history (for a notification). */
    var onHistoryRequest: ((HistoryRequest) -> Unit)? = null

    // --- keys ------------------------------------------------------------------------------

    /**
     * Creates and publishes this device's keys on first run (tops up one-time
     * keys later), then sets up or resumes the account's encrypted history.
     */
    suspend fun start() {
        locked { startKeys() }
        runCatching { setUpHistory() }
    }

    private suspend fun startKeys() {
        if (!crypto.hasIdentity) {
            publish(crypto.createKeys(ONE_TIME_BATCH))
            dao.put(SignalStore.NS_LOCAL, PUBLISHED, byteArrayOf(1))
            return
        }
        val status = api.rpc("prekey_status", buildJsonObject { put("p_device", deviceId) }).firstRow()
        val serverHasKeys = status?.get("has_identity")?.jsonPrimitive?.contentOrNull == "true" &&
            status["signed_key_id"] !is JsonNull && status["kyber_key_id"] !is JsonNull
        if (dao.get(SignalStore.NS_LOCAL, PUBLISHED) == null || !serverHasKeys) {
            publish(KeyUpload(crypto.identityKey.serialize(), crypto.store.localRegistrationId,
                crypto.newSignedPreKey(), crypto.newKyberPreKey(), crypto.newOneTimeKeys(ONE_TIME_BATCH)))
            dao.put(SignalStore.NS_LOCAL, PUBLISHED, byteArrayOf(1))
        } else if ((status["one_time_count"]?.jsonPrimitive?.intOrNull ?: 0) < ONE_TIME_LOW) {
            publish(KeyUpload(null, null, null, null, crypto.newOneTimeKeys(ONE_TIME_BATCH)))
        }
    }

    private suspend fun publish(keys: KeyUpload) {
        api.rpc("publish_keys", buildJsonObject {
            put("p_device", deviceId)
            put("p_identity_key", keys.identityKey?.let(b64::encodeToString))
            put("p_registration_id", keys.registrationId)
            put("p_signed", keys.signed?.toJson() ?: JsonNull)
            put("p_kyber", keys.kyber?.toJson() ?: JsonNull)
            put("p_one_time", buildJsonArray {
                keys.oneTime.forEach { k -> addJsonObject { put("id", k.id); put("key", b64.encodeToString(k.publicKey)) } }
            })
        })
    }

    private fun SignedKey.toJson() = buildJsonObject {
        put("id", id); put("key", b64.encodeToString(publicKey)); put("sig", b64.encodeToString(signature))
    }

    // --- contacts ---------------------------------------------------------------------------

    suspend fun refreshContacts(): List<Contact> {
        val rows = api.rpc("list_contacts", JsonObject(emptyMap())).jsonArray.map { it.jsonObject }
        val myAvatar = runCatching { api.rpc("my_avatar", JsonObject(emptyMap())) }.getOrNull()
        val before = withContext(io) { dao.contacts() }.associateBy { it.userId }
        val list = rows.map { r ->
            Contact(
                userId = r.str("user_id")!!,
                username = r.str("username"),
                displayName = r.str("display_name"),
                state = when (r.str("state")) {
                    "accepted" -> ContactState.Accepted
                    "pending_in" -> ContactState.Incoming
                    else -> ContactState.Outgoing
                },
            )
        }
        withContext(io) {
            // A removed contact whose chat was kept stays readable, read-only, until the chat is deleted.
            val kept = before.values.filter { old ->
                list.none { it.userId == old.userId } && old.state in KEEPABLE && dao.messages(old.userId).isNotEmpty()
            }.map { it.copy(state = ContactState.Removed) }
            dao.replaceContacts(list + kept)
            rows.forEach { r -> dao.setAvatarPath(r.str("user_id")!!, r.str("avatar_path")) }
            if (myAvatar != null) dao.setAvatarPath(userId, (myAvatar as? JsonPrimitive)?.contentOrNull)
        }
        // New contacts get our profile key, so they can see our profile picture.
        list.filter { it.state == ContactState.Accepted && before[it.userId]?.state != ContactState.Accepted }
            .forEach { profileShares += it.userId }
        return list
    }

    suspend fun requestContact(username: String): ContactState {
        val row = api.rpc("request_contact", buildJsonObject { put("p_username", username.trim().lowercase()) }).firstRow()
        refreshContacts()
        flushProfileShares()
        return if (row?.str("state") == "accepted") ContactState.Accepted else ContactState.Outgoing
    }

    suspend fun acceptContact(user: String) {
        api.rpc("accept_contact", buildJsonObject { put("p_user_id", user) })
        refreshContacts()
        flushProfileShares()
    }

    /**
     * Ends a contact (or declines / cancels a request). With [deleteChat] the
     * conversation is also deleted here, from the encrypted history and on this
     * account's other phones; otherwise it stays readable, read-only.
     */
    suspend fun removeContact(user: String, deleteChat: Boolean = false) {
        api.rpc("remove_contact", buildJsonObject { put("p_user_id", user) })
        if (deleteChat) deleteChat(user)
        refreshContacts()
    }

    /** Deletes a conversation here, from the encrypted history, and on this account's other phones. */
    suspend fun deleteChat(peer: String) {
        val ids = withContext(io) { dao.messages(peer).map { it.id } }
        ids.chunked(500).forEach { chunk ->
            api.rpc("delete_history", buildJsonObject { put("p_ids", buildJsonArray { chunk.forEach { add(JsonPrimitive(it)) } }) })
        }
        withContext(io) { dao.deleteMessages(peer) }
        runCatching { locked { sendControl(syncDevices(userId), control(CONTROL_CHAT_DELETE).copy(syncPeer = peer)) } }
        if (dao.contact(peer)?.state == ContactState.Removed) refreshContacts()
    }

    /** A private name for a contact that only this account sees, on all its phones. Blank clears it. */
    suspend fun setNickname(peer: String, nickname: String?) {
        val name = nickname?.trim()?.take(40)?.takeIf { it.isNotEmpty() }
        withContext(io) { dao.setNickname(peer, name) }
        historyKey()?.let { hk ->
            runCatching {
                appendHistory(hk, listOf(HistoryRecord(id = "$NICK_PREFIX$peer:${now()}", peer = peer, outgoing = true,
                    kind = MessageKind.System, text = name, sentAt = now())))
            }
        }
        runCatching { locked { sendControl(syncDevices(userId), control(CONTROL_NICKNAME).copy(syncPeer = peer, text = name)) } }
    }

    // --- profile picture ------------------------------------------------------------------------

    private val profileShares: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())

    private fun ownProfileKey(): ByteArray? = dao.get(NS_PROFILE, "key")

    private suspend fun ensureProfileKey(): ByteArray {
        ownProfileKey()?.let { return it }
        val pk = ProfileCrypto.newKey()
        withContext(io) { dao.put(NS_PROFILE, "key", pk) }
        historyKey()?.let { hk ->
            runCatching {
                appendHistory(hk, listOf(HistoryRecord(id = "$PK_PREFIX${now()}", peer = userId, outgoing = true,
                    kind = MessageKind.System, text = b64.encodeToString(pk), sentAt = now())))
            }
        }
        return pk
    }

    /**
     * Sets this account's profile picture (JPEG), or removes it with null. It is
     * encrypted here with the profile key; only contacts get that key.
     */
    suspend fun setAvatar(jpeg: ByteArray?) {
        val old = dao.get(ContactLocal.NS_AVATAR_PATH, userId)?.decodeToString()
        if (jpeg == null) {
            api.rpc("set_avatar", buildJsonObject { put("p_path", JsonNull) })
            withContext(io) { dao.setAvatarPath(userId, null); dao.delete(NS_AVATAR, userId); dao.delete(NS_AVATAR_LOADED, userId) }
        } else {
            val pk = ensureProfileKey()
            val path = "$userId/${UUID.randomUUID()}"
            api.upload(path, ProfileCrypto.seal(pk, userId, jpeg), BackendApi.AVATARS)
            api.rpc("set_avatar", buildJsonObject { put("p_path", path) })
            withContext(io) {
                dao.put(NS_AVATAR, userId, jpeg)
                dao.put(NS_AVATAR_LOADED, userId, path.encodeToByteArray())
                dao.setAvatarPath(userId, path)
            }
            // Contacts (and our own other phones) get the key now, not only with the next message.
            profileShares += dao.contacts().filter { it.state == ContactState.Accepted }.map { it.userId } + userId
            flushProfileShares()
        }
        if (old != null) runCatching { api.remove(old, BackendApi.AVATARS) }
    }

    /** Someone's profile picture (JPEG), or null. Downloaded and decrypted once per change, then kept here. */
    suspend fun avatar(user: String): ByteArray? = withContext(io) {
        val path = dao.get(ContactLocal.NS_AVATAR_PATH, user)?.decodeToString() ?: return@withContext null
        if (dao.get(NS_AVATAR_LOADED, user)?.decodeToString() == path) return@withContext dao.get(NS_AVATAR, user)
        val key = (if (user == userId) ownProfileKey() else dao.get(NS_PEER_PROFILE_KEY, user)) ?: return@withContext null
        val jpeg = runCatching { ProfileCrypto.open(key, user, api.download(path, BackendApi.AVATARS)) }.getOrNull()
            ?: return@withContext null
        dao.put(NS_AVATAR, user, jpeg)
        dao.put(NS_AVATAR_LOADED, user, path.encodeToByteArray())
        jpeg
    }

    private suspend fun flushProfileShares() {
        val pk = ownProfileKey() ?: run { profileShares.clear(); return }
        val users = synchronized(profileShares) { profileShares.toList().also { profileShares.clear() } }
        for (u in users) runCatching { locked { sendControl(syncDevices(u), control(CONTROL_PROFILE).copy(profileKey = b64.encodeToString(pk))) } }
    }

    private fun adoptPeerProfileKey(user: String, encoded: String) {
        val pk = runCatching { unb64.decode(encoded) }.getOrNull()?.takeIf { it.size == 32 } ?: return
        if (dao.get(NS_PEER_PROFILE_KEY, user)?.contentEquals(pk) == true) return
        dao.put(NS_PEER_PROFILE_KEY, user, pk)
        dao.delete(NS_AVATAR_LOADED, user)          // open their picture again with the new key
        dao.setAvatarPath(user, null); dao.setAvatarPath(user, dao.contact(user)?.avatarPath) // nudge the UI
    }

    private fun control(type: String) = WirePayload(id = UUID.randomUUID().toString(), from = userId, sentAt = now(), control = type)

    // --- devices and trust --------------------------------------------------------------------

    /**
     * Reconciles a user's devices with the server. New devices are pinned and,
     * if the user already had devices, a "safety number changed" notice is added
     * and verification is cleared. A known device offering a different identity
     * key is never used.
     */
    private suspend fun syncDevices(user: String): List<RemoteDevice> {
        val rows = api.rpc("contact_devices", buildJsonObject { put("p_user_id", user) }).jsonArray
            .map { it.jsonObject }
            .filter { it.str("device_id") != deviceId }
        return withContext(io) {
            val known = dao.devices(user)
            val hadDevices = known.any { it.active }
            val serverIds = rows.mapNotNull { it.str("device_id") }.toSet()
            var added = 0
            for (r in rows) {
                val id = r.str("device_id") ?: continue
                val key = unb64.decode(r.str("identity_key") ?: continue)
                val existing = dao.device(id)
                val pinned = existing?.identityKey ?: crypto.knownIdentity(id)?.serialize()
                when {
                    pinned != null && !pinned.contentEquals(key) -> systemNote(user, KEY_MISMATCH_NOTE)
                    existing == null -> {
                        dao.upsertDevice(RemoteDevice(id, user, key, now(), verified = false, active = true)); added++
                    }
                    !existing.active -> dao.setDeviceActive(id, true)
                }
            }
            known.filter { it.active && it.deviceId !in serverIds }.forEach { d ->
                dao.setDeviceActive(d.deviceId, false)
                dao.delete(SignalStore.NS_SESSION, d.deviceId)
            }
            if (added > 0 && hadDevices && user != userId) {
                dao.setVerified(user, false)
                systemNote(user, "${label(user)} has a new device, so your safety number with them changed. Verify it again if you'd verified before.")
            }
            dao.devices(user).filter { it.active && it.deviceId in serverIds }
        }
    }

    fun isVerified(peer: String): Boolean = dao.devices(peer).filter { it.active }.let { it.isNotEmpty() && it.all(RemoteDevice::verified) }

    /** Safety numbers for each of a contact's active devices. */
    fun safetyNumbers(peer: String): List<Pair<RemoteDevice, String>> =
        dao.devices(peer).filter { it.active }.map { d ->
            d to crypto.safetyNumber(deviceId, d.deviceId, IdentityKey(d.identityKey))
        }

    /** Fetches the latest device list first, so the numbers shown are current. */
    suspend fun refreshDevices(peer: String) = locked { syncDevices(peer) }

    suspend fun setVerified(peer: String, verified: Boolean) = withContext(io) {
        dao.setVerified(peer, verified)
        systemNote(peer, if (verified) "You verified ${label(peer)}'s safety number." else "You cleared verification for ${label(peer)}.")
    }

    // --- sending ------------------------------------------------------------------------------

    suspend fun sendText(peer: String, text: String): Message {
        val message = Message(UUID.randomUUID().toString(), peer, true, MessageKind.Text, text.trim(), null, now(), MessageStatus.Sending)
        withContext(io) { dao.insertMessage(message) }
        deliver(message)
        runCatching { archivePending() }
        return dao.message(message.id) ?: message
    }

    suspend fun sendPhoto(peer: String, image: ByteArray, mime: String, width: Int?, height: Int?): Message {
        val id = UUID.randomUUID().toString()
        val sealed = withContext(io) { AttachmentCrypto.seal(image) }
        val pointer = AttachmentPointer(
            path = "$userId/${UUID.randomUUID()}",
            key = b64.encodeToString(sealed.key),
            digest = b64.encodeToString(sealed.digest),
            size = sealed.blob.size.toLong(), mime = mime, width = width, height = height,
        )
        withContext(io) { cache.put(pointer.path, sealed.blob) }
        val message = Message(id, peer, true, MessageKind.Photo, null, pointer, now(), MessageStatus.Sending)
        withContext(io) { dao.insertMessage(message) }
        val uploaded = runCatching { api.upload(pointer.path, sealed.blob) }.isSuccess
        if (uploaded) deliver(message) else withContext(io) { dao.updateStatus(id, MessageStatus.Failed) }
        runCatching { archivePending() }
        return dao.message(id) ?: message
    }

    suspend fun retry(id: String) {
        val m = dao.message(id)?.takeIf { it.outgoing && it.status == MessageStatus.Failed } ?: return
        withContext(io) { dao.updateStatus(id, MessageStatus.Sending) }
        if (m.attachment != null && cache.get(m.attachment.path) != null) {
            runCatching { api.upload(m.attachment.path, cache.get(m.attachment.path)!!) } // may already exist
        }
        deliver(m)
    }

    private suspend fun deliver(message: Message) {
        val ok = runCatching { locked { encryptAndSend(message) } }
            .recoverCatching { e ->
                // A device list that changed under us: reconcile and try once more.
                if (e is AccountError.Server && e.status == 403) locked { encryptAndSend(message) } else throw e
            }
            // Why a send failed, for diagnosis (error type and message only, never the text).
            .onFailure { e -> runCatching { Log.w(TAG, "send failed: ${e.javaClass.name}: ${e.message}") } } // no Log in JVM tests
            .isSuccess
        withContext(io) { dao.updateStatus(message.id, if (ok) MessageStatus.Sent else MessageStatus.Failed) }
    }

    private suspend fun encryptAndSend(message: Message) {
        val payload = WirePayload(id = message.id, from = userId, sentAt = message.sentAt, text = message.text, attachment = message.attachment,
            profileKey = ownProfileKey()?.let(b64::encodeToString))
        val theirs = syncDevices(message.peer)
        if (theirs.isEmpty()) throw IllegalStateException("recipient has no devices ready")
        val mine = syncDevices(userId)
        val out = buildList {
            theirs.forEach { add(it to payload) }
            mine.forEach { add(it to payload.copy(syncPeer = message.peer)) }
        }.mapNotNull { (device, p) -> encryptFor(device, p) }
        if (out.none { it.first in theirs.map(RemoteDevice::deviceId) }) throw IllegalStateException("no usable device")
        out.chunked(20).forEach { chunk ->
            api.rpc("send_messages", buildJsonObject {
                put("p_from_device", deviceId)
                put("p_messages", buildJsonArray {
                    chunk.forEach { (to, e) -> addJsonObject { put("to", to); put("type", e.type); put("body", b64.encodeToString(e.body)) } }
                })
            })
        }
    }

    private suspend fun encryptFor(device: RemoteDevice, payload: WirePayload): Pair<String, Envelope>? {
        if (!crypto.hasSession(device.deviceId)) {
            val bundle = claimBundle(device.deviceId) ?: return null
            if (!bundle.identityKey.contentEquals(device.identityKey)) {
                withContext(io) { systemNote(device.userId, KEY_MISMATCH_NOTE) }
                return null
            }
            try {
                withContext(io) { crypto.startSession(bundle) }
            } catch (e: UntrustedIdentityException) {
                withContext(io) { systemNote(device.userId, KEY_MISMATCH_NOTE) }
                return null
            }
        }
        val plain = json.encodeToString(WirePayload.serializer(), payload).encodeToByteArray()
        return device.deviceId to withContext(io) { crypto.encrypt(device.deviceId, plain) }
    }

    private suspend fun claimBundle(device: String): RemoteBundle? {
        val r = api.rpc("claim_prekey_bundle", buildJsonObject { put("p_device_id", device) }).firstRow() ?: return null
        fun bytes(k: String) = unb64.decode(r.str(k)!!)
        return RemoteBundle(
            deviceId = device,
            registrationId = r["registration_id"]!!.jsonPrimitive.int,
            identityKey = bytes("identity_key"),
            signed = SignedKey(r["signed_key_id"]!!.jsonPrimitive.int, bytes("signed_key"), bytes("signed_sig")),
            kyber = SignedKey(r["kyber_key_id"]!!.jsonPrimitive.int, bytes("kyber_key"), bytes("kyber_sig")),
            oneTime = r["prekey_id"]?.jsonPrimitive?.intOrNull?.let { OneTimeKey(it, bytes("prekey")) },
        )
    }

    // --- receiving ----------------------------------------------------------------------------

    /**
     * Fetches, decrypts and stores everything waiting for this device, then
     * acknowledges it. Returns the new incoming messages (for notifications).
     */
    suspend fun sync(): List<Message> = syncInbox().also {
        runCatching { archivePending() }
        runCatching { flushProfileShares() }
    }

    private suspend fun syncInbox(): List<Message> = locked {
        val received = mutableListOf<Message>()
        repeat(MAX_PAGES) {
            val rows = api.rpc("fetch_messages", buildJsonObject { put("p_device", deviceId); put("p_limit", PAGE) })
                .jsonArray.map { it.jsonObject }
            if (rows.isEmpty()) return@locked received
            for (r in rows) receive(r)?.let { if (!it.outgoing) received += it }
            api.rpc("ack_messages", buildJsonObject {
                put("p_device", deviceId)
                put("p_ids", buildJsonArray { rows.forEach { add(it["id"]!!.jsonPrimitive.long) } })
            })
            if (rows.size < PAGE) return@locked received
        }
        received
    }

    /** The message when a new one was stored. Anything unusable is dropped (and acknowledged). */
    private suspend fun receive(r: JsonObject): Message? {
        val sender = r.str("from_user") ?: return null
        val fromDevice = r.str("from_device") ?: return null
        val envelope = Envelope(r["type"]!!.jsonPrimitive.int, unb64.decode(r.str("body")!!))
        val self = sender == userId

        if (!self && dao.contact(sender)?.state != ContactState.Accepted) {
            refreshContacts()
            if (dao.contact(sender)?.state != ContactState.Accepted) return null
        }
        val device = dao.device(fromDevice)?.takeIf { it.active }
            ?: syncDevices(sender).firstOrNull { it.deviceId == fromDevice }
        if (device == null) {
            if (!self) withContext(io) { systemNote(sender, "A message from an unrecognised device of ${label(sender)} was discarded.") }
            return null
        }
        val claimed = crypto.senderIdentity(envelope)
        if (claimed != null && !claimed.serialize().contentEquals(device.identityKey)) {
            withContext(io) { systemNote(sender, KEY_MISMATCH_NOTE) }
            return null
        }

        val plain = try {
            withContext(io) { crypto.decrypt(fromDevice, envelope) }
        } catch (e: DuplicateMessageException) {
            return null // already handled before an interrupted acknowledgement
        } catch (e: Exception) {
            if (!self) withContext(io) { systemNote(sender, "A message from ${label(sender)} couldn't be decrypted.") }
            return null
        }
        val payload = runCatching { json.decodeFromString(WirePayload.serializer(), plain.decodeToString()) }.getOrNull()
            ?: return null
        if (payload.from != sender) return null
        if (!self) payload.profileKey?.let { withContext(io) { adoptPeerProfileKey(sender, it) } }
        if (payload.control != null) {
            if (payload.control.startsWith("call_") && payload.callId != null) {
                onCallSignal?.invoke(CallSignal(payload.control, payload.callId, sender, fromDevice, self,
                    payload.video ?: true, payload.callKey, payload.reason, payload.sentAt, payload.codecs, payload.screen))
            } else if (self) {
                handleControl(fromDevice, payload)
            }
            return null
        }

        val kind = if (payload.attachment != null) MessageKind.Photo else MessageKind.Text
        val message = when {
            self && payload.syncPeer != null ->
                Message(payload.id, payload.syncPeer, true, kind, payload.text, payload.attachment, payload.sentAt, MessageStatus.Sent)
            !self ->
                Message(payload.id, sender, false, kind, payload.text, payload.attachment, payload.sentAt, MessageStatus.Received, read = false)
            else -> return null
        }
        return message.takeIf { withContext(io) { dao.insertMessage(message) } }
    }

    // --- call signalling ------------------------------------------------------------------------

    /**
     * Sends a call signal end-to-end encrypted to [peer]'s phones, and optionally to
     * our own other phones (so they stop ringing when we answer here). Returns how
     * many of the peer's phones it reached.
     */
    suspend fun sendCallSignal(
        peer: String, type: String, callId: String,
        video: Boolean? = null, callKey: String? = null, reason: String? = null, alsoMine: Boolean = false,
        codecs: List<String>? = null, screen: Int? = null,
    ): Int = locked {
        val payload = WirePayload(id = UUID.randomUUID().toString(), from = userId, sentAt = now(), control = type,
            callId = callId, video = video, callKey = callKey, reason = reason, codecs = codecs, screen = screen)
        val theirs = syncDevices(peer)
        val reached = sendControl(theirs, payload)
        if (alsoMine) sendControl(syncDevices(userId), payload)
        reached
    }

    /** Adds a line like "Missed video call" to the conversation (not part of the backed-up history). */
    fun callNote(peer: String, text: String) = systemNote(peer, text)

    fun contactLabel(user: String) = label(user)

    // --- encrypted history across phones ---------------------------------------------------------

    private fun historyKey(): ByteArray? = dao.get(NS_HISTORY, "key")

    /** First phone of the account: create the history and recovery keys. Later: resume, or wait for unlock. */
    private suspend fun setUpHistory() {
        val local = historyKey()
        val vault = api.rpc("get_history_vault", JsonObject(emptyMap())).firstRow()
        if (local != null) {
            if (vault == null) {
                // Created here but the upload never finished: wrap again with the stored recovery key.
                dao.get(NS_HISTORY, "recovery")?.let { rk -> uploadVault(HistoryCrypto.wrap(local, rk), replace = false) }
            }
            history.value = HistoryState.Ready
            restoreHistory()
            archivePending()
            return
        }
        if (vault != null) { history.value = HistoryState.Locked; return }

        val hk = HistoryCrypto.newHistoryKey()
        val rk = HistoryCrypto.newRecoveryKey()
        try {
            uploadVault(HistoryCrypto.wrap(hk, rk), replace = false)
        } catch (e: AccountError.Server) {
            if (e.code == "vault_exists") { history.value = HistoryState.Locked; return } // another phone was first
            throw e
        }
        withContext(io) {
            dao.put(NS_HISTORY, "recovery", rk)
            dao.put(NS_HISTORY, "recovery_unseen", byteArrayOf(1))
            dao.put(NS_HISTORY, "key", hk)
        }
        recoveryKeyPending.value = true
        history.value = HistoryState.Ready
        archivePending()
    }

    private suspend fun uploadVault(wrapped: ByteArray, replace: Boolean) {
        api.rpc("set_history_vault", buildJsonObject {
            put("p_wrapped_key", b64.encodeToString(wrapped)); put("p_replace", replace)
        })
    }

    /** The recovery key as the user should write it down, while this phone has it. */
    fun recoveryKeyText(): String? = dao.get(NS_HISTORY, "recovery")?.let(HistoryCrypto::formatRecoveryKey)
    fun recoveryKeyUnseen(): Boolean = dao.get(NS_HISTORY, "recovery_unseen") != null
    fun markRecoveryKeySaved() {
        dao.delete(NS_HISTORY, "recovery_unseen")
        recoveryKeyPending.value = false
    }

    /** Unlocks history with the recovery key. False if the key is wrong. */
    suspend fun unlockWithRecoveryKey(input: String): Boolean {
        val rk = HistoryCrypto.parseRecoveryKey(input) ?: return false
        val wrapped = api.rpc("get_history_vault", JsonObject(emptyMap())).firstRow()?.str("wrapped_key") ?: return false
        val hk = try {
            HistoryCrypto.unwrap(unb64.decode(wrapped), rk)
        } catch (e: HistoryCrypto.WrongKeyException) {
            return false
        }
        adoptHistoryKey(hk, rk)
        return true
    }

    /** Asks the account's other phones for the history key. Returns how many were asked (0: none left). */
    suspend fun requestHistory(): Int = locked {
        val mine = syncDevices(userId)
        val payload = WirePayload(id = UUID.randomUUID().toString(), from = userId, sentAt = now(),
            control = CONTROL_HISTORY_REQUEST, deviceName = api.deviceName)
        sendControl(mine, payload)
    }

    /** Gives a waiting phone of this account the history key (end-to-end encrypted to that phone). */
    suspend fun approveHistoryRequest(deviceId: String) {
        val hk = historyKey() ?: return
        locked {
            val target = syncDevices(userId).filter { it.deviceId == deviceId }
            val payload = WirePayload(id = UUID.randomUUID().toString(), from = userId, sentAt = now(),
                control = CONTROL_HISTORY_KEY, historyKey = b64.encodeToString(hk),
                recoveryKey = dao.get(NS_HISTORY, "recovery")?.let(b64::encodeToString))
            if (sendControl(target, payload) == 0) throw IllegalStateException("that phone is no longer signed in")
        }
        dismissHistoryRequest(deviceId)
    }

    fun dismissHistoryRequest(deviceId: String) {
        dao.delete(NS_HISTORY_REQ, deviceId)
        historyRequests.value = loadHistoryRequests()
    }

    /** Runs inside sync's lock: only records what arrived; [applyPendingKey] acts on it afterwards. */
    private suspend fun handleControl(fromDevice: String, p: WirePayload) {
        when (p.control) {
            CONTROL_HISTORY_REQUEST -> if (historyKey() != null) {
                val req = HistoryRequest(fromDevice, p.deviceName?.take(40) ?: "A new phone", p.sentAt)
                withContext(io) { dao.put(NS_HISTORY_REQ, fromDevice, "${req.at}|${req.deviceName}".encodeToByteArray()) }
                historyRequests.value = loadHistoryRequests()
                onHistoryRequest?.invoke(req)
            }
            CONTROL_NICKNAME -> p.syncPeer?.let { peer ->
                withContext(io) { dao.setNickname(peer, p.text?.trim()?.take(40)?.takeIf { it.isNotEmpty() }) }
            }
            CONTROL_CHAT_DELETE -> p.syncPeer?.let { peer -> withContext(io) { dao.deleteMessages(peer) } }
            CONTROL_PROFILE -> p.profileKey?.let { runCatching { unb64.decode(it) }.getOrNull() }?.takeIf { it.size == 32 }?.let { pk ->
                withContext(io) { dao.put(NS_PROFILE, "key", pk); dao.delete(NS_AVATAR_LOADED, userId) }
            }
            CONTROL_HISTORY_KEY -> if (historyKey() == null) {
                val hk = p.historyKey?.let(unb64::decode)?.takeIf { it.size == 32 } ?: return
                pendingKey = hk to p.recoveryKey?.let(unb64::decode)
            }
        }
    }

    @Volatile private var pendingKey: Pair<ByteArray, ByteArray?>? = null

    /** Applies a history key another phone sent, after checking it opens this account's vault. */
    private suspend fun applyPendingKey() {
        val (hk, rk) = pendingKey ?: return
        pendingKey = null
        val wrapped = api.rpc("get_history_vault", JsonObject(emptyMap())).firstRow()?.str("wrapped_key")
        if (rk != null && wrapped != null) {
            val matches = runCatching { HistoryCrypto.unwrap(unb64.decode(wrapped), rk).contentEquals(hk) }.getOrDefault(false)
            if (!matches) return
        }
        adoptHistoryKey(hk, rk)
    }

    private suspend fun adoptHistoryKey(hk: ByteArray, rk: ByteArray?) {
        withContext(io) {
            dao.put(NS_HISTORY, "key", hk)
            if (rk != null) dao.put(NS_HISTORY, "recovery", rk)
        }
        history.value = HistoryState.Ready
        restoreHistory()
        archivePending()
    }

    /** Downloads history records added since last time (by any of the account's phones). */
    private suspend fun restoreHistory() {
        val hk = historyKey() ?: return
        var cursor = dao.get(NS_HISTORY, "cursor")?.decodeToString()?.toLong() ?: 0L
        while (true) {
            val rows = api.rpc("fetch_history", buildJsonObject { put("p_after", cursor); put("p_limit", HISTORY_PAGE) })
                .jsonArray.map { it.jsonObject }
            if (rows.isEmpty()) break
            withContext(io) {
                for (r in rows) {
                    val id = r.str("record_id") ?: continue
                    val record = runCatching {
                        json.decodeFromString(HistoryRecord.serializer(),
                            HistoryCrypto.openRecord(hk, id, unb64.decode(r.str("blob")!!)).decodeToString())
                    }.getOrNull() ?: continue // damaged or not readable with this key: skip it
                    if (record.id != id) continue
                    if (id.startsWith(NICK_PREFIX)) { dao.setNickname(record.peer, record.text); continue }
                    if (id.startsWith(PK_PREFIX)) {
                        record.text?.let { runCatching { unb64.decode(it) }.getOrNull() }?.takeIf { it.size == 32 }
                            ?.let { dao.put(NS_PROFILE, "key", it) }
                        continue
                    }
                    dao.insertMessage(Message(record.id, record.peer, record.outgoing, record.kind, record.text,
                        record.attachment, record.sentAt, if (record.outgoing) MessageStatus.Sent else MessageStatus.Received,
                        read = true, archived = true))
                }
                cursor = rows.last()["seq"]!!.jsonPrimitive.long
                dao.put(NS_HISTORY, "cursor", cursor.toString().encodeToByteArray())
            }
            if (rows.size < HISTORY_PAGE) break
        }
    }

    /** Uploads messages that aren't in the encrypted history yet. */
    private suspend fun archivePending() {
        applyPendingKey()
        val hk = historyKey() ?: return
        while (true) {
            val batch = withContext(io) { dao.unarchived(100) }
                .filter { it.status != MessageStatus.Sending && it.status != MessageStatus.Failed }
            if (batch.isEmpty()) return
            appendHistory(hk, batch.map { m ->
                HistoryRecord(id = m.id, peer = m.peer, outgoing = m.outgoing, kind = m.kind,
                    text = m.text, attachment = m.attachment, sentAt = m.sentAt)
            })
            withContext(io) { dao.markArchived(batch.map { it.id }) }
            if (batch.size < 100) return
        }
    }

    private suspend fun appendHistory(hk: ByteArray, records: List<HistoryRecord>) {
        api.rpc("append_history", buildJsonObject {
            put("p_records", buildJsonArray {
                records.forEach { r ->
                    val blob = HistoryCrypto.sealRecord(hk, r.id, json.encodeToString(HistoryRecord.serializer(), r).encodeToByteArray())
                    addJsonObject { put("id", r.id); put("blob", b64.encodeToString(blob)) }
                }
            })
        })
    }

    private suspend fun sendControl(devices: List<RemoteDevice>, payload: WirePayload): Int {
        val out = devices.mapNotNull { encryptFor(it, payload) }
        if (out.isEmpty()) return 0
        api.rpc("send_messages", buildJsonObject {
            put("p_from_device", deviceId)
            put("p_messages", buildJsonArray {
                out.forEach { (to, e) -> addJsonObject { put("to", to); put("type", e.type); put("body", b64.encodeToString(e.body)) } }
            })
        })
        return out.size
    }

    private fun loadHistoryRequests(): List<HistoryRequest> = dao.keys(NS_HISTORY_REQ).mapNotNull { id ->
        dao.get(NS_HISTORY_REQ, id)?.decodeToString()?.split('|', limit = 2)?.takeIf { it.size == 2 }
            ?.let { HistoryRequest(id, it[1], it[0].toLongOrNull() ?: 0) }
    }.sortedBy { it.at }

    // --- attachments ----------------------------------------------------------------------------

    /** Downloads (once) and decrypts a photo. Throws [AttachmentCrypto.IntegrityException] if it was altered. */
    suspend fun openAttachment(pointer: AttachmentPointer): ByteArray {
        val blob = withContext(io) { cache.get(pointer.path) } ?: api.download(pointer.path).also { b ->
            withContext(io) { cache.put(pointer.path, b) }
        }
        return withContext(io) { AttachmentCrypto.open(blob, unb64.decode(pointer.key), unb64.decode(pointer.digest)) }
    }

    // --- helpers --------------------------------------------------------------------------------

    private suspend fun <T> locked(block: suspend () -> T): T = lock.withLock { block() }

    private fun label(user: String) = dao.contact(user)?.label ?: "This contact"

    private fun systemNote(peer: String, text: String) {
        dao.insertMessage(Message(UUID.randomUUID().toString(), peer, false, MessageKind.System, text, null, now(), MessageStatus.Received, read = true))
    }

    private fun JsonElement.firstRow(): JsonObject? = when (this) {
        is JsonArray -> firstOrNull()?.jsonObject
        is JsonObject -> this
        else -> null
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    companion object {
        private const val TAG = "vynq.msg"
        private const val PUBLISHED = "published"
        private const val NS_HISTORY = "history"
        private const val NS_HISTORY_REQ = "history_req"
        private const val HISTORY_PAGE = 200
        const val CONTROL_HISTORY_REQUEST = "history_request"
        const val CONTROL_NICKNAME = "nickname"
        const val CONTROL_CHAT_DELETE = "chat_delete"
        const val CONTROL_PROFILE = "profile"
        private const val NS_PROFILE = "profile"
        private const val NS_PEER_PROFILE_KEY = "peer_profile_key"
        private const val NS_AVATAR = "avatar"
        private const val NS_AVATAR_LOADED = "avatar_loaded"
        private const val NICK_PREFIX = "nick:"
        private const val PK_PREFIX = "pk:"
        private val KEEPABLE = setOf(ContactState.Accepted, ContactState.Removed)
        const val CONTROL_HISTORY_KEY = "history_key"
        private const val ONE_TIME_BATCH = 100
        private const val ONE_TIME_LOW = 20
        private const val PAGE = 100
        private const val MAX_PAGES = 10
        const val KEY_MISMATCH_NOTE =
            "Security warning: the server offered a different key for a device we already know. Nothing was sent to or accepted from it."
    }
}
