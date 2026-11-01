package app.vynq.data.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Account boundary. UI and domain code depend on these interfaces only. No backend
 * SDK types leak past them, which keeps Supabase replaceable (architecture §4).
 */
interface AuthRepository {
    val state: StateFlow<AuthState>

    /** Sends a one-time sign-in code to [email]. No phone number, no password. */
    suspend fun requestEmailCode(email: String): Result<Unit>

    /** Exchanges the emailed code for a session, stored encrypted on this device. */
    suspend fun verifyEmailCode(email: String, code: String): Result<Unit>

    /**
     * Revokes the refresh token on the server (best effort) and clears the local
     * session. This phone stays the same device for this account, so its
     * messages are kept for the next sign-in. With [eraseThisDevice] the device
     * is removed from the account and its local data is deleted instead.
     */
    suspend fun signOut(eraseThisDevice: Boolean = false)

    /** A non-expired access token, refreshing first if needed. Signs out if the session is dead. */
    suspend fun accessToken(): String

    /** Clears the local session without a network call (expired session, deleted account). */
    suspend fun forgetSession()
}

sealed interface AuthState {
    data object Loading : AuthState
    data object SignedOut : AuthState
    data class SignedIn(val userId: String, val email: String) : AuthState
}

data class Profile(val username: String?, val displayName: String?, val discoverable: Boolean)

interface AccountRepository {
    suspend fun profile(): Result<Profile>
    suspend fun claimUsername(username: String): Result<String>
    suspend fun registerThisDevice(): Result<Unit>
    suspend fun deleteAccount(): Result<Unit>
}

/**
 * Signed-in calls for the messaging layer. Throws [AccountError] on failure.
 * Arguments and results are plain JSON so no backend types leak upward.
 */
interface BackendApi {
    /** This install's device id (see [DeviceIdentity]). */
    val deviceId: String
    /** What the user calls this phone ("Xiaomi 17 Pro"), for prompts on their other phones. */
    val deviceName: String
    /** Realtime websocket endpoint, without credentials beyond the public key. */
    val realtimeUrl: String
    suspend fun rpc(function: String, args: JsonObject): JsonElement
    suspend fun upload(path: String, bytes: ByteArray)
    /** Calls a signed-in Edge Function with a JSON body. */
    suspend fun function(name: String, args: JsonObject): JsonElement
    suspend fun download(path: String): ByteArray
}

/** One backend usually provides all three. */
interface AccountService : AuthRepository, AccountRepository, BackendApi

/** Everything that can go wrong at the account boundary, in terms the UI can explain. */
sealed class AccountError(message: String) : Exception(message) {
    class NotConfigured : AccountError("The vynq account service isn't connected in this build.")
    class Network : AccountError("network")
    class InvalidCode : AccountError("invalid_code")
    class RateLimited : AccountError("rate_limited")
    class SessionExpired : AccountError("session_expired")
    class InvalidUsername : AccountError("invalid_username")
    class UsernameTaken : AccountError("username_taken")
    class UsernameReserved : AccountError("username_reserved")
    class DeviceLimit : AccountError("device_limit")
    class DeviceUnavailable : AccountError("device_unavailable")
    class Server(val status: Int, val code: String?) : AccountError("server $status ${code.orEmpty()}")
}

/** Used when the build has no backend endpoint: says so honestly, never fakes success. */
class UnconfiguredAuthRepository : AccountService {
    private val _state = MutableStateFlow<AuthState>(AuthState.SignedOut)
    override val state: StateFlow<AuthState> = _state.asStateFlow()

    private fun <T> unavailable(): Result<T> = Result.failure(AccountError.NotConfigured())

    override suspend fun requestEmailCode(email: String) = unavailable<Unit>()
    override suspend fun verifyEmailCode(email: String, code: String) = unavailable<Unit>()
    override suspend fun signOut(eraseThisDevice: Boolean) = Unit
    override suspend fun accessToken(): String = throw AccountError.NotConfigured()
    override suspend fun forgetSession() = Unit
    override suspend fun profile() = unavailable<Profile>()
    override suspend fun claimUsername(username: String) = unavailable<String>()
    override suspend fun registerThisDevice() = unavailable<Unit>()
    override suspend fun deleteAccount() = unavailable<Unit>()
    override val deviceId = ""
    override val deviceName = ""
    override val realtimeUrl = ""
    override suspend fun rpc(function: String, args: JsonObject): JsonElement = throw AccountError.NotConfigured()
    override suspend fun upload(path: String, bytes: ByteArray) = throw AccountError.NotConfigured()
    override suspend fun function(name: String, args: JsonObject): JsonElement = throw AccountError.NotConfigured()
    override suspend fun download(path: String): ByteArray = throw AccountError.NotConfigured()
}

object EmailValidator {
    // Deliberately permissive: the server is the source of truth. This only
    // catches obvious typos before a network round-trip.
    private val pattern = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]{2,}$")

    fun normalize(input: String): String = input.trim().lowercase()

    fun isValid(input: String): Boolean {
        val email = normalize(input)
        return email.length in 6..254 && pattern.matches(email)
    }
}

/** Mirrors the server rule in the migration so most mistakes are caught before a round-trip. */
object UsernameRules {
    private val pattern = Regex("^[a-z0-9][a-z0-9._]{1,18}[a-z0-9]$")
    private val doubleSeparator = Regex("[._]{2}")

    fun normalize(input: String): String = input.trim().lowercase()

    /** Null when valid, otherwise a short explanation. */
    fun problem(input: String): String? {
        val u = normalize(input)
        return when {
            u.length < 3 -> "At least 3 characters"
            u.length > 20 -> "At most 20 characters"
            u.any { it !in 'a'..'z' && it !in '0'..'9' && it != '.' && it != '_' } -> "Letters, numbers, . and _ only"
            !u.first().isLetterOrDigit() || !u.last().isLetterOrDigit() -> "Start and end with a letter or number"
            doubleSeparator.containsMatchIn(u) -> "No two . or _ in a row"
            !pattern.matches(u) -> "Not a valid username"
            else -> null
        }
    }
}

/** One plain sentence per error, for alerts and inline messages. */
fun Throwable.userMessage(): String = when (this) {
    is AccountError.NotConfigured -> "This build isn't connected to the vynq account service yet, so nothing was sent."
    is AccountError.Network -> "We couldn't reach vynq. Check your connection and try again."
    is AccountError.InvalidCode -> "That code is wrong or has expired. Check the latest email or send a new code."
    is AccountError.RateLimited -> "Too many attempts. Wait a minute and try again."
    is AccountError.SessionExpired -> "You've been signed out. Sign in again to continue."
    is AccountError.InvalidUsername -> "That username isn't allowed. Use 3–20 letters, numbers, . or _."
    is AccountError.UsernameTaken -> "That username, or one that looks just like it, is taken."
    is AccountError.UsernameReserved -> "That username is reserved."
    is AccountError.DeviceLimit -> "You already have 5 devices. Remove one in Settings first."
    is AccountError.DeviceUnavailable -> "This device was removed from your account. Reinstall vynq to register it again."
    is AccountError.Server -> when (code) {
        "user_not_found" -> "No one with that exact username is on vynq, or they've turned off being found."
        "cannot_add_self" -> "That's your own username."
        "not_a_contact", "request_not_found" -> "You're not connected with this person anymore."
        else -> "Something went wrong on our side. Try again in a moment."
    }
    else -> "Something went wrong on our side. Try again in a moment."
}
