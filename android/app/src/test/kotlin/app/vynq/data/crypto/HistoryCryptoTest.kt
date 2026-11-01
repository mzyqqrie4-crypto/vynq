package app.vynq.data.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryCryptoTest {
    @Test fun `recovery key formats as 10 groups and parses back, forgiving typing`() {
        repeat(50) {
            val key = HistoryCrypto.newRecoveryKey()
            val text = HistoryCrypto.formatRecoveryKey(key)
            assertEquals(10, text.split(" ").size)
            assertTrue(text.split(" ").all { it.length == 4 })
            assertArrayEquals(key, HistoryCrypto.parseRecoveryKey(text))
            assertArrayEquals(key, HistoryCrypto.parseRecoveryKey(text.lowercase().replace(" ", "-")))
            assertArrayEquals(key, HistoryCrypto.parseRecoveryKey(text.replace('1', 'l').replace('0', 'O')))
        }
        assertNull(HistoryCrypto.parseRecoveryKey("too short"))
        assertNull(HistoryCrypto.parseRecoveryKey("U".repeat(40)))   // U isn't in the alphabet
    }

    @Test fun `history key unwraps only with the right recovery key`() {
        val hk = HistoryCrypto.newHistoryKey()
        val rk = HistoryCrypto.newRecoveryKey()
        val wrapped = HistoryCrypto.wrap(hk, rk)
        assertArrayEquals(hk, HistoryCrypto.unwrap(wrapped, rk))
        assertThrows(HistoryCrypto.WrongKeyException::class.java) { HistoryCrypto.unwrap(wrapped, HistoryCrypto.newRecoveryKey()) }
        val flipped = wrapped.copyOf().also { it[20] = (it[20].toInt() xor 1).toByte() }
        assertThrows(HistoryCrypto.WrongKeyException::class.java) { HistoryCrypto.unwrap(flipped, rk) }
    }

    @Test fun `records are bound to their id and key`() {
        val hk = HistoryCrypto.newHistoryKey()
        val blob = HistoryCrypto.sealRecord(hk, "m1", "hello".encodeToByteArray())
        assertEquals("hello", HistoryCrypto.openRecord(hk, "m1", blob).decodeToString())
        // The server can't swap records between ids, or decrypt without the key.
        assertThrows(HistoryCrypto.WrongKeyException::class.java) { HistoryCrypto.openRecord(hk, "m2", blob) }
        assertThrows(HistoryCrypto.WrongKeyException::class.java) { HistoryCrypto.openRecord(HistoryCrypto.newHistoryKey(), "m1", blob) }
        assertTrue(!String(blob, Charsets.ISO_8859_1).contains("hello"))
    }
}
