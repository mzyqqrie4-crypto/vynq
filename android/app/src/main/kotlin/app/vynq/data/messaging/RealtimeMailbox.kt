package app.vynq.data.messaging

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * A doorbell, not a mail carrier: listens on Supabase Realtime for new rows in
 * this device's mailbox and calls [onMail], which then fetches and decrypts
 * through the normal RPCs. Runs only while the app is in the foreground; when
 * it isn't, mail waits on the server until the next start (push arrives in
 * Phase 4).
 */
class RealtimeMailbox(
    private val url: String,
    private val deviceId: String,
    private val token: suspend () -> String,
    private val scope: CoroutineScope,
    private val onMail: () -> Unit,
    private val http: OkHttpClient = OkHttpClient(),
) {
    private var socket: WebSocket? = null
    private var loop: Job? = null
    private val ref = AtomicInteger(0)
    @Volatile private var connected = false

    fun start() {
        if (loop?.isActive == true) return
        loop = scope.launch {
            var backoff = 2_000L
            while (isActive) {
                connected = false
                val accessToken = runCatching { token() }.getOrNull()
                if (accessToken != null) {
                    socket = http.newWebSocket(Request.Builder().url(url).build(), Listener(accessToken))
                    // Heartbeat and token refresh while the socket lives.
                    var beats = 0
                    while (isActive && socket != null) {
                        delay(HEARTBEAT_MS)
                        if (!send("phoenix", "heartbeat", JsonObject(emptyMap()))) break
                        if (++beats % TOKEN_EVERY_BEATS == 0) {
                            runCatching { token() }.getOrNull()?.let { send(TOPIC, "access_token", buildJsonObject { put("access_token", it) }) }
                        }
                        if (connected) backoff = 2_000L
                    }
                }
                socket?.cancel(); socket = null
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(30_000L)
            }
        }
    }

    fun stop() {
        loop?.cancel(); loop = null
        socket?.close(1000, null); socket = null
        connected = false
    }

    private fun send(topic: String, event: String, payload: JsonObject): Boolean {
        val s = socket ?: return false
        val n = ref.incrementAndGet().toString()
        return s.send(buildJsonObject {
            put("topic", topic); put("event", event); put("payload", payload); put("ref", n); put("join_ref", "1")
        }.toString())
    }

    private inner class Listener(private val accessToken: String) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            send(TOPIC, "phx_join", buildJsonObject {
                putJsonObject("config") {
                    putJsonObject("broadcast") { put("self", false) }
                    putJsonObject("presence") { put("key", "") }
                    putJsonArray("postgres_changes") {
                        addJsonObject {
                            put("event", "INSERT"); put("schema", "public"); put("table", "mailbox")
                            put("filter", "to_device=eq.$deviceId")
                        }
                    }
                    put("private", false)
                }
                put("access_token", accessToken)
            })
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val msg = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
            when (msg["event"]?.jsonPrimitive?.contentOrNull) {
                "phx_reply" -> {
                    val ok = msg["payload"]?.jsonObject?.get("status")?.jsonPrimitive?.contentOrNull == "ok"
                    if (msg["topic"]?.jsonPrimitive?.contentOrNull == TOPIC && ok && !connected) {
                        connected = true
                        onMail() // catch anything that arrived while we were away
                    }
                }
                "postgres_changes" -> onMail()
                "phx_error", "phx_close" -> socket = null
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (socket === webSocket) socket = null
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (socket === webSocket) socket = null
        }
    }

    private companion object {
        const val TOPIC = "realtime:mailbox"
        const val HEARTBEAT_MS = 25_000L
        const val TOKEN_EVERY_BEATS = 20 // ~8 minutes; access tokens last an hour
    }
}
