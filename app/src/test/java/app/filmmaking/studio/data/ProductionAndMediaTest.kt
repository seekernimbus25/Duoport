package app.filmmaking.studio.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream

class ProductionAndMediaTest {
    private fun rec(c: String, o: JSONObject) = StudioRecord(c, o.getString("id"), o.optString("title", o.getString("id")), o.toString())
    private val sha = "a".repeat(64)

    private fun film(): MutableList<StudioRecord> = mutableListOf(
        rec("projects", JSONObject().put("id", "p1").put("name", "Tea Film").put("aspectRatio", "9:16").put("format", "Reel")
            .put("targetDurationSec", 60).put("status", "Production").put("altFormats", JSONArray().put("16:9"))),
        rec("scenes", JSONObject().put("id", "sc1").put("projectId", "p1").put("number", 1).put("order", 1).put("title", "Kitchen")),
        rec("shots", JSONObject().put("id", "sh1").put("projectId", "p1").put("sceneId", "sc1").put("code", "S01-01")
            .put("title", "Kettle").put("status", "complete").put("storyOrder", 1).put("shootOrder", 2)
            .put("camera", JSONObject().put("shotSize", "CU").put("lens", "50mm").put("frameRate", "24"))
            .put("sound", JSONObject().put("roomTone", "Kitchen hum")).put("editorial", JSONObject().put("transition", "Cut"))),
        rec("shots", JSONObject().put("id", "sh2").put("projectId", "p1").put("sceneId", "sc1").put("code", "S01-02")
            .put("title", "Cup").put("status", "planned").put("storyOrder", 2).put("shootOrder", 1)
            .put("camera", JSONObject().put("shotSize", "MS").put("lens", "35mm")).put("sound", JSONObject())
            .put("editorial", JSONObject())),
        rec("takes", JSONObject().put("id", "t1").put("projectId", "p1").put("shotId", "sh1").put("number", 1)
            .put("mediaAssetIds", JSONArray().put("m1")).put("capturedAt", "2026-11-20T10:00:00Z").put("preferred", true)
            .put("quality", "usable").put("kind", "picture").put("settings", JSONObject())),
        rec("media", JSONObject().put("id", "m1").put("projectId", "p1").put("shotId", "sh1").put("takeId", "t1")
            .put("filename", "DJI_0045.MP4").put("uri", "content://card/DJI_0045.MP4").put("role", "master")
            .put("mime", "video/mp4").put("placeholder", false).put("verification", "verified")
            .put("checksum", JSONObject().put("algo", "sha256").put("value", sha))),
        rec("shootDays", JSONObject().put("id", "d1").put("projectId", "p1").put("label", "Day 1").put("date", "2026-11-21")
            .put("order", JSONArray().put("sh2").put("sh1")).put("history", JSONArray())),
        rec("externalClips", JSONObject().put("id", "x1").put("projectId", "p1").put("shotId", "sh2")
            .put("filename", "DJI_0046.MP4").put("footageImported", false).put("status", "awaiting-footage-import"))
    )

    @Test fun roomToneTakeInSceneSatisfiesCoverage() {
        val rows = film()
        val before = ProductionLogic.coverage(ProjectData(rows, "p1"))
        assertEquals(listOf("S01-01"), before.missingRoomTone.map { it.getString("code") })
        rows += rec("takes", JSONObject().put("id", "rt").put("projectId", "p1").put("shotId", "sh2").put("number", 1)
            .put("kind", "roomTone").put("quality", "usable").put("mediaAssetIds", JSONArray()).put("capturedAt", "x").put("preferred", false))
        val after = ProductionLogic.coverage(ProjectData(rows, "p1"))
        assertTrue(after.missingRoomTone.isEmpty())
        // A room-tone take is not picture coverage for the cup shot.
        assertEquals(listOf("S01-02"), after.missing.map { it.getString("code") })
    }

    @Test fun packageNeverClaimsFilenameOnlyClipsAsVerifiedAndExportsAllFormats() {
        val pkg = ProductionPackage.build(film(), "p1", "2026-11-20T12:00:00Z")
        val r = pkg.getJSONObject("readiness")
        assertFalse(r.getJSONObject("actualMediaVerified").getBoolean("ok"))
        assertTrue(r.getJSONObject("actualMediaVerified").getJSONArray("reasons").toString().contains("external camera"))
        assertFalse(r.getJSONObject("readyForEditorialHandoff").getBoolean("ok"))
        assertEquals(listOf("sh2", "sh1"), (0 until 2).map { pkg.getJSONArray("shootOrder").getString(it) })
        assertEquals(listOf("sh1", "sh2"), (0 until 2).map { pkg.getJSONArray("narrativeOrder").getString(it) })
        val csv = ProductionPackage.csv(pkg)
        assertTrue(csv.getValue("media.csv").contains("sha256:$sha"))
        assertTrue(csv.getValue("schedule.csv").lines()[1].startsWith("Day 1,2026-11-21,1,S01-02"))
        assertTrue(csv.getValue("external_camera.csv").contains("DJI_0046.MP4,S01-02"))
        val md = ProductionPackage.markdown(pkg)
        assertTrue(md.contains("filename only, footage NOT imported"))
        assertTrue(md.contains("_No original footage is included in this package._"))
        val names = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(ProductionPackage.zip(pkg))).use { z ->
            while (true) { val e = z.nextEntry ?: break; names += e.name }
        }
        assertTrue(names.containsAll(listOf("production.json", "production.md", "README.txt", "shots.csv", "takes.csv", "media.csv", "schedule.csv")))
        assertFalse("never footage", names.any { it.endsWith(".MP4", true) || it.endsWith(".mov", true) })
        // Written for the independent web validator (contracts/validate-package.mjs), run by scripts/validate_export.sh.
        val out = File(System.getProperty("studio.packageOut") ?: "build/test-production-package.json")
        out.parentFile?.mkdirs(); out.writeText(pkg.toString(2))
    }

    private fun file(name: String, h: String = sha) = PickedFile("content://card/$name", name, 10, h, "video/mp4")

    @Test fun matcherLinksOnlyLoggedFilenamesAndRefusesMismatches() {
        val rows = film()
        val clips = rows.filter { it.collection == "externalClips" }
        val media = rows.filter { it.collection == "media" }
        val res = ExternalMediaMatcher.match(listOf(file("dji_0046.mp4", "b".repeat(64)), file("C0001.MOV", "c".repeat(64)),
            file("DJI_0046.MP4", "b".repeat(64))), clips, media)
        assertTrue(res[0] is MatchResult.Link)                 // case-insensitive match
        assertTrue(res[1] is MatchResult.Unmatched)            // not logged -> never imported
        assertTrue(res[2] is MatchResult.DuplicateContent)     // same bytes picked twice
        // Same bytes as footage already imported elsewhere.
        assertTrue(ExternalMediaMatcher.match(listOf(file("DJI_0046.MP4", sha)), clips, media)[0] is MatchResult.DuplicateContent)
        // Already linked: different bytes are refused, identical bytes at a new URI relink.
        val linkedClip = clips[0].copy(payload = JSONObject(clips[0].payload).put("mediaId", "m1").put("sha256", sha).toString())
        assertTrue(ExternalMediaMatcher.match(listOf(file("DJI_0046.MP4", "d".repeat(64))), listOf(linkedClip), media)[0] is MatchResult.ChecksumMismatch)
        val moved = PickedFile("content://usb/DJI_0046.MP4", "DJI_0046.MP4", 10, sha, "video/mp4")
        assertTrue(ExternalMediaMatcher.match(listOf(moved), listOf(linkedClip), media)[0] is MatchResult.Relink)
    }
}
