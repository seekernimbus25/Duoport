package app.filmmaking.studio.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Structured AI proposals are not persisted or approved until accepted by the user.
 * The native app never contains or contacts model-provider API keys directly.
 */
data class GeneratedOptions(
    val projectId: String,
    val op: String,
    val model: String,
    val items: List<JSONObject>,
    val estimatedCostUsd: Double?
)

class StudioCreativeClient(private val endpoint: ApiEndpoint) {
    suspend fun generate(
        accessToken: String, project: StudioRecord,
        op: String, instructions: String
    ): GeneratedOptions = withContext(Dispatchers.IO) {
        require(op == "treatments" || op == "beats") { "Only treatment and beat proposals are supported here" }
        require(accessToken.isNotBlank()) { "Sign in before generating" }
        val base = endpoint.baseUrl
        require(base.startsWith("https://")) { "Configure an HTTPS AI backend URL" }
        val p = JSONObject(project.payload)
        val context = listOf(
            p.optString("name"), p.optString("concept"), p.optString("logline"),
            p.optString("mood"), p.optString("goals")
        ).filter { it.isNotBlank() }.joinToString("\n").take(12000)

        val sources = JSONArray().put(
            JSONObject().put("layer", "A")
                .put("label", "Current film")
                .put("text", if (context.isNotBlank()) context else project.title)
        )
        val brief = JSONObject()
            .put("format", p.optString("format", "Reel"))
            .put("durationSec", p.optInt("targetDurationSec", 60).coerceIn(5, 7200))
            .put("emotion", p.optString("mood").take(300))
        val payload = JSONObject()
            .put("op", op)
            .put("provider", "anthropic")
            .put("model", "claude-haiku-4-5")
            .put("projectId", project.id)
            .put("instructions", instructions.take(2000))
            .put("count", 2)
            .put("targets", JSONArray())
            .put("sources", sources)
            .put("brief", brief)

        val c = URL(base.trimEnd('/') + "/api/v1/ai/create").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 15000
            c.readTimeout = 180000
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Authorization", "Bearer " + accessToken)
            c.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(payload.toString()) }
            val status = c.responseCode
            val responseText = (if (status in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val response = runCatching { JSONObject(responseText) }.getOrNull()
            if (status !in 200..299) {
                val reason = response?.optString("error")?.takeIf { it.isNotBlank() }
                    ?: "Creative generation failed (HTTP $status)"
                throw IllegalStateException(reason.take(260))
            }
            val result = requireNotNull(response?.optJSONObject("result")) {
                "AI returned no structured result"
            }
            val key = if (op == "treatments") "treatments" else "beats"
            val array = requireNotNull(result.optJSONArray(key)) { "AI returned no $key" }
            val items = (0 until array.length()).map { array.getJSONObject(it) }
            require(items.isNotEmpty()) { "No proposals returned" }
            GeneratedOptions(
                projectId = project.id, op = op, model = "claude-haiku-4-5",
                items = items,
                estimatedCostUsd = response.optJSONObject("usage")
                    ?.optDouble("costUsd")?.takeIf { it.isFinite() }
            )
        } finally { c.disconnect() }
    }

    /**
     * Any `/api/v1/ai/create` op (shots, critique, schedule, setup, production, refine_*).
     * Returns the raw `result`; the caller stores it as a PENDING proposal, never as accepted data.
     */
    suspend fun create(
        accessToken: String, projectId: String, op: String, instructions: String,
        targets: JSONArray, sources: JSONArray, count: Int = 4, extra: JSONObject = JSONObject()
    ): JSONObject = withContext(Dispatchers.IO) {
        require(op in setOf("shots", "critique", "schedule", "setup", "production", "refine_treatment", "refine_beat")) { "Unsupported AI operation" }
        val body = JSONObject().put("op", op).put("provider", "anthropic").put("model", "claude-haiku-4-5")
            .put("projectId", projectId).put("instructions", instructions.take(2000)).put("count", count.coerceIn(1, 8))
            .put("targets", targets).put("sources", sources)
        extra.keys().forEach { body.put(it, extra.get(it)) }
        post(accessToken, "/api/v1/ai/create", body)
    }

    /** Hybrid semantic + keyword search over the caller's own Creative Archive (RLS-scoped). */
    suspend fun searchArchive(accessToken: String, query: String, limit: Int = 8): JSONArray = withContext(Dispatchers.IO) {
        require(query.isNotBlank()) { "Enter something to search for" }
        post(accessToken, "/api/v1/archive/search", JSONObject().put("query", query.take(500)).put("limit", limit.coerceIn(1, 30)))
            .optJSONArray("results") ?: JSONArray()
    }

    private fun post(accessToken: String, path: String, payload: JSONObject): JSONObject {
        require(accessToken.isNotBlank()) { "Sign in first" }
        val base = endpoint.baseUrl
        require(base.startsWith("https://")) { "Configure an HTTPS AI backend URL" }
        val c = URL(base.trimEnd('/') + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"; c.doOutput = true
            c.connectTimeout = 15000; c.readTimeout = 180000
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Authorization", "Bearer " + accessToken)
            c.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(payload.toString()) }
            val status = c.responseCode
            val text = (if (status in 200..299) c.inputStream else c.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(text) }.getOrNull()
            if (status !in 200..299) throw IllegalStateException((json?.optString("error")?.takeIf { it.isNotBlank() }
                ?: "AI request failed (HTTP $status)").take(260))
            val response = json ?: error("AI service returned no JSON")
            return if (path.endsWith("/ai/create")) (response.optJSONObject("result") ?: error("AI returned no structured result"))
                .put("_usage", response.optJSONObject("usage") ?: JSONObject())
                .put("_citations", response.optJSONArray("citations") ?: JSONArray())
            else response
        } finally { c.disconnect() }
    }
}
