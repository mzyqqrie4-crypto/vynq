package app.vynq.data.auth

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.AtomicFile
import java.io.File
import java.util.UUID

/**
 * Which device this phone is on the server, per account. A device id belongs
 * to one account forever there, so each account that signs in here gets its
 * own id. Signing out keeps it: the same account signing back in continues as
 * the same device, with its keys and message history. Only "sign out and
 * erase" or deleting the account forgets it.
 */
interface DeviceIdentity {
    /** This phone's device id for the account last passed to [bind]. */
    val id: String
    val name: String

    /** Selects (creating on first use) the device id for [userId]. */
    fun bind(userId: String)

    /** Forgets [userId]'s device on this phone; its next sign-in is a new device. */
    fun forget(userId: String)

    /** Device ids this phone still keeps, for every account that has used it. */
    fun knownIds(): Set<String>
}

class InstallDeviceIdentity(context: Context) : DeviceIdentity {
    private val file = AtomicFile(File(context.noBackupFilesDir, "device-ids"))
    /** Before per-account ids: one id for the whole install. Adopted by the account restored at upgrade. */
    private val legacy = File(context.noBackupFilesDir, "device-id")
    @Volatile private var current: String? = null

    override val id: String
        get() = synchronized(this) { current ?: error("no account bound") }

    override fun bind(userId: String) = synchronized(this) {
        val ids = read()
        current = ids[userId] ?: run {
            val adopted = legacy.takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
            (adopted ?: UUID.randomUUID().toString()).also { id ->
                write(ids + (userId to id))
                legacy.delete()
            }
        }
    }

    override fun forget(userId: String) = synchronized(this) {
        val ids = read()
        if (ids[userId] == current) current = null
        write(ids - userId)
    }

    override fun knownIds(): Set<String> = synchronized(this) { read().values.toSet() }

    private fun read(): Map<String, String> =
        if (!file.baseFile.exists()) emptyMap()
        else file.readFully().decodeToString().lines().mapNotNull { line ->
            line.split('=', limit = 2).takeIf { it.size == 2 && it[0].isNotBlank() }?.let { it[0] to it[1].trim() }
        }.toMap()

    private fun write(ids: Map<String, String>) {
        val out = file.startWrite()
        try {
            out.write(ids.entries.joinToString("\n") { "${it.key}=${it.value}" }.encodeToByteArray())
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out); throw e
        }
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
