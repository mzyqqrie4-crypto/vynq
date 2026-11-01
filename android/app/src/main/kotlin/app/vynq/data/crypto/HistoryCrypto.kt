package app.vynq.data.crypto

import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Message history that follows an account across phones.
 *
 * - The **history key** (32 random bytes) encrypts every history record with
 *   AES-256-GCM, bound to the record id. Only the account's phones hold it.
 * - The **recovery key** (25 random bytes = 200 bits, shown as 40 characters)
 *   wraps the history key so a new phone can unlock it when no old phone is
 *   left. It's random, not a password, so no slow key derivation is needed:
 *   guessing it is out of reach even for someone holding the server data.
 */
object HistoryCrypto {
    private val random = SecureRandom()
    private const val IV = 12
    private const val RECOVERY_BYTES = 25
    // Crockford base32: no I, L, O, U, so it's easy to read back and type.
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    class WrongKeyException : Exception("wrong key")

    fun newHistoryKey(): ByteArray = ByteArray(32).also(random::nextBytes)

    fun newRecoveryKey(): ByteArray = ByteArray(RECOVERY_BYTES).also(random::nextBytes)

    /** "ABCD EFGH …", 10 groups of 4. */
    fun formatRecoveryKey(key: ByteArray): String {
        require(key.size == RECOVERY_BYTES)
        val sb = StringBuilder()
        var buffer = 0; var bits = 0
        for (b in key) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF); bits += 8
            while (bits >= 5) { sb.append(ALPHABET[(buffer shr (bits - 5)) and 31]); bits -= 5 }
        }
        return sb.chunked(4).joinToString(" ")
    }

    /** Accepts any spacing, dashes and case; reads I/L as 1 and O as 0. Null if it isn't a recovery key. */
    fun parseRecoveryKey(input: String): ByteArray? {
        val chars = input.uppercase().filter { !it.isWhitespace() && it != '-' }
            .map { when (it) { 'I', 'L' -> '1'; 'O' -> '0'; else -> it } }
        if (chars.size != RECOVERY_BYTES * 8 / 5) return null
        val out = ByteArray(RECOVERY_BYTES)
        var buffer = 0; var bits = 0; var i = 0
        for (c in chars) {
            val v = ALPHABET.indexOf(c).takeIf { it >= 0 } ?: return null
            buffer = (buffer shl 5) or v; bits += 5
            if (bits >= 8) { out[i++] = (buffer shr (bits - 8)).toByte(); bits -= 8 }
        }
        return out
    }

    fun wrap(historyKey: ByteArray, recoveryKey: ByteArray): ByteArray =
        seal(kek(recoveryKey), WRAP_AAD, historyKey)

    /** Throws [WrongKeyException] if [recoveryKey] isn't the one that wrapped it. */
    fun unwrap(wrapped: ByteArray, recoveryKey: ByteArray): ByteArray =
        open(kek(recoveryKey), WRAP_AAD, wrapped)

    fun sealRecord(historyKey: ByteArray, recordId: String, plain: ByteArray): ByteArray =
        seal(historyKey, recordId.encodeToByteArray(), plain)

    fun openRecord(historyKey: ByteArray, recordId: String, blob: ByteArray): ByteArray =
        open(historyKey, recordId.encodeToByteArray(), blob)

    private val WRAP_AAD = "vynq history key v1".encodeToByteArray()

    private fun kek(recoveryKey: ByteArray) = hkdf(recoveryKey, "vynq history wrap v1".encodeToByteArray())

    private fun seal(key: ByteArray, aad: ByteArray, plain: ByteArray): ByteArray {
        val iv = ByteArray(IV).also(random::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        c.updateAAD(aad)
        return iv + c.doFinal(plain)
    }

    private fun open(key: ByteArray, aad: ByteArray, blob: ByteArray): ByteArray {
        if (blob.size < IV + 16) throw WrongKeyException()
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, blob, 0, IV))
        c.updateAAD(aad)
        return try { c.doFinal(blob, IV, blob.size - IV) } catch (e: AEADBadTagException) { throw WrongKeyException() }
    }

    /** HKDF-SHA256 (RFC 5869) with an empty salt, 32-byte output. */
    private fun hkdf(ikm: ByteArray, info: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(ByteArray(32), "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        mac.update(info); mac.update(1)
        return mac.doFinal()
    }
}
