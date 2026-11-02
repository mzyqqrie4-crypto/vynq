package app.vynq.fusion

import java.nio.ByteBuffer
import kotlin.random.Random

/**
 * Minimal STUN (RFC 8489) Binding request/response, used only as a round-trip
 * probe: a public STUN server answers every request, so no server of ours is
 * needed. We never read the mapped address.
 */
object Stun {
    private const val BINDING_REQUEST: Short = 0x0001
    private const val BINDING_SUCCESS: Short = 0x0101
    private const val MAGIC = 0x2112A442

    /** A 20-byte Binding request; the 12-byte transaction id identifies the probe. */
    fun request(transaction: ByteArray = Random.nextBytes(12)): ByteArray {
        require(transaction.size == 12)
        return ByteBuffer.allocate(20).putShort(BINDING_REQUEST).putShort(0).putInt(MAGIC).put(transaction).array()
    }

    fun transactionOf(packet: ByteArray): ByteArray = packet.copyOfRange(8, 20)

    /** The transaction id of a Binding success response, or null for anything else. */
    fun responseTransaction(packet: ByteArray, length: Int = packet.size): ByteArray? {
        if (length < 20) return null
        val b = ByteBuffer.wrap(packet, 0, length)
        if (b.short != BINDING_SUCCESS) return null
        b.short // message length
        if (b.int != MAGIC) return null
        return packet.copyOfRange(8, 20)
    }
}
