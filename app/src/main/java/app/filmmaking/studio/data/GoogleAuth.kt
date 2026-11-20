package app.filmmaking.studio.data

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Pure (Android-free, unit-tested) pieces of native Google Sign-In.
 *
 * Flow (Supabase native Google sign-in): the app makes a random raw nonce, gives Google its SHA-256 hex
 * through Credential Manager (so it is embedded in the ID token), then sends the ID token plus the RAW nonce
 * to Supabase Auth (`grant_type=id_token`). Supabase hashes the raw nonce and must find it in the token,
 * so a token captured from another app or replayed cannot be exchanged. "Skip nonce checks" stays OFF.
 */
object GoogleAuthConfig {
    private val clientId = Regex("^[0-9]+-[a-z0-9]+\\.apps\\.googleusercontent\\.com$")

    /** The OAuth Web client ID if it is well formed; never a client secret (`GOCSPX-…`). */
    fun webClientIdOrNull(raw: String?): String? = raw?.trim()?.takeIf { clientId.matches(it) }
}

data class GoogleNonce(val raw: String, val hashed: String) {
    companion object {
        fun create(random: SecureRandom = SecureRandom()): GoogleNonce {
            val bytes = ByteArray(32).also(random::nextBytes)
            return from(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes))
        }
        fun from(raw: String) = GoogleNonce(raw, sha256Hex(raw))
        fun sha256Hex(value: String): String =
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}

/** Plain-language messages for Supabase's id_token errors (the raw text goes to logs only). */
object GoogleAuthErrors {
    fun explain(status: Int, serverMessage: String): String {
        val m = serverMessage.lowercase()
        return when {
            "provider is not enabled" in m || "unsupported provider" in m ->
                "Google sign-in is not switched on for this Studio account service yet. Use email and password for now."
            "audience" in m ->
                "This app's Google client ID is not on the Supabase Google provider's Client IDs list yet."
            "nonce" in m -> "Google sign-in could not be verified (nonce mismatch). Please try again."
            "expired" in m || "issued in the future" in m -> "The Google sign-in expired. Check the phone's date and time, then try again."
            status == 429 -> "Too many sign-in attempts. Wait a minute and try again."
            status >= 500 -> "The sign-in service is unavailable right now. Your work stays on this phone; try again later."
            else -> "Google sign-in was refused: " + serverMessage.take(160)
        }
    }
}
