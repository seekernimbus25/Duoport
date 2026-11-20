package app.filmmaking.studio.sync

import app.filmmaking.studio.data.StudioRecord
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.security.MessageDigest

/**
 * Pure mapping between native Room records and the web Studio's Supabase tables
 * (screenshot-match `src/services/cloud/mapping.ts`). Every cloud row keeps the full
 * domain object in `payload`; the other columns exist only for queries, links and policies.
 */
data class CloudRow(
    val table: String,
    val id: String,
    val columns: JSONObject,
    val payload: JSONObject
)

data class RemoteRow(
    val table: String,
    val id: String,
    val version: Long,
    val deletedAt: String?,
    val payload: JSONObject,
    val columns: JSONObject = JSONObject()
) {
    val alive: Boolean get() = deletedAt == null
}

object CloudMapping {
    /** Parent-first push order, identical to the web client. */
    val TABLES = listOf(
        "creative_profiles", "projects", "ideas", "creative_memories", "artifacts", "artifact_versions",
        "scenes", "shots", "takes", "media_assets", "production_items",
        "ai_conversations", "ai_messages", "ai_proposals"
    )

    /** Native collection -> production_items.kind (web PROD_KEYS + native external clips). */
    val PRODUCTION_KINDS = linkedMapOf(
        "tasks" to "task", "equipment" to "equipment", "locations" to "location",
        "schedule" to "schedule", "risks" to "risk", "notes" to "note",
        "references" to "reference", "treatments" to "treatment", "beats" to "beat",
        "shotProposals" to "shot_proposal", "diagrams" to "diagram", "storyboards" to "storyboard",
        "recipes" to "recipe", "shootDays" to "shoot_day", "setups" to "production_setup",
        "scheduleProposals" to "schedule_proposal", "checklist" to "checklist_item",
        "continuity" to "continuity", "omissions" to "omission",
        // Requires migration 20261120120000_external_clip_kind.sql in screenshot-match.
        "externalClips" to "external_clip",
        // Kept losslessly from the web app even where the native UI does not edit them yet.
        "visual" to "visual", "cameraProfiles" to "camera"
    )
    private val KIND_TO_COLLECTION = PRODUCTION_KINDS.entries.associate { (c, k) -> k to c }

    private val DIRECT = mapOf(
        "ideas" to "ideas", "projects" to "projects", "scenes" to "scenes", "shots" to "shots",
        "takes" to "takes", "media" to "media_assets", "memories" to "creative_memories",
        "aiConversations" to "ai_conversations", "aiMessages" to "ai_messages",
        "aiProposals" to "ai_proposals", "profile" to "creative_profiles"
    )
    private val TABLE_TO_COLLECTION = DIRECT.entries.associate { (c, t) -> t to c }

    /** Collections that exist only on this device (never uploaded). */
    fun isSynced(collection: String) =
        collection in DIRECT || collection in PRODUCTION_KINDS || collection == "artifacts"

    fun key(table: String, id: String) = "$table:$id"

    private fun JSONObject.str(name: String): Any = optString(name).takeIf { it.isNotEmpty() } ?: JSONObject.NULL
    private fun JSONObject.num(name: String): Any = if (has(name) && !isNull(name)) get(name) else JSONObject.NULL

    /** Live (non-tombstoned) local records -> cloud rows. */
    fun toRows(records: List<StudioRecord>): List<CloudRow> {
        val out = mutableListOf<CloudRow>()
        for (r in records) {
            if (r.deletedAt != null || !isSynced(r.collection)) continue
            val p = JSONObject(r.payload)
            when (r.collection) {
                "artifacts" -> {
                    val versions = p.optJSONArray("versions") ?: JSONArray()
                    val artifact = JSONObject(p.toString()).apply { remove("versions") }
                    out += CloudRow("artifacts", r.id, JSONObject()
                        .put("project_id", p.str("projectId")).put("kind", p.optString("kind", "script"))
                        .put("approved_version_id", p.str("approvedVersionId")), artifact)
                    for (i in 0 until versions.length()) {
                        val v = versions.optJSONObject(i) ?: continue
                        val vid = v.optString("id").takeIf { it.isNotBlank() } ?: continue
                        val version = JSONObject(v.toString()).put("artifactId", r.id)
                        out += CloudRow("artifact_versions", vid, JSONObject()
                            .put("artifact_id", r.id).put("status", v.optString("status", "draft"))
                            .put("author", v.optString("author", "user")).put("text", v.optString("text"))
                            .put("note", v.optString("note")), version)
                    }
                }
                in PRODUCTION_KINDS -> out += CloudRow("production_items", r.id, JSONObject()
                    .put("kind", PRODUCTION_KINDS.getValue(r.collection))
                    .put("project_id", p.str("projectId")), p)
                else -> {
                    val table = DIRECT.getValue(r.collection)
                    val c = JSONObject()
                    when (table) {
                        "ideas" -> c.put("title", p.optString("title", r.title)).put("status", p.optString("status", "Inbox"))
                            .put("project_id", p.str("projectId"))
                        "projects" -> c.put("name", p.optString("name", r.title)).put("status", p.optString("status", "Idea"))
                            .put("idea_id", p.str("ideaId"))
                        "scenes" -> c.put("project_id", p.str("projectId")).put("number", p.optInt("number", 1))
                            .put("sort_order", p.optDouble("order", 0.0))
                        "shots" -> c.put("project_id", p.str("projectId")).put("scene_id", p.str("sceneId"))
                            .put("code", p.optString("code")).put("story_order", p.optDouble("storyOrder", 0.0))
                            .put("shoot_order", p.optDouble("shootOrder", 0.0)).put("status", p.optString("status", "planned"))
                        "takes" -> c.put("project_id", p.str("projectId")).put("shot_id", p.str("shotId"))
                            .put("number", p.optInt("number", 1)).put("preferred", p.optBoolean("preferred"))
                            .put("quality", p.optString("quality", "review")).put("captured_at", p.str("capturedAt"))
                        // Raw recordings stay on the device: only the URI and technical metadata go up.
                        "media_assets" -> c.put("project_id", p.str("projectId")).put("shot_id", p.str("shotId"))
                            .put("take_id", p.str("takeId")).put("filename", p.optString("filename"))
                            .put("local_uri", p.optString("uri")).put("role", p.optString("role", "master"))
                            .put("mime", p.optString("mime")).put("size_bytes", p.num("sizeBytes"))
                            .put("duration_sec", p.num("durationSec"))
                            .put("checksum", p.optJSONObject("checksum")?.optString("value") ?: JSONObject.NULL)
                            .put("storage_state", "local_only")
                        "creative_memories" -> c.put("scope", p.optString("scope", "identity"))
                            .put("status", p.optString("status", "proposed")).put("text", p.optString("text"))
                            .put("project_id", p.str("projectId"))
                        "ai_conversations" -> c.put("project_id", p.str("projectId"))
                            .put("model", p.optString("model", "Claude")).put("mode", p.optString("mode", "Explore"))
                            .put("role", p.optString("role", "Creative Partner"))
                        "ai_messages" -> c.put("conversation_id", p.str("conversationId"))
                            .put("sender", p.optString("from", "user")).put("text", p.optString("text"))
                        "ai_proposals" -> c.put("conversation_id", p.str("conversationId"))
                            .put("target_type", p.optJSONObject("target")?.optString("type") ?: "idea")
                            .put("status", p.optString("status", "pending"))
                    }
                    out += CloudRow(table, r.id, c, p)
                }
            }
        }
        return out
    }

    /** Which native collection a remote row belongs to (artifact_versions merge into artifacts). */
    fun collectionFor(row: RemoteRow): String? = when (row.table) {
        "artifacts", "artifact_versions" -> "artifacts"
        "production_items" -> KIND_TO_COLLECTION[row.columns.optString("kind")]
            ?: row.payload.optString("__kind").let { KIND_TO_COLLECTION[it] }
        else -> TABLE_TO_COLLECTION[row.table]
    }

    fun titleOf(payload: JSONObject, fallback: String): String =
        listOf("title", "name", "label", "filename", "planned", "text", "code")
            .map { payload.optString(it) }.firstOrNull { it.isNotBlank() }?.take(140) ?: fallback

    /**
     * Canonical, order-independent payload hash. Numbers are normalised so that Postgres
     * jsonb round-trips (4 vs 4.0) do not look like edits.
     */
    fun hash(payload: JSONObject): String {
        val text = canonical(payload)
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return text.length.toString() + ":" + digest.take(12).joinToString("") { "%02x".format(it) }
    }

    fun canonical(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().sorted()
            .joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.opt(it)) }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.opt(it)) }
        is String -> JSONObject.quote(value)
        is Boolean -> value.toString()
        is Number -> runCatching {
            val d = BigDecimal(value.toString()).stripTrailingZeros()
            if (d.signum() == 0) "0" else d.toPlainString()
        }.getOrDefault(value.toString())
        else -> JSONObject.quote(value.toString())
    }
}
