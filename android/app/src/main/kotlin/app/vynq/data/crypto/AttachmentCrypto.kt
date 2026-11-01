package app.vynq.data.crypto

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Attachments are encrypted on the device with a fresh random AES-256-GCM key
 * per file before upload. The key and the SHA-256 of the uploaded blob travel
 * only inside the Signal message, so storage holds bytes nobody else can read,
 * and a swapped or truncated blob is caught before decryption.
 */
object AttachmentCrypto {
    class Sealed(val blob: ByteArray, val key: ByteArray, val digest: ByteArray)
    class IntegrityException(message: String) : Exception(message)

    private const val IV_BYTES = 12
    private val random = SecureRandom()

    fun seal(plain: ByteArray): Sealed {
        val key = ByteArray(32).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val blob = iv + cipher.doFinal(plain)
        return Sealed(blob, key, sha256(blob))
    }

    fun open(blob: ByteArray, key: ByteArray, digest: ByteArray): ByteArray {
        if (!MessageDigest.isEqual(sha256(blob), digest)) throw IntegrityException("attachment digest mismatch")
        if (blob.size < IV_BYTES + 16) throw IntegrityException("attachment too short")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, blob, 0, IV_BYTES))
        return try {
            cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
        } catch (e: AEADBadTagException) {
            throw IntegrityException("attachment authentication failed")
        }
    }

    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
}
