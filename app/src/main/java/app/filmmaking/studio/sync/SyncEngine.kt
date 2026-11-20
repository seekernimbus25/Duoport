package app.filmmaking.studio.sync

import androidx.room.withTransaction
import app.filmmaking.studio.data.StudioDatabase
import app.filmmaking.studio.data.StudioRecord
import app.filmmaking.studio.data.SyncBaseRow
import org.json.JSONArray
import org.json.JSONObject

data class SyncOutcome(
    val base: Map<String, BaseEntry>,
    val pulled: List<RemoteRow>,
    val removed: List<RemoteRow>,
    val conflicts: List<SyncOp.Conflict>,
    val errors: List<String>,
    val sent: Int
)

/**
 * Executes a plan against the cloud. Pushes go parent-first; a write that loses a version
 * race becomes a conflict, never an overwrite. Retries are idempotent: when the cloud already
 * holds exactly what this device tried to write, it counts as success.
 */
object SyncExecutor {
    suspend fun execute(
        api: CloudApi, plan: SyncPlan, base: Map<String, BaseEntry>,
        deviceId: String, choices: Map<String, Resolution> = emptyMap()
    ): SyncOutcome {
        val next = base.toMutableMap().apply { putAll(plan.inSync) }
        val pulled = mutableListOf<RemoteRow>()
        val removed = mutableListOf<RemoteRow>()
        val conflicts = mutableListOf<SyncOp.Conflict>()
        val errors = mutableListOf<String>()
        var sent = 0
        val ops = plan.ops.map { op ->
            val choice = choices[op.key]
            if (op is SyncOp.Conflict && choice != null) SyncPlanner.resolve(op, choice) else op
        }.sortedBy { CloudMapping.TABLES.indexOf(it.table) }

        for (op in ops) {
            val key = op.key
            try {
                when (op) {
                    is SyncOp.Conflict -> conflicts += op
                    is SyncOp.Pull -> { pulled += op.remote; next[key] = BaseEntry(op.remote.version, CloudMapping.hash(op.remote.payload)) }
                    is SyncOp.RemoveLocal -> { removed += op.remote; next[key] = BaseEntry(op.remote.version, SyncPlanner.DELETED) }
                    is SyncOp.Create -> {
                        val version = api.insert(op.row, deviceId)
                        val hash = CloudMapping.hash(op.row.payload)
                        if (version != null) { next[key] = BaseEntry(version, hash); sent++; continue }
                        val existing = api.fetchOne(op.table, op.id)
                        when {
                            existing != null && existing.alive && CloudMapping.hash(existing.payload) == hash -> {
                                next[key] = BaseEntry(existing.version, hash); sent++
                            }
                            existing != null -> conflicts += SyncOp.Conflict(op.table, op.id, "different-on-first-sync", op.row, existing)
                            else -> errors += "$key: insert refused"
                        }
                    }
                    is SyncOp.Update, is SyncOp.Delete -> {
                        val isDelete = op is SyncOp.Delete
                        val row = (op as? SyncOp.Update)?.row
                        val baseVersion = (op as? SyncOp.Update)?.baseVersion ?: (op as SyncOp.Delete).baseVersion
                        val version = if (row != null) api.updateIfVersion(row, baseVersion, deviceId)
                            else api.tombstoneIfVersion(op.table, op.id, baseVersion, deviceId)
                        val hash = row?.let { CloudMapping.hash(it.payload) } ?: SyncPlanner.DELETED
                        if (version != null) { next[key] = BaseEntry(version, hash); sent++; continue }
                        val existing = api.fetchOne(op.table, op.id)
                        val done = existing != null && (if (isDelete) !existing.alive
                            else existing.alive && CloudMapping.hash(existing.payload) == hash)
                        when {
                            existing != null && done -> { next[key] = BaseEntry(existing.version, hash); sent++ }
                            existing != null -> conflicts += SyncOp.Conflict(op.table, op.id,
                                when { isDelete -> "deleted-here-edited-in-cloud"; !existing.alive -> "edited-here-deleted-in-cloud"; else -> "both-edited" },
                                row, existing)
                            else -> errors += "$key: missing in cloud"
                        }
                    }
                }
            } catch (e: Exception) {
                errors += key + ": " + (e.message ?: e.javaClass.simpleName).take(200)
            }
        }
        return SyncOutcome(next, pulled, removed, conflicts, errors, sent)
    }
}

/** Turns pulled / removed cloud rows into local Room records. Pure, so it is unit-tested. */
object LocalMerge {
    fun changes(local: List<StudioRecord>, pulled: List<RemoteRow>, removed: List<RemoteRow>,
                now: Long = System.currentTimeMillis()): List<StudioRecord> {
        val byKey = local.associateBy { it.collection + "/" + it.id }
        val out = linkedMapOf<String, StudioRecord>()
        fun current(collection: String, id: String) = out["$collection/$id"] ?: byKey["$collection/$id"]
        fun put(r: StudioRecord) { out[r.collection + "/" + r.id] = r }

        // 1. Plain records and artifact headers.
        for (row in pulled.filter { it.table != "artifact_versions" }) {
            val collection = CloudMapping.collectionFor(row) ?: continue
            val payload = JSONObject(row.payload.toString())
            if (row.table == "artifacts") {
                val old = current("artifacts", row.id)?.let { JSONObject(it.payload) }
                payload.put("versions", old?.optJSONArray("versions") ?: JSONArray())
            }
            put(StudioRecord(collection, row.id, CloudMapping.titleOf(payload, row.id), payload.toString(),
                updatedAt = now, baseVersion = row.version, dirty = false, deletedAt = null))
        }
        for (row in removed.filter { it.table != "artifact_versions" }) {
            val collection = CloudMapping.collectionFor(row) ?: continue
            val old = current(collection, row.id) ?: continue
            put(old.copy(deletedAt = now, updatedAt = now, dirty = false, baseVersion = row.version))
        }
        // 2. Artifact versions live inside their artifact on the device.
        val versionRows = pulled.filter { it.table == "artifact_versions" }.map { it to true } +
            removed.filter { it.table == "artifact_versions" }.map { it to false }
        for ((row, alive) in versionRows) {
            val artifactId = row.payload.optString("artifactId").ifBlank { row.columns.optString("artifact_id") }
            val artifact = current("artifacts", artifactId) ?: continue
            val body = JSONObject(artifact.payload)
            val versions = body.optJSONArray("versions") ?: JSONArray()
            val kept = JSONArray()
            for (i in 0 until versions.length()) {
                val v = versions.optJSONObject(i) ?: continue
                if (v.optString("id") != row.id) kept.put(v)
            }
            if (alive) kept.put(JSONObject(row.payload.toString()))
            val sorted = (0 until kept.length()).map { kept.getJSONObject(it) }.sortedBy { it.optString("createdAt") }
            body.put("versions", JSONArray(sorted))
            put(artifact.copy(payload = body.toString(), updatedAt = now, dirty = false))
        }
        return out.values.toList()
    }
}

data class SyncReport(
    val at: Long,
    val sent: Int,
    val received: Int,
    val conflicts: List<SyncOp.Conflict>,
    val errors: List<String>
) {
    val summary: String get() = buildString {
        append("$sent sent, $received received")
        if (conflicts.isNotEmpty()) append(", ${conflicts.size} conflict(s) need a decision")
        if (errors.isNotEmpty()) append(", ${errors.size} error(s)")
    }
}

/** Room-backed two-way sync for one signed-in user. */
class SyncEngine(
    private val db: StudioDatabase,
    private val api: CloudApi,
    private val userId: String,
    private val deviceId: String,
    private val saveRecoveryPoint: suspend () -> Unit = {}
) {
    private val dao = db.dao()

    suspend fun run(choices: Map<String, Resolution> = emptyMap()): SyncReport {
        require(userId.isNotBlank()) { "Sign in before syncing" }
        val snapshot = dao.allRecords()
        val local = CloudMapping.toRows(snapshot)
        val remote = CloudMapping.TABLES.flatMap { api.fetchAll(it) }
            .filter { it.table == "artifact_versions" || CloudMapping.collectionFor(it) != null }
        val base = dao.syncBase(userId).associate { CloudMapping.key(it.cloudTable, it.id) to BaseEntry(it.version, it.hash) }
        val plan = SyncPlanner.plan(local, remote, base)
        val outcome = SyncExecutor.execute(api, plan, base, deviceId, choices)
        if (outcome.pulled.isNotEmpty() || outcome.removed.isNotEmpty()) saveRecoveryPoint()

        val changes = LocalMerge.changes(snapshot, outcome.pulled, outcome.removed)
        db.withTransaction {
            val before = snapshot.associateBy { it.collection + "/" + it.id }
            val skipped = mutableSetOf<String>()
            for (record in changes) {
                val old = before[record.collection + "/" + record.id]
                val now = dao.find(record.collection, record.id)
                // An edit made while the sync was running stays; its base is not advanced,
                // so the next sync reports a conflict instead of overwriting either side.
                if (now != null && old != null && now.updatedAt != old.updatedAt) { skipped += record.id; continue }
                dao.upsert(record)
            }
            val heldBack = (outcome.pulled + outcome.removed).filter { row ->
                row.id in skipped || (row.table == "artifact_versions" &&
                    (row.payload.optString("artifactId").ifBlank { row.columns.optString("artifact_id") }) in skipped)
            }.map { CloudMapping.key(it.table, it.id) }.toSet()
            dao.saveSyncBase(outcome.base.filterKeys { it !in heldBack || it in base }.map { (key, entry0) ->
                val entry = if (key in heldBack) base.getValue(key) else entry0
                val table = key.substringBefore(":")
                SyncBaseRow(userId, table, key.substringAfter(":"), entry.version, entry.hash)
            })
        }
        return SyncReport(System.currentTimeMillis(), outcome.sent,
            outcome.pulled.size + outcome.removed.size, outcome.conflicts, outcome.errors)
    }
}
