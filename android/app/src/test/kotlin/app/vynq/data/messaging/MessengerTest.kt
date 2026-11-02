package app.vynq.data.messaging

import app.vynq.data.auth.AccountError
import app.vynq.data.auth.BackendApi
import app.vynq.data.crypto.HistoryCrypto
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two (or more) Messengers with real libsignal talking through an in-memory
 * stand-in for the Supabase RPCs (same rules as the migration: accepted
 * contacts only, one-time keys popped, ciphertext-only mailbox).
 */
class MessengerTest {

    private class FakeServer {
        val contacts = mutableMapOf<Pair<String, String>, String>()  // (owner, contact) -> state
        val usernames = mutableMapOf<String, String>()                // username -> user
        val devices = mutableMapOf<String, Dev>()
        val mailbox = mutableListOf<Mail>()
        val blobs = mutableMapOf<String, ByteArray>()
        val vaults = mutableMapOf<String, String>()                    // user -> wrapped key (b64)
        val history = mutableListOf<Triple<Long, String, Pair<String, String>>>() // seq, user, (record id, blob b64)
        var nextId = 1L
        /** Set to swap a device's identity key in everything the server hands out. */
        var forgedIdentity: Pair<String, String>? = null

        class Dev(val id: String, val user: String) {
            var identity: String? = null; var regId = 0
            var signed: JsonObject? = null; var kyber: JsonObject? = null
            val oneTime = ArrayDeque<JsonObject>()
        }
        class Mail(val id: Long, val to: String, val fromUser: String, val fromDevice: String, val type: Int, val body: ByteArray)

        fun accepted(a: String, b: String) = contacts[a to b] == "accepted"
        fun reachable(caller: String, user: String) = caller == user || accepted(caller, user)
        private fun forbidden(): Nothing = throw AccountError.Server(403, "device_unavailable")

        fun handle(user: String, device: String, fn: String, a: JsonObject): JsonElement = when (fn) {
            "publish_keys" -> {
                val d = devices.getValue(a.s("p_device")!!).also { require(it.user == user) }
                a.s("p_identity_key")?.let { d.identity = it; d.regId = a["p_registration_id"]!!.jsonPrimitive.int }
                (a["p_signed"] as? JsonObject)?.let { d.signed = it }
                (a["p_kyber"] as? JsonObject)?.let { d.kyber = it }
                a["p_one_time"]!!.jsonArray.forEach { d.oneTime.add(it.jsonObject) }
                JsonNull
            }
            "prekey_status" -> devices.getValue(a.s("p_device")!!).let { d ->
                buildJsonArray { add(buildJsonObject {
                    put("one_time_count", d.oneTime.size); put("has_identity", d.identity != null)
                    put("signed_key_id", d.signed?.get("id") ?: JsonNull); put("kyber_key_id", d.kyber?.get("id") ?: JsonNull)
                }) }
            }
            "list_contacts" -> JsonArray(contacts.filterKeys { it.first == user }.map { (k, state) ->
                buildJsonObject {
                    put("user_id", k.second); put("username", usernames.entries.first { it.value == k.second }.key); put("state", state)
                }
            })
            "request_contact" -> {
                val target = usernames.getValue(a.s("p_username")!!)
                val state = if (contacts[target to user] == "pending_out") "accepted" else "pending_out"
                contacts[user to target] = state
                contacts[target to user] = if (state == "accepted") "accepted" else "pending_in"
                buildJsonArray { add(buildJsonObject { put("user_id", target); put("state", state) }) }
            }
            "accept_contact" -> {
                val other = a.s("p_user_id")!!
                contacts[user to other] = "accepted"; contacts[other to user] = "accepted"; JsonNull
            }
            "contact_devices" -> {
                val target = a.s("p_user_id")!!
                if (!reachable(user, target)) throw AccountError.Server(403, "not_a_contact")
                JsonArray(devices.values.filter { it.user == target && it.identity != null }.map { d ->
                    buildJsonObject { put("device_id", d.id); put("registration_id", d.regId); put("identity_key", identityOf(d)) }
                })
            }
            "claim_prekey_bundle" -> {
                val d = devices[a.s("p_device_id")!!]?.takeIf { reachable(user, it.user) } ?: forbidden()
                val otk = d.oneTime.removeFirstOrNull()
                buildJsonArray { add(buildJsonObject {
                    put("registration_id", d.regId); put("identity_key", identityOf(d))
                    put("signed_key_id", d.signed!!["id"]!!); put("signed_key", d.signed!!["key"]!!); put("signed_sig", d.signed!!["sig"]!!)
                    put("kyber_key_id", d.kyber!!["id"]!!); put("kyber_key", d.kyber!!["key"]!!); put("kyber_sig", d.kyber!!["sig"]!!)
                    put("prekey_id", otk?.get("id") ?: JsonNull); put("prekey", otk?.get("key") ?: JsonNull)
                }) }
            }
            "send_messages" -> {
                a["p_messages"]!!.jsonArray.forEach { m ->
                    val to = devices[m.jsonObject.s("to")!!] ?: forbidden()
                    if (to.id == device || !reachable(user, to.user)) forbidden()
                    mailbox += Mail(nextId++, to.id, user, device, m.jsonObject["type"]!!.jsonPrimitive.int,
                        Base64.getDecoder().decode(m.jsonObject.s("body")))
                }
                JsonPrimitive(1)
            }
            "fetch_messages" -> JsonArray(mailbox.filter { it.to == device }.map { m ->
                buildJsonObject {
                    put("id", m.id); put("from_user", m.fromUser); put("from_device", m.fromDevice)
                    put("type", m.type); put("body", Base64.getEncoder().encodeToString(m.body))
                }
            })
            "ack_messages" -> {
                val ids = a["p_ids"]!!.jsonArray.map { it.jsonPrimitive.long }.toSet()
                mailbox.removeAll { it.to == device && it.id in ids }; JsonNull
            }
            "get_history_vault" -> JsonArray(listOfNotNull(vaults[user]?.let { w -> buildJsonObject { put("wrapped_key", w) } }))
            "set_history_vault" -> {
                val replace = (a["p_replace"] as? JsonPrimitive)?.contentOrNull == "true"
                if (!replace && user in vaults) throw AccountError.Server(409, "vault_exists")
                vaults[user] = a.s("p_wrapped_key")!!; JsonNull
            }
            "append_history" -> {
                if (user !in vaults) throw AccountError.Server(409, "vault_missing")
                var n = 0
                a["p_records"]!!.jsonArray.forEach { r ->
                    val id = r.jsonObject.s("id")!!
                    if (history.none { it.second == user && it.third.first == id }) {
                        history += Triple(nextId++, user, id to r.jsonObject.s("blob")!!); n++
                    }
                }
                JsonPrimitive(n)
            }
            "fetch_history" -> {
                val after = a["p_after"]!!.jsonPrimitive.long
                val limit = a["p_limit"]!!.jsonPrimitive.int
                JsonArray(history.filter { it.second == user && it.first > after }.take(limit).map { (seq, _, r) ->
                    buildJsonObject { put("seq", seq); put("record_id", r.first); put("blob", r.second) }
                })
            }
            else -> error("unknown rpc $fn")
        }

        private fun identityOf(d: Dev) = forgedIdentity?.takeIf { it.first == d.id }?.second ?: d.identity!!
        private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
    }

    private class Phone(server: FakeServer, val user: String, scope: TestScope) {
        val deviceId = UUID.randomUUID().toString().also { server.devices[it] = FakeServer.Dev(it, user) }
        val dao = MemoryMessagingDao()
        val api = object : BackendApi {
            override val deviceId = this@Phone.deviceId
            override val realtimeUrl = ""
            override val deviceName = "Phone ${deviceId.take(4)}"
            override suspend fun function(name: String, args: JsonObject): JsonElement = error("no functions in tests")
            override suspend fun rpc(function: String, args: JsonObject) = server.handle(user, deviceId, function, args)
            override suspend fun upload(path: String, bytes: ByteArray) { server.blobs[path] = bytes }
            override suspend fun download(path: String) = server.blobs.getValue(path)
        }
        val messenger = Messenger(api, dao, user, MemoryAttachmentCache(), StandardTestDispatcher(scope.testScheduler))
        fun texts(peer: String, kind: MessageKind = MessageKind.Text) = dao.messages(peer).filter { it.kind == kind }.map { it.text }
    }

    private val server = FakeServer()
    private val alice = "a0000000-0000-4000-8000-000000000001".also { server.usernames["alice"] = it }
    private val bob = "b0000000-0000-4000-8000-000000000002".also { server.usernames["bob"] = it }

    private suspend fun connect(a: Phone, b: Phone, bUsername: String) {
        a.messenger.requestContact(bUsername)
        b.messenger.refreshContacts()
        b.messenger.acceptContact(a.user)
        a.messenger.refreshContacts()
    }

    @Test fun `messages sent while the recipient is offline arrive when it comes online`() = runTest {
        val a = Phone(server, alice, this); val b = Phone(server, bob, this)
        a.messenger.start(); b.messenger.start()
        connect(a, b, "bob")

        listOf("one", "two", "three").forEach { assertEquals(MessageStatus.Sent, a.messenger.sendText(bob, it).status) }
        assertEquals(3, server.mailbox.count { it.to == b.deviceId })   // waiting on the server

        assertEquals(3, b.messenger.sync().size)                          // Bob comes online
        assertEquals(listOf("one", "two", "three"), b.texts(alice))
        assertTrue(server.mailbox.isEmpty())                              // acknowledged and deleted
        assertEquals(3, b.dao.conversations().single().unread)
    }

    @Test fun `the server only ever holds ciphertext`() = runTest {
        val a = Phone(server, alice, this); val b = Phone(server, bob, this)
        a.messenger.start(); b.messenger.start()
        connect(a, b, "bob")
        a.messenger.sendText(bob, "meet at the old bridge at nine")
        val stored = server.mailbox.single().body
        assertFalse(String(stored, Charsets.ISO_8859_1).contains("bridge"))
        assertFalse(String(stored, Charsets.UTF_8).contains("bridge"))
    }

    @Test fun `replies flow back and the conversation keeps working`() = runTest {
        val a = Phone(server, alice, this); val b = Phone(server, bob, this)
        a.messenger.start(); b.messenger.start()
        connect(a, b, "bob")
        a.messenger.sendText(bob, "hi"); b.messenger.sync()
        b.messenger.sendText(alice, "hey alice"); a.messenger.sync()
        a.messenger.sendText(bob, "how are you"); b.messenger.sync()
        assertEquals(listOf("hey alice"), a.texts(bob).filter { it != "hi" && it != "how are you" })
        assertEquals(listOf("hi", "how are you"), b.texts(alice).filter { it != "hey alice" })
    }

    @Test fun `strangers can't message each other`() = runTest {
        val a = Phone(server, alice, this); val b = Phone(server, bob, this)
        a.messenger.start(); b.messenger.start()
        a.messenger.requestContact("bob")   // not accepted yet
        assertEquals(MessageStatus.Failed, a.messenger.sendText(bob, "let me in").status)
        assertTrue(server.mailbox.isEmpty())
    }

    @Test fun `a new device for a contact triggers a safety number warning and clears verification`() = runTest {
        val a = Phone(server, alice, this); val b = Phone(server, bob, this)
        a.messenger.start(); b.messenger.start()
        connect(a, b, "bob")
        a.messenger.sendText(bob, "first")
        a.messenger.setVerified(bob, true)
        assertTrue(a.messenger.isVerified(bob))

        val b2 = Phone(server, bob, this); b2.messenger.start()   // Bob signs in on a new phone
        a.messenger.sendText(bob, "second")
        assertFalse(a.messenger.isVerified(bob))
        assertTrue(a.texts(bob, MessageKind.System).any { it!!.contains("new device") })
        b2.messenger.sync()
        assertEquals(listOf("second"), b2.texts(alice))           // the new device got it too
    }

    @Test fun `safety numbers match on both phones`() = runTest {
        val a = Phone(server, alice, this); val b = Phone(server, bob, this)
        a.messenger.start(); b.messenger.start()
        connect(a, b, "bob")
        a.messenger.refreshDevices(bob); b.messenger.refreshDevices(alice)
        assertEquals(a.messenger.safetyNumbers(bob).single().second, b.messenger.safetyNumbers(alice).single().second)
    }

    @Test fun `a server that swaps a known device's key is refused`() = runTest {
        val a = Phone(server, alice, this); val b = Phone(server, bob, this)
        a.messenger.start(); b.messenger.start()
        connect(a, b, "bob")
        a.messenger.sendText(bob, "pinned now")
        b.messenger.sync()
        // The server now hands out a different identity key for Bob's known device.
        server.forgedIdentity = b.deviceId to Base64.getEncoder().encodeToString(ByteArray(33).also { it[0] = 5 })
        a.dao.delete("session", b.deviceId)   // force a new session (as if it had to start over)
        assertEquals(MessageStatus.Failed, a.messenger.sendText(bob, "should not go").status)
        assertTrue(a.texts(bob, MessageKind.System).any { it == Messenger.KEY_MISMATCH_NOTE })
    }

    @Test fun `my other devices get a copy of what I send`() = runTest {
        val a = Phone(server, alice, this); val a2 = Phone(server, alice, this); val b = Phone(server, bob, this)
        a.messenger.start(); a2.messenger.start(); b.messenger.start()
        connect(a, b, "bob"); a2.messenger.refreshContacts()
        a.messenger.sendText(bob, "from phone one")
        a2.messenger.sync()
        val copy = a2.dao.messages(bob).single()
        assertTrue(copy.outgoing)
        assertEquals("from phone one", copy.text)
    }

    @Test fun `a duplicate delivery is stored once`() = runTest {
        val a = Phone(server, alice, this); val b = Phone(server, bob, this)
        a.messenger.start(); b.messenger.start()
        connect(a, b, "bob")
        a.messenger.sendText(bob, "once")
        val copy = server.mailbox.single()
        b.messenger.sync()
        server.mailbox += copy   // the server (or a lost ack) delivers it again
        b.messenger.sync()
        assertEquals(listOf("once"), b.texts(alice))
    }

    @Test fun `photos round trip encrypted and a swapped blob is caught`() = runTest {
        val a = Phone(server, alice, this); val b = Phone(server, bob, this)
        a.messenger.start(); b.messenger.start()
        connect(a, b, "bob")
        val image = ByteArray(50_000) { (it % 255).toByte() }
        a.messenger.sendPhoto(bob, image, "image/jpeg", 100, 100)
        b.messenger.sync()
        val pointer = b.dao.messages(alice).single().attachment!!
        assertFalse(server.blobs.getValue(pointer.path).toList().windowed(64, 4096).any { it == image.toList().take(64) })
        assertTrue(image.contentEquals(b.messenger.openAttachment(pointer)))

        // A tampered blob on the server is rejected on a device that hasn't cached it yet.
        val c = Phone(server, bob, this)
        server.blobs[pointer.path] = server.blobs.getValue(pointer.path).copyOf().also { it[100] = (it[100] + 1).toByte() }
        val error = runCatching { c.messenger.openAttachment(pointer) }.exceptionOrNull()
        assertTrue(error is app.vynq.data.crypto.AttachmentCrypto.IntegrityException)
    }

    @Test fun `one-time keys are topped up when they run low`() = runTest {
        val b = Phone(server, bob, this)
        b.messenger.start()
        val dev = server.devices.getValue(b.deviceId)
        repeat(90) { dev.oneTime.removeFirst() }
        b.messenger.start()
        assertEquals(110, dev.oneTime.size)
    }

    // --- history across phones ---------------------------------------------------------------

    private suspend fun chat(a: Phone, b: Phone) {
        a.messenger.start(); b.messenger.start()
        connect(a, b, "bob")
        a.messenger.sendText(bob, "first from alice")
        b.messenger.sync()
        b.messenger.sendText(alice, "reply from bob")
        a.messenger.sync()
    }

    @Test fun `the first phone sets up history and backs every message up encrypted`() = runTest {
        val a = Phone(server, alice, this); val b = Phone(server, bob, this)
        chat(a, b)
        assertEquals(HistoryState.Ready, a.messenger.history.value)
        assertTrue(a.messenger.recoveryKeyUnseen())
        assertEquals(10, a.messenger.recoveryKeyText()!!.split(" ").size)
        val mine = server.history.filter { it.second == alice }
        assertEquals(2, mine.size)
        mine.forEach { (_, _, r) ->
            val bytes = Base64.getDecoder().decode(r.second)
            assertFalse(String(bytes, Charsets.ISO_8859_1).contains("alice"))
            assertFalse(String(bytes, Charsets.ISO_8859_1).contains("bob"))
        }
    }

    @Test fun `a new phone with the recovery key gets the whole history`() = runTest {
        val a = Phone(server, alice, this); val b = Phone(server, bob, this)
        chat(a, b)
        val recovery = a.messenger.recoveryKeyText()!!

        val newPhone = Phone(server, alice, this)        // old phone lost
        server.devices.remove(a.deviceId)
        newPhone.messenger.start()
        assertEquals(HistoryState.Locked, newPhone.messenger.history.value)
        newPhone.messenger.refreshContacts()
        assertFalse(newPhone.messenger.unlockWithRecoveryKey(HistoryCrypto.formatRecoveryKey(HistoryCrypto.newRecoveryKey())))
        assertFalse(newPhone.messenger.unlockWithRecoveryKey("not a key"))
        assertTrue(newPhone.messenger.unlockWithRecoveryKey(recovery.lowercase()))
        assertEquals(HistoryState.Ready, newPhone.messenger.history.value)
        assertEquals(listOf("first from alice", "reply from bob"), newPhone.dao.messages(bob).map { it.text })
        assertTrue(newPhone.dao.messages(bob).all { it.read })

        // It keeps backing up: a new message from it shows up for the next phone too.
        newPhone.messenger.sendText(bob, "from the new phone")
        val third = Phone(server, alice, this)
        third.messenger.start()
        third.messenger.unlockWithRecoveryKey(recovery)
        assertEquals(3, third.dao.messages(bob).size)
    }

    @Test fun `a new phone gets history after the old phone approves`() = runTest {
        val a = Phone(server, alice, this); val b = Phone(server, bob, this)
        chat(a, b)

        val newPhone = Phone(server, alice, this)
        newPhone.messenger.start()
        assertEquals(HistoryState.Locked, newPhone.messenger.history.value)
        assertEquals(1, newPhone.messenger.requestHistory())

        a.messenger.sync()                                  // the old phone sees the request
        val request = a.messenger.historyRequests.value.single()
        assertEquals(newPhone.deviceId, request.deviceId)
        a.messenger.approveHistoryRequest(request.deviceId)
        assertTrue(a.messenger.historyRequests.value.isEmpty())

        newPhone.messenger.sync()                           // receives the key, restores
        assertEquals(HistoryState.Ready, newPhone.messenger.history.value)
        assertEquals(listOf("first from alice", "reply from bob"), newPhone.dao.messages(bob).map { it.text })
        assertEquals(a.messenger.recoveryKeyText(), newPhone.messenger.recoveryKeyText())
    }

    @Test fun `nothing is handed over without approval, and other accounts can't ask`() = runTest {
        val a = Phone(server, alice, this); val b = Phone(server, bob, this)
        chat(a, b)
        val newPhone = Phone(server, alice, this)
        newPhone.messenger.start()
        newPhone.messenger.requestHistory()
        a.messenger.sync()
        a.messenger.dismissHistoryRequest(a.messenger.historyRequests.value.single().deviceId)
        newPhone.messenger.sync()
        assertEquals(HistoryState.Locked, newPhone.messenger.history.value)
        assertTrue(newPhone.dao.messages(bob).isEmpty())
        // Bob's phone has its own history; it never sees Alice's requests.
        assertTrue(b.messenger.historyRequests.value.isEmpty())
    }

    @Test fun `messages that arrive while a phone is locked are backed up after it unlocks`() = runTest {
        val a = Phone(server, alice, this); val b = Phone(server, bob, this)
        chat(a, b)
        val recovery = a.messenger.recoveryKeyText()!!
        server.devices.remove(a.deviceId)
        val newPhone = Phone(server, alice, this)
        newPhone.messenger.start(); newPhone.messenger.refreshContacts()
        b.messenger.sendText(alice, "while you were locked")
        newPhone.messenger.sync()
        newPhone.messenger.unlockWithRecoveryKey(recovery)
        assertEquals(3, newPhone.dao.messages(bob).size)
        assertEquals(3, server.history.count { it.second == alice })
    }

    // --- call signalling -----------------------------------------------------------------------

    @Test fun `a call invite reaches the contact's phones with the media key, end to end`() = runTest {
        val a = Phone(server, alice, this); val b = Phone(server, bob, this)
        a.messenger.start(); b.messenger.start()
        connect(a, b, "bob")
        val got = mutableListOf<CallSignal>()
        b.messenger.onCallSignal = { got += it }
        val key = "k".repeat(43)
        assertEquals(1, a.messenger.sendCallSignal(bob, "call_invite", "c-1", video = true, callKey = key))
        assertFalse(server.mailbox.any { String(it.body, Charsets.ISO_8859_1).contains(key) })   // key is inside the ciphertext
        b.messenger.sync()
        val s = got.single()
        assertEquals("call_invite", s.type); assertEquals("c-1", s.callId); assertEquals(alice, s.from)
        assertEquals(key, s.callKey); assertTrue(s.video); assertFalse(s.self)
        assertTrue(b.dao.messages(alice).isEmpty())   // signals are never shown as messages
    }

    @Test fun `answering tells my other phones to stop ringing`() = runTest {
        val a = Phone(server, alice, this); val b = Phone(server, bob, this); val b2 = Phone(server, bob, this)
        a.messenger.start(); b.messenger.start(); b2.messenger.start()
        connect(a, b, "bob"); b2.messenger.refreshContacts()
        val onB2 = mutableListOf<CallSignal>(); val onA = mutableListOf<CallSignal>()
        b2.messenger.onCallSignal = { onB2 += it }; a.messenger.onCallSignal = { onA += it }
        b.messenger.sendCallSignal(alice, "call_answer", "c-2", alsoMine = true)
        b2.messenger.sync(); a.messenger.sync()
        assertTrue(onB2.single().self)
        assertEquals("call_answer", onA.single().type)
    }

    @Test fun `strangers can't ring you`() = runTest {
        val a = Phone(server, alice, this); val b = Phone(server, bob, this)
        a.messenger.start(); b.messenger.start()
        a.messenger.requestContact("bob")   // not accepted
        val got = mutableListOf<CallSignal>()
        b.messenger.onCallSignal = { got += it }
        assertEquals(0, runCatching { a.messenger.sendCallSignal(bob, "call_invite", "c-3", callKey = "x") }.getOrDefault(0))
        b.messenger.sync()
        assertTrue(got.isEmpty())
    }
}
