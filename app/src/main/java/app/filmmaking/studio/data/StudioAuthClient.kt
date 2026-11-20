package app.filmmaking.studio.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Sign-in uses the EXISTING Supabase Auth project. Access tokens are memory-only. */
class StudioAuthClient {
    data class Session(val accessToken: String, val email: String)

    suspend fun signIn(email: String, password: String): Session = withContext(Dispatchers.IO) {
        val url = URL(StudioConfig.SUPABASE_URL.trimEnd('/') + "/auth/v1/token?grant_type=password")
        val c = url.openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 12000
            c.readTimeout = 15000
            c.doOutput = true
            c.setRequestProperty("apikey", StudioConfig.SUPABASE_PUBLISHABLE_KEY)
            c.setRequestProperty("Content-Type", "application/json")
            val body = JSONObject().put("email", email.trim()).put("password", password).toString()
            c.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }
            val response = (if (c.responseCode in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            val parsed = runCatching { JSONObject(response) }.getOrNull()
            if (c.responseCode !in 200..299) {
                val reason = parsed?.optString("msg")?.ifBlank { null }
                    ?: parsed?.optString("error_description")?.ifBlank { null }
                    ?: parsed?.optString("message")?.ifBlank { null }
                    ?: "Sign-in failed (HTTP " + c.responseCode + ")"
                throw IllegalStateException(reason.take(220))
            }
            val token = parsed?.optString("access_token").orEmpty()
            if (token.split(".").size != 3) throw IllegalStateException("Supabase returned no valid access token")
            Session(token, email.trim())
        } finally { c.disconnect() }
    }
}
