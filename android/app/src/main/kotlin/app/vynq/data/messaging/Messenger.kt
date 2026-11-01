package app.vynq.data.messaging

import app.vynq.data.auth.AccountError
import app.vynq.data.auth.BackendApi
import app.vynq.data.crypto.AttachmentCrypto
import app.vynq.data.crypto.Envelope
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

    // --- keys ------------------------------------------------------------------------------

    /** Creates and publishes this device's keys on first run; tops up one-time keys later. */
    suspend fun start() = locked {
        if (!crypto.hasIdentity) {
            publish(crypto.createKeys(ONE_TIME_BATCH))
            dao.put(SignalStore.NS_LOCAL, PUBLISHED, byteArrayOf(1))
            return@locked
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
        withContext(io) { dao.replaceContacts(list) }
        return list
    }

    suspend fun requestContact(username: String): ContactState {
        val row = api.rpc("request_contact", buildJsonObject { put("p_username", username.trim().lowercase()) }).firstRow()
        refreshContacts()
        return if (row?.str("state") == "accepted") ContactState.Accepted else ContactState.Outgoing
    }

    suspend fun acceptContact(user: String) {
        api.rpc("accept_contact", buildJsonObject { put("p_user_id", user) })
        refreshContacts()
    }

    suspend fun removeContact(user: String) {
        api.rpc("remove_contact", buildJsonObject { put("p_user_id", user) })
        refreshContacts()
    }

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
            }.isSuccess
        withContext(io) { dao.updateStatus(message.id, if (ok) MessageStatus.Sent else MessageStatus.Failed) }
    }

    private suspend fun encryptAndSend(message: Message) {
        val payload = WirePayload(id = message.id, from = userId, sentAt = message.sentAt, text = message.text, attachment = message.attachment)
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

    /** Fetches, decrypts and stores everything waiting for this device, then acknowledges it. */
    suspend fun sync(): Int = locked {
        var received = 0
        repeat(MAX_PAGES) {
            val rows = api.rpc("fetch_messages", buildJsonObject { put("p_device", deviceId); put("p_limit", PAGE) })
                .jsonArray.map { it.jsonObject }
            if (rows.isEmpty()) return@locked received
            for (r in rows) if (receive(r)) received++
            api.rpc("ack_messages", buildJsonObject {
                put("p_device", deviceId)
                put("p_ids", buildJsonArray { rows.forEach { add(it["id"]!!.jsonPrimitive.long) } })
            })
            if (rows.size < PAGE) return@locked received
        }
        received
    }

    /** True when a new message was stored. Anything unusable is dropped (and acknowledged). */
    private suspend fun receive(r: JsonObject): Boolean {
        val sender = r.str("from_user") ?: return false
        val fromDevice = r.str("from_device") ?: return false
        val envelope = Envelope(r["type"]!!.jsonPrimitive.int, unb64.decode(r.str("body")!!))
        val self = sender == userId

        if (!self && dao.contact(sender)?.state != ContactState.Accepted) {
            refreshContacts()
            if (dao.contact(sender)?.state != ContactState.Accepted) return false
        }
        val device = dao.device(fromDevice)?.takeIf { it.active }
            ?: syncDevices(sender).firstOrNull { it.deviceId == fromDevice }
        if (device == null) {
            if (!self) withContext(io) { systemNote(sender, "A message from an unrecognised device of ${label(sender)} was discarded.") }
            return false
        }
        val claimed = crypto.senderIdentity(envelope)
        if (claimed != null && !claimed.serialize().contentEquals(device.identityKey)) {
            withContext(io) { systemNote(sender, KEY_MISMATCH_NOTE) }
            return false
        }

        val plain = try {
            withContext(io) { crypto.decrypt(fromDevice, envelope) }
        } catch (e: DuplicateMessageException) {
            return false // already handled before an interrupted acknowledgement
        } catch (e: Exception) {
            if (!self) withContext(io) { systemNote(sender, "A message from ${label(sender)} couldn't be decrypted.") }
            return false
        }
        val payload = runCatching { json.decodeFromString(WirePayload.serializer(), plain.decodeToString()) }.getOrNull()
            ?: return false
        if (payload.from != sender) return false

        val kind = if (payload.attachment != null) MessageKind.Photo else MessageKind.Text
        val message = when {
            self && payload.syncPeer != null ->
                Message(payload.id, payload.syncPeer, true, kind, payload.text, payload.attachment, payload.sentAt, MessageStatus.Sent)
            !self ->
                Message(payload.id, sender, false, kind, payload.text, payload.attachment, payload.sentAt, MessageStatus.Received, read = false)
            else -> return false
        }
        return withContext(io) { dao.insertMessage(message) }
    }

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
        private const val PUBLISHED = "published"
        private const val ONE_TIME_BATCH = 100
        private const val ONE_TIME_LOW = 20
        private const val PAGE = 100
        private const val MAX_PAGES = 10
        const val KEY_MISMATCH_NOTE =
            "Security warning: the server offered a different key for a device we already know. Nothing was sent to or accepted from it."
    }
}
