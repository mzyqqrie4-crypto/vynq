package app.vynq.data.auth

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SupabaseAuthRepositoryTest {
    private val server = MockWebServer()
    private val store = MemoryStore()
    private var now = 1_000_000L

    private val device = object : DeviceIdentity {
        override val id = "6f1f2c1e-0000-4000-8000-000000000001"
        override val name = "Test Phone"
    }

    @Before fun start() = server.start()
    @After fun stop() = server.close()

    private fun repo(dispatcher: kotlinx.coroutines.CoroutineDispatcher) = SupabaseAuthRepository(
        baseUrl = server.url("/").toString(),
        anonKey = "anon-test-key",
        store = store,
        device = device,
        io = dispatcher,
        nowSeconds = { now },
    )

    private fun json(code: Int, body: String) =
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

    private fun tokenBody(access: String = "access-1", refresh: String = "refresh-1", expiresAt: Long = now + 3600) =
        """{"access_token":"$access","refresh_token":"$refresh","expires_at":$expiresAt,"expires_in":3600,
           "user":{"id":"user-1","email":"alice@example.test"}}"""

    @Test fun `requesting a code posts the email with the anon key`() = runTest {
        server.enqueue(json(200, "{}"))
        val result = repo(StandardTestDispatcher(testScheduler)).requestEmailCode("alice@example.test")
        assertTrue(result.isSuccess)
        val req = server.takeRequest()
        assertEquals("/auth/v1/otp", req.url.encodedPath)
        assertEquals("anon-test-key", req.headers["apikey"])
        assertTrue(req.body!!.utf8().contains("\"email\":\"alice@example.test\""))
    }

    @Test fun `verifying a code stores the session and signs in`() = runTest {
        server.enqueue(json(200, tokenBody()))
        val repo = repo(StandardTestDispatcher(testScheduler))
        repo.restore()
        assertEquals(AuthState.SignedOut, repo.state.value)

        assertTrue(repo.verifyEmailCode("alice@example.test", "123456").isSuccess)
        assertEquals(AuthState.SignedIn("user-1", "alice@example.test"), repo.state.value)
        assertEquals("refresh-1", store.session?.refreshToken)
        assertTrue(server.takeRequest().body!!.utf8().contains("\"type\":\"email\""))
    }

    @Test fun `a wrong code is reported as InvalidCode and stays signed out`() = runTest {
        server.enqueue(json(403, """{"code":403,"error_code":"otp_expired","msg":"Token has expired or is invalid"}"""))
        val repo = repo(StandardTestDispatcher(testScheduler))
        repo.restore()
        val error = repo.verifyEmailCode("alice@example.test", "000000").exceptionOrNull()
        assertTrue(error is AccountError.InvalidCode)
        assertEquals(AuthState.SignedOut, repo.state.value)
    }

    @Test fun `concurrent callers share a single refresh`() = runTest {
        store.session = Session("old", "refresh-1", now + 10, "user-1", "alice@example.test")
        server.enqueue(json(200, tokenBody(access = "access-2", refresh = "refresh-2")))
        val repo = repo(StandardTestDispatcher(testScheduler))
        repo.restore()

        val tokens = List(5) { async { repo.accessToken() } }.awaitAll()
        assertEquals(List(5) { "access-2" }, tokens)
        assertEquals(1, server.requestCount)
        assertEquals("refresh-2", store.session?.refreshToken)
    }

    @Test fun `a rejected refresh token signs out and wipes the session`() = runTest {
        store.session = Session("old", "stolen", now - 5, "user-1", "alice@example.test")
        server.enqueue(json(400, """{"code":400,"error_code":"refresh_token_not_found","msg":"Invalid Refresh Token"}"""))
        val repo = repo(StandardTestDispatcher(testScheduler))
        repo.restore()

        val error = runCatching { repo.accessToken() }.exceptionOrNull()
        assertTrue(error is AccountError.SessionExpired)
        assertNull(store.session)
        assertEquals(AuthState.SignedOut, repo.state.value)
    }

    @Test fun `being offline keeps the session`() = runTest {
        store.session = Session("old", "refresh-1", now - 5, "user-1", "alice@example.test")
        val repo = repo(StandardTestDispatcher(testScheduler))
        repo.restore()
        server.close()

        val error = runCatching { repo.accessToken() }.exceptionOrNull()
        assertTrue(error is AccountError.Network)
        assertEquals("refresh-1", store.session?.refreshToken)
        assertTrue(repo.state.value is AuthState.SignedIn)
    }

    @Test fun `username errors from the RPC map to typed errors`() = runTest {
        store.session = Session("access-1", "refresh-1", now + 3600, "user-1", "alice@example.test")
        server.enqueue(json(409, """{"code":"PT409","details":null,"hint":null,"message":"username_taken"}"""))
        server.enqueue(json(409, """{"code":"PT409","details":null,"hint":null,"message":"username_reserved"}"""))
        server.enqueue(json(200, "\"alice\""))
        val repo = repo(StandardTestDispatcher(testScheduler))
        repo.restore()

        assertTrue(repo.claimUsername("alice").exceptionOrNull() is AccountError.UsernameTaken)
        assertTrue(repo.claimUsername("admin").exceptionOrNull() is AccountError.UsernameReserved)
        assertEquals("alice", repo.claimUsername("Alice").getOrThrow())

        val req = server.takeRequest()
        assertEquals("/rest/v1/rpc/claim_username", req.url.encodedPath)
        assertEquals("Bearer access-1", req.headers["Authorization"])
    }

    @Test fun `device registration sends this install's id`() = runTest {
        store.session = Session("access-1", "refresh-1", now + 3600, "user-1", "alice@example.test")
        server.enqueue(json(200, ""))
        val repo = repo(StandardTestDispatcher(testScheduler))
        repo.restore()

        assertTrue(repo.registerThisDevice().isSuccess)
        val body = server.takeRequest().body!!.utf8()
        assertTrue(body.contains(device.id))
        assertTrue(body.contains("\"p_platform\":\"android\""))
    }

    @Test fun `sign out revokes on the server and clears locally even if the server fails`() = runTest {
        store.session = Session("access-1", "refresh-1", now + 3600, "user-1", "alice@example.test")
        server.enqueue(json(500, "{}"))
        val repo = repo(StandardTestDispatcher(testScheduler))
        repo.restore()

        repo.signOut()
        val req = server.takeRequest()
        assertEquals("/auth/v1/logout", req.url.encodedPath)
        assertEquals("Bearer access-1", req.headers["Authorization"])
        assertNull(store.session)
        assertEquals(AuthState.SignedOut, repo.state.value)
    }

    @Test fun `deleting the account clears the session`() = runTest {
        store.session = Session("access-1", "refresh-1", now + 3600, "user-1", "alice@example.test")
        server.enqueue(json(200, """{"deleted":true}"""))
        val repo = repo(StandardTestDispatcher(testScheduler))
        repo.restore()

        assertTrue(repo.deleteAccount().isSuccess)
        assertEquals("/functions/v1/delete-account", server.takeRequest().url.encodedPath)
        assertNull(store.session)
    }

    @Test fun `a 401 from the API ends the session`() = runTest {
        store.session = Session("access-1", "refresh-1", now + 3600, "user-1", "alice@example.test")
        server.enqueue(json(401, """{"code":"PGRST301","message":"JWT expired"}"""))
        val repo = repo(StandardTestDispatcher(testScheduler))
        repo.restore()

        assertTrue(repo.profile().exceptionOrNull() is AccountError.SessionExpired)
        assertEquals(AuthState.SignedOut, repo.state.value)
    }

    private class MemoryStore : SessionStore {
        var session: Session? = null
        override fun load() = session
        override fun save(session: Session) { this.session = session }
        override fun clear() { session = null }
    }
}

class UsernameRulesTest {
    @Test fun `valid usernames pass`() {
        for (name in listOf("bob", "alice.smith", "a_b", "x1y2z3", "  Mixed.Case ")) {
            assertNull(name, UsernameRules.problem(name))
        }
    }

    @Test fun `invalid usernames explain why`() {
        mapOf(
            "ab" to "At least 3 characters",
            "a".repeat(21) to "At most 20 characters",
            "al-ice" to "Letters, numbers, . and _ only",
            "älice" to "Letters, numbers, . and _ only",
            ".alice" to "Start and end with a letter or number",
            "al..ice" to "No two . or _ in a row",
        ).forEach { (input, expected) -> assertEquals(input, expected, UsernameRules.problem(input)) }
    }
}
