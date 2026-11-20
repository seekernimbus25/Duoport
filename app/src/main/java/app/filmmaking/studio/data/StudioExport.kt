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

    suspend fun production(projectId: String): String {
        val rows = db.dao().allRecords().filter { it.deletedAt == null }
        val project = rows.find { it.collection == "projects" && it.id == projectId }
            ?: error("Project not found")
        val p = json(project)
        fun inProject(collection: String) = rows.filter {
            it.collection == collection && json(it).optString("projectId") == projectId
        }.map(::json)
        val scenes = inProject("scenes").sortedBy { it.optInt("order") }
        val shots = inProject("shots").sortedWith(compareBy<JSONObject> {
            scenes.indexOfFirst { scene -> scene.optString("id") == it.optString("sceneId") }
        }.thenBy { it.optDouble("storyOrder") })
        val takes = inProject("takes")
        val externalClips = inProject(ExternalCameraNaming.COLLECTION)
        val media = inProject("media")
        val days = inProject("shootDays")
        val setups = inProject("setups")
        val continuity = inProject("continuity")
        val omissions = inProject("omissions")
        val artifacts = inProject("artifacts")
        val storyboards = inProject("storyboards")
        val takeByShot = takes.groupBy { it.optString("shotId") }
        val shotsPacked = shots.map { s ->
            val ids = takeByShot[s.getString("id")].orEmpty()
            JSONObject(s.toString()).put("takeIds", arr(ids.map { JSONObject().put("id", it.getString("id")) }).toIdArray())
                .put("preferredTakeId", ids.firstOrNull { it.optBoolean("preferred") }
                    ?.optString("id") ?: JSONObject.NULL)
        }
        val relink = media.map { m ->
            val uri = m.optString("uri")
            val value = JSONObject()
                .put("mediaId", m.getString("id"))
                .put("recordingId", m.optString("recordingId").takeIf { it.isNotBlank() } ?: JSONObject.NULL)
                .put("projectId", projectId)
                .put("sceneId", m.optString("sceneId").takeIf { it.isNotBlank() } ?: JSONObject.NULL)
                .put("shotId", m.optString("shotId").takeIf { it.isNotBlank() } ?: JSONObject.NULL)
                .put("takeId", m.optString("takeId").takeIf { it.isNotBlank() } ?: JSONObject.NULL)
                .put("role", m.optString("role"))
                .put("masterId", m.optString("masterId").takeIf { it.isNotBlank() } ?: JSONObject.NULL)
                .put("filename", m.optString("filename"))
                .put("originalFilename", m.optString("originalFilename", m.optString("filename")))
                .put("deviceUri", uri)
                .put("uriKind", when {
                    uri.startsWith("content://") -> "android-content"
                    uri.startsWith("file://") || uri.startsWith("/") -> "android-file"
                    uri.startsWith("http://") || uri.startsWith("https://") -> "remote"
                    uri.startsWith("mock:") -> "simulated"
                    else -> "unknown"
                })
                .put("portablePath", JSONObject.NULL)
                .put("mime", m.optString("mime"))
                .put("verification", m.optString("verification", if (m.optBoolean("placeholder")) "simulated" else "reported"))
                .put("checksum", m.optJSONObject("checksum") ?: JSONObject.NULL)
            for (field in listOf("sizeBytes", "frameRate", "width", "height", "durationSec", "timecodeStart", "deliveryFormat")) {
                value.put(field, if (m.has(field)) m.get(field) else JSONObject.NULL)
            }
            value
        }
        val narrativeOrder = JSONArray().also { dst -> shots.forEach { dst.put(it.getString("id")) } }
        val shootOrder = JSONArray().also { dst ->
            days.sortedBy { it.optString("date") }.forEach { day ->
                val ids = day.optJSONArray("order") ?: JSONArray()
                for (i in 0 until ids.length()) dst.put(ids.getString(i))
            }
        }
        val preReasons = mutableListOf<String>()
        if (shots.isEmpty()) preReasons += "No shots planned"
        if (artifacts.none { a -> a.optString("approvedVersionId").isNotBlank() &&
                a.optString("kind") in listOf("script","voiceover") })
            preReasons += "No approved script or voiceover"
        shots.filter { it.optJSONObject("camera")?.optString("shotSize").isNullOrBlank() }.forEach {
            preReasons += "Missing shot size on " + it.optString("code")
        }
        val prodReasons = mutableListOf<String>()
        shots.forEach { shot ->
            if (takeByShot[shot.getString("id")].orEmpty().none { it.optString("quality") != "unusable" })
                prodReasons += "No usable take for " + shot.optString("code")
        }
        if (continuity.any { !it.optBoolean("resolved") }) prodReasons += "Unresolved continuity observations"
        val mediaReasons = mutableListOf<String>()
        if (externalClips.isNotEmpty()) mediaReasons += "External camera clips are only filename references; footage must be linked and verified"
        if (media.isEmpty()) mediaReasons += "No actual media"
        if (relink.any { it.optString("verification") != "verified" }) mediaReasons += "Some media are not checksum-verified"
        val pre = preReasons.isEmpty()
        val prod = prodReasons.isEmpty() && takes.isNotEmpty()
        val verified = mediaReasons.isEmpty()
        val ready = pre && prod && verified
        fun stage(ok: Boolean, reasons: List<String>): JSONObject =
            JSONObject().put("ok", ok).put("reasons", JSONArray(reasons))
        val readiness = JSONObject()
            .put("preProductionComplete", stage(pre, preReasons))
            .put("productionRecordsComplete", stage(prod, prodReasons))
            .put("actualMediaVerified", stage(verified, mediaReasons))
            .put("readyForEditorialHandoff", stage(ready, if (ready) emptyList() else listOf("Prerequisites outstanding")))
        val wrapReport = JSONObject()
            .put("plannedVsActual", JSONObject()
                .put("planned", shots.size).put("completed", shots.count { it.optString("status") == "complete" })
                .put("recorded", takes.size))
            .put("mediaInventory", arr(media))
            .put("externalCameraReferences", arr(externalClips))
            .put("bestTakes", arr(takes.filter { it.optBoolean("preferred") }))
            .put("omissions", arr(omissions))
            .put("unresolved", JSONArray(prodReasons))
            .put("readyForEdit", prod)
        val storyboardIndex = storyboards.map { board ->
            JSONObject().put("id", board.optString("id")).put("shotId", board.optString("shotId"))
                .put("version", board.optInt("version", 1)).put("chosen", board.optBoolean("chosen"))
                .put("conceptual", board.optBoolean("conceptual"))
                .put("file", JSONObject.NULL)
        }
        val pkg = JSONObject()
            .put("schema", "studio-production-package").put("version", "2.0.0")
            .put("exportedAt", now())
            .put("source", JSONObject().put("app", "Native Filmmaking Studio")
                .put("note", "Device metadata only. No footage transferred."))
            .put("project", p)
            .put("brief", approved(artifacts, "brief"))
            .put("finalScript", approved(artifacts, "script"))
            .put("finalVoiceover", approved(artifacts, "voiceover"))
            .put("scenes", arr(scenes))
            .put("shots", arr(shotsPacked))
            .put("takes", arr(takes))
            .put("media", arr(media))
            .put("mediaRelink", arr(relink))
            .put("narrativeOrder", narrativeOrder).put("editOrder", narrativeOrder)
            .put("shootOrder", shootOrder)
            .put("shootDays", arr(days)).put("setups", arr(setups))
            .put("continuity", arr(continuity)).put("omissions", arr(omissions))
            .put("externalCameraReferences", arr(externalClips))
            .put("storyboardIndex", arr(storyboardIndex))
            .put("readiness", readiness)
            .put("contents", JSONObject().put("footageIncluded", false))
            .put("wrapReport", wrapReport)
        return pkg.toString(2)
    }

    private fun JSONArray.toIdArray(): JSONArray {
        val out = JSONArray()
        for (i in 0 until length()) out.put(getJSONObject(i).getString("id"))
        return out
    }

    private fun approved(artifacts: List<JSONObject>, kind: String): Any {
        val a = artifacts.firstOrNull { it.optString("kind") == kind } ?: return JSONObject.NULL
        val versions = a.optJSONArray("versions") ?: return JSONObject.NULL
        val key = a.optString("approvedVersionId")
        for (i in 0 until versions.length()) {
            val v = versions.getJSONObject(i)
            if (v.optString("id") == key && v.optString("status") == "approved")
                return JSONObject().put("artifactId", a.optString("id"))
                    .put("versionId", key).put("text", v.optString("text"))
        }
        return JSONObject.NULL
    }
}
