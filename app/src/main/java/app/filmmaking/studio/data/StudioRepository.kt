package app.filmmaking.studio.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

/** Native-first records retain the canonical TypeScript Studio JSON shape. */
class StudioRepository(private val database: StudioDatabase) {
    private val dao = database.dao()
    fun ideas(): Flow<List<StudioRecord>> = dao.observe("ideas")
    fun projects(): Flow<List<StudioRecord>> = dao.observe("projects")
    fun scenes(): Flow<List<StudioRecord>> = dao.observe("scenes")
    fun shots(): Flow<List<StudioRecord>> = dao.observe("shots")
    fun takes(): Flow<List<StudioRecord>> = dao.observe("takes")

    private fun uuid() = UUID.randomUUID().toString()
    private fun now() = Instant.now().toString()
    private fun record(collection: String, key: String, title: String, json: JSONObject) =
        StudioRecord(collection = collection, id = key, title = title, payload = json.toString())

    suspend fun captureIdea(text: String, kind: String = "text", title: String = "", tags: List<String> = emptyList(), source: String = ""): StudioRecord {
        val key = uuid()
        val stamp = now()
        val name = title.trim().ifBlank { text.trim().take(64) }
        val data = JSONObject().put("id", key).put("title", name)
            .put("kind", if (kind in IDEA_KINDS) kind else "text").put("original", text).put("expansions", JSONArray())
            .put("tags", JSONArray(tags.map { it.trim() }.filter { it.isNotEmpty() }.take(20)))
            .put("sources", JSONArray(listOfNotNull(source.trim().takeIf { it.isNotEmpty() }?.let { src ->
                // Same shape as attachToIdea's sources so every reader can treat entries alike.
                val link = src.startsWith("https://") || src.startsWith("http://")
                JSONObject().put("id", uuid()).put("kind", if (link) "link" else "note")
                    .put("uri", if (link) src.take(2000) else "").put("label", src.take(200)).put("addedAt", stamp)
            }))).put("attachments", JSONArray())
            .put("connectedIdeaIds", JSONArray()).put("status", "Inbox")
            .put("createdAt", stamp).put("updatedAt", stamp)
        return record("ideas", key, name, data).also { dao.upsert(it) }
    }

    suspend fun createProject(name: String, ideaId: String? = null, concept: String = ""): StudioRecord {
        val key = uuid()
        val stamp = now()
        val data = JSONObject().put("id", key).put("name", name).put("concept", concept)
            .put("logline", "").put("format", "Reel").put("narrativeApproach", "")
            .put("targetDurationSec", 60).put("aspectRatio", "9:16")
            .put("altFormats", JSONArray()).put("mood", "").put("goals", "")
            .put("status", "Idea").put("notes", "").put("decisions", JSONArray())
            .put("lastOpenedAt", stamp).put("createdAt", stamp).put("updatedAt", stamp)
        if (ideaId != null) data.put("ideaId", ideaId)
        return record("projects", key, name, data).also { dao.upsert(it) }
    }

    /** Creates a project once and preserves the captured original idea. */
    suspend fun convertIdea(ideaId: String): StudioRecord = database.withTransaction {
        val idea = requireNotNull(dao.find("ideas", ideaId))
        val body = JSONObject(idea.payload)
        val existing = body.optString("projectId").takeIf { it.isNotEmpty() }
        if (existing != null) return@withTransaction requireNotNull(dao.find("projects", existing))
        val project = createProject(idea.title, ideaId, body.optString("original"))
        body.put("status", "Converted").put("projectId", project.id).put("updatedAt", now())
        dao.upsert(idea.copy(payload = body.toString(), dirty = true, updatedAt = System.currentTimeMillis()))
        project
    }

    suspend fun addScene(projectId: String, title: String): StudioRecord {
        requireNotNull(dao.find("projects", projectId))
        val existing = dao.listByProject("scenes", projectId)
        val number = (existing.maxOfOrNull { JSONObject(it.payload).optInt("number") } ?: 0) + 1
        val key = uuid()
        val data = JSONObject().put("id", key).put("projectId", projectId).put("number", number)
            .put("title", title).put("purpose", "").put("description", "").put("linkedPassage", "").put("order", number)
        return record("scenes", key, title, data).also { dao.upsert(it) }
    }

    suspend fun addShot(sceneId: String, title: String): StudioRecord {
        val scene = requireNotNull(dao.find("scenes", sceneId))
        val s = JSONObject(scene.payload)
        val projectId = s.getString("projectId")
        val allShots = dao.listByProject("shots", projectId)
        val inScene = allShots.filter { JSONObject(it.payload).optString("sceneId") == sceneId }
        val n = (inScene.maxOfOrNull {
            JSONObject(it.payload).optString("code").substringAfter("-").filter { c -> c.isDigit() }.toIntOrNull() ?: 0
        } ?: 0) + 1
        val key = uuid()
        val stamp = now()
        val code = "S" + s.getInt("number").toString().padStart(2,'0') + "-" + n.toString().padStart(2,'0')
        val camera = JSONObject().put("shotSize", "MS").put("angle", "Eye level")
            .put("composition", "").put("movement", "Static").put("lens", "").put("focus", "")
            .put("frameRate", "24").put("resolution", "4K").put("shutter", "1/50")
            .put("iso", "").put("whiteBalance", "").put("profile", "").put("lut", "").put("aspect", "9:16")
        val light = JSONObject()
        listOf("source", "keyFillBack", "direction", "quality", "colourTemp", "practicals", "modifiers", "reference", "timeOfDay").forEach { light.put(it, "") }
        val sound = JSONObject()
        listOf("voiceover", "ambient", "foley", "roomTone", "music", "silence").forEach { sound.put(it, "") }
        val production = JSONObject().put("props", "").put("wardrobe", "").put("equipment", "")
            .put("setup", "").put("talent", "").put("setupMin", 10).put("shootMin", 10).put("dependencies", "")
        val editorial = JSONObject().put("transition", "Cut")
        listOf("position", "cutPoints", "vfx", "sfx", "colour", "speed", "editorNotes").forEach { editorial.put(it, "") }
        val data = JSONObject().put("id", key).put("code", code).put("projectId", projectId).put("sceneId", sceneId)
            .put("storyOrder", allShots.size+1).put("shootOrder", allShots.size+1)
            .put("title", title).put("description", "").put("purpose", "").put("scriptPassage", "")
            .put("durationSec", 4).put("visualNotes", "").put("camera", camera)
            .put("lighting", light).put("sound", sound).put("production", production)
            .put("editorial", editorial).put("status", "planned").put("inChecklist", false)
            .put("spontaneous", false).put("createdAt", stamp).put("updatedAt", stamp)
        return record("shots", key, title, data).also { dao.upsert(it) }
    }

    suspend fun setShotStatus(shotId: String, status: String) {
        require(status in listOf("planned", "ready", "in progress", "complete", "skipped", "missing"))
        val shot = requireNotNull(dao.find("shots", shotId))
        val body = JSONObject(shot.payload).put("status", status).put("updatedAt", now())
        dao.upsert(shot.copy(payload = body.toString(), updatedAt = System.currentTimeMillis(), dirty = true))
    }


    /** Modifies the planned shot, never the device-reported settings in an existing take. */
    suspend fun saveShotPlan(
        shotId: String, title: String, description: String, passage: String,
        shotSize: String, angle: String, lens: String, frameRate: String,
        lightingNotes: String, soundNotes: String, editorialNotes: String
    ) {
        require(title.isNotBlank()) { "Shot title cannot be blank" }
        val previous = requireNotNull(dao.find("shots", shotId))
        val data = JSONObject(previous.payload)
        val camera = data.optJSONObject("camera") ?: JSONObject()
        val light = data.optJSONObject("lighting") ?: JSONObject()
        val sound = data.optJSONObject("sound") ?: JSONObject()
        val editorial = data.optJSONObject("editorial") ?: JSONObject()
        camera.put("shotSize", shotSize.take(40)).put("angle", angle.take(80))
            .put("lens", lens.take(80)).put("frameRate", frameRate.take(20))
        light.put("source", lightingNotes.take(2000))
        sound.put("ambient", soundNotes.take(2000))
        editorial.put("editorNotes", editorialNotes.take(2000))
        data.put("title", title.trim().take(140))
            .put("description", description.take(4000))
            .put("scriptPassage", passage.take(8000))
            .put("camera", camera).put("lighting", light)
            .put("sound", sound).put("editorial", editorial)
            .put("updatedAt", now())
        dao.upsert(previous.copy(
            title = title.trim().take(140),
            payload = data.toString(),
            updatedAt = System.currentTimeMillis(), dirty = true
        ))
    }

    /** Record a human quality assessment; the first camera import remains "review". */
    suspend fun rateTake(takeId: String, quality: String) {
        require(quality in setOf("usable", "unusable", "review"))
        val old = requireNotNull(dao.find("takes", takeId))
        val obj = JSONObject(old.payload).put("quality", quality)
        dao.upsert(old.copy(payload = obj.toString(), updatedAt = System.currentTimeMillis(), dirty = true))
    }

    /** Exactly one preferred take per shot, even if several were imported. */
    suspend fun preferTake(takeId: String) = database.withTransaction {
        val selected = requireNotNull(dao.find("takes", takeId))
        val payload = JSONObject(selected.payload)
        val projectId = payload.getString("projectId")
        val shotId = payload.getString("shotId")
        val candidates = dao.listByProject("takes", projectId)
        require(candidates.any { it.id == takeId })
        candidates.forEach { candidate ->
            val take = JSONObject(candidate.payload)
            if (take.optString("shotId") != shotId) return@forEach
            val shouldPrefer = candidate.id == takeId
            if (take.optBoolean("preferred") != shouldPrefer) {
                take.put("preferred", shouldPrefer)
                dao.upsert(candidate.copy(
                    payload = take.toString(), updatedAt = System.currentTimeMillis(), dirty = true
                ))
            }
        }
    }
}

/** The web app's idea kinds (src/domain/types.ts IdeaKind). */
val IDEA_KINDS = listOf("text", "voice", "image", "link", "quote", "question", "observation", "sound")
