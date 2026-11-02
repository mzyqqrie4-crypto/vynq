package app.vynq.data.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class ProfileCryptoTest {
    private val picture = ByteArray(5000) { (it % 251).toByte() }

    @Test fun roundTrip() {
        val key = ProfileCrypto.newKey()
        assertArrayEquals(picture, ProfileCrypto.open(key, "alice", ProfileCrypto.seal(key, "alice", picture)))
    }

    @Test fun theServerCopyDoesNotContainThePicture() {
        val sealed = ProfileCrypto.seal(ProfileCrypto.newKey(), "alice", picture)
        assertFalse(sealed.toList().windowed(32).any { it == picture.take(32) })
    }

    @Test fun wrongKeyOwnerOrTamperingIsRefused() {
        val key = ProfileCrypto.newKey()
        val sealed = ProfileCrypto.seal(key, "alice", picture)
        assertThrows(Exception::class.java) { ProfileCrypto.open(ProfileCrypto.newKey(), "alice", sealed) }
        assertThrows(Exception::class.java) { ProfileCrypto.open(key, "mallory", sealed) }   // someone else's file
        val tampered = sealed.copyOf().also { it[40] = (it[40] + 1).toByte() }
        assertThrows(Exception::class.java) { ProfileCrypto.open(key, "alice", tampered) }
    }
}
