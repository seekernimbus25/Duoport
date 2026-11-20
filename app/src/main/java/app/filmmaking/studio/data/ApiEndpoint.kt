package app.filmmaking.studio.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Only public backend URL is saved on device; never save OpenAI/Anthropic secrets here. */
class ApiEndpoint(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("studio_config", Context.MODE_PRIVATE)
    var baseUrl: String
        get() = prefs.getString("api_origin", StudioConfig.DEFAULT_AI_API) ?: StudioConfig.DEFAULT_AI_API
        set(value) {
            val clean = value.trim().trimEnd('/')
            require(clean.isEmpty() || clean.startsWith("https://")) { "Use an HTTPS address" }
            prefs.edit().putString("api_origin", clean).apply()
        }

    suspend fun health(): String = withContext(Dispatchers.IO) {
        if (baseUrl.isEmpty()) return@withContext "Enter an HTTPS API address first"
        val connection = URL(baseUrl + "/api/v1/health").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            connection.requestMethod = "GET"
            val code = connection.responseCode
            if (code !in 200..299) return@withContext "Server returned HTTP " + code
            val result = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val providers = result.optJSONObject("providers")
            val openai = if (providers?.optBoolean("openai") == true) "ready" else "not set"
            val claude = if (providers?.optBoolean("anthropic") == true) "ready" else "not set"
            "Server: " + (if (result.optBoolean("ok")) "ready" else "not ready") +
                " | OpenAI: " + openai + " | Claude: " + claude
        } finally {
            connection.disconnect()
        }
    }
}
