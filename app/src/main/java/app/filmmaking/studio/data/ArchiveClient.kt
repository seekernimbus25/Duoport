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
    private val rest = ArchiveRest()

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

    suspend fun upload(session: StudioAuthClient.Session, uri: Uri, projectId: String?): JSONObject = withContext(Dispatchers.IO) {
        require(session.userId.isNotBlank()) { "Sign in again to upload to your archive" }
        var name = "document"; var size = -1L
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) { c.getString(0)?.let { name = it }; if (!c.isNull(1)) size = c.getLong(1) }
        }
        val mime = app.contentResolver.getType(uri) ?: "application/octet-stream"
        require(size <= MAX_BYTES) { "Archive files are limited to 10 MB" }
        val bytes = (app.contentResolver.openInputStream(uri) ?: error("Cannot read $name")).use { it.readBytes() }
        rest.upload(session, name, mime, bytes, projectId, endpoint.baseUrl)
    }

    /** The caller's own documents that can be analysed (indexed, AI enabled, not in Trash). RLS scopes the list. */
    suspend fun analysable(session: StudioAuthClient.Session): List<JSONObject> = withContext(Dispatchers.IO) { rest.analysable(session) }
}

/** Android-free REST half of the archive client, also used by the live two-account isolation test. */
class ArchiveRest(private val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(180, TimeUnit.SECONDS).build()) {
    private val base = StudioConfig.SUPABASE_URL.trimEnd('/')
    fun call(req: Request): Pair<Int, String> = http.newCall(req).execute().use { it.code to (it.body?.string().orEmpty()) }
    fun authed(session: StudioAuthClient.Session, path: String): Request.Builder = Request.Builder().url(base + path)
        .header("apikey", StudioConfig.SUPABASE_PUBLISHABLE_KEY).header("Authorization", "Bearer " + session.accessToken)
    private fun fail(code: Int, text: String, what: String): Nothing {
        val msg = runCatching { JSONObject(text).let { it.optString("message").ifBlank { it.optString("error") } } }.getOrNull()
        throw IllegalStateException("$what failed: " + (msg?.ifBlank { null } ?: "HTTP $code").take(200))
    }

    /** Private bucket `studio-files/<uid>/archive/<docId>/<file>` → `archive_documents` row → ingest (skipped when [apiBase] is null). */
    fun upload(session: StudioAuthClient.Session, name: String, mime: String, bytes: ByteArray, projectId: String?, apiBase: String?): JSONObject {
        require(session.userId.isNotBlank()) { "Sign in again to upload to your archive" }
        val type = ArchiveClient.sourceType(name, mime) ?: error("Only PDF, DOCX, Markdown and text files can be added to the archive")
        require(bytes.isNotEmpty() && bytes.size <= ArchiveClient.MAX_BYTES) { "Archive files must be 1 byte to 10 MB" }
        val id = UUID.randomUUID().toString()
        val path = session.userId + "/archive/" + id + "/" + ArchiveClient.safeName(name)
        val (upCode, upText) = call(authed(session, "/storage/v1/object/studio-files/$path")
            .header("x-upsert", "true").post(bytes.toRequestBody(mime.toMediaType())).build())
        if (upCode !in 200..299) fail(upCode, upText, "Upload")
        val row = JSONObject().put("id", id).put("user_id", session.userId).put("title", name.take(200)).put("source_type", type)
            .put("description", "").put("tags", JSONArray()).put("project_id", projectId ?: JSONObject.NULL)
            .put("storage_path", path).put("mime", mime).put("size_bytes", bytes.size)
        val (insCode, insText) = call(authed(session, "/rest/v1/archive_documents")
            .header("Prefer", "return=minimal").post(row.toString().toRequestBody(JSON)).build())
        if (insCode !in 200..299) {
            // Never leave an orphaned file behind.
            runCatching { call(authed(session, "/storage/v1/object/studio-files/$path").delete().build()) }
            fail(insCode, insText, "Saving the archive entry")
        }
        if (apiBase == null) return JSONObject().put("documentId", id).put("title", name).put("storagePath", path).put("status", "pending")
        val api = apiBase.trimEnd('/')
        require(api.startsWith("https://")) { "Configure the HTTPS AI backend to index documents" }
        val (code, text) = call(Request.Builder().url("$api/api/v1/archive/ingest").header("Authorization", "Bearer " + session.accessToken)
            .post(JSONObject().put("documentId", id).toString().toRequestBody(JSON)).build())
        if (code !in 200..299) fail(code, text, "Indexing")
        return JSONObject(text).put("documentId", id).put("title", name).put("storagePath", path)
    }

    fun analysable(session: StudioAuthClient.Session): List<JSONObject> {
        val (code, text) = call(authed(session, "/rest/v1/archive_documents?select=id,title,status,ai_enabled,created_at" +
            "&deleted_at=is.null&status=eq.indexed&ai_enabled=is.true&order=created_at.desc&limit=200").get().build())
        if (code !in 200..299) fail(code, text, "Listing your archive")
        val a = JSONArray(text); return (0 until a.length()).map { a.getJSONObject(it) }
    }

    /** Permanently removes the stored original and the row (chunks first), as the web purge does. */
    fun purge(session: StudioAuthClient.Session, documentId: String, storagePath: String) {
        call(authed(session, "/rest/v1/archive_chunks?document_id=eq.$documentId").delete().build())
        call(authed(session, "/storage/v1/object/studio-files/$storagePath").delete().build())
        call(authed(session, "/rest/v1/archive_documents?id=eq.$documentId").delete().build())
    }

    companion object { val JSON = "application/json".toMediaType() }
}
