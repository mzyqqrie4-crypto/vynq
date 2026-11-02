package app.vynq.data.crypto

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Profile pictures: encrypted on the phone with the owner's 32-byte profile key
 * (AES-256-GCM, random nonce, bound to the owner's user id), so the server stores
 * a file it can't read. The profile key reaches contacts only inside Signal
 * messages; anyone without it sees nothing.
 */
object ProfileCrypto {
    private const val NONCE = 12
    private val random = SecureRandom()

    fun newKey(): ByteArray = ByteArray(32).also(random::nextBytes)

    fun seal(key: ByteArray, owner: String, plain: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE).also(random::nextBytes)
        val cipher = cipher(Cipher.ENCRYPT_MODE, key, nonce, owner)
        return nonce + cipher.doFinal(plain)
    }

    /** Throws if the key, the owner or the bytes don't match. */
    fun open(key: ByteArray, owner: String, sealed: ByteArray): ByteArray {
        require(sealed.size > NONCE + 16) { "too short" }
        val cipher = cipher(Cipher.DECRYPT_MODE, key, sealed.copyOfRange(0, NONCE), owner)
        return cipher.doFinal(sealed, NONCE, sealed.size - NONCE)
    }

    private fun cipher(mode: Int, key: ByteArray, nonce: ByteArray, owner: String): Cipher {
        require(key.size == 32) { "profile key must be 32 bytes" }
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            updateAAD("vynq.avatar.v1|$owner".encodeToByteArray())
        }
    }
}
