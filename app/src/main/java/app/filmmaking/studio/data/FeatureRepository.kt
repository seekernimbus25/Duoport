package app.filmmaking.studio.data

import androidx.room.withTransaction
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

/**
 * Native ports of the web Studio rules for the parity checklist (FEATURE_PARITY_CHECKLIST.md).
 * Every operation is offline and stores the web domain shape, so cloud sync and exports stay lossless.
 */
class FeatureRepository(private val db: StudioDatabase) {
    private val dao = db.dao()
    private fun uid() = UUID.randomUUID().toString()
    private fun now() = Instant.now().toString()
    private fun ms() = System.currentTimeMillis()
    private suspend fun get(c: String, id: String) = requireNotNull(dao.find(c, id)?.takeIf { it.deletedAt == null }) { "Item no longer exists" }
    private suspend fun save(r: StudioRecord, body: JSONObject, title: String = r.title) =
        dao.upsert(r.copy(title = title, payload = body.put("updatedAt", now()).toString(), updatedAt = ms(), dirty = true))
    private suspend fun insert(c: String, body: JSONObject, title: String): StudioRecord {
        val id = body.optString("id").ifBlank { uid().also { body.put("id", it) } }
        return StudioRecord(c, id, title.take(140), body.toString()).also { dao.upsert(it) }
    }
    private fun JSONArray?.strings() = this?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()

    // ---------- #1 Idea capture: text, link, image, voice. The original text never changes. ----------
    suspend fun attachToIdea(ideaId: String, kind: String, value: String, label: String = "") {
        require(kind in setOf("link", "image", "voice")) { "Unsupported attachment" }
        require(value.isNotBlank()) { "Nothing to attach" }
        if (kind == "link") require(value.startsWith("https://") || value.startsWith("http://")) { "Links must start with http(s)://" }
        val r = get("ideas", ideaId); val b = JSONObject(r.payload)
        val target = if (kind == "link") "sources" else "attachments"
        val list = b.optJSONArray(target) ?: JSONArray()
        list.put(JSONObject().put("id", uid()).put("kind", kind).put("uri", value.take(2000)).put("label", label.take(200)).put("addedAt", now()))
        b.put(target, list)
        save(r, b)
    }

    suspend fun expandIdea(ideaId: String, text: String, author: String = "user") {
        require(text.isNotBlank())
        val r = get("ideas", ideaId); val b = JSONObject(r.payload)
        val list = b.optJSONArray("expansions") ?: JSONArray()
        list.put(JSONObject().put("id", uid()).put("text", text.take(4000)).put("author", author).put("at", now()))
        save(r, b.put("expansions", list))
    }

    // ---------- #4 Persistent AI Director conversations ----------
    suspend fun newConversation(projectId: String?, model: String, mode: String, role: String): StudioRecord {
        val b = JSONObject().put("projectId", projectId ?: JSONObject.NULL).put("model", model).put("mode", mode)
            .put("role", role).put("title", "Conversation " + now().take(16).replace('T', ' ')).put("createdAt", now()).put("updatedAt", now())
        return insert("aiConversations", b, b.getString("title"))
    }

    suspend fun addMessage(conversationId: String, from: String, text: String, citations: JSONArray = JSONArray()): StudioRecord {
        require(from == "user" || from == "assistant")
        val conv = get("aiConversations", conversationId)
        val b = JSONObject().put("conversationId", conversationId).put("from", from).put("text", text.take(20000))
            .put("citations", citations).put("proposalIds", JSONArray()).put("at", now())
        if (from == "user" && JSONObject(conv.payload).optString("title").startsWith("Conversation "))
            save(conv, JSONObject(conv.payload).put("title", text.take(60)), text.take(60))
        return insert("aiMessages", b, text.take(60))
    }

    // ---------- #6 Beat board: reorder swaps `order` only; links survive ----------
    suspend fun moveBeat(projectId: String, beatId: String, up: Boolean) = db.withTransaction {
        val beats = dao.listByProject("beats", projectId).sortedBy { JSONObject(it.payload).optDouble("order") }
        val i = beats.indexOfFirst { it.id == beatId }
        val j = if (up) i - 1 else i + 1
        if (i < 0 || j !in beats.indices) return@withTransaction
        val a = JSONObject(beats[i].payload); val b = JSONObject(beats[j].payload)
        val oa = a.optDouble("order"); val ob = b.optDouble("order")
        save(beats[i], a.put("order", ob)); save(beats[j], b.put("order", oa))
    }

    suspend fun linkBeatShots(beatId: String, shotIds: List<String>) = db.withTransaction {
        val beat = get("beats", beatId); val b = JSONObject(beat.payload)
        val old = b.optJSONArray("shotIds").strings().toSet()
        save(beat, b.put("shotIds", JSONArray(shotIds.distinct())))
        for (id in old + shotIds) {
            val shot = dao.find("shots", id) ?: continue
            val s = JSONObject(shot.payload)
            if (id in shotIds) s.put("beatId", beatId) else if (s.optString("beatId") == beatId) s.remove("beatId") else continue
            save(shot, s)
        }
    }

    // ---------- #7 Shot proposals: pending until the user accepts; accepted shot keeps beat + proposal ids ----------
    suspend fun saveShotProposals(projectId: String, sceneId: String, beatId: String?, items: List<JSONObject>, generatedBy: String): List<StudioRecord> {
        require(items.isNotEmpty()) { "No proposals" }
        get("scenes", sceneId)
        val batch = uid()
        return items.mapIndexed { i, g ->
            val b = JSONObject(g.toString()).put("projectId", projectId).put("sceneId", sceneId).put("batchId", batch)
                .put("order", i + 1).put("status", "pending").put("generatedBy", generatedBy).put("createdAt", now())
            if (beatId != null) b.put("beatId", beatId)
            b.remove("id")
            insert("shotProposals", b, g.optString("title").ifBlank { "Shot proposal" })
        }
    }

    suspend fun decideShotProposal(proposalId: String, accept: Boolean): String? = db.withTransaction {
        val p = get("shotProposals", proposalId); val b = JSONObject(p.payload)
        require(b.optString("status") == "pending") { "This proposal was already decided" }
        if (!accept) { save(p, b.put("status", "rejected")); return@withTransaction null }
        val shot = StudioRepository(db).addShot(b.getString("sceneId"), b.optString("title").ifBlank { "Proposed shot" })
        val s = JSONObject(shot.payload).put("proposalId", proposalId).put("purpose", b.optString("purpose"))
            .put("description", listOf(b.optString("composition"), b.optString("framing")).filter { it.isNotBlank() }.joinToString(" · "))
        b.optString("beatId").takeIf { it.isNotBlank() }?.let { s.put("beatId", it) }
        s.optJSONObject("camera")?.let { c ->
            listOf("shotSize", "angle", "movement", "lens", "frameRate", "focus").forEach { k -> b.optString(k).takeIf { it.isNotBlank() }?.let { c.put(k, it.take(80)) } }
        }
        b.optString("lighting").takeIf { it.isNotBlank() }?.let { s.optJSONObject("lighting")?.put("source", it) }
        b.optString("sound").takeIf { it.isNotBlank() }?.let { s.optJSONObject("sound")?.put("ambient", it) }
        if (b.optBoolean("pickup")) s.put("pickup", true)
        save(shot, s)
        b.optString("beatId").takeIf { it.isNotBlank() }?.let { beatId ->
            dao.find("beats", beatId)?.let { beat ->
                val bb = JSONObject(beat.payload); val ids = bb.optJSONArray("shotIds") ?: JSONArray()
                save(beat, bb.put("shotIds", ids.put(shot.id)))
            }
        }
        save(p, b.put("status", "accepted").put("acceptedShotId", shot.id))
        shot.id
    }

    // ---------- #8 Script critique: a revision is a NEW draft version; approved text is untouched ----------
    suspend fun addDraftVersion(artifactId: String, text: String, note: String, author: String): String {
        require(author == "user" || author == "assistant")
        require(text.isNotBlank()) { "Revision is empty" }
        val r = get("artifacts", artifactId); val b = JSONObject(r.payload)
        val versions = b.optJSONArray("versions") ?: JSONArray()
        val id = uid()
        versions.put(JSONObject().put("id", id).put("artifactId", artifactId).put("text", text.take(60000))
            .put("status", "draft").put("author", author).put("note", note.take(500)).put("createdAt", now()))
        b.put("versions", versions).put("draft", text.take(60000))
        save(r, b)
        return id
    }

    // ---------- #9 Storyboards: chosen frame unique per shot ----------
    suspend fun addStoryboard(projectId: String, shotId: String, imageUri: String, source: String, annotations: JSONObject, conceptual: Boolean): StudioRecord {
        require(source in setOf("upload", "drawing", "archive", "ai"))
        get("shots", shotId)
        val n = dao.listByProject("storyboards", projectId).count { JSONObject(it.payload).optString("shotId") == shotId } + 1
        val b = JSONObject().put("projectId", projectId).put("shotId", shotId).put("version", n).put("source", source)
            .put("imageUri", imageUri).put("conceptual", conceptual || source == "ai").put("annotations", annotations)
            .put("chosen", false).put("createdAt", now())
        return insert("storyboards", b, "Frame v$n")
    }

    suspend fun chooseStoryboard(frameId: String) = db.withTransaction {
        val f = get("storyboards", frameId); val fb = JSONObject(f.payload)
        for (o in dao.listByProject("storyboards", fb.getString("projectId"))) {
            val ob = JSONObject(o.payload)
            if (ob.optString("shotId") != fb.optString("shotId")) continue
            val want = o.id == frameId
            if (ob.optBoolean("chosen") != want) save(o, ob.put("chosen", want))
        }
    }

    // ---------- #10 Diagrams (metres): editing an approved diagram forks a new draft ----------
    suspend fun createDiagram(projectId: String, sceneId: String, shotId: String?, name: String): StudioRecord {
        require(name.isNotBlank())
        val b = JSONObject().put("projectId", projectId).put("sceneId", sceneId).put("shotId", shotId ?: JSONObject.NULL)
            .put("name", name.take(120)).put("version", 1).put("status", "draft").put("width", 10).put("height", 8)
            .put("elements", JSONArray()).put("createdAt", now())
        return insert("diagrams", b, name)
    }

    /** Returns the id of the diagram that now holds the change (a fork when the original was approved). */
    suspend fun editDiagram(diagramId: String, change: (JSONArray) -> JSONArray): String = db.withTransaction {
        val d = get("diagrams", diagramId); val b = JSONObject(d.payload)
        if (b.optString("status") == "approved") {
            val fork = JSONObject(b.toString()).put("id", uid()).put("status", "draft").put("parentId", diagramId)
                .put("version", b.optInt("version", 1) + 1).put("createdAt", now())
            fork.put("elements", change(JSONArray(b.optJSONArray("elements")?.toString() ?: "[]")))
            insert("diagrams", fork, b.optString("name")).id
        } else {
            save(d, b.put("elements", change(b.optJSONArray("elements") ?: JSONArray())))
            diagramId
        }
    }

    suspend fun addDiagramElement(diagramId: String, type: String, x: Double, y: Double, label: String, rotation: Double = 0.0): String {
        require(type in DIAGRAM_TYPES) { "Unknown element type" }
        require(x in 0.0..50.0 && y in 0.0..50.0) { "Position must be within the set (metres)" }
        return editDiagram(diagramId) { els ->
            els.put(JSONObject().put("id", uid()).put("type", type).put("x", x).put("y", y).put("rotation", rotation).put("label", label.take(80)))
        }
    }

    suspend fun removeDiagramElement(diagramId: String, elementId: String): String = editDiagram(diagramId) { els ->
        JSONArray((0 until els.length()).map { els.getJSONObject(it) }.filter { it.optString("id") != elementId })
    }

    suspend fun approveDiagram(diagramId: String) = db.withTransaction {
        val d = get("diagrams", diagramId); val b = JSONObject(d.payload)
        for (o in dao.listByProject("diagrams", b.getString("projectId"))) {
            if (o.id == diagramId) continue
            val ob = JSONObject(o.payload)
            if (ob.optString("status") == "approved" && ob.optString("name") == b.optString("name") &&
                ob.optString("shotId") == b.optString("shotId") && ob.optString("sceneId") == b.optString("sceneId"))
                save(o, ob.put("status", "superseded"))
        }
        save(d, b.put("status", "approved"))
    }

    // ---------- #11 Lookbook: applying copies elements with new IDs; the recipe never changes ----------
    suspend fun saveDiagramAsRecipe(diagramId: String, name: String): StudioRecord {
        val d = JSONObject(get("diagrams", diagramId).payload)
        val els = JSONArray((0 until (d.optJSONArray("elements")?.length() ?: 0)).map {
            JSONObject(d.getJSONArray("elements").getJSONObject(it).toString()).apply { remove("shotId") }
        })
        val b = JSONObject().put("projectId", d.optString("projectId")).put("name", name.ifBlank { d.optString("name") + " recipe" })
            .put("kind", "Lighting setup").put("effect", "").put("narrativeUse", "").put("elements", els)
            .put("equipment", "").put("guidance", "Saved from diagram " + d.optString("name")).put("limitations", "")
            .put("exampleShotIds", JSONArray().apply { d.optString("shotId").takeIf { it.isNotBlank() && it != "null" }?.let { put(it) } })
            .put("tags", JSONArray()).put("inCreativeDna", false).put("createdAt", now())
        return insert("recipes", b, b.getString("name"))
    }

    suspend fun applyRecipe(recipeId: String, projectId: String, sceneId: String, shotId: String?): StudioRecord {
        val recipe = JSONObject(get("recipes", recipeId).payload)
        val els = recipe.optJSONArray("elements") ?: JSONArray()
        val copied = JSONArray((0 until els.length()).map { JSONObject(els.getJSONObject(it).toString()).put("id", uid()) })
        val diagram = createDiagram(projectId, sceneId, shotId, recipe.optString("name"))
        val b = JSONObject(diagram.payload).put("elements", copied).put("recipeId", recipeId)
        save(diagram, b)
        return diagram
    }

    // ---------- #12 AI cinematographer: only listed element ids change; preview before accept ----------
    fun previewSetupChanges(diagram: JSONObject, changes: JSONArray): JSONArray {
        val els = (0 until (diagram.optJSONArray("elements")?.length() ?: 0)).map { JSONObject(diagram.getJSONArray("elements").getJSONObject(it).toString()) }.toMutableList()
        val ids = els.map { it.optString("id") }.toSet()
        for (i in 0 until changes.length()) {
            val c = changes.getJSONObject(i)
            val target = c.optString("elementId")
            when (c.optString("action")) {
                "remove" -> if (target in ids) els.removeAll { it.optString("id") == target }
                "update" -> if (target in ids) els.first { it.optString("id") == target }.let { e ->
                    listOf("x", "y", "rotation", "label", "x2", "y2", "fov").forEach { k -> if (c.has(k) && !c.isNull(k)) e.put(k, c.get(k)) }
                }
                "add" -> {
                    val type = c.optString("type")
                    if (type in DIAGRAM_TYPES && c.has("x") && !c.isNull("x") && c.has("y") && !c.isNull("y"))
                        els += JSONObject().put("id", uid()).put("type", type).put("x", c.getDouble("x")).put("y", c.getDouble("y"))
                            .put("rotation", c.optDouble("rotation", 0.0)).put("label", c.optString("label").take(80))
                }
            }
        }
        return JSONArray(els)
    }

    suspend fun acceptSetupChanges(diagramId: String, previewed: JSONArray): String = editDiagram(diagramId) { previewed }

    // ---------- #13/#14 Shoot days: shooting order never changes narrative order; history kept ----------
    suspend fun setDayDraftOrder(dayId: String, shotIds: List<String>) {
        val d = get("shootDays", dayId)
        save(d, JSONObject(d.payload).put("draftOrder", JSONArray(shotIds.distinct())))
    }

    suspend fun approveDayOrder(dayId: String, order: List<String>, reason: String) = db.withTransaction {
        val d = get("shootDays", dayId); val b = JSONObject(d.payload)
        val history = b.optJSONArray("history") ?: JSONArray()
        if ((b.optJSONArray("order")?.length() ?: 0) > 0)
            history.put(JSONObject().put("order", b.getJSONArray("order")).put("replacedAt", now()).put("reason", reason.take(200)))
        b.put("order", JSONArray(order.distinct())).put("history", history).remove("draftOrder")
        save(d, b)
        // shootOrder follows the approved day order; storyOrder (narrative) is never touched.
        order.distinct().forEachIndexed { i, id ->
            dao.find("shots", id)?.let { s -> save(s, JSONObject(s.payload).put("shootOrder", i + 1).put("shootDayId", dayId)) }
        }
    }

    suspend fun saveScheduleProposal(projectId: String, dayId: String, result: JSONObject, generatedBy: String): StudioRecord {
        val b = JSONObject().put("projectId", projectId).put("dayId", dayId)
            .put("order", result.optJSONArray("order") ?: JSONArray()).put("reasoning", result.optString("reasoning"))
            .put("groups", result.optJSONArray("groups") ?: JSONArray()).put("warnings", result.optJSONArray("warnings") ?: JSONArray())
            .put("estimatedMin", result.optInt("estimatedMin")).put("status", "pending").put("generatedBy", generatedBy).put("createdAt", now())
        return insert("scheduleProposals", b, "Schedule proposal")
    }

    suspend fun decideScheduleProposal(id: String, approve: Boolean) = db.withTransaction {
        val p = get("scheduleProposals", id); val b = JSONObject(p.payload)
        require(b.optString("status") == "pending") { "Already decided" }
        if (approve) approveDayOrder(b.getString("dayId"), b.optJSONArray("order").strings(), "AI schedule proposal approved")
        save(p, b.put("status", if (approve) "approved" else "rejected").put("decidedAt", now()))
    }

    // ---------- #15 Production setups: a shot belongs to at most one setup ----------
    suspend fun assignShotToSetup(setupId: String, shotId: String, assign: Boolean) = db.withTransaction {
        val setup = get("setups", setupId); val sb = JSONObject(setup.payload)
        if (assign) for (o in dao.listByProject("setups", sb.getString("projectId"))) {
            if (o.id == setupId) continue
            val ob = JSONObject(o.payload); val ids = ob.optJSONArray("shotIds").strings()
            if (shotId in ids) save(o, ob.put("shotIds", JSONArray(ids - shotId)))
        }
        val ids = sb.optJSONArray("shotIds").strings()
        save(setup, sb.put("shotIds", JSONArray(if (assign) (ids + shotId).distinct() else ids - shotId)))
    }

    // ---------- #16 Checklist: regenerate keeps statuses (stable keys); optional never blocks ----------
    suspend fun regenerateChecklist(projectId: String): Int = db.withTransaction {
        val existing = dao.listByProject("checklist", projectId).associateBy { JSONObject(it.payload).optString("key") }
        val wanted = linkedMapOf<String, Triple<String, String, MutableSet<String>>>()
        fun want(category: String, label: String, shotId: String) {
            val key = "auto:" + category.lowercase() + ":" + label.trim().lowercase()
            wanted.getOrPut(key) { Triple(category, label.trim(), linkedSetOf()) }.third += shotId
        }
        for (s in dao.listByProject("shots", projectId).map { JSONObject(it.payload) }) {
            val id = s.getString("id"); val prod = s.optJSONObject("production") ?: JSONObject()
            listOf("equipment" to "Equipment", "props" to "Props", "wardrobe" to "Wardrobe", "talent" to "Talent").forEach { (k, cat) ->
                prod.optString(k).split(',', '\n').map { it.trim() }.filter { it.isNotBlank() }.forEach { want(cat, it, id) }
            }
            s.optJSONObject("camera")?.optString("lens")?.takeIf { it.isNotBlank() }?.let { want("Camera", "Lens: $it", id) }
            if (s.optJSONObject("sound")?.optString("roomTone").orEmpty().isNotBlank()) want("Audio", "Record room tone", id)
        }
        var added = 0
        for ((key, v) in wanted) {
            val old = existing[key]
            if (old != null) {
                val b = JSONObject(old.payload)
                if (b.optJSONArray("shotIds").strings().toSet() != v.third) save(old, b.put("shotIds", JSONArray(v.third.toList())))
            } else {
                insert("checklist", JSONObject().put("projectId", projectId).put("key", key).put("category", v.first)
                    .put("label", v.second).put("status", "unresolved").put("optional", false).put("source", "auto")
                    .put("shotIds", JSONArray(v.third.toList())).put("note", "").put("updatedAt", now()), v.second)
                added++
            }
        }
        added
    }

    suspend fun setChecklistStatus(id: String, status: String) {
        require(status in setOf("unresolved", "ready", "unavailable", "na"))
        val r = get("checklist", id); save(r, JSONObject(r.payload).put("status", status))
    }

    suspend fun setChecklistOptional(id: String, optional: Boolean) {
        val r = get("checklist", id); save(r, JSONObject(r.payload).put("optional", optional))
    }

    // ---------- #19 Takes: audio / room tone, ratings ----------
    suspend fun logSoundTake(shotId: String, kind: String, notes: String): StudioRecord = db.withTransaction {
        require(kind == "audio" || kind == "roomTone")
        val s = JSONObject(get("shots", shotId).payload)
        val projectId = s.getString("projectId")
        val n = 1 + dao.listByProject("takes", projectId).count { JSONObject(it.payload).optString("shotId") == shotId }
        insert("takes", JSONObject().put("projectId", projectId).put("shotId", shotId).put("number", n)
            .put("kind", kind).put("mediaAssetIds", JSONArray()).put("capturedAt", now()).put("settings", JSONObject())
            .put("preferred", false).put("favourite", false).put("quality", "usable").put("directorNotes", notes.take(2000))
            .put("continuityNotes", "").put("source", "separate-recorder").put("unplanned", false), (if (kind == "roomTone") "Room tone " else "Audio ") + n)
    }

    suspend fun rateTakeStars(takeId: String, stars: Int) {
        require(stars in 1..5)
        val r = get("takes", takeId); save(r, JSONObject(r.payload).put("rating", stars))
    }

    suspend fun setTakeNotes(takeId: String, notes: String) {
        val r = get("takes", takeId); save(r, JSONObject(r.payload).put("directorNotes", notes.take(2000)))
    }

    // ---------- #20 Continuity ----------
    suspend fun setContinuity(id: String, basis: String? = null, resolved: Boolean? = null) {
        val r = get("continuity", id); val b = JSONObject(r.payload)
        basis?.let { require(it == "record" || it == "speculative"); b.put("basis", it) }
        resolved?.let { b.put("resolved", it) }
        save(r, b)
    }

    // ---------- #21 Coverage, pickups, omissions ----------
    suspend fun addPickup(sceneId: String, title: String, reason: String): StudioRecord = db.withTransaction {
        val shot = StudioRepository(db).addShot(sceneId, title)
        val b = JSONObject(shot.payload).put("pickup", true).put("purpose", reason.take(500))
        save(shot, b)
        shot
    }

    suspend fun omitShot(shotId: String, reason: String): StudioRecord {
        require(reason.isNotBlank()) { "Say why the shot is intentionally omitted" }
        val s = JSONObject(get("shots", shotId).payload)
        return insert("omissions", JSONObject().put("projectId", s.getString("projectId")).put("shotId", shotId)
            .put("what", s.optString("code") + " " + s.optString("title")).put("reason", reason.take(500)).put("createdAt", now()),
            "Omitted " + s.optString("code"))
    }

    suspend fun removeRecord(collection: String, id: String) { get(collection, id); dao.tombstone(collection, id) }

    // ---------- #27 Camera profile: verified only when device-reported ----------
    suspend fun saveCameraProfile(profile: JSONObject, deviceReported: Boolean) {
        val id = "00000000-0000-4000-8000-0000000000ca"
        val b = JSONObject(profile.toString()).put("id", id).put("verified", deviceReported)
            .put("source", if (deviceReported) "device-reported (Android Camera2)" else "user-entered").put("updatedAt", now())
        dao.upsert(StudioRecord("cameraProfiles", id, "Camera profile", b.toString()))
    }

    companion object {
        val DIAGRAM_TYPES = listOf("camera", "subject", "furniture", "wall", "door", "window", "cameraMove", "subjectMove",
            "key", "fill", "back", "practical", "natural", "reflector", "diffuser", "negative")
    }
}
