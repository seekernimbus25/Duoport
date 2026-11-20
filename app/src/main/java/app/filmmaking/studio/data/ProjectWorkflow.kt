package app.filmmaking.studio.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

/** Real offline manual-workflow records. Each collection keeps the web Studio's keys. */
enum class WorkflowKind(val collection: String, val label: String, val detailLabel: String) {
    TREATMENT("treatments", "Treatment", "Premise"),
    SCRIPT("artifacts", "Script / voiceover", "Draft"),
    BEAT("beats", "Visual beat", "Visual intention"),
    RECIPE("recipes", "Lookbook recipe", "Technique / guidance"),
    DAY("shootDays", "Shoot day", "Plan / logistics"),
    SETUP("setups", "Production setup", "Instructions"),
    CHECKLIST("checklist", "Pre-shoot checklist", "Notes"),
    CONTINUITY("continuity", "Continuity observation", "What you actually observed"),
    REFERENCE("references", "Creative reference", "Notes")
}

class ProjectWorkflowRepository(private val db: StudioDatabase) {
    private val dao = db.dao()
    fun all(): Flow<List<StudioRecord>> = dao.observeAll()

    private fun uid() = UUID.randomUUID().toString()
    private fun now() = Instant.now().toString()
    private fun row(collection: String, id: String, title: String, body: JSONObject) =
        StudioRecord(collection, id, title, body.toString())
    private fun doc(record: StudioRecord) = JSONObject(record.payload)

    suspend fun create(projectId: String, kind: WorkflowKind, title: String, detail: String, shotId: String? = null): StudioRecord {
        require(title.isNotBlank())
        requireNotNull(dao.find("projects", projectId)) { "Project not found" }
        if (kind == WorkflowKind.CONTINUITY) require(!shotId.isNullOrBlank()) { "Select a shot for continuity" }
        val key = uid()
        val stamp = now()
        val obj = JSONObject().put("id", key).put("projectId", projectId)
        val rows = dao.listByProject(kind.collection, projectId)
        when (kind) {
            WorkflowKind.TREATMENT -> {
                obj.put("title", title).put("approach", "Visual essay")
                    .put("premise", detail).put("thematicQuestion", "")
                    .put("structure", "").put("argument", "").put("audienceExperience", "")
                    .put("beginning", "").put("progression", "").put("ending", "").put("hook", "")
                    .put("visualMetaphors", "").put("voiceApproach", "").put("soundDirection", "")
                    .put("durationSec", 60).put("complexity", "Low").put("complexityNotes", "")
                    .put("status", "draft").put("author", "user").put("parentIds", JSONArray())
                    .put("sources", JSONArray()).put("createdAt", stamp).put("updatedAt", stamp)
            }
            WorkflowKind.SCRIPT -> {
                obj.put("kind", "script").put("title", title).put("draft", detail)
                    .put("versions", JSONArray()).put("updatedAt", stamp)
            }
            WorkflowKind.BEAT -> {
                obj.put("order", rows.size + 1).put("title", title).put("intention", "")
                    .put("passage", "").put("dialogue", "").put("emotion", "")
                    .put("visual", detail).put("metaphor", "").put("soundCue", "")
                    .put("durationSec", 4).put("references", "")
                    .put("shotIds", JSONArray()).put("origin", "visual")
                    .put("createdAt", stamp).put("updatedAt", stamp)
            }
            WorkflowKind.RECIPE -> {
                // Lookbook recipes are global in the canonical model. projectId is an additive local link.
                obj.put("name", title).put("kind", "Technique")
                    .put("effect", "").put("narrativeUse", "").put("elements", JSONArray())
                    .put("equipment", "").put("guidance", detail).put("limitations", "")
                    .put("exampleShotIds", JSONArray()).put("tags", JSONArray())
                    .put("inCreativeDna", false).put("createdAt", stamp).put("updatedAt", stamp)
            }
            WorkflowKind.DAY -> {
                obj.put("label", title).put("date", "").put("callTime", "")
                    .put("wrapTime", "").put("locationIds", JSONArray())
                    .put("order", JSONArray()).put("blocks", JSONArray()).put("notes", detail)
                    .put("history", JSONArray()).put("createdAt", stamp).put("updatedAt", stamp)
            }
            WorkflowKind.SETUP -> {
                obj.put("name", title).put("cameraPosition", "").put("lighting", "")
                    .put("equipmentIds", JSONArray()).put("accessories", "").put("sound", "")
                    .put("referenceImages", JSONArray()).put("instructions", detail)
                    .put("shotIds", JSONArray()).put("setupMin", 10)
                    .put("version", 1).put("createdAt", stamp).put("updatedAt", stamp)
            }
            WorkflowKind.CHECKLIST -> {
                obj.put("key", key).put("shotIds", JSONArray())
                    .put("category", "Other").put("label", title)
                    .put("status", "unresolved").put("optional", false)
                    .put("source", "custom").put("note", detail).put("updatedAt", stamp)
            }
            WorkflowKind.CONTINUITY -> {
                obj.put("shotId", shotId).put("aspect", "Action & movement")
                    .put("planned", title).put("observed", detail)
                    .put("basis", "record").put("resolved", false)
                    .put("history", JSONArray()).put("createdAt", stamp)
            }
            WorkflowKind.REFERENCE -> {
                obj.put("kind", "note").put("title", title).put("notes", detail)
                    .put("attachedSceneIds", JSONArray()).put("attachedShotIds", JSONArray())
            }
        }
        return row(kind.collection, key, title, obj).also { dao.upsert(it) }
    }

    /**
     * An image on the project's reference board (web `CreativeReference` kind "image"). [image] is a downscaled
     * data URI kept with the record, so it works offline and syncs; [sourceUrl] records where it came from.
     */
    suspend fun addImageReference(projectId: String, title: String, image: String, notes: String,
                                  sourceUrl: String?, addedFrom: String, shotId: String?): StudioRecord {
        require(image.startsWith("data:image/")) { "No image to save" }
        require(addedFrom in setOf("link", "phone", "clipboard"))
        requireNotNull(dao.find("projects", projectId)) { "Project not found" }
        val shotIds = JSONArray(); val sceneIds = JSONArray()
        if (!shotId.isNullOrBlank()) {
            val shot = requireNotNull(dao.find("shots", shotId)) { "Shot not found" }
            shotIds.put(shotId); doc(shot).optString("sceneId").takeIf { it.isNotBlank() }?.let { sceneIds.put(it) }
        }
        val key = uid(); val stamp = now()
        val name = title.trim().ifBlank { "Reference" }.take(200)
        val obj = JSONObject().put("id", key).put("projectId", projectId).put("kind", "image").put("title", name)
            .put("image", image).put("notes", notes.take(4000)).put("attachedSceneIds", sceneIds).put("attachedShotIds", shotIds)
            .put("addedFrom", addedFrom).put("createdAt", stamp).put("updatedAt", stamp)
        sourceUrl?.takeIf { it.startsWith("https://") || it.startsWith("http://") }?.let { obj.put("sourceUrl", it.take(2000)) }
        return row("references", key, name, obj).also { dao.upsert(it) }
    }

    /** Pins a reference to one shot (and its scene), or clears the pin. The image and notes stay as they are. */
    suspend fun attachReference(id: String, shotId: String?) {
        val item = requireNotNull(dao.find("references", id)); val body = doc(item)
        val shotIds = JSONArray(); val sceneIds = JSONArray()
        if (!shotId.isNullOrBlank()) {
            val shot = requireNotNull(dao.find("shots", shotId)) { "Shot not found" }
            shotIds.put(shotId); doc(shot).optString("sceneId").takeIf { it.isNotBlank() }?.let { sceneIds.put(it) }
        }
        body.put("attachedShotIds", shotIds).put("attachedSceneIds", sceneIds).put("updatedAt", now())
        dao.upsert(item.copy(payload = body.toString(), updatedAt = System.currentTimeMillis(), dirty = true))
    }

    suspend fun update(collection: String, id: String, title: String, detail: String) {
        val item = requireNotNull(dao.find(collection, id))
        val body = doc(item)
        val kind = WorkflowKind.entries.firstOrNull { it.collection == collection } ?: error("Unknown collection")
        when(kind) {
            WorkflowKind.TREATMENT -> { require(body.optString("status") != "approved") { "Fork approved treatment to edit" }; body.put("title", title).put("premise", detail) }
            WorkflowKind.SCRIPT -> { body.put("title", title).put("draft", detail) }
            WorkflowKind.BEAT -> { body.put("title", title).put("visual", detail) }
            WorkflowKind.RECIPE -> { body.put("name", title).put("guidance", detail) }
            WorkflowKind.DAY -> { body.put("label", title).put("notes", detail) }
            WorkflowKind.SETUP -> { body.put("name", title).put("instructions", detail) }
            WorkflowKind.CHECKLIST -> { body.put("label", title).put("note", detail) }
            WorkflowKind.CONTINUITY -> {
                val history = body.optJSONArray("history") ?: JSONArray()
                history.put(JSONObject().put("at", now()).put("observed", body.optString("observed")))
                body.put("history", history).put("planned", title).put("observed", detail)
            }
            WorkflowKind.REFERENCE -> { body.put("title", title).put("notes", detail) }
        }
        body.put("updatedAt", now())
        dao.upsert(item.copy(title = title, payload = body.toString(), updatedAt = System.currentTimeMillis(), dirty = true))
    }

    /** Approving treatment changes only the chosen project's treatment status, retaining other versions. */
    suspend fun approveTreatment(projectId: String, id: String) = db.withTransaction {
        val choices = dao.listByProject("treatments", projectId)
        require(choices.any { it.id == id }) { "Treatment not in this project" }
        for (item in choices) {
            val body = doc(item)
            if (item.id == id) body.put("status", "approved")
            else if (body.optString("status") == "approved") body.put("status", "superseded")
            else continue
            body.put("updatedAt", now())
            dao.upsert(item.copy(payload = body.toString(), updatedAt = System.currentTimeMillis(), dirty = true))
        }
    }

    /** Previous approved script versions remain frozen in the versions array. */
    suspend fun approveScript(id: String) {
        val item = requireNotNull(dao.find("artifacts", id))
        val obj = doc(item)
        val draft = obj.optString("draft")
        require(draft.isNotBlank()) { "Write a script before approving it" }
        val versions = obj.optJSONArray("versions") ?: JSONArray()
        for (i in 0 until versions.length()) {
            val version = versions.optJSONObject(i) ?: continue
            if (version.optString("status") == "approved") version.put("status", "superseded")
        }
        val versionId = uid()
        versions.put(JSONObject().put("id", versionId).put("artifactId", id)
            .put("text", draft).put("status", "approved").put("author", "user")
            .put("note", "Approved in native Studio").put("createdAt", now()))
        obj.put("versions", versions).put("approvedVersionId", versionId).put("updatedAt", now())
        dao.upsert(item.copy(payload = obj.toString(), dirty = true, updatedAt = System.currentTimeMillis()))
    }

    suspend fun toggleChecklist(id: String) {
        val item = requireNotNull(dao.find("checklist", id))
        val body = doc(item)
        body.put("status", if (body.optString("status") == "ready") "unresolved" else "ready")
        body.put("updatedAt", now())
        dao.upsert(item.copy(payload = body.toString(), dirty = true, updatedAt = System.currentTimeMillis()))
    }


    /**
     * A server-validated AI option is accepted into Room only after the user taps Accept.
     * The server never chooses IDs or approves drafts on our behalf.
     */
    suspend fun acceptGenerated(projectId: String, op: String, proposed: JSONObject): StudioRecord {
        require(op == "treatments" || op == "beats") { "Unsupported generated record" }
        requireNotNull(dao.find("projects", projectId)) { "Unknown project" }
        val collection = if (op == "treatments") "treatments" else "beats"
        val key = uid()
        val stamp = now()
        val body = JSONObject(proposed.toString())
            .put("id", key)
            .put("projectId", projectId)
            .put("createdAt", stamp)
            .put("updatedAt", stamp)
            .put("generatedBy", "ai:claude-haiku-4-5")
        if (op == "treatments") {
            body.put("status", "draft").put("author", "assistant")
            body.put("parentIds", JSONArray())
            val citations = body.optJSONArray("citations") ?: JSONArray()
            val labels = JSONArray()
            for (i in 0 until citations.length()) labels.put(citations.getString(i))
            body.put("sources", labels)
            body.remove("citations")
        } else {
            val existing = dao.listByProject("beats", projectId)
            body.put("order", existing.size + 1)
                .put("shotIds", JSONArray())
                .put("origin", "visual")
            body.remove("sceneHint")
            body.remove("citations")
        }
        val title = body.optString("title").take(120)
        require(title.isNotBlank()) { "The AI option is missing its title" }
        return row(collection, key, title, body).also { dao.upsert(it) }
    }
}
