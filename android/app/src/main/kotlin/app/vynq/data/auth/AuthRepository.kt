package app.vynq.data.auth

/**
 * Account boundary. UI and domain code depend on this interface only. No backend
 * SDK types leak past it, which keeps Supabase replaceable (architecture §4).
 */
interface AuthRepository {
    /** Sends a one-time sign-in code to [email]. No phone number, no password. */
    suspend fun requestEmailCode(email: String): Result<Unit>
}

class AuthNotConfiguredException :
    IllegalStateException("The vynq account service isn't connected in this build yet.")

/** Phase 1 implementation: says honestly that no backend is configured. */
class UnconfiguredAuthRepository : AuthRepository {
    override suspend fun requestEmailCode(email: String): Result<Unit> =
        Result.failure(AuthNotConfiguredException())
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
