package app.filmmaking.studio.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class AiTurn(val from: String, val text: String)
data class AiAnswer(val text: String, val costUsd: Double?, val citations: JSONArray = JSONArray())

/**
 * Authenticated AI Director chat via the user's AWS Lambda URL.
 * Streaming NDJSON is consumed on IO; UI receives a completed response.
 * No model provider keys ever leave Lambda.
 */
class StudioAiClient(private val endpoint: ApiEndpoint) {
    suspend fun ask(
        token: String,
        conversationId: String,
        provider: String,
        model: String,
        message: String,
        history: List<AiTurn>,
        project: StudioRecord?,
        useArchive: Boolean = false,
        mode: String = "Explore",
        role: String = "Creative Partner",
        memories: List<JSONObject> = emptyList()
    ): AiAnswer = withContext(Dispatchers.IO) {
        require(provider == "anthropic" || provider == "openai")
        require(endpoint.baseUrl.startsWith("https://")) { "Configure an HTTPS AI endpoint in Settings" }
        val h = JSONArray()
        history.takeLast(12).forEach { h.put(JSONObject().put("from", it.from).put("text", it.text.take(8000))) }
        val sources = JSONArray()
        // Approved Creative DNA only (web layer A); proposed suggestions are never sent.
        memories.forEach { m -> sources.put(JSONObject().put("layer", "A")
            .put("label", if (m.optString("scope") == "identity") "Memory" else "Project choice").put("text", m.optString("text").take(500))) }
        val targets = JSONObject()
            .put("artifactIds", JSONArray())
            .put("shotIds", JSONArray())
            .put("ideaIds", JSONArray())
        if (project != null) {
            val p = JSONObject(project.payload)
            targets.put("projectId", project.id)
            val context = listOf(p.optString("name"), p.optString("concept"), p.optString("logline"))
                .filter { it.isNotBlank() }.joinToString("\n").take(12000)
            if (context.isNotBlank()) sources.put(
                JSONObject().put("layer", "A").put("label", "Current film project").put("text", context)
            )
        }
        val payload = JSONObject()
            .put("conversationId", conversationId)
            .put("provider", provider)
            .put("model", model)
            .put("mode", mode)
            .put("role", role)
            .put("experiment", false)
            .put("screen", "android/ai-director")
            .put("message", message.take(4000))
            .put("history", h)
            .put("sources", sources)
            .put("allowedTargets", targets)
        if (useArchive) payload.put("archive", JSONObject().put("enabled", true))

        val c = URL(endpoint.baseUrl.trimEnd('/') + "/api/v1/ai/chat").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 15000
            c.readTimeout = 180000
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Accept", "application/x-ndjson")
            c.setRequestProperty("Authorization", "Bearer " + token)
            c.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(payload.toString()) }
            val status = c.responseCode
            if (status !in 200..299) {
                val body = c.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                val reason = runCatching { JSONObject(body).optString("error") }.getOrDefault("")
                throw IllegalStateException((reason.ifBlank { "AI service HTTP " + status }).take(250))
            }
            val combined = StringBuilder()
            var complete: String? = null
            var cost: Double? = null
            var citations = JSONArray()
            c.inputStream.bufferedReader().useLines { stream ->
                stream.forEach { line ->
                    if (line.isBlank()) return@forEach
                    val event = runCatching { JSONObject(line) }.getOrNull() ?: return@forEach
                    when (event.optString("t")) {
                        "delta" -> combined.append(event.optString("v"))
                        "done" -> {
                            complete = event.optString("text").ifBlank { combined.toString() }
                            val usage = event.optJSONObject("usage")
                            cost = usage?.optDouble("costUsd")?.takeIf { it.isFinite() }
                            citations = event.optJSONArray("citations") ?: JSONArray()
                            if (event.optString("status") == "error")
                                throw IllegalStateException("AI stream interrupted; please retry")
                        }
                        "error" -> throw IllegalStateException(event.optString("error", "AI stream failed").take(250))
                    }
                }
            }
            val result = complete ?: combined.toString()
            require(result.isNotBlank()) { "AI returned no text; please retry" }
            AiAnswer(result, cost, citations)
        } finally { c.disconnect() }
    }
}
