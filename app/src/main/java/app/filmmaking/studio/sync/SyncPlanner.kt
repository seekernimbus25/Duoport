package app.filmmaking.studio.sync

/**
 * Port of the web `planSync` decision table (screenshot-match CLOUD_BACKEND.md):
 *
 * | device    | cloud                  | action                         |
 * |-----------|------------------------|--------------------------------|
 * | new       | absent                 | create                         |
 * | changed   | unchanged since base   | update where version = base    |
 * | unchanged | changed                | pull                           |
 * | unchanged | tombstoned             | remove locally                 |
 * | deleted   | unchanged              | tombstone where version = base |
 * | changed   | changed / tombstoned   | conflict                       |
 * | deleted   | changed                | conflict                       |
 * | present   | present, no base, diff | conflict                       |
 *
 * No last-write-wins: anything ambiguous becomes a conflict the user resolves.
 */
data class BaseEntry(val version: Long, val hash: String)

sealed class SyncOp {
    abstract val table: String
    abstract val id: String
    val key get() = CloudMapping.key(table, id)

    data class Create(override val table: String, override val id: String, val row: CloudRow) : SyncOp()
    data class Update(override val table: String, override val id: String, val row: CloudRow, val baseVersion: Long) : SyncOp()
    data class Delete(override val table: String, override val id: String, val baseVersion: Long) : SyncOp()
    data class Pull(override val table: String, override val id: String, val remote: RemoteRow) : SyncOp()
    data class RemoveLocal(override val table: String, override val id: String, val remote: RemoteRow) : SyncOp()
    data class Conflict(
        override val table: String, override val id: String, val reason: String,
        val local: CloudRow?, val remote: RemoteRow
    ) : SyncOp()
}

enum class Resolution { MINE, CLOUD }

data class SyncPlan(
    val ops: List<SyncOp>,
    val inSync: Map<String, BaseEntry>
) {
    val sends get() = ops.count { it is SyncOp.Create || it is SyncOp.Update || it is SyncOp.Delete }
    val receives get() = ops.count { it is SyncOp.Pull || it is SyncOp.RemoveLocal }
    val conflicts get() = ops.filterIsInstance<SyncOp.Conflict>()
}

object SyncPlanner {
    const val DELETED = "deleted"

    fun plan(local: List<CloudRow>, remote: List<RemoteRow>, base: Map<String, BaseEntry>): SyncPlan {
        val ops = mutableListOf<SyncOp>()
        val inSync = linkedMapOf<String, BaseEntry>()
        for (table in CloudMapping.TABLES) {
            val l = local.filter { it.table == table }.associateBy { it.id }
            val r = remote.filter { it.table == table }.associateBy { it.id }
            val prefix = "$table:"
            val ids = linkedSetOf<String>().apply {
                addAll(l.keys); addAll(r.keys)
                base.keys.filter { it.startsWith(prefix) }.forEach { add(it.removePrefix(prefix)) }
            }
            for (id in ids) {
                val lr = l[id]; val rr = r[id]; val b = base[CloudMapping.key(table, id)]
                val lh = lr?.let { CloudMapping.hash(it.payload) }
                val rAlive = rr?.alive == true
                if (lr != null && rr == null) { ops += SyncOp.Create(table, id, lr); continue }
                if (lr == null && rr == null) continue
                rr!!
                if (lr == null && b == null) {
                    if (rAlive) ops += SyncOp.Pull(table, id, rr)
                    else inSync[CloudMapping.key(table, id)] = BaseEntry(rr.version, DELETED)
                    continue
                }
                if (lr != null && b == null) {
                    if (rAlive && lh == CloudMapping.hash(rr.payload)) inSync[CloudMapping.key(table, id)] = BaseEntry(rr.version, lh!!)
                    else ops += SyncOp.Conflict(table, id,
                        if (rAlive) "different-on-first-sync" else "edited-here-deleted-in-cloud", lr, rr)
                    continue
                }
                b!!
                val rChanged = rr.version != b.version
                if (lr != null) {
                    val lChanged = lh != b.hash
                    when {
                        !lChanged && !rChanged -> Unit
                        lChanged && !rChanged -> ops += SyncOp.Update(table, id, lr, b.version)
                        !lChanged && rChanged -> ops += if (rAlive) SyncOp.Pull(table, id, rr) else SyncOp.RemoveLocal(table, id, rr)
                        rAlive && lh == CloudMapping.hash(rr.payload) -> inSync[CloudMapping.key(table, id)] = BaseEntry(rr.version, lh!!)
                        else -> ops += SyncOp.Conflict(table, id,
                            if (rAlive) "both-edited" else "edited-here-deleted-in-cloud", lr, rr)
                    }
                } else {
                    when {
                        !rChanged -> if (rAlive) ops += SyncOp.Delete(table, id, b.version)
                        !rAlive -> inSync[CloudMapping.key(table, id)] = BaseEntry(rr.version, DELETED)
                        else -> ops += SyncOp.Conflict(table, id, "deleted-here-edited-in-cloud", null, rr)
                    }
                }
            }
        }
        return SyncPlan(ops, inSync)
    }

    /** Turn a conflict into a concrete op according to the user's choice. Nothing is lost silently. */
    fun resolve(op: SyncOp.Conflict, choice: Resolution): SyncOp = when (choice) {
        Resolution.CLOUD -> if (op.remote.alive) SyncOp.Pull(op.table, op.id, op.remote)
            else SyncOp.RemoveLocal(op.table, op.id, op.remote)
        Resolution.MINE -> if (op.local == null) SyncOp.Delete(op.table, op.id, op.remote.version)
            else SyncOp.Update(op.table, op.id, op.local, op.remote.version)
    }
}
