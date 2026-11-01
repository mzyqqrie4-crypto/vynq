package app.vynq.data.auth

import android.content.Context
import android.os.Build
import java.io.File
import java.util.UUID

interface DeviceIdentity {
    /** Stable for this install; a new install (or cleared data) is a new device. */
    val id: String
    val name: String
}

class InstallDeviceIdentity(context: Context) : DeviceIdentity {
    private val file = File(context.noBackupFilesDir, "device-id")

    override val id: String by lazy {
        file.takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
            ?: UUID.randomUUID().toString().also { file.writeText(it) }
    }

    override val name: String
        get() {
            val model = Build.MODEL.orEmpty()
            val maker = Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() }
            val full = if (model.startsWith(maker, ignoreCase = true)) model else "$maker $model"
            return full.trim().ifEmpty { "Android device" }.take(40)
        }
}
