package app.vynq.data.update

import android.content.Context
import app.vynq.BuildConfig
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * vynq is shared as an APK on GitHub Releases, which doesn't update itself. At
 * most once a day, release builds ask GitHub for the latest release and the app
 * shows a banner when it is newer. GitHub sees the phone's IP address and
 * nothing else; nothing about the user is sent.
 */
object UpdateCheck {
    data class Release(val version: String, val url: String)

    const val RELEASES_URL = "https://github.com/mzyqqrie4-crypto/vynq/releases"
    private const val API = "https://api.github.com/repos/mzyqqrie4-crypto/vynq/releases/latest"
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    /** A newer release than this build, or null (none, debug build, offline, or checked today and nothing new). */
    suspend fun newer(context: Context): Release? {
        if (BuildConfig.DEBUG) return null
        val prefs = context.getSharedPreferences("update_check", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val latest = if (now - prefs.getLong("checked_at", 0) < DAY_MS) {
            prefs.getString("version", null)?.let { Release(it, prefs.getString("url", RELEASES_URL)!!) }
        } else {
            fetch()?.also { prefs.edit().putLong("checked_at", now).putString("version", it.version).putString("url", it.url).apply() }
        }
        return latest?.takeIf { isNewer(it.version, BuildConfig.VERSION_NAME) }
    }

    private suspend fun fetch(): Release? = withContext(Dispatchers.IO) {
        runCatching {
            val c = URL(API).openConnection() as HttpURLConnection
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.connectTimeout = 10_000
            c.readTimeout = 10_000
            try {
                if (c.responseCode != 200) return@runCatching null
                val body = Json.parseToJsonElement(c.inputStream.bufferedReader().readText()).jsonObject
                Release(
                    version = body["tag_name"]!!.jsonPrimitive.content.removePrefix("v"),
                    url = body["html_url"]?.jsonPrimitive?.content ?: RELEASES_URL,
                )
            } finally {
                c.disconnect()
            }
        }.getOrNull()
    }

    /** "0.7.0" > "0.6.2"; suffixes like "-debug" are ignored. */
    fun isNewer(candidate: String, current: String): Boolean {
        fun parts(v: String) = v.removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val a = parts(candidate)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
