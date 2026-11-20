package app.filmmaking.studio.sync

import app.filmmaking.studio.data.StudioConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder

/** The minimum the sync engine needs from the cloud; faked in unit tests. */
interface CloudApi {
    suspend fun fetchAll(table: String): List<RemoteRow>
    suspend fun fetchOne(table: String, id: String): RemoteRow?
    /** Insert; returns the new version, or null if a row with this id already exists. */
    suspend fun insert(row: CloudRow, deviceId: String): Long?
    /** Conditional update `where id = ? and version = ?`; null when 0 rows matched (lost race). */
    suspend fun updateIfVersion(row: CloudRow, baseVersion: Long, deviceId: String): Long?
    suspend fun tombstoneIfVersion(table: String, id: String, baseVersion: Long, deviceId: String): Long?
}

class CloudHttpException(val status: Int, message: String) : Exception(message)

/**
 * PostgREST client authenticated as the signed-in user. Row Level Security limits every
 * request to the caller's rows; no service-role key ever exists in the APK.
 */
class SupabaseRest(
    private val accessToken: () -> String,
    private val baseUrl: String = StudioConfig.SUPABASE_URL,
    private val apiKey: String = StudioConfig.SUPABASE_PUBLISHABLE_KEY
) : CloudApi, RawRest {
    override suspend fun request(method: String, path: String, body: String?, prefer: String?): Pair<Int, String> =
        call(method, path, body, prefer)

    private val pageSize = 500

    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8")

    private val http = okhttp3.OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    private val jsonType = "application/json".toMediaType()

    private suspend fun call(method: String, path: String, body: String? = null,
                             prefer: String? = null): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(baseUrl.trimEnd('/') + "/rest/v1/" + path)
                .header("apikey", apiKey)
                .header("Authorization", "Bearer " + accessToken())
                .header("Accept", "application/json")
                .apply { prefer?.let { header("Prefer", it) } }
                .method(method, body?.toRequestBody(jsonType))
                .build()
            http.newCall(request).execute().use { r -> r.code to (r.body?.string().orEmpty()) }
        }

    private fun parse(table: String, o: JSONObject): RemoteRow {
        val columns = JSONObject()
        o.keys().forEach { k -> if (k !in setOf("id", "payload", "version", "deleted_at")) columns.put(k, o.get(k)) }
        return RemoteRow(
            table = table, id = o.getString("id"), version = o.getLong("version"),
            deletedAt = o.optString("deleted_at").takeIf { !o.isNull("deleted_at") && it.isNotBlank() },
            payload = o.optJSONObject("payload") ?: JSONObject(), columns = columns
        )
    }

    private fun failure(code: Int, text: String): Nothing {
        val msg = runCatching { JSONObject(text).optString("message") }.getOrNull()?.takeIf { it.isNotBlank() }
        throw CloudHttpException(code, (msg ?: "Cloud request failed").take(200) + " (HTTP $code)")
    }

    override suspend fun fetchAll(table: String): List<RemoteRow> {
        val out = mutableListOf<RemoteRow>()
        var offset = 0
        while (true) {
            val (code, text) = call("GET", "$table?select=*&order=id.asc&limit=$pageSize&offset=$offset")
            if (code !in 200..299) failure(code, text)
            val arr = JSONArray(text)
            for (i in 0 until arr.length()) out += parse(table, arr.getJSONObject(i))
            if (arr.length() < pageSize) break
            offset += pageSize
        }
        return out
    }

    override suspend fun fetchOne(table: String, id: String): RemoteRow? {
        val (code, text) = call("GET", "$table?select=*&id=eq.${enc(id)}&limit=1")
        if (code !in 200..299) failure(code, text)
        val arr = JSONArray(text)
        return if (arr.length() == 0) null else parse(table, arr.getJSONObject(0))
    }

    private fun body(row: CloudRow, deviceId: String, alive: Boolean = true): JSONObject {
        val b = JSONObject(row.columns.toString()).put("payload", row.payload).put("device_id", deviceId)
        if (alive) b.put("deleted_at", JSONObject.NULL)
        return b
    }

    override suspend fun insert(row: CloudRow, deviceId: String): Long? {
        val b = body(row, deviceId).put("id", row.id)
        val (code, text) = call("POST", row.table + "?select=version", b.toString(), "return=representation")
        if (code == 409) return null // primary key (user_id, id) already exists
        if (code !in 200..299) failure(code, text)
        return JSONArray(text).getJSONObject(0).getLong("version")
    }

    override suspend fun updateIfVersion(row: CloudRow, baseVersion: Long, deviceId: String): Long? {
        val (code, text) = call("PATCH", "${row.table}?id=eq.${enc(row.id)}&version=eq.$baseVersion&select=version",
            body(row, deviceId).toString(), "return=representation")
        if (code !in 200..299) failure(code, text)
        val arr = JSONArray(text)
        return if (arr.length() == 0) null else arr.getJSONObject(0).getLong("version")
    }

    override suspend fun tombstoneIfVersion(table: String, id: String, baseVersion: Long, deviceId: String): Long? {
        val patch = JSONObject().put("deleted_at", java.time.Instant.now().toString()).put("device_id", deviceId)
        val (code, text) = call("PATCH", "$table?id=eq.${enc(id)}&version=eq.$baseVersion&select=version",
            patch.toString(), "return=representation")
        if (code !in 200..299) failure(code, text)
        val arr = JSONArray(text)
        return if (arr.length() == 0) null else arr.getJSONObject(0).getLong("version")
    }
}
