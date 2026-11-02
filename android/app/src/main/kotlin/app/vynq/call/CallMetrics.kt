package app.vynq.call

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import io.livekit.android.room.Room
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import livekit.org.webrtc.RTCStats

/**
 * Phase 5 measurements (debug builds only): one CSV row per second for the whole
 * call, in the app's external files folder (`call-metrics/`), pulled with adb and
 * summarised by `tools/call_report.py`. Numbers only: no names, ids or content.
 */
class CallMetrics(private val context: Context, private val room: Room, private val scope: CoroutineScope) {
    private var job: Job? = null

    fun start(video: Boolean, quality: String, codecSent: String) {
        if (job != null) return
        val dir = context.getExternalFilesDir("call-metrics") ?: return
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "call-$stamp.csv")
        job = scope.launch(Dispatchers.IO) {
            file.writeText("# model=${Build.MODEL} video=$video quality=$quality codec_sent=$codecSent\n$HEADER\n")
            val begin = System.currentTimeMillis()
            var last: Totals? = null
            while (isActive) {
                val now = System.currentTimeMillis()
                val t = totals(now)
                val prev = last
                if (prev != null && t != null) file.appendText(row(now - begin, prev, t) + "\n")
                if (t != null) last = t
                delay(1_000)
            }
        }
    }

    fun stop() { job?.cancel(); job = null }

    private class Totals(
        val at: Long,
        val inVideo: RTCStats?, val outVideo: RTCStats?, val pair: RTCStats?,
        val recvBytes: Long, val sendBytes: Long,
        val lost: Long, val received: Long,
        val recvCodec: String?, val sendCodec: String?,
    )

    private suspend fun totals(now: Long): Totals? {
        val sub = rtcReport { room.getSubscriberRTCStats(it) }?.statsMap
        val pub = rtcReport { room.getPublisherRTCStats(it) }?.statsMap
        if (sub == null && pub == null) return null
        val inbound = sub?.values?.filter { it.type == "inbound-rtp" && it.members["kind"] == "video" }.orEmpty()
        val inVideo = inbound.maxByOrNull { it.long("bytesReceived") }
        val outVideo = pub?.values?.filter { it.type == "outbound-rtp" && it.members["kind"] == "video" }
            ?.maxByOrNull { it.long("frameWidth") }
        val pair = (pub ?: sub)?.values?.firstOrNull {
            it.type == "candidate-pair" && it.members["state"] == "succeeded" && it.members["nominated"] == true
        }
        return Totals(
            at = now, inVideo = inVideo, outVideo = outVideo, pair = pair,
            recvBytes = sub?.values?.filter { it.type == "inbound-rtp" }?.sumOf { it.long("bytesReceived") } ?: 0,
            sendBytes = pub?.values?.filter { it.type == "outbound-rtp" }?.sumOf { it.long("bytesSent") } ?: 0,
            lost = inbound.sumOf { it.long("packetsLost") },
            received = inbound.sumOf { it.long("packetsReceived") },
            recvCodec = inVideo?.members?.get("codecId")?.let { sub?.get(it)?.members?.get("mimeType") as? String },
            sendCodec = outVideo?.members?.get("codecId")?.let { pub?.get(it)?.members?.get("mimeType") as? String },
        )
    }

    private fun row(t: Long, a: Totals, b: Totals): String {
        val secs = ((b.at - a.at) / 1000.0).coerceAtLeast(0.001)
        val lostNow = (b.lost - a.lost).coerceAtLeast(0)
        val gotNow = (b.received - a.received).coerceAtLeast(0)
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val thermal = if (Build.VERSION.SDK_INT >= 29) context.getSystemService(PowerManager::class.java).currentThermalStatus else -1
        val cols = listOf(
            t / 1000.0,
            b.inVideo?.int("frameWidth"), b.inVideo?.int("frameHeight"), b.inVideo?.double("framesPerSecond"),
            (b.recvBytes - a.recvBytes) * 8 / 1000 / secs,
            b.inVideo?.long("freezeCount"), b.inVideo?.double("totalFreezesDuration"), b.inVideo?.long("framesDropped"),
            if (lostNow + gotNow > 0) lostNow * 100.0 / (lostNow + gotNow) else 0.0,
            b.inVideo?.double("jitter")?.times(1000),
            b.outVideo?.int("frameWidth"), b.outVideo?.int("frameHeight"), b.outVideo?.double("framesPerSecond"),
            (b.sendBytes - a.sendBytes) * 8 / 1000 / secs,
            b.outVideo?.members?.get("qualityLimitationReason"),
            b.pair?.double("currentRoundTripTime")?.times(1000),
            b.recvCodec?.removePrefix("video/"), b.sendCodec?.removePrefix("video/"),
            thermal,
            battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }?.div(10.0),
            battery?.let { it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / it.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1) },
        )
        return cols.joinToString(",") { v ->
            when (v) {
                null -> ""
                is Double -> "%.2f".format(Locale.US, v)
                else -> v.toString()
            }
        }
    }

    private fun RTCStats.long(key: String) = (members[key] as? Number)?.toLong() ?: 0L
    private fun RTCStats.int(key: String) = (members[key] as? Number)?.toInt()
    private fun RTCStats.double(key: String) = (members[key] as? Number)?.toDouble()

    companion object {
        const val HEADER = "t_s,recv_w,recv_h,recv_fps,recv_kbps,freezes,freeze_s,frames_dropped,loss_pct,jitter_ms," +
            "send_w,send_h,send_fps,send_kbps,limit,rtt_ms,recv_codec,send_codec,thermal,battery_c,battery_pct"
    }
}
