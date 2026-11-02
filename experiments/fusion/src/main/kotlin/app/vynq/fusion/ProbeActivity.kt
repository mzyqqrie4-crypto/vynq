package app.vynq.fusion

import android.app.Activity
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Fusion Probe (Phase 6). Probes Wi-Fi and mobile data at the same time, each
 * through its own socket bound to that network, and writes one row per probe to
 * `Android/data/app.vynq.fusion/files/fusion/<run>/`. Summarise with
 * `tools/fusion_report.py`.
 *
 * Start from adb:
 * `adb shell am start -n app.vynq.fusion/.ProbeActivity --es mode both --ei minutes 10 --es label wifi-drop`
 * (mode: both | wifi | cell). The screen stays on while it runs. Run `adb shell am force-stop app.vynq.fusion`
 * first if it's already open: a second start reaches the running screen and is ignored.
 */
class ProbeActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var button: Button

    private val probers = mutableMapOf<String, PathProber>()
    private val callbacks = mutableListOf<ConnectivityManager.NetworkCallback>()
    private var probes: Writer? = null
    private var events: Writer? = null
    private var startNanos = 0L
    private var endAt = 0L
    private var runDir: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        status = TextView(this).apply { textSize = 15f; setPadding(32, 32, 32, 32) }
        button = Button(this).apply { text = "Start (both, 10 min)"; setOnClickListener { if (probes == null) start("both", 10, "manual") else stop("stopped") } }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            addView(button); addView(status)
        })
        intent.getStringExtra("mode")?.let { mode ->
            start(mode, intent.getIntExtra("minutes", 10), intent.getStringExtra("label") ?: mode)
        } ?: render()
    }

    override fun onDestroy() {
        stop("closed")
        super.onDestroy()
    }

    private fun start(mode: String, minutes: Int, label: String) {
        if (probes != null) return
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val dir = File(getExternalFilesDir("fusion"), "$stamp-$label").apply { mkdirs() }
        runDir = dir
        probes = File(dir, "probes.csv").bufferedWriter().also { it.write("seq,path,send_ms,rtt_ms,status\n") }
        events = File(dir, "events.csv").bufferedWriter().also { it.write("t_ms,event\n") }
        startNanos = System.nanoTime()
        endAt = System.currentTimeMillis() + minutes * 60_000L
        event("start mode=$mode minutes=$minutes server=$HOST:$PORT")
        if (mode == "both" || mode == "wifi") watch("wifi", NetworkCapabilities.TRANSPORT_WIFI)
        if (mode == "both" || mode == "cell") watch("cell", NetworkCapabilities.TRANSPORT_CELLULAR)
        button.text = "Stop"
        tick()
    }

    /** Ask Android to keep this transport up (mobile data stays on while Wi-Fi is the default). */
    private fun watch(path: String, transport: Int) {
        val cm = getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                event("$path available")
                main.post {
                    probers.remove(path)?.stop()
                    val out = probes ?: return@post
                    probers[path] = PathProber(path, network, HOST, PORT, startNanos, out).also { it.start() }
                }
            }

            override fun onLost(network: Network) {
                event("$path lost")
            }
        }
        cm.requestNetwork(
            NetworkRequest.Builder().addTransportType(transport).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
            cb,
        )
        callbacks += cb
    }

    private fun tick() {
        if (probes == null) return
        if (System.currentTimeMillis() >= endAt) { stop("done"); return }
        synchronized(probes!!) { probes?.flush() }
        events?.flush()
        render()
        main.postDelayed({ tick() }, 1_000)
    }

    private fun stop(reason: String) {
        if (probes == null) return
        event("stop $reason")
        val cm = getSystemService(ConnectivityManager::class.java)
        callbacks.forEach { runCatching { cm.unregisterNetworkCallback(it) } }
        callbacks.clear()
        probers.values.forEach { it.stop() }
        probes?.let { w -> synchronized(w) { w.close() } }
        events?.close()
        probes = null; events = null
        button.text = "Start (both, 10 min)"
        render(reason)
    }

    private fun event(text: String) {
        val t = (System.nanoTime() - startNanos) / 1_000_000
        events?.let { w -> synchronized(w) { w.write("$t,$text\n") } }
    }

    private fun render(finished: String? = null) {
        val lines = mutableListOf<String>()
        lines += if (finished != null) "Finished ($finished)" else if (probes != null) "Running, ${(endAt - System.currentTimeMillis()) / 1000} s left" else "Idle"
        runDir?.let { lines += "Folder: ${it.name}" }
        probers.forEach { (path, p) ->
            lines += "$path: ok ${p.ok.get()} · lost ${p.lost.get()} · down ${p.down.get()} · last ${p.lastRttMs?.let { "%.0f ms".format(it) } ?: "—"}"
        }
        status.text = lines.joinToString("\n")
    }

    companion object {
        // Cloudflare's public STUN service (anycast, answers every Binding request).
        const val HOST = "stun.cloudflare.com"
        const val PORT = 3478
    }
}
