package app.filmmaking.studio.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Sign-in uses the EXISTING Supabase Auth project.
 * The refresh token is kept in app-private storage (allowBackup=false) so sessions survive
 * restarts and background sync can renew the short-lived access token.
 */
class StudioAuthClient(
    private val baseUrl: String = StudioConfig.SUPABASE_URL,
    private val apiKey: String = StudioConfig.SUPABASE_PUBLISHABLE_KEY
) {
    data class Session(
        val accessToken: String,
        val email: String,
        val refreshToken: String = "",
        val expiresAtMs: Long = 0L,
        val userId: String = "",
        /** "email" or "google": how this device signed in (display only; the account is userId). */
        val provider: String = "email"
    ) {
        fun expiringSoon(now: Long = System.currentTimeMillis()) = expiresAtMs - now < 90_000L
    }

    suspend fun signIn(email: String, password: String): Session =
        token("password", JSONObject().put("email", email.trim()).put("password", password), email.trim())

    /**
     * Supabase native Google sign-in: exchange a Google ID token (audience = the OAuth Web client ID) for a
     * Supabase session. [rawNonce] is the value whose SHA-256 was given to Google; Supabase checks the pair.
     */
    suspend fun signInWithGoogle(idToken: String, rawNonce: String): Session = try {
        token("id_token", idTokenBody(idToken, rawNonce), "", provider = "google")
    } catch (e: AuthException) {
        throw AuthException(e.status, GoogleAuthErrors.explain(e.status, e.message.orEmpty()))
    }

    suspend fun refresh(old: Session): Session {
        require(old.refreshToken.isNotBlank()) { "Session expired: sign in again" }
        return token("refresh_token", JSONObject().put("refresh_token", old.refreshToken), old.email, old.provider)
    }

    /** The account the server says this access token belongs to (`GET /auth/v1/user`). */
    suspend fun user(accessToken: String): JSONObject = withContext(Dispatchers.IO) {
        val (code, text) = http("GET", "/auth/v1/user", null, accessToken)
        if (code !in 200..299) throw AuthException(code, "Could not confirm the signed-in account (HTTP $code)")
        JSONObject(text)
    }

    /** Revokes this device's refresh token on the server (`scope=local`: other devices stay signed in). */
    suspend fun logout(accessToken: String) = withContext(Dispatchers.IO) {
        runCatching { http("POST", "/auth/v1/logout?scope=local", "{}", accessToken) }
        Unit
    }

    private fun http(method: String, path: String, body: String?, bearer: String): Pair<Int, String> {
        val c = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 12000
            c.readTimeout = 15000
            c.setRequestProperty("apikey", apiKey)
            c.setRequestProperty("Authorization", "Bearer $bearer")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }
            }
            val text = (if (c.responseCode in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            return c.responseCode to text
        } finally { c.disconnect() }
    }

    private suspend fun token(grant: String, body: JSONObject, email: String, provider: String = "email"): Session = withContext(Dispatchers.IO) {
        val url = URL(baseUrl.trimEnd('/') + "/auth/v1/token?grant_type=" + grant)
        val c = url.openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 12000
            c.readTimeout = 15000
            c.doOutput = true
            c.setRequestProperty("apikey", apiKey)
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body.toString()) }
            val response = (if (c.responseCode in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            val parsed = runCatching { JSONObject(response) }.getOrNull()
            if (c.responseCode !in 200..299) {
                val reason = parsed?.optString("msg")?.ifBlank { null }
                    ?: parsed?.optString("error_description")?.ifBlank { null }
                    ?: parsed?.optString("message")?.ifBlank { null }
                    ?: "Sign-in failed (HTTP " + c.responseCode + ")"
                throw AuthException(c.responseCode, reason.take(220))
            }
            val tokenValue = parsed?.optString("access_token").orEmpty()
            if (tokenValue.split(".").size != 3) throw IllegalStateException("Supabase returned no valid access token")
            val expiresIn = parsed?.optLong("expires_in", 3600L) ?: 3600L
            Session(
                accessToken = tokenValue,
                email = parsed?.optJSONObject("user")?.optString("email")?.ifBlank { null } ?: email,
                refreshToken = parsed?.optString("refresh_token").orEmpty(),
                expiresAtMs = System.currentTimeMillis() + expiresIn * 1000L,
                userId = parsed?.optJSONObject("user")?.optString("id").orEmpty(),
                provider = provider
            )
        } finally { c.disconnect() }
    }

    companion object {
        fun idTokenBody(idToken: String, rawNonce: String): JSONObject =
            JSONObject().put("provider", "google").put("id_token", idToken).put("nonce", rawNonce)
    }
}

class AuthException(val status: Int, message: String) : Exception(message)

/** One persisted session per device; access tokens are refreshed on demand. */
class SessionStore(context: Context, private val auth: StudioAuthClient) {
    private val prefs = context.applicationContext.getSharedPreferences("studio_session", Context.MODE_PRIVATE)
    private val lock = Mutex()

    fun load(): StudioAuthClient.Session? {
        val token = prefs.getString("access", null) ?: return null
        return StudioAuthClient.Session(
            accessToken = token,
            email = prefs.getString("email", "").orEmpty(),
            refreshToken = prefs.getString("refresh", "").orEmpty(),
            expiresAtMs = prefs.getLong("expires", 0L),
            userId = prefs.getString("user", "").orEmpty(),
            provider = prefs.getString("provider", "email").orEmpty()
        )
    }

    fun save(s: StudioAuthClient.Session) {
        prefs.edit().putString("access", s.accessToken).putString("email", s.email)
            .putString("refresh", s.refreshToken).putLong("expires", s.expiresAtMs)
            .putString("user", s.userId).putString("provider", s.provider).apply()
    }

    /** Signs out locally; the device id is kept so the cloud can still tell devices apart. */
    fun clear() {
        prefs.edit().remove("access").remove("email").remove("refresh").remove("expires").remove("user").remove("provider").apply()
    }

    /** A valid access token, refreshing it first when it is about to expire. */
    suspend fun fresh(): StudioAuthClient.Session? = lock.withLock {
        val current = load() ?: return@withLock null
        if (!current.expiringSoon()) return@withLock current
        try {
            auth.refresh(current).also(::save)
        } catch (e: AuthException) {
            // Revoked or expired refresh token: the user has to sign in again.
            if (e.status in 400..499) clear()
            throw e
        }
    }

    val deviceId: String
        get() = prefs.getString("device", null) ?: java.util.UUID.randomUUID().toString().also {
            prefs.edit().putString("device", it).apply()
        }
}
