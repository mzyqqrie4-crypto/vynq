package app.vynq.data.auth

import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Talks to Supabase Auth (GoTrue) and PostgREST over plain HTTPS. A thin client
 * on purpose: the API surface is small (backend/README.md) and this keeps the
 * dependency footprint and the replaceability promise intact.
 *
 * Never logs tokens, codes or email addresses.
 */
class SupabaseAuthRepository(
    baseUrl: String,
    private val anonKey: String,
    private val store: SessionStore,
    private val device: DeviceIdentity,
    private val http: OkHttpClient = OkHttpClient(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) : AccountService {

    private val base = baseUrl.trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true }
    private val refreshLock = Mutex()

    @Volatile private var session: Session? = null
    private val _state = MutableStateFlow<AuthState>(AuthState.Loading)
    override val state: StateFlow<AuthState> = _state.asStateFlow()

    /** Reads the stored session. Call once at startup, off the main thread. */
    suspend fun restore() {
        val restored = withContext(io) { store.load()?.also { device.bind(it.userId) } }
        session = restored
        publish()
    }

    // --- sign-in ---------------------------------------------------------------

    override suspend fun requestEmailCode(email: String): Result<Unit> = runCatching<Unit> {
        call("POST", "/auth/v1/otp", buildJsonObject {
            put("email", email)
            put("create_user", true)
        })
    }

    override suspend fun verifyEmailCode(email: String, code: String): Result<Unit> = runCatching {
        val body = call("POST", "/auth/v1/verify", buildJsonObject {
            put("type", "email")
            put("email", email)
            put("token", code)
        })
        setSession(parseSession(body))
    }

    override suspend fun signOut(eraseThisDevice: Boolean) {
        val userId = session?.userId
        // Never block sign-out on the network, but try, in this order:
        // 1. Erasing only: retire this device, since its keys are about to be
        //    deleted (the server also drops its undelivered mail). A normal sign-out
        //    keeps the device so mail waits for the next sign-in.
        // 2. Revoke the refresh token so a copied one is useless.
        runCatching {
            val token = accessToken()
            if (eraseThisDevice) runCatching {
                call("POST", "/rest/v1/rpc/revoke_device", buildJsonObject { put("p_device_id", device.id) }, token)
            }
            call("POST", "/auth/v1/logout?scope=local", null, token = token)
        }
        if (eraseThisDevice && userId != null) withContext(io) { device.forget(userId) }
        forgetSession()
    }

    override suspend fun forgetSession() {
        session = null
        withContext(io) { store.clear() }
        publish()
    }

    override suspend fun accessToken(): String {
        val current = session ?: throw AccountError.SessionExpired()
        if (current.expiresAt - nowSeconds() > REFRESH_MARGIN_S) return current.accessToken

        // Single flight: refresh tokens rotate, so two parallel refreshes would
        // look like token reuse to the server and revoke the whole session.
        return refreshLock.withLock {
            val latest = session ?: throw AccountError.SessionExpired()
            if (latest.expiresAt - nowSeconds() > REFRESH_MARGIN_S) return@withLock latest.accessToken
            try {
                val body = call("POST", "/auth/v1/token?grant_type=refresh_token", buildJsonObject {
                    put("refresh_token", latest.refreshToken)
                })
                parseSession(body).also { setSession(it) }.accessToken
            } catch (e: AccountError) {
                val dead = e is AccountError.SessionExpired || e is AccountError.InvalidCode ||
                    (e is AccountError.Server && e.status in 400..403)
                if (dead) {
                    forgetSession()
                    throw AccountError.SessionExpired()
                }
                throw e // offline or server trouble: keep the session and try later
            }
        }
    }

    // --- account ---------------------------------------------------------------

    override suspend fun profile(): Result<Profile> = authed { token ->
        val userId = session?.userId ?: throw AccountError.SessionExpired()
        val body = call(
            "GET", "/rest/v1/profiles?select=username,display_name,discoverable&user_id=eq.$userId",
            null, token, accept = "application/vnd.pgrst.object+json",
        )
        val o = json.parseToJsonElement(body).jsonObject
        Profile(
            username = o.string("username"),
            displayName = o.string("display_name"),
            discoverable = (o["discoverable"] as? JsonPrimitive)?.booleanOrNull ?: true,
        )
    }

    override suspend fun claimUsername(username: String): Result<String> = authed { token ->
        val body = call("POST", "/rest/v1/rpc/claim_username", buildJsonObject {
            put("p_username", UsernameRules.normalize(username))
        }, token)
        json.parseToJsonElement(body).jsonPrimitive.content
    }

    override suspend fun registerThisDevice(): Result<Unit> = authed<Unit> { token ->
        call("POST", "/rest/v1/rpc/register_device", buildJsonObject {
            put("p_device_id", device.id)
            put("p_platform", "android")
            put("p_name", device.name)
        }, token)
    }

    // --- messaging backend ---------------------------------------------------------

    override val deviceId: String get() = device.id
    override val deviceName: String get() = device.name

    override val realtimeUrl: String =
        base.replaceFirst("https://", "wss://") + "/realtime/v1/websocket?apikey=$anonKey&vsn=1.0.0"

    override suspend fun rpc(function: String, args: JsonObject): JsonElement = authed { token ->
        val body = call("POST", "/rest/v1/rpc/$function", args, token)
        if (body.isBlank()) JsonNull else json.parseToJsonElement(body)
    }.getOrThrow()

    override suspend fun upload(path: String, bytes: ByteArray) = authed<Unit> { token ->
        send(Request.Builder().url("$base/storage/v1/object/attachments/$path")
            .post(bytes.toRequestBody(BLOB_TYPE)).withAuth(token).build())
    }.getOrThrow()

    override suspend fun download(path: String): ByteArray = authed { token ->
        send(Request.Builder().url("$base/storage/v1/object/attachments/$path").get().withAuth(token).build())
    }.getOrThrow()

    override suspend fun deleteAccount(): Result<Unit> = authed { token ->
        val userId = session?.userId
        call("POST", "/functions/v1/delete-account", JsonObject(emptyMap()), token)
        if (userId != null) withContext(io) { device.forget(userId) }
        forgetSession()
    }

    // --- plumbing --------------------------------------------------------------

    private suspend fun <T> authed(block: suspend (String) -> T): Result<T> = runCatching {
        try {
            block(accessToken())
        } catch (e: AccountError.SessionExpired) {
            forgetSession() // the server no longer accepts this session
            throw e
        }
    }

    private suspend fun setSession(s: Session) {
        withContext(io) { device.bind(s.userId); store.save(s) }
        session = s
        publish()
    }

    private fun publish() {
        _state.value = session?.let { AuthState.SignedIn(it.userId, it.email) } ?: AuthState.SignedOut
    }

    private fun parseSession(body: String): Session {
        val o = json.parseToJsonElement(body).jsonObject
        val user = o["user"]?.jsonObject ?: throw AccountError.Server(200, "missing_user")
        val access = o.string("access_token") ?: throw AccountError.Server(200, "missing_token")
        val expiresAt = (o["expires_at"] as? JsonPrimitive)?.long
            ?: (nowSeconds() + ((o["expires_in"] as? JsonPrimitive)?.long ?: 3600))
        return Session(
            accessToken = access,
            refreshToken = o.string("refresh_token") ?: throw AccountError.Server(200, "missing_refresh"),
            expiresAt = expiresAt,
            userId = user.string("id") ?: throw AccountError.Server(200, "missing_user_id"),
            email = user.string("email").orEmpty(),
        )
    }

    private suspend fun call(
        method: String,
        path: String,
        body: JsonElement?,
        token: String? = null,
        accept: String = "application/json",
    ): String = withContext(io) {
        val request = Request.Builder()
            .url(base + path)
            // Publishable keys go in `apikey` only; Authorization carries a user's JWT.
            .header("apikey", anonKey)
            .apply { if (token != null) header("Authorization", "Bearer $token") }
            .header("Accept", accept)
            .method(method, body?.toString()?.toRequestBody(JSON_TYPE) ?: if (method == "GET") null else EMPTY_BODY)
            .build()
        try {
            http.newCall(request).execute().use { response ->
                val text = response.body.string()
                if (!response.isSuccessful) throw errorFor(response.code, text)
                text
            }
        } catch (e: IOException) {
            throw AccountError.Network()
        }
    }

    private fun Request.Builder.withAuth(token: String) =
        header("apikey", anonKey).header("Authorization", "Bearer $token")

    private suspend fun send(request: Request): ByteArray = withContext(io) {
        try {
            http.newCall(request).execute().use { response ->
                val bytes = response.body.bytes()
                if (!response.isSuccessful) throw errorFor(response.code, bytes.decodeToString())
                bytes
            }
        } catch (e: IOException) {
            throw AccountError.Network()
        }
    }

    private fun errorFor(status: Int, body: String): AccountError {
        val o = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
        // GoTrue: {"error_code": "...", "msg": "..."}; PostgREST/RPC: {"code": "PT409", "message": "username_taken"}
        val code = o?.string("error_code") ?: o?.string("message") ?: o?.string("error")
        return when (code) {
            "otp_expired", "invalid_credentials", "otp_disabled" -> AccountError.InvalidCode()
            "invalid_username" -> AccountError.InvalidUsername()
            "username_taken" -> AccountError.UsernameTaken()
            "username_reserved" -> AccountError.UsernameReserved()
            "device_limit" -> AccountError.DeviceLimit()
            "device_unavailable" -> AccountError.DeviceUnavailable()
            "rate_limited" -> AccountError.RateLimited()
            else -> when {
                status == 429 || code?.startsWith("over_") == true -> AccountError.RateLimited()
                status == 401 -> AccountError.SessionExpired()
                else -> AccountError.Server(status, code)
            }
        }
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private companion object {
        val JSON_TYPE = "application/json".toMediaType()
        val BLOB_TYPE = "application/octet-stream".toMediaType()
        val EMPTY_BODY = ByteArray(0).toRequestBody(null)
        const val REFRESH_MARGIN_S = 60
    }
}
