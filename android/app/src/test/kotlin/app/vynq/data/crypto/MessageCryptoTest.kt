package app.vynq.data.crypto

import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.signal.libsignal.protocol.DuplicateMessageException
import org.signal.libsignal.protocol.InvalidMessageException
import org.signal.libsignal.protocol.UntrustedIdentityException
import org.signal.libsignal.protocol.message.CiphertextMessage

/** Runs the real libsignal (desktop natives) end to end between simulated devices. */
class MessageCryptoTest {
    private class Device {
        val id = UUID.randomUUID().toString()
        val storage = MemoryCryptoStorage()
        val crypto = MessageCrypto(storage)
        val keys = crypto.createKeys(oneTimeCount = 5)
        private var nextOneTime = 0

        /** What the server would hand out, popping one one-time key. */
        fun bundle(withOneTime: Boolean = true) = RemoteBundle(
            deviceId = id,
            registrationId = keys.registrationId!!,
            identityKey = keys.identityKey!!,
            signed = keys.signed!!,
            kyber = keys.kyber!!,
            oneTime = if (withOneTime) keys.oneTime[nextOneTime++] else null,
        )
    }

    private fun Device.send(to: Device, text: String) = crypto.encrypt(to.id, text.encodeToByteArray())
    private fun Device.read(from: Device, e: Envelope) = crypto.decrypt(from.id, e).decodeToString()

    @Test fun `first message is a pre-key message and decrypts on the other side`() {
        val alice = Device(); val bob = Device()
        alice.crypto.startSession(bob.bundle())
        val e = alice.send(bob, "hello bob")
        assertEquals(CiphertextMessage.PREKEY_TYPE, e.type)
        assertEquals("hello bob", bob.read(alice, e))
    }

    @Test fun `ciphertext never contains the plaintext`() {
        val alice = Device(); val bob = Device()
        alice.crypto.startSession(bob.bundle())
        val secret = "the vault code is 4711"
        val e = alice.send(bob, secret)
        assertFalse(String(e.body, Charsets.ISO_8859_1).contains("vault"))
        assertFalse(e.body.toList().windowed(secret.length).any { String(it.toByteArray()) == secret })
    }

    @Test fun `messages queued while the recipient is offline all decrypt later, in order`() {
        val alice = Device(); val bob = Device()
        alice.crypto.startSession(bob.bundle())
        val queued = (1..5).map { alice.send(bob, "msg $it") }   // Bob hasn't fetched anything yet
        assertEquals((1..5).map { "msg $it" }, queued.map { bob.read(alice, it) })
    }

    @Test fun `out of order delivery still decrypts`() {
        val alice = Device(); val bob = Device()
        alice.crypto.startSession(bob.bundle())
        val first = alice.send(bob, "one")
        bob.read(alice, first)
        val a = alice.send(bob, "two"); val b = alice.send(bob, "three")
        assertEquals("three", bob.read(alice, b))
        assertEquals("two", bob.read(alice, a))
    }

    @Test fun `conversation ratchets in both directions`() {
        val alice = Device(); val bob = Device()
        alice.crypto.startSession(bob.bundle())
        assertEquals("hi", bob.read(alice, alice.send(bob, "hi")))
        val reply = bob.send(alice, "hey")
        assertEquals(CiphertextMessage.WHISPER_TYPE, reply.type)  // Bob already has the session
        assertEquals("hey", alice.read(bob, reply))
        assertEquals(CiphertextMessage.WHISPER_TYPE, alice.send(bob, "x").type)
    }

    @Test fun `a replayed message is rejected`() {
        val alice = Device(); val bob = Device()
        alice.crypto.startSession(bob.bundle())
        val e = alice.send(bob, "once")
        bob.read(alice, e)
        val second = alice.send(bob, "twice")
        bob.read(alice, second)
        assertThrows(DuplicateMessageException::class.java) { bob.read(alice, second) }
    }

    @Test fun `a tampered ciphertext is rejected`() {
        val alice = Device(); val bob = Device()
        alice.crypto.startSession(bob.bundle())
        bob.read(alice, alice.send(bob, "setup"))
        alice.read(bob, bob.send(alice, "ack"))     // session confirmed: Alice now sends plain Signal messages
        val e = alice.send(bob, "payload")
        assertEquals(CiphertextMessage.WHISPER_TYPE, e.type)
        // Flip one bit in the encrypted body (authenticated by the message MAC).
        val body = e.body.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() }
        assertThrows(InvalidMessageException::class.java) { bob.crypto.decrypt(alice.id, Envelope(e.type, body)) }
        assertEquals("payload", bob.read(alice, e))  // the genuine message still decrypts afterwards
    }

    @Test fun `a session can start without a one-time pre-key`() {
        val alice = Device(); val bob = Device()
        alice.crypto.startSession(bob.bundle(withOneTime = false))
        assertEquals("still works", bob.read(alice, alice.send(bob, "still works")))
    }

    @Test fun `a bundle with a forged signed pre-key is refused`() {
        val alice = Device(); val bob = Device(); val mallory = Device()
        val forged = bob.bundle().copy(signed = mallory.keys.signed!!)
        assertThrows(Exception::class.java) { alice.crypto.startSession(forged) }
        assertFalse(alice.crypto.hasSession(bob.id))
    }

    @Test fun `a different identity key for a known device is refused`() {
        val alice = Device(); val bob = Device(); val mallory = Device()
        alice.crypto.startSession(bob.bundle())
        bob.read(alice, alice.send(bob, "pinned"))
        // The server tries to swap Bob's device keys for Mallory's.
        val swapped = mallory.bundle().copy(deviceId = bob.id)
        assertThrows(UntrustedIdentityException::class.java) { alice.crypto.startSession(swapped) }
    }

    @Test fun `the pre-key message reveals the sender identity for checking`() {
        val alice = Device(); val bob = Device()
        alice.crypto.startSession(bob.bundle())
        val e = alice.send(bob, "who am i")
        assertEquals(alice.crypto.identityKey, bob.crypto.senderIdentity(e))
    }

    @Test fun `both sides compute the same safety number and it changes with the key`() {
        val alice = Device(); val bob = Device(); val other = Device()
        val a = alice.crypto.safetyNumber(alice.id, bob.id, bob.crypto.identityKey)
        val b = bob.crypto.safetyNumber(bob.id, alice.id, alice.crypto.identityKey)
        assertEquals(60, a.length)
        assertTrue(a.all(Char::isDigit))
        assertEquals(a, b)
        assertNotEquals(a, alice.crypto.safetyNumber(alice.id, bob.id, other.crypto.identityKey))
    }

    @Test fun `protocol state survives a reload from storage`() {
        val alice = Device(); val bob = Device()
        alice.crypto.startSession(bob.bundle())
        bob.read(alice, alice.send(bob, "before"))
        val bobReloaded = MessageCrypto(bob.storage)
        assertEquals("after", bobReloaded.decrypt(alice.id, alice.send(bob, "after")).decodeToString())
    }

    @Test fun `padding hides exact length and round trips`() {
        for (n in listOf(0, 1, 159, 160, 1000)) {
            val data = ByteArray(n) { (it % 251 + 1).toByte() }
            val padded = MessageCrypto.pad(data)
            assertEquals(0, padded.size % 160)
            assertArrayEquals(data, MessageCrypto.unpad(padded))
        }
        assertEquals(MessageCrypto.pad(ByteArray(3)).size, MessageCrypto.pad(ByteArray(150)).size)
    }

    @Test fun `attachments round trip and any change is caught`() {
        val photo = ByteArray(300_000) { (it * 31).toByte() }
        val sealed = AttachmentCrypto.seal(photo)
        assertArrayEquals(photo, AttachmentCrypto.open(sealed.blob, sealed.key, sealed.digest))

        val flipped = sealed.blob.copyOf().also { it[5000] = (it[5000].toInt() xor 1).toByte() }
        assertThrows(AttachmentCrypto.IntegrityException::class.java) { AttachmentCrypto.open(flipped, sealed.key, sealed.digest) }
        // Even if someone fixes up the digest, GCM authentication still fails.
        assertThrows(AttachmentCrypto.IntegrityException::class.java) {
            AttachmentCrypto.open(flipped, sealed.key, AttachmentCrypto.sha256(flipped))
        }
        val wrongKey = ByteArray(32)
        assertThrows(AttachmentCrypto.IntegrityException::class.java) { AttachmentCrypto.open(sealed.blob, wrongKey, sealed.digest) }
    }
}
