package app.vynq.data.crypto

import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.ReusedBaseKeyException
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.KyberPreKeyStore
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyStore
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SessionStore
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyStore

/**
 * Byte storage for protocol state, by namespace and key. On a device this is a
 * table in the SQLCipher database; tests use [MemoryCryptoStorage].
 */
interface CryptoStorage {
    fun get(ns: String, key: String): ByteArray?
    fun put(ns: String, key: String, value: ByteArray)
    fun delete(ns: String, key: String)
    fun keys(ns: String): List<String>
}

class MemoryCryptoStorage : CryptoStorage {
    private val map = ConcurrentHashMap<String, ByteArray>()
    override fun get(ns: String, key: String) = map["$ns/$key"]
    override fun put(ns: String, key: String, value: ByteArray) { map["$ns/$key"] = value }
    override fun delete(ns: String, key: String) { map.remove("$ns/$key") }
    override fun keys(ns: String) = map.keys.filter { it.startsWith("$ns/") }.map { it.removePrefix("$ns/") }
}

/**
 * libsignal's store interfaces over [CryptoStorage]. Remote parties are
 * addressed by device id (every vynq device has its own identity key), always
 * with Signal device number 1.
 *
 * Trust rule: the first identity key seen for a device is pinned forever. A
 * different key for the same device is refused. The server can't legitimately
 * change it (identity keys are immutable there), so a mismatch means tampering.
 */
class SignalStore(private val storage: CryptoStorage) :
    IdentityKeyStore, PreKeyStore, SignedPreKeyStore, KyberPreKeyStore, SessionStore {

    // --- local identity ---------------------------------------------------------

    fun hasLocalIdentity() = storage.get(NS_LOCAL, "identity") != null

    fun setLocalIdentity(pair: IdentityKeyPair, registrationId: Int) {
        check(!hasLocalIdentity()) { "identity already exists" }
        storage.put(NS_LOCAL, "registration", registrationId.toString().encodeToByteArray())
        storage.put(NS_LOCAL, "identity", pair.serialize())
    }

    override fun getIdentityKeyPair() = IdentityKeyPair(storage.get(NS_LOCAL, "identity") ?: error("no identity"))

    override fun getLocalRegistrationId() =
        storage.get(NS_LOCAL, "registration")?.decodeToString()?.toInt() ?: error("no identity")

    fun nextCounter(name: String): Int {
        val next = (storage.get(NS_LOCAL, name)?.decodeToString()?.toInt() ?: 0) % MAX_KEY_ID + 1
        storage.put(NS_LOCAL, name, next.toString().encodeToByteArray())
        return next
    }

    // --- remote identities --------------------------------------------------------

    override fun saveIdentity(address: SignalProtocolAddress, identityKey: IdentityKey): IdentityKeyStore.IdentityChange {
        val known = getIdentity(address)
        if (known == null) storage.put(NS_IDENTITY, address.name, identityKey.serialize())
        return if (known == null || known == identityKey) IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED
        else IdentityKeyStore.IdentityChange.REPLACED_EXISTING
    }

    override fun isTrustedIdentity(address: SignalProtocolAddress, identityKey: IdentityKey, direction: IdentityKeyStore.Direction): Boolean {
        val known = getIdentity(address)
        return known == null || known == identityKey
    }

    override fun getIdentity(address: SignalProtocolAddress): IdentityKey? =
        storage.get(NS_IDENTITY, address.name)?.let(::IdentityKey)

    // --- one-time pre-keys --------------------------------------------------------

    override fun loadPreKey(preKeyId: Int) =
        PreKeyRecord(storage.get(NS_PREKEY, "$preKeyId") ?: throw InvalidKeyIdException("no prekey $preKeyId"))
    override fun storePreKey(preKeyId: Int, record: PreKeyRecord) = storage.put(NS_PREKEY, "$preKeyId", record.serialize())
    override fun containsPreKey(preKeyId: Int) = storage.get(NS_PREKEY, "$preKeyId") != null
    override fun removePreKey(preKeyId: Int) = storage.delete(NS_PREKEY, "$preKeyId")

    // --- signed pre-keys ------------------------------------------------------------

    override fun loadSignedPreKey(signedPreKeyId: Int) =
        SignedPreKeyRecord(storage.get(NS_SIGNED, "$signedPreKeyId") ?: throw InvalidKeyIdException("no signed prekey"))
    override fun loadSignedPreKeys() = storage.keys(NS_SIGNED).mapNotNull { k -> storage.get(NS_SIGNED, k)?.let(::SignedPreKeyRecord) }
    override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord) =
        storage.put(NS_SIGNED, "$signedPreKeyId", record.serialize())
    override fun containsSignedPreKey(signedPreKeyId: Int) = storage.get(NS_SIGNED, "$signedPreKeyId") != null
    override fun removeSignedPreKey(signedPreKeyId: Int) = storage.delete(NS_SIGNED, "$signedPreKeyId")

    // --- Kyber (post-quantum) pre-keys -------------------------------------------------

    override fun loadKyberPreKey(kyberPreKeyId: Int) =
        KyberPreKeyRecord(storage.get(NS_KYBER, "$kyberPreKeyId") ?: throw InvalidKeyIdException("no kyber prekey"))
    override fun loadKyberPreKeys() = storage.keys(NS_KYBER).mapNotNull { k -> storage.get(NS_KYBER, k)?.let(::KyberPreKeyRecord) }
    override fun storeKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord) =
        storage.put(NS_KYBER, "$kyberPreKeyId", record.serialize())
    override fun containsKyberPreKey(kyberPreKeyId: Int) = storage.get(NS_KYBER, "$kyberPreKeyId") != null

    // Our Kyber key is a reusable last-resort key, so a replayed session start
    // (same base key) must be refused rather than the key deleted.
    override fun markKyberPreKeyUsed(kyberPreKeyId: Int, signedPreKeyId: Int, baseKey: ECPublicKey) {
        val marker = "$kyberPreKeyId:$signedPreKeyId:" + Base64.getEncoder().encodeToString(baseKey.serialize())
        if (storage.get(NS_KYBER_USED, marker) != null) throw ReusedBaseKeyException("base key reused")
        storage.put(NS_KYBER_USED, marker, byteArrayOf(1))
    }

    // --- sessions ------------------------------------------------------------------------

    override fun loadSession(address: SignalProtocolAddress) =
        storage.get(NS_SESSION, address.name)?.let(::SessionRecord) ?: SessionRecord()
    override fun loadExistingSessions(addresses: List<SignalProtocolAddress>) = addresses.map { a ->
        storage.get(NS_SESSION, a.name)?.let(::SessionRecord)
            ?: throw org.signal.libsignal.protocol.NoSessionException("no session for ${a.name}")
    }
    override fun getSubDeviceSessions(name: String) = emptyList<Int>()
    override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) =
        storage.put(NS_SESSION, address.name, record.serialize())
    override fun containsSession(address: SignalProtocolAddress) =
        storage.get(NS_SESSION, address.name)?.let { SessionRecord(it).hasSenderChain() } ?: false
    override fun deleteSession(address: SignalProtocolAddress) = storage.delete(NS_SESSION, address.name)
    override fun deleteAllSessions(name: String) = storage.delete(NS_SESSION, name)

    companion object {
        const val NS_LOCAL = "local"
        const val NS_IDENTITY = "identity"
        const val NS_PREKEY = "prekey"
        const val NS_SIGNED = "signed"
        const val NS_KYBER = "kyber"
        const val NS_KYBER_USED = "kyber_used"
        const val NS_SESSION = "session"
        const val MAX_KEY_ID = 0xFFFFFE
    }
}
