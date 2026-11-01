package app.vynq.data.auth

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.io.File
import java.util.UUID

interface DeviceIdentity {
    /**
     * Stable while signed in. A device id belongs to one account forever on the
     * server, so signing out starts a new device ([reset]); a new install or
     * cleared data does too.
     */
    val id: String
    val name: String

    /** Forgets the current id; the next [id] read creates a fresh one. */
    fun reset()
}

class InstallDeviceIdentity(context: Context) : DeviceIdentity {
    private val file = File(context.noBackupFilesDir, "device-id")
    @Volatile private var cached: String? = null

    override val id: String
        get() = cached ?: synchronized(this) {
            cached ?: (file.takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
                ?: UUID.randomUUID().toString().also { file.writeText(it) }).also { cached = it }
        }

    override fun reset() = synchronized(this) {
        file.delete()
        cached = null
    }

    private val resolver = context.contentResolver

    // The name the owner sees in system settings ("Xiaomi 17 Pro"); the model
    // code ("25098PN5AC") is only a fallback.
    override val name: String
        get() {
            Settings.Global.getString(resolver, Settings.Global.DEVICE_NAME)
                ?.trim()?.takeIf { it.isNotEmpty() }?.let { return it.take(40) }
            val model = Build.MODEL.orEmpty()
            val maker = Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() }
            val full = if (model.startsWith(maker, ignoreCase = true)) model else "$maker $model"
            return full.trim().ifEmpty { "Android device" }.take(40)
        }
}
