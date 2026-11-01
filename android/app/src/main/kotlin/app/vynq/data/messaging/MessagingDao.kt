package app.vynq.data.messaging

import app.vynq.data.crypto.CryptoStorage
import app.vynq.data.crypto.MemoryCryptoStorage
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Local store for everything messaging: protocol state ([CryptoStorage]),
 * contacts, devices and messages. On a phone it's one SQLCipher database
 * ([SqlCipherMessagingDao]); tests use [MemoryMessagingDao].
 */
interface MessagingDao : CryptoStorage {
    /** Bumps after every write, so screens can re-read. */
    val changes: StateFlow<Long>

    fun contacts(): List<Contact>
    fun contact(userId: String): Contact?
    /** Replaces the contact list with the server's. */
    fun replaceContacts(contacts: List<Contact>)

    fun devices(userId: String): List<RemoteDevice>
    fun device(deviceId: String): RemoteDevice?
    fun upsertDevice(device: RemoteDevice)
    fun setDeviceActive(deviceId: String, active: Boolean)
    fun setVerified(userId: String, verified: Boolean)

    fun messages(peer: String): List<Message>
    fun message(id: String): Message?
    /** False when a message with this id already exists (duplicate delivery). */
    fun insertMessage(message: Message): Boolean
    fun updateStatus(id: String, status: MessageStatus)
    fun markRead(peer: String)
    fun conversations(): List<Conversation>
}

class MemoryMessagingDao : MessagingDao, CryptoStorage by MemoryCryptoStorage() {
    override val changes = MutableStateFlow(0L)
    private val contacts = ConcurrentHashMap<String, Contact>()
    private val devices = ConcurrentHashMap<String, RemoteDevice>()
    private val messages = ConcurrentHashMap<String, Message>()
    private fun bump() { changes.value++ }

    override fun contacts() = contacts.values.sortedBy { it.label }
    override fun contact(userId: String) = contacts[userId]
    override fun replaceContacts(contacts: List<Contact>) {
        this.contacts.clear(); contacts.forEach { this.contacts[it.userId] = it }; bump()
    }

    override fun devices(userId: String) = devices.values.filter { it.userId == userId }.sortedBy { it.firstSeen }
    override fun device(deviceId: String) = devices[deviceId]
    override fun upsertDevice(device: RemoteDevice) { devices[device.deviceId] = device; bump() }
    override fun setDeviceActive(deviceId: String, active: Boolean) {
        devices.computeIfPresent(deviceId) { _, d -> d.copy(active = active) }; bump()
    }
    override fun setVerified(userId: String, verified: Boolean) {
        devices.replaceAll { _, d -> if (d.userId == userId && d.active) d.copy(verified = verified) else d }; bump()
    }

    override fun messages(peer: String) = messages.values.filter { it.peer == peer }.sortedBy { it.sentAt }
    override fun message(id: String) = messages[id]
    override fun insertMessage(message: Message): Boolean {
        val added = messages.putIfAbsent(message.id, message) == null
        if (added) bump()
        return added
    }
    override fun updateStatus(id: String, status: MessageStatus) {
        messages.computeIfPresent(id) { _, m -> m.copy(status = status) }; bump()
    }
    override fun markRead(peer: String) {
        messages.replaceAll { _, m -> if (m.peer == peer) m.copy(read = true) else m }; bump()
    }
    override fun conversations() = contacts.values.mapNotNull { c ->
        val list = messages(c.userId)
        if (list.isEmpty()) null else Conversation(c, list.last(), list.count { !it.read })
    }.sortedByDescending { it.last?.sentAt }
}
