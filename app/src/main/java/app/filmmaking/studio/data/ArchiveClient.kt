package app.filmmaking.studio.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Creative Archive upload, same flow as the web `archiveClient.ts`:
 * private bucket `studio-files/<uid>/archive/<docId>/<file>` → `archive_documents` row → ingest.
 * Every call carries the user's own token, so Storage policies and RLS keep documents private.
 */
class ArchiveClient(context: Context, private val endpoint: ApiEndpoint) {
    private val app = context.applicationContext
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(180, TimeUnit.SECONDS).build()

    companion object {
        const val MAX_BYTES = 10L * 1024 * 1024
        fun sourceType(name: String, mime: String): String? = when {
            mime == "application/pdf" || name.endsWith(".pdf", true) -> "pdf"
            name.endsWith(".docx", true) -> "docx"
            name.endsWith(".md", true) || mime == "text/markdown" -> "md"
            name.endsWith(".txt", true) || mime == "text/plain" -> "txt"
            else -> null
        }
        fun safeName(name: String) = name.replace(Regex("[^\\w.\\-]+"), "_").takeLast(100)
    }

    private fun call(req: Request): Pair<Int, String> = http.newCall(req).execute().use { it.code to (it.body?.string().orEmpty()) }
    private fun fail(code: Int, text: String, what: String): Nothing {
        val msg = runCatching { JSONObject(text).let { it.optString("message").ifBlank { it.optString("error") } } }.getOrNull()
        throw IllegalStateException("$what failed: " + (msg?.ifBlank { null } ?: "HTTP $code").take(200))
    }

    suspend fun upload(session: StudioAuthClient.Session, uri: Uri, projectId: String?): JSONObject = withContext(Dispatchers.IO) {
        require(session.userId.isNotBlank()) { "Sign in again to upload to your archive" }
        var name = "document"; var size = -1L
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) { c.getString(0)?.let { name = it }; if (!c.isNull(1)) size = c.getLong(1) }
        }
        val mime = app.contentResolver.getType(uri) ?: "application/octet-stream"
        val type = sourceType(name, mime) ?: error("Only PDF, DOCX, Markdown and text files can be added to the archive")
        require(size <= MAX_BYTES) { "Archive files are limited to 10 MB" }
        val bytes = (app.contentResolver.openInputStream(uri) ?: error("Cannot read $name")).use { it.readBytes() }
        require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES) { "Archive files must be 1 byte to 10 MB" }
        val id = UUID.randomUUID().toString()
        val path = session.userId + "/archive/" + id + "/" + safeName(name)
        val base = StudioConfig.SUPABASE_URL.trimEnd('/')
        val (upCode, upText) = call(Request.Builder().url("$base/storage/v1/object/studio-files/$path")
            .header("apikey", StudioConfig.SUPABASE_PUBLISHABLE_KEY).header("Authorization", "Bearer " + session.accessToken)
            .header("x-upsert", "true").post(bytes.toRequestBody(mime.toMediaType())).build())
        if (upCode !in 200..299) fail(upCode, upText, "Upload")
        val row = JSONObject().put("id", id).put("user_id", session.userId).put("title", name.take(200)).put("source_type", type)
            .put("description", "").put("tags", JSONArray()).put("project_id", projectId ?: JSONObject.NULL)
            .put("storage_path", path).put("mime", mime).put("size_bytes", bytes.size)
        val (insCode, insText) = call(Request.Builder().url("$base/rest/v1/archive_documents")
            .header("apikey", StudioConfig.SUPABASE_PUBLISHABLE_KEY).header("Authorization", "Bearer " + session.accessToken)
            .header("Prefer", "return=minimal").post(row.toString().toRequestBody("application/json".toMediaType())).build())
        if (insCode !in 200..299) {
            // Never leave an orphaned file behind.
            runCatching { call(Request.Builder().url("$base/storage/v1/object/studio-files/$path")
                .header("apikey", StudioConfig.SUPABASE_PUBLISHABLE_KEY).header("Authorization", "Bearer " + session.accessToken).delete().build()) }
            fail(insCode, insText, "Saving the archive entry")
        }
        val api = endpoint.baseUrl.trimEnd('/')
        require(api.startsWith("https://")) { "Configure the HTTPS AI backend to index documents" }
        val (code, text) = call(Request.Builder().url("$api/api/v1/archive/ingest").header("Authorization", "Bearer " + session.accessToken)
            .post(JSONObject().put("documentId", id).toString().toRequestBody("application/json".toMediaType())).build())
        if (code !in 200..299) fail(code, text, "Indexing")
        JSONObject(text).put("documentId", id).put("title", name)
    }
}
