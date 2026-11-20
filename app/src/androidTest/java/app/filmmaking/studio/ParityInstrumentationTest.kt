package app.filmmaking.studio

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.filmmaking.studio.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest

/** Room-backed acceptance tests for the parity checklist rules (no network, no paid AI). */
@RunWith(AndroidJUnit4::class)
class ParityInstrumentationTest {
    private lateinit var context: Context
    private lateinit var db: StudioDatabase
    private lateinit var repo: StudioRepository
    private lateinit var f: FeatureRepository
    private val created = mutableListOf<Uri>()

    @Before fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, StudioDatabase::class.java).build()
        repo = StudioRepository(db)
        f = FeatureRepository(db)
    }

    @After fun tearDown() {
        created.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
        db.close()
    }

    private fun body(c: String, id: String) = JSONObject(runBlocking { db.dao().find(c, id) }!!.payload)

    private suspend fun film(): Triple<StudioRecord, StudioRecord, StudioRecord> {
        val p = repo.createProject("Tea Film")
        val sc = repo.addScene(p.id, "Kitchen")
        val sh = repo.addShot(sc.id, "Kettle")
        return Triple(p, sc, sh)
    }

    /** A real content:// file in MediaStore Downloads, exactly like a card reader document. */
    private fun cardFile(name: String, bytes: ByteArray): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "video/mp4")
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
        context.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
        created += uri
        return uri
    }

    @Test fun externalFootageImportVerifiesChecksumAndOnlyMatchesLoggedNames() = runBlocking {
        val (p, _, sh) = film()
        val log = ExternalCameraLog(db)
        val unique = System.nanoTime().toString()
        val name = "DJI_${unique.takeLast(4)}.MP4"
        log.add(sh.id, name)
        val bytes = ("real footage bytes " + unique).toByteArray()
        val expected = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val good = cardFile(name, bytes)
        val stray = cardFile("C9999_$unique.MOV", "other".toByteArray())
        val summary = ExternalMediaImport(context, db).import(p.id, listOf(good, stray))
        assertEquals(1, summary.linked)
        assertTrue(summary.skipped.single().contains("matches no logged filename"))
        val media = db.dao().listByProject("media", p.id).single()
        val m = JSONObject(media.payload)
        assertEquals("verified", m.getString("verification"))
        assertEquals(expected, m.getJSONObject("checksum").getString("value"))
        val take = JSONObject(db.dao().listByProject("takes", p.id).single().payload)
        assertEquals(media.id, take.getJSONArray("mediaAssetIds").getString(0))
        val clip = JSONObject(db.dao().listByProject(ExternalCameraNaming.COLLECTION, p.id).single().payload)
        assertTrue(clip.getBoolean("footageImported"))
        // Importing the same file again never duplicates the take.
        val again = ExternalMediaImport(context, db).import(p.id, listOf(good))
        assertEquals(0, again.linked)
        assertEquals(1, db.dao().listByProject("takes", p.id).size)
        // The package now reports the master as verified, never more than that.
        repo.preferTake(take.getString("id"))
        val pkg = ProductionPackage.build(db.dao().allRecords(), p.id, "2026-11-20T00:00:00Z")
        assertTrue(pkg.getJSONObject("readiness").getJSONObject("actualMediaVerified").getBoolean("ok"))
    }

    @Test fun beatReorderKeepsShotLinksAndAcceptedProposalKeepsIds() = runBlocking {
        val (p, sc, sh) = film()
        val w = ProjectWorkflowRepository(db)
        val b1 = w.create(p.id, WorkflowKind.BEAT, "Steam", "")
        val b2 = w.create(p.id, WorkflowKind.BEAT, "Pour", "")
        f.linkBeatShots(b1.id, listOf(sh.id))
        f.moveBeat(p.id, b2.id, up = true)
        assertEquals(1.0, body("beats", b2.id).getDouble("order"), 0.0)
        assertEquals(sh.id, body("beats", b1.id).getJSONArray("shotIds").getString(0))
        assertEquals(b1.id, body("shots", sh.id).getString("beatId"))
        val proposal = f.saveShotProposals(p.id, sc.id, b2.id, listOf(JSONObject().put("title", "Pour close-up").put("shotSize", "ECU")), "ai:test").single()
        val shotId = f.decideShotProposal(proposal.id, true)!!
        val accepted = body("shots", shotId)
        assertEquals(b2.id, accepted.getString("beatId"))
        assertEquals(proposal.id, accepted.getString("proposalId"))
        assertEquals("ECU", accepted.getJSONObject("camera").getString("shotSize"))
        assertEquals("accepted", body("shotProposals", proposal.id).getString("status"))
        try { f.decideShotProposal(proposal.id, false); fail("decided twice") } catch (_: IllegalArgumentException) {}
    }

    @Test fun critiqueRevisionIsANewDraftAndApprovedTextIsImmutable() = runBlocking {
        val (p, _, _) = film()
        val w = ProjectWorkflowRepository(db)
        val script = w.create(p.id, WorkflowKind.SCRIPT, "VO", "Original words")
        w.approveScript(script.id)
        val approvedId = body("artifacts", script.id).getString("approvedVersionId")
        f.addDraftVersion(script.id, "Revised words", "AI critique revision", "assistant")
        val a = body("artifacts", script.id)
        assertEquals(approvedId, a.getString("approvedVersionId"))
        val versions = a.getJSONArray("versions")
        assertEquals("Original words", versions.getJSONObject(0).getString("text"))
        assertEquals("draft", versions.getJSONObject(1).getString("status"))
        assertEquals("assistant", versions.getJSONObject(1).getString("author"))
    }

    @Test fun storyboardChosenIsUniqueAndDiagramForksWhenApproved() = runBlocking {
        val (p, sc, sh) = film()
        val a = f.addStoryboard(p.id, sh.id, "data:image/jpeg;base64,AA==", "upload", JSONObject(), false)
        val b = f.addStoryboard(p.id, sh.id, "data:image/jpeg;base64,AB==", "upload", JSONObject(), false)
        f.chooseStoryboard(a.id); f.chooseStoryboard(b.id)
        assertFalse(body("storyboards", a.id).getBoolean("chosen"))
        assertTrue(body("storyboards", b.id).getBoolean("chosen"))
        val d = f.createDiagram(p.id, sc.id, sh.id, "Kitchen key")
        f.addDiagramElement(d.id, "camera", 2.0, 3.0, "A cam")
        f.approveDiagram(d.id)
        val forkId = f.addDiagramElement(d.id, "key", 5.0, 1.0, "Window key")
        assertNotEquals(d.id, forkId)
        assertEquals(1, body("diagrams", d.id).getJSONArray("elements").length())
        assertEquals("approved", body("diagrams", d.id).getString("status"))
        assertEquals(d.id, body("diagrams", forkId).getString("parentId"))
        assertEquals(2, body("diagrams", forkId).getJSONArray("elements").length())
        // Lookbook: applying copies elements with NEW ids and never edits the recipe.
        val recipe = f.saveDiagramAsRecipe(forkId, "Window light")
        val before = db.dao().find("recipes", recipe.id)!!.payload
        val applied = f.applyRecipe(recipe.id, p.id, sc.id, null)
        assertEquals(before, db.dao().find("recipes", recipe.id)!!.payload)
        val ids = body("diagrams", applied.id).getJSONArray("elements").let { e -> (0 until e.length()).map { e.getJSONObject(it).getString("id") } }
        val originals = body("recipes", recipe.id).getJSONArray("elements").let { e -> (0 until e.length()).map { e.getJSONObject(it).getString("id") } }
        assertTrue(ids.none { it in originals })
        // AI setup preview: unknown element ids are ignored, adds need a type and position.
        val changes = JSONArray().put(JSONObject().put("action", "remove").put("elementId", "not-in-diagram"))
            .put(JSONObject().put("action", "add").put("type", "fill").put("x", 1.0).put("y", 1.0).put("label", "Bounce"))
        val preview = f.previewSetupChanges(body("diagrams", applied.id), changes)
        assertEquals(3, preview.length())
    }

    @Test fun shootingOrderNeverChangesNarrativeOrderAndKeepsHistory() = runBlocking {
        val (p, sc, s1) = film()
        val s2 = repo.addShot(sc.id, "Cup")
        val w = ProjectWorkflowRepository(db)
        val day = w.create(p.id, WorkflowKind.DAY, "Day 1", "")
        f.approveDayOrder(day.id, listOf(s2.id, s1.id), "first")
        f.approveDayOrder(day.id, listOf(s1.id, s2.id), "second")
        assertEquals(1, body("shootDays", day.id).getJSONArray("history").length())
        assertEquals(1, body("shots", s1.id).getInt("storyOrder"))
        assertEquals(2, body("shots", s2.id).getInt("storyOrder"))
        val proposal = f.saveScheduleProposal(p.id, day.id, JSONObject().put("order", JSONArray().put(s2.id).put(s1.id)), "ai:test")
        assertEquals(s1.id, body("shootDays", day.id).getJSONArray("order").getString(0)) // not applied yet
        f.decideScheduleProposal(proposal.id, true)
        assertEquals(s2.id, body("shootDays", day.id).getJSONArray("order").getString(0))
        assertEquals(2, body("shootDays", day.id).getJSONArray("history").length())
    }

    @Test fun checklistRegenerateKeepsStatusesAndSetupsAreExclusive() = runBlocking {
        val (p, _, sh) = film()
        val shot = db.dao().find("shots", sh.id)!!
        val b = JSONObject(shot.payload)
        b.getJSONObject("production").put("equipment", "Tripod, LED panel")
        b.getJSONObject("sound").put("roomTone", "Kitchen hum")
        db.dao().upsert(shot.copy(payload = b.toString()))
        assertEquals(3, f.regenerateChecklist(p.id))
        val tripod = db.dao().listByProject("checklist", p.id).first { JSONObject(it.payload).getString("label") == "Tripod" }
        f.setChecklistStatus(tripod.id, "ready")
        assertEquals(0, f.regenerateChecklist(p.id))
        assertEquals("ready", body("checklist", tripod.id).getString("status"))
        val w = ProjectWorkflowRepository(db)
        val a = w.create(p.id, WorkflowKind.SETUP, "A", ""); val c = w.create(p.id, WorkflowKind.SETUP, "B", "")
        f.assignShotToSetup(a.id, sh.id, true); f.assignShotToSetup(c.id, sh.id, true)
        assertEquals(0, body("setups", a.id).getJSONArray("shotIds").length())
        assertEquals(sh.id, body("setups", c.id).getJSONArray("shotIds").getString(0))
    }

    @Test fun roomToneTakeSatisfiesSceneAndPickupLandsInSameScene() = runBlocking {
        val (p, sc, sh) = film()
        val shot = db.dao().find("shots", sh.id)!!
        db.dao().upsert(shot.copy(payload = JSONObject(shot.payload).also { it.getJSONObject("sound").put("roomTone", "hum") }.toString()))
        val second = repo.addShot(sc.id, "Window")
        assertEquals(1, ProductionLogic.coverage(ProjectData(db.dao().allRecords(), p.id)).missingRoomTone.size)
        f.logSoundTake(second.id, "roomTone", "Zoom H5 file 003")
        assertTrue(ProductionLogic.coverage(ProjectData(db.dao().allRecords(), p.id)).missingRoomTone.isEmpty())
        val pickup = f.addPickup(sc.id, "Insert of lid", "Missing cutaway")
        assertEquals(sc.id, body("shots", pickup.id).getString("sceneId"))
        assertTrue(body("shots", pickup.id).getBoolean("pickup"))
        f.omitShot(second.id, "Cut in edit")
        assertTrue(ProductionLogic.coverage(ProjectData(db.dao().allRecords(), p.id)).missing.none { it.getString("id") == second.id })
    }

    @Test fun continuityCorrectionKeepsOriginalAndIdeaOriginalUnchanged() = runBlocking {
        val (p, _, sh) = film()
        val w = ProjectWorkflowRepository(db)
        val c = w.create(p.id, WorkflowKind.CONTINUITY, "Cup in right hand", "Cup in left hand", sh.id)
        w.update("continuity", c.id, "Cup in right hand", "Cup in right hand after reset")
        f.setContinuity(c.id, basis = "speculative", resolved = true)
        val cb = body("continuity", c.id)
        assertEquals("Cup in left hand", cb.getJSONArray("history").getJSONObject(0).getString("observed"))
        assertEquals("speculative", cb.getString("basis"))
        val idea = repo.captureIdea("Steam as memory")
        f.attachToIdea(idea.id, "link", "https://example.org/ref")
        f.expandIdea(idea.id, "Shoot at dawn")
        val ib = body("ideas", idea.id)
        assertEquals("Steam as memory", ib.getString("original"))
        assertEquals(1, ib.getJSONArray("sources").length())
        assertEquals(1, ib.getJSONArray("expansions").length())
    }

    @Test fun cameraProfileIsVerifiedOnlyWhenDeviceReported() = runBlocking {
        f.saveCameraProfile(JSONObject().put("device", "typed"), deviceReported = false)
        assertFalse(body("cameraProfiles", "00000000-0000-4000-8000-0000000000ca").getBoolean("verified"))
        val probed = app.filmmaking.studio.camera.CameraProfileProbe.probe(context)
        f.saveCameraProfile(probed, deviceReported = true)
        val saved = body("cameraProfiles", "00000000-0000-4000-8000-0000000000ca")
        assertTrue(saved.getBoolean("verified"))
        assertTrue(saved.getString("source").contains("device-reported"))
    }

    @Test fun conversationsPersistWithCitations() = runBlocking {
        val conv = f.newConversation(null, "Claude", "Explore", "Creative Partner")
        f.addMessage(conv.id, "user", "What should the opening feel like?")
        f.addMessage(conv.id, "assistant", "Quiet. [S1]", JSONArray().put(JSONObject().put("label", "S1").put("title", "Notes")))
        val msgs = db.dao().allRecords().filter { it.collection == "aiMessages" }
        assertEquals(2, msgs.size)
        assertEquals("What should the opening feel like?", body("aiConversations", conv.id).getString("title"))
    }

    @Test fun roomMigrationFromVersion1AddsSyncBaseAndKeepsData() {
        val name = "migration-test.db"
        context.deleteDatabase(name)
        val helper = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null)
        helper.execSQL("CREATE TABLE IF NOT EXISTS `studio_records` (`collection` TEXT NOT NULL, `id` TEXT NOT NULL, `title` TEXT NOT NULL, " +
            "`payload` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, `baseVersion` INTEGER, `baseHash` TEXT, `dirty` INTEGER NOT NULL, " +
            "`deletedAt` INTEGER, `deviceId` TEXT NOT NULL, PRIMARY KEY(`collection`, `id`))")
        helper.execSQL("CREATE INDEX IF NOT EXISTS `index_studio_records_collection_updatedAt` ON `studio_records` (`collection`, `updatedAt`)")
        helper.execSQL("INSERT INTO studio_records VALUES ('ideas','i1','Kept','{\"id\":\"i1\"}',1,NULL,NULL,1,NULL,'')")
        helper.version = 1
        helper.close()
        val migrated = Room.databaseBuilder(context, StudioDatabase::class.java, name).addMigrations(MIGRATION_1_2).build()
        runBlocking {
            assertEquals("Kept", migrated.dao().find("ideas", "i1")!!.title)
            migrated.dao().saveSyncBase(listOf(SyncBaseRow("u", "ideas", "i1", 3, "h")))
            assertEquals(3L, migrated.dao().syncBase("u").single().version)
        }
        migrated.close()
        context.deleteDatabase(name)
    }
}
