package app.vynq.fusion

import android.net.Network
import java.io.Writer
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Probes one network path: a UDP socket bound to [network] sends a STUN Binding
 * request every [intervalMs] on a shared clock (so probe n on Wi-Fi and probe n
 * on mobile data leave at the same moment) and records the round trip, or
 * "lost" after [timeoutMs], or "down" when the path can't send at all.
 *
 * Rows: `seq,path,send_ms,rtt_ms,status`.
 */
class PathProber(
    private val path: String,
    private val network: Network,
    private val host: String,
    private val port: Int,
    private val startNanos: Long,
    private val out: Writer,
    private val intervalMs: Long = 100,
    private val timeoutMs: Long = 1_000,
) {
    private class Pending(val seq: Int, val sentNanos: Long)

    private val pending = ConcurrentHashMap<String, Pending>()
    @Volatile private var running = false
    private var socket: DatagramSocket? = null
    private val threads = mutableListOf<Thread>()

    val ok = AtomicInteger()
    val lost = AtomicInteger()
    val down = AtomicInteger()
    @Volatile var lastRttMs: Double? = null

    fun start() {
        running = true
        threads += Thread({ sendLoop() }, "probe-send-$path")
        threads += Thread({ receiveLoop() }, "probe-recv-$path")
        threads.forEach { it.start() }
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
        threads.forEach { runCatching { it.join(2_000) } }
    }

    private fun open(): DatagramSocket? = runCatching {
        DatagramSocket().also { s ->
            network.bindSocket(s)
            s.soTimeout = 200
            socket = s
        }
    }.getOrNull()

    private fun sendLoop() {
        var target: InetSocketAddress? = null
        var seq = ((System.nanoTime() - startNanos) / 1_000_000 / intervalMs).toInt()
        while (running) {
            val dueNanos = startNanos + seq * intervalMs * 1_000_000
            val wait = (dueNanos - System.nanoTime()) / 1_000_000
            if (wait > 0) Thread.sleep(wait)
            val sentNanos = System.nanoTime()
            val sent = runCatching {
                val s = socket ?: open() ?: error("no socket")
                val t = target ?: InetSocketAddress(network.getByName(host), port).also { target = it }
                val packet = Stun.request()
                pending[Stun.transactionOf(packet).toHex()] = Pending(seq, sentNanos)
                s.send(DatagramPacket(packet, packet.size, t))
            }.isSuccess
            if (!sent) {
                down.incrementAndGet()
                write(seq, sentNanos, null, "down")
                runCatching { socket?.close() }; socket = null; target = null   // reopen when the path returns
            }
            expire()
            seq++
        }
        // Anything still waiting when we stop is left out (not counted as lost).
    }

    private fun receiveLoop() {
        val buf = ByteArray(1500)
        while (running) {
            val s = socket
            if (s == null) { Thread.sleep(50); continue }
            val packet = DatagramPacket(buf, buf.size)
            val got = runCatching { s.receive(packet) }.isSuccess
            if (!got) continue
            val id = Stun.responseTransaction(buf, packet.length)?.toHex() ?: continue
            val p = pending.remove(id) ?: continue
            val rtt = (System.nanoTime() - p.sentNanos) / 1e6
            lastRttMs = rtt
            ok.incrementAndGet()
            write(p.seq, p.sentNanos, rtt, "ok")
        }
    }

    private fun expire() {
        val now = System.nanoTime()
        pending.entries.removeIf { (_, p) ->
            val old = (now - p.sentNanos) / 1_000_000 > timeoutMs
            if (old) { lost.incrementAndGet(); write(p.seq, p.sentNanos, null, "lost") }
            old
        }
    }

    private fun write(seq: Int, sentNanos: Long, rtt: Double?, status: String) {
        val line = "$seq,$path,${(sentNanos - startNanos) / 1_000_000},${rtt?.let { "%.2f".format(java.util.Locale.US, it) } ?: ""},$status\n"
        synchronized(out) { out.write(line) }
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
