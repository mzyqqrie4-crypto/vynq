package app.vynq.call

import io.livekit.android.room.Room
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import livekit.org.webrtc.RTCStatsReport

/** What the call details panel shows: live numbers from WebRTC, nothing estimated. */
data class CallStats(
    val recvResolution: String? = null,
    val recvFps: Double? = null,
    val recvKbps: Long? = null,
    val sendResolution: String? = null,
    val sendFps: Double? = null,
    val sendKbps: Long? = null,
    val rttMs: Long? = null,
    val lossPercent: Double? = null,
    val codec: String? = null,
    val path: String? = null,
) {
    fun lines(): List<String> = listOfNotNull(
        "Receiving: ${recvResolution ?: "—"} · ${recvFps?.let { "%.0f fps".format(it) } ?: "—"} · ${recvKbps?.let { "$it kbps" } ?: "—"}",
        "Sending: ${sendResolution ?: "—"} · ${sendFps?.let { "%.0f fps".format(it) } ?: "—"} · ${sendKbps?.let { "$it kbps" } ?: "—"}",
        "Round trip: ${rttMs?.let { "$it ms" } ?: "—"}",
        "Packet loss: ${lossPercent?.let { "%.1f%%".format(it) } ?: "—"}",
        codec?.let { "Codec: $it" },
        path?.let { "Route: $it" },
    )
}

/** Turns cumulative WebRTC counters into per-second rates between samples. */
class StatsSampler(private val room: Room) {
    private var lastRecvBytes = 0L
    private var lastSendBytes = 0L
    private var lastAt = 0L

    suspend fun sample(): CallStats {
        val sub = report { room.getSubscriberRTCStats(it) }
        val pub = report { room.getPublisherRTCStats(it) }
        val now = System.currentTimeMillis()
        val secs = if (lastAt == 0L) 0.0 else (now - lastAt) / 1000.0
        lastAt = now

        val inbound = sub?.statsMap?.values?.filter { it.type == "inbound-rtp" && it.members["kind"] == "video" }.orEmpty()
        val inVideo = inbound.maxByOrNull { (it.members["bytesReceived"] as? Number)?.toLong() ?: 0 }
        val inBytes = sub?.statsMap?.values?.filter { it.type == "inbound-rtp" }?.sumOf { (it.members["bytesReceived"] as? Number)?.toLong() ?: 0 } ?: 0
        val outVideo = pub?.statsMap?.values?.filter { it.type == "outbound-rtp" && it.members["kind"] == "video" }
            ?.maxByOrNull { ((it.members["frameWidth"] as? Number)?.toLong() ?: 0) }
        val outBytes = pub?.statsMap?.values?.filter { it.type == "outbound-rtp" }?.sumOf { (it.members["bytesSent"] as? Number)?.toLong() ?: 0 } ?: 0

        val recvKbps = if (secs > 0) ((inBytes - lastRecvBytes) * 8 / 1000 / secs).toLong().coerceAtLeast(0) else null
        val sendKbps = if (secs > 0) ((outBytes - lastSendBytes) * 8 / 1000 / secs).toLong().coerceAtLeast(0) else null
        lastRecvBytes = inBytes; lastSendBytes = outBytes

        val pair = (pub ?: sub)?.statsMap?.values?.firstOrNull {
            it.type == "candidate-pair" && it.members["state"] == "succeeded" && it.members["nominated"] == true
        }
        val remote = pair?.members?.get("remoteCandidateId")?.let { id -> (pub ?: sub)?.statsMap?.get(id) }
        val lost = inbound.sumOf { (it.members["packetsLost"] as? Number)?.toLong() ?: 0 }
        val received = inbound.sumOf { (it.members["packetsReceived"] as? Number)?.toLong() ?: 0 }
        val codec = inVideo?.members?.get("codecId")?.let { sub?.statsMap?.get(it)?.members?.get("mimeType") as? String }

        return CallStats(
            recvResolution = inVideo?.resolution(),
            recvFps = (inVideo?.members?.get("framesPerSecond") as? Number)?.toDouble(),
            recvKbps = recvKbps,
            sendResolution = outVideo?.resolution(),
            sendFps = (outVideo?.members?.get("framesPerSecond") as? Number)?.toDouble(),
            sendKbps = sendKbps,
            rttMs = ((pair?.members?.get("currentRoundTripTime") as? Number)?.toDouble()?.times(1000))?.toLong(),
            lossPercent = if (lost + received > 0) lost * 100.0 / (lost + received) else null,
            codec = codec?.removePrefix("video/"),
            path = when (remote?.members?.get("candidateType")) {
                "relay" -> "relayed (TURN)"
                "srflx", "prflx" -> "direct to server (NAT)"
                "host" -> "direct to server"
                else -> null
            },
        )
    }

    private fun livekit.org.webrtc.RTCStats.resolution(): String? {
        val w = (members["frameWidth"] as? Number)?.toInt() ?: return null
        val h = (members["frameHeight"] as? Number)?.toInt() ?: return null
        return "${w}×$h"
    }

    private suspend fun report(fetch: (livekit.org.webrtc.RTCStatsCollectorCallback) -> Unit): RTCStatsReport? =
        withTimeoutOrNull(2_000) {
            suspendCancellableCoroutine { cont ->
                runCatching { fetch { r -> if (cont.isActive) cont.resume(r) } }.onFailure { if (cont.isActive) cont.resume(null) }
            }
        }
}
