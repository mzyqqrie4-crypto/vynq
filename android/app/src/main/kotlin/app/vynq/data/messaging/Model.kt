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
    /** Uploaded to the account's encrypted history. */
    val archived: Boolean = false,
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
    /** Device-to-device messages within one account (history hand-over); never shown. */
    val control: String? = null,
    val historyKey: String? = null,
    val recoveryKey: String? = null,
    val deviceName: String? = null,
    /** Call signalling (control = call_*): the call, video or voice, and the media key. */
    val callId: String? = null,
    val video: Boolean? = null,
    val callKey: String? = null,
    val reason: String? = null,
)

/** A call signal from a contact (or from one of our own phones, e.g. "answered elsewhere"). */
data class CallSignal(
    val type: String,
    val callId: String,
    val from: String,
    val fromDevice: String,
    val self: Boolean,
    val video: Boolean = true,
    val callKey: String? = null,
    val reason: String? = null,
    val sentAt: Long,
) {
    companion object {
        const val INVITE = "call_invite"
        const val ANSWER = "call_answer"
        const val DECLINE = "call_decline"
        const val END = "call_end"
    }
}

/** One message in the account's encrypted history (see HistoryCrypto). */
@Serializable
data class HistoryRecord(
    val v: Int = 1,
    val id: String,
    val peer: String,
    val outgoing: Boolean,
    val kind: MessageKind,
    val text: String? = null,
    val attachment: AttachmentPointer? = null,
    val sentAt: Long,
)

enum class HistoryState {
    /** Not checked yet (offline, or still starting). */
    Unknown,
    /** This phone has the history key: messages are backed up and restored. */
    Ready,
    /** The account has history this phone can't read yet: approve on another phone or enter the recovery key. */
    Locked,
}

/** Another of the user's phones asking for the history key. */
data class HistoryRequest(val deviceId: String, val deviceName: String, val at: Long)
