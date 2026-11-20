package app.filmmaking.studio.data

import androidx.room.withTransaction
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * Offline JSON backup and version-2.0 production metadata handoff.
 * Camera footage is never copied into these JSON documents.
 */
class StudioExport(private val db: StudioDatabase) {
    private fun now() = Instant.now().toString()
    private fun json(row: StudioRecord) = JSONObject(row.payload)
    private fun arr(items: Iterable<JSONObject>) = JSONArray().also { out -> items.forEach { out.put(it) } }

    suspend fun backup(): String {
        val rows = db.dao().allRecords().map { r ->
            JSONObject().put("collection", r.collection).put("id", r.id).put("title", r.title)
                .put("payload", JSONObject(r.payload)).put("updatedAt", r.updatedAt)
                .put("baseVersion", r.baseVersion ?: JSONObject.NULL)
                .put("baseHash", r.baseHash ?: JSONObject.NULL)
                .put("dirty", r.dirty).put("deletedAt", r.deletedAt ?: JSONObject.NULL)
                .put("deviceId", r.deviceId)
        }
        return JSONObject().put("schema", "studio-native-backup")
            .put("version", 1).put("exportedAt", now())
            .put("records", arr(rows)).toString(2)
    }

    /** Fail closed on mismatching local IDs. A backup never silently overwrites local edits. */
    suspend fun restore(source: String): Int {
        require(source.length < 20_000_000) { "Backup too large" }
        val obj = JSONObject(source)
        require(obj.optString("schema") == "studio-native-backup" && obj.optInt("version") == 1) {
            "Not a Studio native backup"
        }
        val entries = obj.getJSONArray("records")
        require(entries.length() <= 20000) { "Backup contains too many records" }
        return db.withTransaction {
            var restored = 0
            for (i in 0 until entries.length()) {
                val r = entries.getJSONObject(i)
                val collection = r.getString("collection")
                val id = r.getString("id")
                require(collection.matches(Regex("[A-Za-z][A-Za-z0-9]*")) &&
                    id.length in 1..100) { "Invalid record identity" }
                val item = StudioRecord(
                    collection = collection, id = id, title = r.getString("title"),
                    payload = r.getJSONObject("payload").toString(),
                    updatedAt = r.getLong("updatedAt"),
                    baseVersion = r.optLong("baseVersion").takeIf { !r.isNull("baseVersion") },
                    baseHash = r.optString("baseHash").takeIf { !r.isNull("baseHash") },
                    dirty = true,
                    deletedAt = r.optLong("deletedAt").takeIf { !r.isNull("deletedAt") },
                    deviceId = r.optString("deviceId")
                )
                require(item.payload.length < 2_000_000) { "Oversized record" }
                val old = db.dao().find(collection, id)
                if (old != null && old.payload != item.payload) {
                    throw IllegalStateException("Conflicting local record " + collection + "/" + id + ". Import stopped without overwriting.")
                }
                if (old == null) { db.dao().upsert(item); restored++ }
            }
            restored
        }
    }

    suspend fun production(projectId: String): String =
        ProductionPackage.build(db.dao().allRecords(), projectId, now()).toString(2)

    suspend fun productionPackage(projectId: String): JSONObject =
        ProductionPackage.build(db.dao().allRecords(), projectId, now())
}
