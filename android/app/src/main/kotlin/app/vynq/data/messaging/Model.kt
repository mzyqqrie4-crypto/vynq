package app.vynq.data.messaging

import kotlinx.serialization.Serializable

enum class ContactState { Outgoing, Incoming, Accepted }

data class Contact(
    val userId: String,
    val username: String?,
    val displayName: String?,
    val state: ContactState,
) {
    val label: String get() = displayName ?: username?.let { "@$it" } ?: "Unknown"
}

enum class MessageKind { Text, Photo, System }
enum class MessageStatus { Sending, Sent, Failed, Received }

data class Message(
    val id: String,
    val peer: String,
    val outgoing: Boolean,
    val kind: MessageKind,
    val text: String?,
    val attachment: AttachmentPointer?,
    val sentAt: Long,
    val status: MessageStatus,
    val read: Boolean = outgoing,
)

/** Where an encrypted attachment lives and how to open it. Travels only inside Signal messages. */
@Serializable
data class AttachmentPointer(
    val path: String,
    val key: String,      // base64, AES-256
    val digest: String,   // base64, SHA-256 of the uploaded blob
    val size: Long,
    val mime: String,
    val width: Int? = null,
    val height: Int? = null,
)

/** A device of a contact (or one of our own other devices), with its pinned identity key. */
data class RemoteDevice(
    val deviceId: String,
    val userId: String,
    val identityKey: ByteArray,
    val firstSeen: Long,
    val verified: Boolean,
    val active: Boolean,
)

data class Conversation(val contact: Contact, val last: Message?, val unread: Int)

/** What actually goes inside the Signal ciphertext. */
@Serializable
data class WirePayload(
    val v: Int = 1,
    val id: String,
    val from: String,
    val sentAt: Long,
    val text: String? = null,
    val attachment: AttachmentPointer? = null,
    /** Set on copies sent to our own other devices: who the message was sent to. */
    val syncPeer: String? = null,
)
