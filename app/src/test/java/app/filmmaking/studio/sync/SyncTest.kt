package app.filmmaking.studio.sync

import app.filmmaking.studio.data.StudioRecord
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory PostgREST stand-in with the same version/tombstone semantics as the sync triggers. */
class FakeCloud : CloudApi {
    data class Row(var version: Long, var deletedAt: String?, var payload: JSONObject, var columns: JSONObject, var device: String)
    val tables = mutableMapOf<String, MutableMap<String, Row>>()
    var failNextUpdateWithRace = false

    private fun t(name: String) = tables.getOrPut(name) { linkedMapOf() }
    private fun remote(table: String, id: String, r: Row) = RemoteRow(table, id, r.version, r.deletedAt, JSONObject(r.payload.toString()), JSONObject(r.columns.toString()))
    override suspend fun fetchAll(table: String) = t(table).map { (id, r) -> remote(table, id, r) }
    override suspend fun fetchOne(table: String, id: String) = t(table)[id]?.let { remote(table, id, it) }
    override suspend fun insert(row: CloudRow, deviceId: String): Long? {
        if (t(row.table).containsKey(row.id)) return null
        t(row.table)[row.id] = Row(1, null, JSONObject(row.payload.toString()), JSONObject(row.columns.toString()), deviceId)
        return 1
    }
    override suspend fun updateIfVersion(row: CloudRow, baseVersion: Long, deviceId: String): Long? {
        val r = t(row.table)[row.id] ?: return null
        if (failNextUpdateWithRace) { failNextUpdateWithRace = false; r.version++; r.payload.put("raced", true) }
        if (r.version != baseVersion) return null
        r.version++; r.deletedAt = null; r.payload = JSONObject(row.payload.toString()); r.columns = JSONObject(row.columns.toString()); r.device = deviceId
        return r.version
    }
    override suspend fun tombstoneIfVersion(table: String, id: String, baseVersion: Long, deviceId: String): Long? {
        val r = t(table)[id] ?: return null
        if (r.version != baseVersion) return null
        r.version++; r.deletedAt = "2026-11-20T00:00:00Z"; r.device = deviceId
        return r.version
    }
    fun editInCloud(table: String, id: String, field: String, value: Any) {
        val r = t(table).getValue(id); r.version++; r.payload.put(field, value)
    }
}

/** A device: its Room records plus its sync base, driven exactly like SyncEngine. */
class Device(val name: String) {
    val records = linkedMapOf<String, StudioRecord>()
    var base = mapOf<String, BaseEntry>()
    var lastConflicts = listOf<SyncOp.Conflict>()

    fun put(collection: String, payload: JSONObject) {
        val id = payload.getString("id")
        records["$collection/$id"] = StudioRecord(collection, id, CloudMapping.titleOf(payload, id), payload.toString())
    }
    fun get(collection: String, id: String) = records["$collection/$id"]?.let { JSONObject(it.payload) }
    fun edit(collection: String, id: String, field: String, value: Any) {
        val r = records.getValue("$collection/$id")
        records["$collection/$id"] = r.copy(payload = JSONObject(r.payload).put(field, value).toString(), updatedAt = r.updatedAt + 1)
    }
    fun delete(collection: String, id: String) {
        val r = records.getValue("$collection/$id"); records["$collection/$id"] = r.copy(deletedAt = 5L)
    }

    fun sync(cloud: FakeCloud, choices: Map<String, Resolution> = emptyMap()): SyncOutcome = runBlocking {
        val snapshot = records.values.toList()
        val remote = CloudMapping.TABLES.flatMap { cloud.fetchAll(it) }
        val plan = SyncPlanner.plan(CloudMapping.toRows(snapshot), remote, base)
        val outcome = SyncExecutor.execute(cloud, plan, base, name, choices)
        LocalMerge.changes(snapshot, outcome.pulled, outcome.removed).forEach { records[it.collection + "/" + it.id] = it }
        base = outcome.base
        lastConflicts = outcome.conflicts
        outcome
    }
}

class SyncTest {
    private fun project(id: String = "p1", name: String = "Tea Film") = JSONObject().put("id", id).put("name", name)
        .put("status", "Idea").put("aspectRatio", "9:16").put("targetDurationSec", 60)
    private fun scene(id: String = "sc1") = JSONObject().put("id", id).put("projectId", "p1").put("number", 1).put("order", 1).put("title", "Opening")
    private fun shot(id: String = "sh1") = JSONObject().put("id", id).put("projectId", "p1").put("sceneId", "sc1")
        .put("code", "S01-01").put("title", "Hero").put("status", "planned").put("storyOrder", 1).put("shootOrder", 1)

    @Test fun hashIgnoresKeyOrderAndJsonbNumberFormatting() {
        val a = JSONObject().put("b", 1).put("a", JSONArray().put(4.0).put("x"))
        val b = JSONObject("{\"a\":[4,\"x\"],\"b\":1.0}")
        assertEquals(CloudMapping.hash(a), CloudMapping.hash(b))
        assertTrue(CloudMapping.hash(a) != CloudMapping.hash(JSONObject(a.toString()).put("b", 2)))
    }

    @Test fun mappingUsesWebTablesAndColumns() {
        val artifact = JSONObject().put("id", "a1").put("projectId", "p1").put("kind", "script").put("draft", "x")
            .put("approvedVersionId", "v1").put("versions", JSONArray().put(JSONObject().put("id", "v1").put("status", "approved")
                .put("author", "user").put("text", "Final").put("note", "").put("createdAt", "2026")))
        val rows = CloudMapping.toRows(listOf(
            StudioRecord("projects", "p1", "Tea", project().toString()),
            StudioRecord("shots", "sh1", "Hero", shot().toString()),
            StudioRecord("artifacts", "a1", "Script", artifact.toString()),
            StudioRecord("treatments", "t1", "T", JSONObject().put("id", "t1").put("projectId", "p1").toString()),
            StudioRecord("externalClips", "x1", "DJI", JSONObject().put("id", "x1").put("projectId", "p1").toString()),
            StudioRecord("projects", "gone", "x", project("gone").toString(), deletedAt = 1L)
        ))
        assertEquals(listOf("projects", "shots", "artifacts", "artifact_versions", "production_items", "production_items"), rows.map { it.table })
        assertEquals("sc1", rows[1].columns.getString("scene_id"))
        assertEquals("S01-01", rows[1].columns.getString("code"))
        assertTrue("versions are split into their own table", !rows[2].payload.has("versions"))
        assertEquals("a1", rows[3].columns.getString("artifact_id"))
        assertEquals("treatment", rows[4].columns.getString("kind"))
        assertEquals("external_clip", rows[5].columns.getString("kind"))
    }

    @Test fun twoDevicesEditPullAndTombstonePropagation() {
        val cloud = FakeCloud(); val phone = Device("phone"); val tablet = Device("tablet")
        phone.put("projects", project()); phone.put("scenes", scene()); phone.put("shots", shot())
        val first = phone.sync(cloud)
        assertEquals(3, first.sent); assertTrue(first.conflicts.isEmpty())
        // Second device receives everything.
        assertEquals(3, tablet.sync(cloud).pulled.size)
        assertEquals("Hero", tablet.get("shots", "sh1")!!.getString("title"))
        // Edit on tablet -> pushed; phone pulls, no conflict.
        tablet.edit("shots", "sh1", "title", "Hero close-up")
        assertEquals(1, tablet.sync(cloud).sent)
        val pulled = phone.sync(cloud)
        assertEquals(1, pulled.pulled.size); assertTrue(pulled.conflicts.isEmpty())
        assertEquals("Hero close-up", phone.get("shots", "sh1")!!.getString("title"))
        // Delete on phone -> tombstone in cloud -> removed on tablet.
        phone.delete("shots", "sh1")
        assertEquals(1, phone.sync(cloud).sent)
        assertNotNull(cloud.tables.getValue("shots").getValue("sh1").deletedAt)
        val removed = tablet.sync(cloud)
        assertEquals(1, removed.removed.size)
        assertNotNull("tombstoned locally, never hard-deleted", tablet.records.getValue("shots/sh1").deletedAt)
        // Steady state: nothing to do.
        val quiet = tablet.sync(cloud)
        assertEquals(0, quiet.sent + quiet.pulled.size + quiet.removed.size)
    }

    @Test fun bothEditedIsAConflictAndCloudIsUntouchedUntilResolved() {
        val cloud = FakeCloud(); val phone = Device("phone"); val tablet = Device("tablet")
        phone.put("projects", project()); phone.sync(cloud); tablet.sync(cloud)
        phone.edit("projects", "p1", "logline", "phone version"); phone.sync(cloud)
        tablet.edit("projects", "p1", "logline", "tablet version")
        val out = tablet.sync(cloud)
        assertEquals(1, out.conflicts.size)
        assertEquals("both-edited", out.conflicts[0].reason)
        assertEquals("phone version", cloud.tables.getValue("projects").getValue("p1").payload.getString("logline"))
        assertEquals("tablet version", tablet.get("projects", "p1")!!.getString("logline"))
        // "Decide later" is simply re-planning without a choice: still a conflict, still untouched.
        assertEquals(1, tablet.sync(cloud).conflicts.size)
        // Keep this device.
        val resolved = tablet.sync(cloud, mapOf("projects:p1" to Resolution.MINE))
        assertTrue(resolved.conflicts.isEmpty())
        assertEquals("tablet version", cloud.tables.getValue("projects").getValue("p1").payload.getString("logline"))
        phone.sync(cloud)
        assertEquals("tablet version", phone.get("projects", "p1")!!.getString("logline"))
    }

    @Test fun keepCloudResolutionReplacesLocalCopy() {
        val cloud = FakeCloud(); val a = Device("a"); val b = Device("b")
        a.put("projects", project()); a.sync(cloud); b.sync(cloud)
        a.edit("projects", "p1", "mood", "warm"); a.sync(cloud)
        b.edit("projects", "p1", "mood", "cold")
        assertEquals(1, b.sync(cloud).conflicts.size)
        b.sync(cloud, mapOf("projects:p1" to Resolution.CLOUD))
        assertEquals("warm", b.get("projects", "p1")!!.getString("mood"))
    }

    @Test fun deletedHereEditedInCloudIsAConflict() {
        val cloud = FakeCloud(); val a = Device("a"); val b = Device("b")
        a.put("projects", project()); a.sync(cloud); b.sync(cloud)
        a.edit("projects", "p1", "goals", "x"); a.sync(cloud)
        b.delete("projects", "p1")
        val out = b.sync(cloud)
        assertEquals("deleted-here-edited-in-cloud", out.conflicts.single().reason)
        assertNull(cloud.tables.getValue("projects").getValue("p1").deletedAt)
    }

    @Test fun lostVersionRaceBecomesConflictNotOverwrite() {
        val cloud = FakeCloud(); val a = Device("a")
        a.put("projects", project()); a.sync(cloud)
        a.edit("projects", "p1", "notes", "mine")
        cloud.failNextUpdateWithRace = true
        val out = a.sync(cloud)
        assertEquals(1, out.conflicts.size)
        assertTrue(cloud.tables.getValue("projects").getValue("p1").payload.getBoolean("raced"))
    }

    @Test fun retryAfterLostResponseIsIdempotent() {
        val cloud = FakeCloud(); val a = Device("a")
        a.put("projects", project())
        a.sync(cloud)
        a.base = emptyMap() // response lost: device never stored its base
        val retry = a.sync(cloud)
        assertTrue(retry.conflicts.isEmpty())
        assertEquals(1L, a.base.getValue("projects:p1").version)
    }

    @Test fun firstSyncWithDifferentCloudCopyAsksTheUser() {
        val cloud = FakeCloud(); val a = Device("a"); val b = Device("b")
        a.put("projects", project(name = "A")); a.sync(cloud)
        b.put("projects", project(name = "B"))
        assertEquals("different-on-first-sync", b.sync(cloud).conflicts.single().reason)
    }

    @Test fun artifactVersionsRoundTripIntoTheArtifact() {
        val cloud = FakeCloud(); val a = Device("a"); val b = Device("b")
        a.put("projects", project())
        a.put("artifacts", JSONObject().put("id", "a1").put("projectId", "p1").put("kind", "script").put("title", "Script")
            .put("draft", "draft text").put("approvedVersionId", "v1").put("versions", JSONArray()
                .put(JSONObject().put("id", "v1").put("artifactId", "a1").put("status", "approved").put("author", "user")
                    .put("text", "Approved words").put("note", "").put("createdAt", "2026-11-20T01:00:00Z"))))
        a.sync(cloud)
        assertEquals(1, cloud.tables.getValue("artifact_versions").size)
        b.sync(cloud)
        val got = b.get("artifacts", "a1")!!
        assertEquals("Approved words", got.getJSONArray("versions").getJSONObject(0).getString("text"))
        // Unchanged artifact after a round trip stays in sync (no phantom edits).
        val again = b.sync(cloud)
        assertEquals(0, again.sent + again.pulled.size)
    }
}
