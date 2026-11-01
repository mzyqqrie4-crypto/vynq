package app.vynq.data.crypto

import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.UUID
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.fingerprint.NumericFingerprintGenerator
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.kem.KEMPublicKey
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord

data class SignedKey(val id: Int, val publicKey: ByteArray, val signature: ByteArray)
data class OneTimeKey(val id: Int, val publicKey: ByteArray)

/** Everything a device publishes so others can start sessions with it. */
data class KeyUpload(
    val identityKey: ByteArray?,
    val registrationId: Int?,
    val signed: SignedKey?,
    val kyber: SignedKey?,
    val oneTime: List<OneTimeKey>,
)

/** A device's published keys, as handed out by the server. */
data class RemoteBundle(
    val deviceId: String,
    val registrationId: Int,
    val identityKey: ByteArray,
    val signed: SignedKey,
    val kyber: SignedKey,
    val oneTime: OneTimeKey?,
)

/** One Signal ciphertext for one device. [type] is 2 (message) or 3 (pre-key message). */
class Envelope(val type: Int, val body: ByteArray)

/**
 * The Signal protocol for this device, via libsignal: PQXDH session setup
 * (X25519 + ML-KEM-1024) and the Double Ratchet. Plaintext is padded before
 * encryption so ciphertext length reveals only a coarse size.
 *
 * Not thread-safe: the caller serialises access (protocol state is read and
 * written on every operation).
 */
class MessageCrypto(storage: CryptoStorage, private val now: () -> Long = System::currentTimeMillis) {
    val store = SignalStore(storage)
    private val random = SecureRandom()

    val hasIdentity get() = store.hasLocalIdentity()
    val identityKey: IdentityKey get() = store.identityKeyPair.publicKey

    /** First run: identity, signed pre-key, Kyber pre-key and [oneTimeCount] one-time pre-keys. */
    fun createKeys(oneTimeCount: Int = 100): KeyUpload {
        val identity = IdentityKeyPair.generate()
        val registrationId = random.nextInt(16380) + 1
        store.setLocalIdentity(identity, registrationId)
        return KeyUpload(
            identityKey = identity.publicKey.serialize(),
            registrationId = registrationId,
            signed = newSignedPreKey(),
            kyber = newKyberPreKey(),
            oneTime = newOneTimeKeys(oneTimeCount),
        )
    }

    fun newSignedPreKey(): SignedKey {
        val id = store.nextCounter("signed_id")
        val pair = ECKeyPair.generate()
        val signature = store.identityKeyPair.privateKey.calculateSignature(pair.publicKey.serialize())
        store.storeSignedPreKey(id, SignedPreKeyRecord(id, now(), pair, signature))
        return SignedKey(id, pair.publicKey.serialize(), signature)
    }

    fun newKyberPreKey(): SignedKey {
        val id = store.nextCounter("kyber_id")
        val pair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        val signature = store.identityKeyPair.privateKey.calculateSignature(pair.publicKey.serialize())
        store.storeKyberPreKey(id, KyberPreKeyRecord(id, now(), pair, signature))
        return SignedKey(id, pair.publicKey.serialize(), signature)
    }

    fun newOneTimeKeys(count: Int): List<OneTimeKey> = List(count) {
        val id = store.nextCounter("prekey_id")
        val pair = ECKeyPair.generate()
        store.storePreKey(id, PreKeyRecord(id, pair))
        OneTimeKey(id, pair.publicKey.serialize())
    }

    fun hasSession(deviceId: String) = store.containsSession(address(deviceId))

    /** The identity key pinned for a remote device, if we've ever talked to it. */
    fun knownIdentity(deviceId: String): IdentityKey? = store.getIdentity(address(deviceId))

    /**
     * Starts a session from a bundle. libsignal verifies both pre-key signatures
     * against the bundle's identity key; [org.signal.libsignal.protocol.UntrustedIdentityException]
     * means the device's pinned identity key doesn't match.
     */
    fun startSession(bundle: RemoteBundle) {
        val b = PreKeyBundle(
            bundle.registrationId, DEVICE_NUMBER,
            bundle.oneTime?.id ?: PreKeyBundle.NULL_PRE_KEY_ID,
            bundle.oneTime?.let { ECPublicKey(it.publicKey) },
            bundle.signed.id, ECPublicKey(bundle.signed.publicKey), bundle.signed.signature,
            IdentityKey(bundle.identityKey),
            bundle.kyber.id, KEMPublicKey(bundle.kyber.publicKey), bundle.kyber.signature,
        )
        // Note: SessionBuilder takes (remote, local); SessionCipher takes (local, remote).
        SessionBuilder(store, store, store, store, address(bundle.deviceId), local).process(b)
    }

    fun encrypt(deviceId: String, plaintext: ByteArray): Envelope {
        val message = cipher(deviceId).encrypt(pad(plaintext))
        return Envelope(message.type, message.serialize())
    }

    fun decrypt(fromDeviceId: String, envelope: Envelope): ByteArray {
        val cipher = cipher(fromDeviceId)
        val padded = when (envelope.type) {
            CiphertextMessage.PREKEY_TYPE -> cipher.decrypt(PreKeySignalMessage(envelope.body))
            CiphertextMessage.WHISPER_TYPE -> cipher.decrypt(SignalMessage(envelope.body))
            else -> throw IllegalArgumentException("unknown envelope type ${envelope.type}")
        }
        return unpad(padded)
    }

    /** The identity key inside a pre-key message (who claims to be starting a session). */
    fun senderIdentity(envelope: Envelope): IdentityKey? =
        if (envelope.type == CiphertextMessage.PREKEY_TYPE) PreKeySignalMessage(envelope.body).identityKey else null

    /**
     * The 60-digit safety number for this device and a remote device. Both
     * sides compute the same digits; comparing them in person (or by QR) proves
     * no one is in the middle.
     */
    fun safetyNumber(localDeviceId: String, remoteDeviceId: String, remoteIdentity: IdentityKey): String =
        NumericFingerprintGenerator(FINGERPRINT_ITERATIONS)
            .createFor(FINGERPRINT_VERSION, uuidBytes(localDeviceId), identityKey, uuidBytes(remoteDeviceId), remoteIdentity)
            .displayableFingerprint.displayText

    private val local get() = SignalProtocolAddress(LOCAL_NAME, DEVICE_NUMBER)
    private fun address(deviceId: String) = SignalProtocolAddress(deviceId, DEVICE_NUMBER)
    private fun cipher(deviceId: String) = SessionCipher(store, store, store, store, store, local, address(deviceId))

    companion object {
        const val DEVICE_NUMBER = 1
        private const val LOCAL_NAME = "self"
        private const val FINGERPRINT_ITERATIONS = 5200
        private const val FINGERPRINT_VERSION = 2
        private const val PAD_BLOCK = 160

        private fun uuidBytes(id: String): ByteArray {
            val u = UUID.fromString(id)
            return ByteBuffer.allocate(16).putLong(u.mostSignificantBits).putLong(u.leastSignificantBits).array()
        }

        /** ISO 7816-4 style: 0x80, then zeros up to the next multiple of 160 bytes. */
        fun pad(data: ByteArray): ByteArray {
            val size = (data.size / PAD_BLOCK + 1) * PAD_BLOCK
            return data.copyOf(size).also { it[data.size] = 0x80.toByte() }
        }

        fun unpad(data: ByteArray): ByteArray {
            val end = data.indexOfLast { it != 0.toByte() }
            require(end >= 0 && data[end] == 0x80.toByte()) { "bad padding" }
            return data.copyOf(end)
        }
    }
}
