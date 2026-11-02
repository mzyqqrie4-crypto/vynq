package app.vynq.data.messaging

import android.content.Context
import android.database.Cursor
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import net.zetetic.database.sqlcipher.SQLiteDatabase

/**
 * All messaging state in one SQLCipher (AES-256) database. Its random 32-byte
 * key is stored wrapped by a non-exportable Android Keystore key, in no-backup
 * storage, so the file is unreadable off the device and isn't in backups.
 *
 * One database per device id, i.e. per account on this phone. It survives
 * sign-out; [deleteAllExcept] removes the ones this phone has forgotten.
 */
class SqlCipherMessagingDao private constructor(private val db: SQLiteDatabase) : MessagingDao {
    override val changes = MutableStateFlow(0L)
    private val json = Json { ignoreUnknownKeys = true }
    private fun bump() { changes.value++ }

    // --- protocol state -----------------------------------------------------------------

    override fun get(ns: String, key: String): ByteArray? =
        db.rawQuery("select v from kv where ns = ? and k = ?", arrayOf(ns, key)).use { c -> if (c.moveToFirst()) c.getBlob(0) else null }
    override fun put(ns: String, key: String, value: ByteArray) =
        db.execSQL("insert or replace into kv (ns, k, v) values (?, ?, ?)", arrayOf<Any>(ns, key, value))
    override fun delete(ns: String, key: String) = db.execSQL("delete from kv where ns = ? and k = ?", arrayOf<Any>(ns, key))
    override fun keys(ns: String): List<String> = db.rawQuery("select k from kv where ns = ?", arrayOf(ns)).use { c -> c.map { getString(0) } }

    // --- contacts --------------------------------------------------------------------------

    override fun contacts() = db.rawQuery("select * from contacts order by coalesce(display_name, username)", emptyArray<String>()).use { c -> c.map { contact() } }
    override fun contact(userId: String) = db.rawQuery("select * from contacts where user_id = ?", arrayOf(userId)).use { c -> if (c.moveToFirst()) c.contact() else null }

    override fun replaceContacts(contacts: List<Contact>) = tx {
        db.execSQL("delete from contacts")
        contacts.forEach {
            db.execSQL("insert into contacts (user_id, username, display_name, state) values (?, ?, ?, ?)",
                arrayOf<Any?>(it.userId, it.username, it.displayName, it.state.name))
        }
    }

    private fun Cursor.contact(): Contact {
        val id = str("user_id")!!
        return Contact(id, str("username"), str("display_name"), ContactState.valueOf(str("state")!!),
            nickname = get(ContactLocal.NS_NICKNAME, id)?.decodeToString(),
            avatarPath = get(ContactLocal.NS_AVATAR_PATH, id)?.decodeToString())
    }

    override fun setNickname(userId: String, nickname: String?) = setLocal(ContactLocal.NS_NICKNAME, userId, nickname)
    override fun setAvatarPath(userId: String, path: String?) = setLocal(ContactLocal.NS_AVATAR_PATH, userId, path)

    private fun setLocal(ns: String, key: String, value: String?) {
        if (get(ns, key)?.decodeToString() == value) return
        if (value == null) delete(ns, key) else put(ns, key, value.encodeToByteArray())
        bump()
    }

    // --- devices -----------------------------------------------------------------------------

    override fun devices(userId: String) = db.rawQuery("select * from devices where user_id = ? order by first_seen", arrayOf(userId)).use { c -> c.map { device() } }
    override fun device(deviceId: String) = db.rawQuery("select * from devices where device_id = ?", arrayOf(deviceId)).use { c -> if (c.moveToFirst()) c.device() else null }
    override fun upsertDevice(device: RemoteDevice) = tx {
        db.execSQL("insert or replace into devices (device_id, user_id, identity_key, first_seen, verified, active) values (?, ?, ?, ?, ?, ?)",
            arrayOf<Any>(device.deviceId, device.userId, device.identityKey, device.firstSeen, device.verified.int, device.active.int))
    }
    override fun setDeviceActive(deviceId: String, active: Boolean) = tx {
        db.execSQL("update devices set active = ? where device_id = ?", arrayOf<Any>(active.int, deviceId))
    }
    override fun setVerified(userId: String, verified: Boolean) = tx {
        db.execSQL("update devices set verified = ? where user_id = ? and active = 1", arrayOf<Any>(verified.int, userId))
    }

    private fun Cursor.device() = RemoteDevice(
        str("device_id")!!, str("user_id")!!, getBlob(getColumnIndexOrThrow("identity_key")),
        long("first_seen"), long("verified") == 1L, long("active") == 1L,
    )

    // --- messages ------------------------------------------------------------------------------

    override fun messages(peer: String) = db.rawQuery("select * from messages where peer = ? order by sent_at, rowid", arrayOf(peer)).use { c -> c.map { message() } }
    override fun message(id: String) = db.rawQuery("select * from messages where id = ?", arrayOf(id)).use { c -> if (c.moveToFirst()) c.message() else null }

    override fun insertMessage(message: Message): Boolean {
        val exists = db.rawQuery("select 1 from messages where id = ?", arrayOf(message.id)).use { it.moveToFirst() }
        if (exists) return false
        tx {
            db.execSQL(
                "insert into messages (id, peer, outgoing, kind, body, attachment, sent_at, status, read, archived) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(message.id, message.peer, message.outgoing.int, message.kind.name, message.text,
                    message.attachment?.let { json.encodeToString(AttachmentPointer.serializer(), it) },
                    message.sentAt, message.status.name, message.read.int, message.archived.int),
            )
        }
        return true
    }

    override fun updateStatus(id: String, status: MessageStatus) = tx {
        db.execSQL("update messages set status = ? where id = ?", arrayOf<Any>(status.name, id))
    }
    override fun markRead(peer: String) = tx {
        db.execSQL("update messages set read = 1 where peer = ? and read = 0", arrayOf<Any>(peer))
    }

    override fun unarchived(limit: Int) = db.rawQuery(
        "select * from messages where archived = 0 and kind != 'System' order by sent_at, rowid limit $limit", emptyArray<String>(),
    ).use { c -> c.map { message() } }

    override fun markArchived(ids: Collection<String>) {
        db.beginTransaction()
        try {
            ids.forEach { db.execSQL("update messages set archived = 1 where id = ?", arrayOf<Any>(it)) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    override fun deleteMessages(peer: String) {
        db.execSQL("delete from messages where peer = ?", arrayOf<Any>(peer))
        bump()
    }

    override fun conversations(): List<Conversation> {
        val sql = """
            select c.*, m.*, (select count(*) from messages u where u.peer = c.user_id and u.read = 0) as unread
              from contacts c
              join messages m on m.rowid = (select rowid from messages x where x.peer = c.user_id order by sent_at desc, rowid desc limit 1)
             order by m.sent_at desc
        """
        return db.rawQuery(sql, emptyArray<String>()).use { c -> c.map { Conversation(contact(), message(), long("unread").toInt()) } }
    }

    private fun Cursor.message() = Message(
        id = str("id")!!, peer = str("peer")!!, outgoing = long("outgoing") == 1L,
        kind = MessageKind.valueOf(str("kind")!!), text = str("body"),
        attachment = str("attachment")?.let { json.decodeFromString(AttachmentPointer.serializer(), it) },
        sentAt = long("sent_at"), status = MessageStatus.valueOf(str("status")!!), read = long("read") == 1L,
        archived = long("archived") == 1L,
    )

    // --- plumbing -------------------------------------------------------------------------------

    private inline fun tx(block: () -> Unit) {
        db.beginTransaction()
        try { block(); db.setTransactionSuccessful() } finally { db.endTransaction() }
        bump()
    }

    private val Boolean.int get() = if (this) 1 else 0
    private fun Cursor.str(col: String): String? = getColumnIndexOrThrow(col).let { if (isNull(it)) null else getString(it) }
    private fun Cursor.long(col: String): Long = getLong(getColumnIndexOrThrow(col))
    private inline fun <T> Cursor.map(f: Cursor.() -> T): List<T> = buildList { while (moveToNext()) add(f()) }

    companion object {
        private const val SCHEMA_VERSION = 2
        private const val PREFIX = "messages-"

        init { System.loadLibrary("sqlcipher") }

        fun open(context: Context, deviceId: String): SqlCipherMessagingDao {
            val dir = context.noBackupFilesDir
            val file = File(dir, "$PREFIX$deviceId.db")
            val db = SQLiteDatabase.openDatabase(
                file.path, DatabaseKey.load(context, deviceId), null,
                SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY, null, null,
            )
            if (db.version < SCHEMA_VERSION) {
                db.beginTransaction()
                try {
                    if (db.version < 1) SCHEMA.forEach(db::execSQL)
                    if (db.version < 2) db.execSQL("alter table messages add column archived integer not null default 0")
                    db.version = SCHEMA_VERSION
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
            }
            return SqlCipherMessagingDao(db)
        }

        /** Removes every messaging database (and its key) whose device isn't in [keep]. */
        fun deleteAllExcept(context: Context, keep: Set<String>) {
            context.noBackupFilesDir.listFiles().orEmpty()
                .filter { it.name.startsWith(PREFIX) && keep.none { id -> it.name.startsWith("$PREFIX$id") } }
                .forEach { it.delete() }
            DatabaseKey.deleteAllExcept(context, keep)
        }

        private val SCHEMA = listOf(
            "create table kv (ns text not null, k text not null, v blob not null, primary key (ns, k))",
            "create table contacts (user_id text primary key, username text, display_name text, state text not null)",
            """create table devices (device_id text primary key, user_id text not null, identity_key blob not null,
               first_seen integer not null, verified integer not null default 0, active integer not null default 1)""",
            """create table messages (id text primary key, peer text not null, outgoing integer not null, kind text not null,
               body text, attachment text, sent_at integer not null, status text not null, read integer not null default 0)""",
            "create index messages_peer on messages (peer, sent_at)",
        )
    }
}

/** The database key: 32 random bytes, sealed by an AES-GCM key in the Android Keystore. */
private object DatabaseKey {
    private const val ALIAS = "vynq.db.v1"
    private const val PREFIX = "db-key-"

    fun load(context: Context, deviceId: String): ByteArray {
        val file = AtomicFile(File(context.noBackupFilesDir, "$PREFIX$deviceId"))
        if (file.baseFile.exists()) {
            val blob = file.readFully()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(128, blob, 0, 12))
            return cipher.doFinal(blob, 12, blob.size - 12)
        }
        val key = ByteArray(32).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey())
        val out = file.startWrite()
        try {
            out.write(cipher.iv + cipher.doFinal(key))
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out); throw e
        }
        return key
    }

    fun deleteAllExcept(context: Context, keep: Set<String>) {
        context.noBackupFilesDir.listFiles().orEmpty()
            .filter { it.name.startsWith(PREFIX) && it.name.removePrefix(PREFIX) !in keep }
            .forEach { it.delete() }
    }

    private fun keystoreKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
        }.generateKey()
    }
}
