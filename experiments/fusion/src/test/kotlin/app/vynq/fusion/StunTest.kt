package app.vynq.fusion

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StunTest {
    private val tx = ByteArray(12) { it.toByte() }

    @Test fun requestIsABindingRequestWithTheMagicCookie() {
        val r = Stun.request(tx)
        assertEquals(20, r.size)
        assertArrayEquals(byteArrayOf(0x00, 0x01, 0x00, 0x00, 0x21, 0x12, 0xA4.toByte(), 0x42), r.copyOfRange(0, 8))
        assertArrayEquals(tx, Stun.transactionOf(r))
    }

    @Test fun successResponseYieldsItsTransaction() {
        val response = Stun.request(tx).also { it[0] = 0x01; it[1] = 0x01 } + ByteArray(12)   // + an attribute
        assertArrayEquals(tx, Stun.responseTransaction(response))
    }

    @Test fun otherPacketsAreIgnored() {
        assertNull(Stun.responseTransaction(Stun.request(tx)))            // a request, not a response
        assertNull(Stun.responseTransaction(ByteArray(10)))               // too short
        val wrongCookie = Stun.request(tx).also { it[0] = 0x01; it[1] = 0x01; it[4] = 0 }
        assertNull(Stun.responseTransaction(wrongCookie))
    }
}
