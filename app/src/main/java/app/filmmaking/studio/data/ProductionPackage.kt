package app.filmmaking.studio.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Pure production logic shared by the Wrap screen, Shoot Mode and exports.
 * Port of screenshot-match `src/domain/production.ts` (coverage, wrapReport) and
 * `src/features/handoff/export.ts` (studio-production-package 2.0.0, CSV, Markdown, ZIP).
 */
class ProjectData(rows: List<StudioRecord>, val projectId: String) {
    private val live = rows.filter { it.deletedAt == null }
    fun of(collection: String): List<JSONObject> = live.filter { it.collection == collection }
        .map { JSONObject(it.payload) }.filter { it.optString("projectId") == projectId }

    val project: JSONObject = live.firstOrNull { it.collection == "projects" && it.id == projectId }
        ?.let { JSONObject(it.payload) } ?: error("Project not found")
    val scenes = of("scenes").sortedBy { it.optDouble("order", 0.0) }
    private val sceneIndex = scenes.mapIndexed { i, s -> s.optString("id") to i }.toMap()
    val shots = of("shots").sortedWith(compareBy<JSONObject> { sceneIndex[it.optString("sceneId")] ?: Int.MAX_VALUE }
        .thenBy { it.optDouble("storyOrder", 0.0) })
    val takes = of("takes")
    val media = of("media")
    val omissions = of("omissions")
    val continuity = of("continuity")
    val notes = of("notes")
    val shootDays = of("shootDays").sortedBy { it.optString("date") }
    val artifacts = of("artifacts")

    fun shot(id: String) = shots.firstOrNull { it.optString("id") == id }
}

data class Coverage(
    val planned: Int, val recorded: Int, val usable: Int, val preferred: Int,
    val missing: List<JSONObject>, val missingRoomTone: List<JSONObject>, val missingSound: List<JSONObject>,
    val unresolvedContinuity: List<JSONObject>, val pickups: List<JSONObject>, val retakes: List<JSONObject>
)

object ProductionLogic {
    private fun JSONObject.kind() = optString("kind").ifBlank { "picture" }
    private fun JSONObject.sound(field: String) = optJSONObject("sound")?.optString(field).orEmpty()

    fun coverage(d: ProjectData): Coverage {
        val omitted = d.omissions.map { it.optString("shotId") }.filter { it.isNotBlank() }.toSet()
        fun pic(id: String) = d.takes.filter { it.optString("shotId") == id && it.kind() == "picture" }
        fun sceneHas(sceneId: String, kind: String) = d.takes.any { t ->
            t.kind() == kind && d.shot(t.optString("shotId"))?.optString("sceneId") == sceneId
        }
        val shots = d.shots
        return Coverage(
            planned = shots.count { !it.optBoolean("pickup") && !it.optBoolean("spontaneous") },
            recorded = shots.count { pic(it.optString("id")).isNotEmpty() },
            usable = shots.count { s -> pic(s.optString("id")).any { it.optString("quality") == "usable" } },
            preferred = shots.count { s -> pic(s.optString("id")).any { it.optBoolean("preferred") } },
            missing = shots.filter { s ->
                pic(s.optString("id")).none { it.optString("quality") != "unusable" } &&
                    s.optString("status") != "skipped" && s.optString("id") !in omitted
            },
            // A room-tone take anywhere in the scene satisfies every shot in that scene.
            missingRoomTone = shots.filter { s ->
                s.sound("roomTone").isNotBlank() && !sceneHas(s.optString("sceneId"), "roomTone") && s.optString("id") !in omitted
            },
            missingSound = shots.filter { s ->
                (s.sound("ambient").isNotBlank() || s.sound("foley").isNotBlank()) &&
                    !sceneHas(s.optString("sceneId"), "audio") && pic(s.optString("id")).isEmpty() && s.optString("id") !in omitted
            },
            unresolvedContinuity = d.continuity.filter { !it.optBoolean("resolved") },
            pickups = shots.filter { it.optBoolean("pickup") },
            retakes = shots.filter { it.optString("retake").isNotBlank() }
        )
    }

    fun unresolved(d: ProjectData, cov: Coverage = coverage(d)): List<String> =
        cov.missing.map { "Missing coverage: " + it.optString("code") + " " + it.optString("title") } +
            cov.missingRoomTone.map { "Room tone not recorded for " + it.optString("code") } +
            cov.unresolvedContinuity.map {
                "Continuity (" + it.optString("aspect") + ") on " + (d.shot(it.optString("shotId"))?.optString("code") ?: "?") +
                    ": " + it.optString("observed")
            } +
            cov.retakes.map { "Retake needed: " + it.optString("code") + " — " + it.optString("retake") } +
            d.notes.filter { !it.optBoolean("resolved") }.map { "Note: " + it.optString("text") }

    /** True only when every preferred take has a master whose bytes were really hashed. */
    fun mediaVerified(d: ProjectData): Boolean {
        val preferred = d.takes.filter { it.optBoolean("preferred") }
        if (preferred.isEmpty()) return false
        return preferred.all { t ->
            d.media.any { it.optString("takeId") == t.optString("id") && it.optString("role") == "master" &&
                it.optString("verification") == "verified" }
        }
    }

    fun approved(d: ProjectData, kind: String): Any {
        val a = d.artifacts.firstOrNull { it.optString("kind") == kind } ?: return JSONObject.NULL
        val versions = a.optJSONArray("versions") ?: return JSONObject.NULL
        val key = a.optString("approvedVersionId")
        for (i in 0 until versions.length()) {
            val v = versions.getJSONObject(i)
            if (v.optString("id") == key && v.optString("status") == "approved")
                return JSONObject().put("artifactId", a.optString("id")).put("versionId", key)
                    .put("text", v.optString("text")).put("approvedAt", v.optString("createdAt"))
        }
        return JSONObject.NULL
    }

    fun uriKind(uri: String) = when {
        uri.startsWith("content://") -> "android-content"
        uri.startsWith("file://") || uri.startsWith("/") -> "android-file"
        uri.startsWith("http://") || uri.startsWith("https://") -> "remote"
        uri.startsWith("mock:") -> "simulated"
        else -> "unknown"
    }
}

object ProductionPackage {
    const val SCHEMA = "studio-production-package"
    const val VERSION = "2.0.0"
    private fun arr(items: Iterable<Any>) = JSONArray().also { out -> items.forEach { out.put(it) } }
    private fun JSONObject.opt0(name: String): Any = if (has(name) && !isNull(name)) get(name) else JSONObject.NULL
    private fun JSONObject.idOrNull(name: String): Any = optString(name).takeIf { it.isNotBlank() } ?: JSONObject.NULL

    fun build(rows: List<StudioRecord>, projectId: String, exportedAt: String): JSONObject {
        val d = ProjectData(rows, projectId)
        val p = d.project
        val cov = ProductionLogic.coverage(d)
        val unresolved = ProductionLogic.unresolved(d, cov)
        val takeByShot = d.takes.groupBy { it.optString("shotId") }
        val shots = d.shots.map { s ->
            val ids = takeByShot[s.optString("id")].orEmpty()
            JSONObject(s.toString()).put("takeIds", arr(ids.map { it.optString("id") }))
                .put("preferredTakeId", ids.firstOrNull { it.optBoolean("preferred") }?.optString("id") ?: JSONObject.NULL)
        }
        val relink = d.media.map { m ->
            val uri = m.optString("uri")
            val role = m.optString("role", "master")
            val master = if (role == "derivative") (m.optString("masterId").takeIf { id -> d.media.any { it.optString("id") == id } }
                ?: d.media.firstOrNull { it.optString("takeId") == m.optString("takeId") && it.optString("role") == "master" }?.optString("id"))
                else null
            JSONObject().put("mediaId", m.optString("id")).put("recordingId", m.idOrNull("recordingId"))
                .put("projectId", projectId)
                .put("sceneId", m.optString("sceneId").ifBlank { d.shot(m.optString("shotId"))?.optString("sceneId").orEmpty() }.ifBlank { null } ?: JSONObject.NULL)
                .put("shotId", m.idOrNull("shotId")).put("takeId", m.idOrNull("takeId"))
                .put("role", role).put("masterId", master ?: JSONObject.NULL)
                .put("filename", m.optString("filename"))
                .put("originalFilename", m.optString("originalFilename").ifBlank { m.optString("filename") })
                .put("deviceUri", uri).put("uriKind", ProductionLogic.uriKind(uri)).put("portablePath", JSONObject.NULL)
                .put("mime", m.optString("mime"))
                .put("verification", m.optString("verification").ifBlank { if (m.optBoolean("placeholder")) "simulated" else "reported" })
                .put("checksum", m.optJSONObject("checksum")?.takeIf { it.optString("value").matches(Regex("^[a-f0-9]{64}$")) } ?: JSONObject.NULL)
                .also { o -> listOf("sizeBytes", "frameRate", "width", "height", "durationSec", "timecodeStart", "deliveryFormat").forEach { o.put(it, m.opt0(it)) } }
        }
        val narrative = arr(d.shots.map { it.optString("id") })
        val shotIds = d.shots.map { it.optString("id") }.toSet()
        val shootOrder = arr(d.shootDays.flatMap { day ->
            val o = day.optJSONArray("order") ?: JSONArray()
            (0 until o.length()).map { o.getString(it) }.filter { it in shotIds }
        })

        // Readiness (four separate states; "ready" needs the other three).
        val plannedShots = d.shots.filter { !it.optBoolean("pickup") && !it.optBoolean("spontaneous") }
        val pre = mutableListOf<String>()
        if (d.artifacts.none { it.optString("kind") in setOf("script", "voiceover") && it.optString("approvedVersionId").isNotBlank() })
            pre += "No approved script or voiceover."
        if (plannedShots.isEmpty()) pre += "No planned shots."
        plannedShots.forEach { s ->
            val c = s.optJSONObject("camera")
            if (c?.optString("shotSize").isNullOrBlank() || c?.optString("lens").isNullOrBlank())
                pre += s.optString("code") + " is missing shot size or lens."
        }
        val ready = unresolved.isEmpty()
        val mediaOk = ProductionLogic.mediaVerified(d)
        val external = d.of(ExternalCameraNaming.COLLECTION)
        val mediaReasons = mutableListOf<String>()
        if (!mediaOk) mediaReasons += if (d.media.any { it.optBoolean("placeholder") || it.optString("verification") == "simulated" })
            "Recordings are simulated — no real files to verify." else "Preferred-take masters have not been verified against real files."
        val pendingExternal = external.filter { !it.optBoolean("footageImported") }
        if (pendingExternal.isNotEmpty()) mediaReasons += "${pendingExternal.size} external camera filename(s) not yet linked to imported footage."
        val mediaReady = mediaOk && pendingExternal.isEmpty()
        fun stage(ok: Boolean, reasons: List<String>) = JSONObject().put("ok", ok).put("reasons", JSONArray(reasons.take(20)))
        val readiness = JSONObject()
            .put("preProductionComplete", stage(pre.isEmpty(), pre))
            .put("productionRecordsComplete", stage(ready, unresolved))
            .put("actualMediaVerified", stage(mediaReady, mediaReasons))
            .put("readyForEditorialHandoff", stage(pre.isEmpty() && ready && mediaReady, emptyList()))

        val wrap = JSONObject()
            .put("plannedVsActual", arr(d.shots.map { s ->
                val pt = takeByShot[s.optString("id")].orEmpty()
                val pref = pt.firstOrNull { it.optBoolean("preferred") }
                JSONObject().put("shotId", s.optString("id")).put("code", s.optString("code")).put("title", s.optString("title"))
                    .put("status", s.optString("status")).put("takes", pt.size)
                    .put("preferredTakeId", pref?.optString("id") ?: JSONObject.NULL)
                    .put("plannedSettings", s.optJSONObject("camera") ?: JSONObject())
                    .put("actualSettings", (pref ?: pt.lastOrNull())?.optJSONObject("settings") ?: JSONObject.NULL)
                    .put("omitted", d.omissions.any { it.optString("shotId") == s.optString("id") })
                    .put("pickup", s.optBoolean("pickup"))
            }))
            .put("mediaInventory", arr(relink.map { m ->
                JSONObject().put("id", m.get("mediaId")).put("filename", m.get("filename")).put("role", m.get("role"))
                    .put("shotId", m.get("shotId")).put("takeId", m.get("takeId")).put("masterId", m.get("masterId"))
                    .put("verification", m.get("verification"))
            }))
            .put("bestTakes", arr(d.takes.filter { it.optBoolean("preferred") }.map {
                JSONObject().put("shotId", it.optString("shotId")).put("takeId", it.optString("id"))
                    .put("number", it.optInt("number")).put("rating", it.opt0("rating")).put("notes", it.optString("directorNotes"))
            }))
            .put("additionalFootage", arr(d.takes.filter { it.optBoolean("unplanned") || it.optString("kind") in setOf("audio", "roomTone") }.map {
                JSONObject().put("takeId", it.optString("id")).put("shotId", it.optString("shotId"))
                    .put("kind", it.optString("kind").ifBlank { "picture" }).put("notes", it.optString("directorNotes"))
            }))
            .put("externalCameraReferences", arr(external))
            .put("omissions", arr(d.omissions))
            .put("unresolved", JSONArray(unresolved))
            .put("editorialIntentions", arr(d.shots.filter {
                it.optJSONObject("editorial")?.optString("editorNotes").orEmpty().isNotBlank()
            }.map {
                JSONObject().put("shotId", it.optString("id")).put("code", it.optString("code"))
                    .put("notes", it.optJSONObject("editorial")?.optString("editorNotes"))
                    .put("transition", it.optJSONObject("editorial")?.optString("transition"))
            }))
            .put("readyForEdit", ready)
            .put("counts", JSONObject().put("planned", cov.planned).put("recorded", cov.recorded)
                .put("usable", cov.usable).put("preferred", cov.preferred).put("pickups", cov.pickups.size))

        val storyboards = d.of("storyboards")
        return JSONObject()
            .put("schema", SCHEMA).put("version", VERSION).put("exportedAt", exportedAt)
            .put("source", JSONObject().put("app", "Native Filmmaking Studio + Duoport")
                .put("note", "Device metadata only. No footage transferred."))
            .put("project", JSONObject(p.toString()).apply { if (!has("aspectRatio")) put("aspectRatio", "9:16") })
            .put("artifacts", arr(d.artifacts.map { a ->
                JSONObject().put("id", a.optString("id")).put("kind", a.optString("kind")).put("title", a.optString("title"))
                    .put("approvedVersionId", a.idOrNull("approvedVersionId"))
                    .put("approvedText", (ProductionLogic.approved(d, a.optString("kind")) as? JSONObject)?.optString("text") ?: JSONObject.NULL)
                    .put("workingDraft", a.optString("draft")).put("versions", a.optJSONArray("versions") ?: JSONArray())
            }))
            .put("brief", ProductionLogic.approved(d, "brief"))
            .put("finalScript", ProductionLogic.approved(d, "script"))
            .put("finalVoiceover", ProductionLogic.approved(d, "voiceover"))
            .put("references", arr(d.of("references")))
            .put("treatments", arr(d.of("treatments")))
            .put("beats", arr(d.of("beats").sortedBy { it.optDouble("order", 0.0) }))
            .put("shotProposals", arr(d.of("shotProposals")))
            .put("diagrams", arr(d.of("diagrams")))
            .put("recipes", arr(d.of("recipes")))
            .put("scenes", arr(d.scenes)).put("shots", arr(shots)).put("takes", arr(d.takes)).put("media", arr(d.media))
            .put("mediaRelink", arr(relink))
            .put("narrativeOrder", narrative).put("editOrder", JSONArray(narrative.toString())).put("shootOrder", shootOrder)
            .put("shootDays", arr(d.shootDays.map { day ->
                JSONObject(day.toString()).apply {
                    val o = optJSONArray("order") ?: JSONArray()
                    put("order", arr((0 until o.length()).map { o.getString(it) }.filter { it in shotIds }))
                    if (!has("history")) put("history", JSONArray())
                }
            }))
            .put("setups", arr(d.of("setups").map { st ->
                JSONObject(st.toString()).apply {
                    val o = optJSONArray("shotIds") ?: JSONArray()
                    put("shotIds", arr((0 until o.length()).map { o.getString(it) }.filter { it in shotIds }))
                }
            }))
            .put("scheduleProposals", arr(d.of("scheduleProposals")))
            .put("checklist", arr(d.of("checklist")))
            .put("continuity", arr(d.continuity.filter { it.optString("shotId") in shotIds }))
            .put("omissions", arr(d.omissions))
            .put("notes", arr(d.notes))
            .put("externalCameraReferences", arr(external))
            .put("storyboardIndex", arr(storyboards.filter { it.optString("shotId") in shotIds }.map { f ->
                val image = f.optString("imageUri")
                JSONObject().put("id", f.optString("id")).put("shotId", f.optString("shotId"))
                    .put("version", f.optInt("version", 1)).put("chosen", f.optBoolean("chosen"))
                    .put("source", f.optString("source", "upload")).put("conceptual", f.optBoolean("conceptual"))
                    .put("file", if (image.startsWith("data:image/")) "storyboards/" + f.optString("id") +
                        (if (image.startsWith("data:image/png")) ".png" else ".jpg") else JSONObject.NULL)
            }))
            .put("audioRecords", arr(d.takes.filter { it.optString("kind") in setOf("audio", "roomTone") }.map { t ->
                JSONObject().put("takeId", t.optString("id")).put("shotId", t.optString("shotId"))
                    .put("sceneId", d.shot(t.optString("shotId"))?.optString("sceneId") ?: JSONObject.NULL)
                    .put("kind", t.optString("kind")).put("durationSec", t.opt0("durationSec")).put("notes", t.optString("directorNotes"))
            }))
            .put("editorialRequests", arr(d.shots.mapNotNull { s ->
                val e = s.optJSONObject("editorial") ?: JSONObject(); val c = s.optJSONObject("camera") ?: JSONObject()
                val r = JSONObject().put("shotId", s.optString("id")).put("code", s.optString("code"))
                    .put("transition", e.optString("transition")).put("vfx", e.optString("vfx")).put("sfx", e.optString("sfx"))
                    .put("speed", e.optString("speed")).put("colour", e.optString("colour"))
                    .put("profile", c.optString("profile")).put("lut", c.optString("lut"))
                r.takeIf { listOf("transition", "vfx", "sfx", "lut", "colour").any { k -> r.optString(k).isNotBlank() } }
            }))
            .put("coverage", JSONObject().put("planned", d.shots.size)
                .put("withUsableTakes", d.shots.size - cov.missing.size)
                .put("missingShotIds", arr(cov.missing.map { it.optString("id") })))
            .put("deliverables", arr(listOf(p.optString("aspectRatio", "9:16")) +
                (p.optJSONArray("altFormats")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList())))
            .put("readiness", readiness)
            .put("contents", JSONObject().put("footageIncluded", false)
                .put("note", "Contains production metadata only. Original recordings stay on the recording device and must be relinked by mediaId/checksum."))
            .put("wrapReport", wrap)
    }

    // ---------- CSV (same columns as the web export) ----------
    private fun cell(v: Any?): String {
        val t = if (v == null || v == JSONObject.NULL) "" else v.toString()
        return if (t.contains('"') || t.contains(',') || t.contains('\n')) "\"" + t.replace("\"", "\"\"") + "\"" else t
    }
    private fun csv(rows: List<Map<String, Any?>>, cols: List<String>) =
        (listOf(cols.joinToString(",")) + rows.map { r -> cols.joinToString(",") { cell(r[it]) } }).joinToString("\n") + "\n"
    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }

    fun csv(pkg: JSONObject): Map<String, String> {
        val shots = pkg.getJSONArray("shots").objects()
        val shootOrder = pkg.getJSONArray("shootOrder").let { a -> (0 until a.length()).map { a.getString(it) } }
        fun code(id: Any?) = shots.firstOrNull { it.optString("id") == id?.toString() }?.optString("code").orEmpty()
        return linkedMapOf(
            "shots.csv" to csv(shots.mapIndexed { i, s ->
                val c = s.optJSONObject("camera") ?: JSONObject(); val e = s.optJSONObject("editorial") ?: JSONObject()
                mapOf("narrativeIndex" to i + 1, "shootIndex" to (shootOrder.indexOf(s.optString("id")) + 1).takeIf { it > 0 },
                    "id" to s.optString("id"), "code" to s.optString("code"), "sceneId" to s.optString("sceneId"),
                    "title" to s.optString("title"), "status" to s.optString("status"), "shotSize" to c.optString("shotSize"),
                    "lens" to c.optString("lens"), "fps" to c.optString("frameRate"), "resolution" to c.optString("resolution"),
                    "profile" to c.optString("profile"), "lut" to c.optString("lut"), "preferredTakeId" to s.opt("preferredTakeId"),
                    "pickup" to s.optBoolean("pickup"), "transition" to e.optString("transition"), "vfx" to e.optString("vfx"),
                    "sfx" to e.optString("sfx"), "editorNotes" to e.optString("editorNotes"))
            }, listOf("narrativeIndex", "shootIndex", "id", "code", "sceneId", "title", "status", "shotSize", "lens", "fps",
                "resolution", "profile", "lut", "preferredTakeId", "pickup", "transition", "vfx", "sfx", "editorNotes")),
            "takes.csv" to csv(pkg.getJSONArray("takes").objects().map { t ->
                val st = t.optJSONObject("settings") ?: JSONObject()
                mapOf("id" to t.optString("id"), "shot" to code(t.optString("shotId")), "shotId" to t.optString("shotId"),
                    "number" to t.optInt("number"), "kind" to t.optString("kind").ifBlank { "picture" },
                    "quality" to t.optString("quality"), "preferred" to t.optBoolean("preferred"),
                    "rating" to t.opt("rating"), "unplanned" to t.optBoolean("unplanned"), "capturedAt" to t.optString("capturedAt"),
                    "actualFps" to st.opt("frameRate"), "actualLens" to st.opt("lens"),
                    "directorNotes" to t.optString("directorNotes"), "continuityNotes" to t.optString("continuityNotes"))
            }, listOf("id", "shot", "shotId", "number", "kind", "quality", "preferred", "rating", "unplanned", "capturedAt",
                "actualFps", "actualLens", "directorNotes", "continuityNotes")),
            "media.csv" to csv(pkg.getJSONArray("mediaRelink").objects().map { m ->
                val out = m.keys().asSequence().associateWith { m.opt(it) }.toMutableMap<String, Any?>()
                out["shot"] = code(m.opt("shotId"))
                out["checksum"] = m.optJSONObject("checksum")?.let { it.optString("algo") + ":" + it.optString("value") } ?: ""
                out
            }, listOf("mediaId", "recordingId", "shot", "shotId", "takeId", "role", "masterId", "originalFilename", "filename",
                "deviceUri", "uriKind", "mime", "sizeBytes", "checksum", "frameRate", "width", "height", "durationSec",
                "timecodeStart", "deliveryFormat", "verification")),
            "schedule.csv" to csv(pkg.getJSONArray("shootDays").objects().flatMap { day ->
                val o = day.getJSONArray("order")
                (0 until o.length()).map { i -> mapOf("day" to day.optString("label"), "date" to day.optString("date"),
                    "position" to i + 1, "shot" to code(o.getString(i)), "shotId" to o.getString(i)) }
            }, listOf("day", "date", "position", "shot", "shotId")),
            "external_camera.csv" to csv(pkg.getJSONArray("externalCameraReferences").objects().map { x ->
                mapOf("filename" to x.optString("filename"), "shot" to code(x.optString("shotId")), "shotId" to x.optString("shotId"),
                    "status" to x.optString("status"), "footageImported" to x.optBoolean("footageImported"),
                    "mediaId" to x.optString("mediaId"), "sha256" to x.optString("sha256"))
            }, listOf("filename", "shot", "shotId", "status", "footageImported", "mediaId", "sha256"))
        )
    }

    // ---------- Markdown ----------
    fun markdown(pkg: JSONObject): String {
        val l = mutableListOf<String>()
        val p = pkg.getJSONObject("project")
        val cov = pkg.getJSONObject("coverage")
        l += listOf("# " + p.optString("name") + " — Production Package", "",
            "*" + pkg.optString("schema") + " v" + pkg.optString("version") + " · exported " + pkg.optString("exportedAt") + "*", "",
            "**Logline:** " + p.optString("logline").ifBlank { "—" },
            "**Format:** " + p.optString("format") + " · " + p.optString("aspectRatio") + " · " + p.optInt("targetDurationSec") + "s",
            "**Status:** " + p.optString("status"), "",
            "## Coverage", cov.optInt("withUsableTakes").toString() + "/" + cov.optInt("planned") + " shots have usable takes.", "")
        (pkg.opt("finalVoiceover") as? JSONObject)?.let { l += listOf("## Approved voiceover", "", it.optString("text"), "") }
        (pkg.opt("finalScript") as? JSONObject)?.let { l += listOf("## Approved script", "", it.optString("text"), "") }
        val missing = cov.getJSONArray("missingShotIds").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
        val shots = pkg.getJSONArray("shots").objects(); val takes = pkg.getJSONArray("takes").objects()
        val media = pkg.getJSONArray("media").objects()
        for (sc in pkg.getJSONArray("scenes").objects()) {
            l += "## Scene " + sc.optInt("number") + " — " + sc.optString("title")
            if (sc.optString("purpose").isNotBlank()) l += "_" + sc.optString("purpose") + "_"
            l += ""
            for (s in shots.filter { it.optString("sceneId") == sc.optString("id") }) {
                val c = s.optJSONObject("camera") ?: JSONObject()
                l += "### " + s.optString("code") + " · " + s.optString("title") + if (s.optString("id") in missing) " ⚠ missing coverage" else " ✓"
                l += "- " + s.optString("description").ifBlank { "—" }
                l += "- Camera: " + c.optString("shotSize") + ", " + c.optString("frameRate") + "fps, " + c.optString("resolution") +
                    ", LUT " + c.optString("lut").ifBlank { "—" }
                s.optJSONObject("editorial")?.optString("editorNotes")?.takeIf { it.isNotBlank() }?.let { l += "- Editor: $it" }
                for (t in takes.filter { it.optString("shotId") == s.optString("id") }) {
                    val files = media.filter { it.optString("takeId") == t.optString("id") }
                        .joinToString(", ") { it.optString("filename") + " (" + it.optString("role") + ", " + it.optString("verification") + ")" }
                    l += "  - Take " + t.optInt("number") + (if (t.optBoolean("preferred")) " ★ preferred" else "") + " · " +
                        t.optString("kind").ifBlank { "picture" } + " · " + t.optString("quality") + " · " + files
                }
                l += ""
            }
        }
        val ext = pkg.getJSONArray("externalCameraReferences").objects()
        if (ext.isNotEmpty()) {
            l += "## External camera files"
            ext.forEach { l += "- " + it.optString("filename") + " — " + if (it.optBoolean("footageImported")) "imported, SHA-256 verified" else "filename only, footage NOT imported" }
            l += ""
        }
        val r = pkg.getJSONObject("readiness")
        l += "## Readiness"
        listOf("Pre-production complete" to "preProductionComplete", "Production records complete" to "productionRecordsComplete",
            "Actual media verified" to "actualMediaVerified", "Ready for editorial handoff" to "readyForEditorialHandoff").forEach { (label, k) ->
            val x = r.getJSONObject(k); val reasons = x.getJSONArray("reasons").let { a -> (0 until minOf(3, a.length())).map { a.getString(it) } }
            l += "- " + (if (x.getBoolean("ok")) "✓ " else "✗ ") + label + if (reasons.isNotEmpty()) " — " + reasons.joinToString("; ") else ""
        }
        l += listOf("", "_No original footage is included in this package._", "")
        val w = pkg.getJSONObject("wrapReport"); val counts = w.getJSONObject("counts")
        l += listOf("## Wrap report", counts.optInt("recorded").toString() + "/" + counts.optInt("planned") + " planned shots recorded · " +
            counts.optInt("preferred") + " with preferred takes · " + counts.optInt("pickups") + " pickups.", "")
        w.getJSONArray("omissions").objects().takeIf { it.isNotEmpty() }?.let { o ->
            l += "### Intentional omissions"; o.forEach { l += "- " + it.optString("what") + " — " + it.optString("reason") }; l += ""
        }
        w.getJSONArray("unresolved").let { a -> (0 until a.length()).map { a.getString(it) } }.takeIf { it.isNotEmpty() }?.let { u ->
            l += "### Unresolved"; u.forEach { l += "- $it" }; l += ""
        }
        return l.joinToString("\n")
    }

    /** ZIP of metadata + small reference images. Never contains footage. */
    fun zip(pkg: JSONObject, storyboardImages: Map<String, ByteArray> = emptyMap()): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            fun add(name: String, bytes: ByteArray) { z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry() }
            add("production.json", pkg.toString(2).toByteArray())
            add("production.md", markdown(pkg).toByteArray())
            add("README.txt", (pkg.getJSONObject("project").optString("name") + " — production package " + pkg.optString("version") +
                "\n\nThis archive contains production METADATA and small reference images only.\nIt does NOT contain original footage. " +
                "Relink recordings using media.csv (mediaId, originalFilename, checksum).\nIt is not a DaVinci Resolve timeline.\n").toByteArray())
            csv(pkg).forEach { (n, c) -> add(n, c.toByteArray()) }
            storyboardImages.forEach { (n, b) -> add(n, b) }
            pkg.getJSONArray("diagrams").objects().forEach { add("diagrams/" + it.optString("id") + ".json", it.toString(2).toByteArray()) }
        }
        return out.toByteArray()
    }
}
