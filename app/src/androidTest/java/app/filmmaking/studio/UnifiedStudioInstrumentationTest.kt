package app.filmmaking.studio

import android.content.Context
import com.dualframe.camera.StudioDiagnostics
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.filmmaking.studio.camera.DuoportCaptureContract
import app.filmmaking.studio.camera.StudioCaptureImporter
import app.filmmaking.studio.data.*
import app.studio.contracts.duoport.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android Room + Intent contract tests, no fake media and no paid model calls. */
@RunWith(AndroidJUnit4::class)
class UnifiedStudioInstrumentationTest {
    private lateinit var context: Context
    private lateinit var db: StudioDatabase
    private lateinit var repo: StudioRepository

    @Before fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, StudioDatabase::class.java).build()
        repo = StudioRepository(db)
    }

    @After fun tearDown() { db.close() }

    @Test fun onlyStudioIsLauncher() {
        val activity = context.packageManager.getLaunchIntentForPackage(context.packageName)
        assertNotNull("Combined package should have a launch activity", activity)
        assertEquals("app.filmmaking.studio.MainActivity", activity!!.component!!.className)
        assertEquals("com.dualframe.camera", activity.component!!.packageName)
    }

    @Test fun ideaConversionPreservesSourceAndCreatesSceneShot() = runBlocking {
        val text = "A cinematic thought that must remain exactly unchanged"
        val idea = repo.captureIdea(text)
        val project = repo.convertIdea(idea.id)
        val savedIdea = db.dao().find("ideas", idea.id)!!
        assertEquals(text, JSONObject(savedIdea.payload).getString("original"))
        assertEquals(project.id, JSONObject(savedIdea.payload).getString("projectId"))
        assertEquals("Converted", JSONObject(savedIdea.payload).getString("status"))
        val again = repo.convertIdea(idea.id)
        assertEquals(project.id, again.id)
        val scene = repo.addScene(project.id, "Opening")
        val shot = repo.addShot(scene.id, "Hero detail")
        val body = JSONObject(shot.payload)
        assertEquals(project.id, body.getString("projectId"))
        assertEquals(scene.id, body.getString("sceneId"))
        assertEquals("S01-01", body.getString("code"))
        assertEquals("planned", body.getString("status"))
        repo.setShotStatus(shot.id, "ready")
        assertEquals("ready", JSONObject(db.dao().find("shots", shot.id)!!.payload).getString("status"))
    }

    @Test fun treatmentApprovalSupersedesPriorAndScriptVersionFrozen() = runBlocking {
        val project = repo.createProject("Test Film")
        val wf = ProjectWorkflowRepository(db)
        val first = wf.create(project.id, WorkflowKind.TREATMENT, "First", "Premise one")
        wf.approveTreatment(project.id, first.id)
        assertEquals("approved", JSONObject(db.dao().find("treatments",first.id)!!.payload).getString("status"))
        val second = wf.create(project.id, WorkflowKind.TREATMENT, "Second", "Premise two")
        wf.approveTreatment(project.id, second.id)
        assertEquals("superseded", JSONObject(db.dao().find("treatments",first.id)!!.payload).getString("status"))
        assertEquals("approved", JSONObject(db.dao().find("treatments",second.id)!!.payload).getString("status"))

        val script = wf.create(project.id, WorkflowKind.SCRIPT, "Voiceover", "The first draft")
        wf.approveScript(script.id)
        wf.update("artifacts", script.id, "Voiceover", "The second draft")
        wf.approveScript(script.id)
        val versions = JSONObject(db.dao().find("artifacts",script.id)!!.payload).getJSONArray("versions")
        assertEquals(2, versions.length())
        assertEquals("The first draft", versions.getJSONObject(0).getString("text"))
        assertEquals("superseded", versions.getJSONObject(0).getString("status"))
        assertEquals("The second draft", versions.getJSONObject(1).getString("text"))
        assertEquals("approved", versions.getJSONObject(1).getString("status"))
    }

    @Test fun backupRoundTripKeepsIdsAndDoesNotOverwrite() = runBlocking {
        val idea = repo.captureIdea("backup source")
        val export = StudioExport(db)
        val json = JSONObject(export.backup())
        assertEquals("studio-native-backup", json.getString("schema"))
        val restoredDb = Room.inMemoryDatabaseBuilder(context, StudioDatabase::class.java).build()
        try {
            val restore = StudioExport(restoredDb)
            assertEquals(1, restore.restore(json.toString()))
            assertEquals(idea.id, restoredDb.dao().find("ideas",idea.id)!!.id)
            assertEquals(0, restore.restore(json.toString()))
            val local = restoredDb.dao().find("ideas",idea.id)!!
            restoredDb.dao().upsert(local.copy(payload=JSONObject(local.payload).put("original","LOCAL_EDIT").toString()))
            try {
                restore.restore(json.toString())
                fail("Conflicting edits must never silently overwrite local changes")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message!!.contains("Conflicting"))
            }
            assertEquals("LOCAL_EDIT", JSONObject(restoredDb.dao().find("ideas",idea.id)!!.payload).getString("original"))
        } finally { restoredDb.close() }
    }

    @Test fun exportNeverClaimsUnrecordedFootageVerified() = runBlocking {
        val project = repo.createProject("Film without recordings")
        val scene = repo.addScene(project.id,"Intro")
        repo.addShot(scene.id,"Plan only")
        val result = JSONObject(StudioExport(db).production(project.id))
        assertEquals("studio-production-package", result.getString("schema"))
        assertEquals("2.0.0", result.getString("version"))
        assertEquals(1, result.getJSONArray("shots").length())
        assertEquals(0, result.getJSONArray("takes").length())
        assertFalse(result.getJSONObject("contents").getBoolean("footageIncluded"))
        assertFalse(result.getJSONObject("readiness").getJSONObject("actualMediaVerified").getBoolean("ok"))
        assertFalse(result.getJSONObject("readiness").getJSONObject("readyForEditorialHandoff").getBoolean("ok"))
    }

    @Test fun captureCancelAndEmptyMediaNeverCreateTake() = runBlocking {
        val project = repo.createProject("Film")
        val scene = repo.addScene(project.id, "Scene")
        val shot = repo.addShot(scene.id, "Shot")
        val importer = StudioCaptureImporter(context, db)
        val cancel = CameraResult(requestId="req1",shotId=shot.id,status=ResultStatus.CANCELLED)
        try {
            importer.import(shot.id,"req1",cancel)
            fail("Cancelled capture was accepted")
        } catch (_: IllegalArgumentException) { }
        val noMedia = CameraResult(requestId="req2",shotId=shot.id,status=ResultStatus.CAPTURED)
        try {
            importer.import(shot.id,"req2",noMedia)
            fail("Empty capture was accepted")
        } catch (_: IllegalArgumentException) { }
        assertTrue(db.dao().listByProject("takes",project.id).isEmpty())
    }

    @Test fun diagnosticsOptInCannotSendWhenDisabled() {
        StudioDiagnostics.setConsent(context, false)
        assertFalse(StudioDiagnostics.isEnabled(context))
        assertFalse(StudioDiagnostics.sendTestEvent())
        // In CI, the Sentry DSN is deliberately absent: no telemetry leaves emulator tests.
        if (!StudioDiagnostics.isConfigured()) {
            assertFalse(StudioDiagnostics.initialize(context))
        }
    }

    @Test fun externalCameraNamingRecognizesDJISuffixAndPreservesPadding() {
        val dji = requireNotNull(ExternalCameraNaming.detect("DJI_20261120_0045.MP4"))
        assertEquals("DJI_20261120_", dji.prefix)
        assertEquals(".MP4", dji.extension)
        assertEquals(4, dji.width)
        assertEquals("DJI_20261120_0046.MP4", dji.nextFilename)
        assertEquals("DJI_20261120_0052.MP4", dji.filenameFor("52"))
        assertEquals("C0002.MOV", ExternalCameraNaming.detect("C0001.MOV")?.nextFilename)
        assertNull(ExternalCameraNaming.detect("scene_alpha.mov"))
        try {
            ExternalCameraNaming.checkedFilename("../../danger.MP4")
            fail("Full paths must not be accepted as filenames")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun externalCameraTakeLogSurvivesScenesAndNeverPretendsFootageExists() = runBlocking {
        val film = repo.createProject("DJI film")
        val sceneA = repo.addScene(film.id, "Intro")
        val sceneB = repo.addScene(film.id, "Closing")
        val shotA = repo.addShot(sceneA.id, "Wide")
        val shotB = repo.addShot(sceneB.id, "Insert")
        val logger = ExternalCameraLog(db)
        val one = logger.add(shotA.id, "DJI_0045.MP4")
        val records = db.dao().listByProject(ExternalCameraNaming.COLLECTION, film.id)
        assertEquals(1, records.size)
        assertEquals("DJI_0046.MP4", ExternalCameraNaming.nextFrom(records)?.nextFilename)
        val two = logger.add(shotB.id, "DJI_0046.MP4")
        assertEquals(shotB.id, JSONObject(two.payload).getString("shotId"))
        assertEquals(2, db.dao().listByProject(ExternalCameraNaming.COLLECTION, film.id).size)
        try {
            logger.add(shotB.id, "dji_0046.mp4")
            fail("Duplicate camera filenames must be rejected across project")
        } catch (_: IllegalArgumentException) { }
        assertTrue(db.dao().listByProject("takes", film.id).isEmpty())
        assertTrue(db.dao().listByProject("media", film.id).isEmpty())
        val handoff = JSONObject(StudioExport(db).production(film.id))
        assertEquals(2, handoff.getJSONArray("externalCameraReferences").length())
        assertEquals(0, handoff.getJSONArray("takes").length())
        assertFalse(handoff.getJSONObject("readiness")
            .getJSONObject("actualMediaVerified").getBoolean("ok"))
        logger.remove(one.id)
        val remaining = db.dao().listByProject(ExternalCameraNaming.COLLECTION, film.id)
        assertEquals(1, remaining.size)
        assertEquals("DJI_0047.MP4", ExternalCameraNaming.nextFrom(remaining)?.nextFilename)
    }

    @Test fun samePackageCaptureIntentIncludesShotContext() {
        val shot = ShotContext(
            projectId="project",projectName="Film",sceneId="scene",shotId="shot",
            shotCode="S01-01",shotType="CU",title="Close up",instructions="Observe",takeNumber=1
        )
        val req = OpenCameraRequest(requestId="request",mode=OpenMode.SHOT,shot=shot)
        val intent = DuoportCaptureContract().createIntent(context,req)
        assertEquals(context.packageName,intent.component!!.packageName)
        assertEquals("com.dualframe.camera.StudioCaptureActivity",intent.component!!.className)
        assertEquals(DuoportIntent.ACTION_OPEN,intent.action)
        val json = intent.getStringExtra(DuoportIntent.EXTRA_REQUEST_JSON)
        assertNotNull(json)
        assertEquals("shot",JSONObject(json!!).getJSONObject("shot").getString("shotId"))
    }
}
