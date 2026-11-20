package app.filmmaking.studio.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StoryboardBriefTest {
    private fun shot(aspect: String = "9:16") = JSONObject()
        .put("title", "Hero closeup").put("description", "She stirs her coffee, not drinking it")
        .put("purpose", "to show she is waiting for someone who will not come").put("visualNotes", "steam catching the light")
        .put("scriptPassage", "SECRET SCRIPT TEXT")
        .put("camera", JSONObject().put("shotSize", "MCU").put("angle", "Eye level").put("lens", "85mm")
            .put("movement", "Static").put("composition", "subject on the left third").put("aspect", aspect))
        .put("lighting", JSONObject().put("timeOfDay", "late afternoon").put("source", "window light")
            .put("direction", "from frame left").put("quality", "soft").put("colourTemp", "warm 3200K").put("practicals", ""))
        .put("production", JSONObject().put("wardrobe", "green raincoat").put("props", "chipped mug").put("talent", "Priya Sharma"))

    @Test fun shortIdeaBecomesADetailedBriefWithTheIdeaFirst() {
        val b = StoryboardBrief.build("woman sitting in the cafe", shot(),
            scene = JSONObject().put("title", "INT. CAFE - DAY").put("description", "a near-empty corner cafe in the rain"),
            project = JSONObject().put("mood", "quiet melancholy"), tasteNotes = listOf("Prefers negative space and muted colour"))
        val t = b.text
        assertTrue(t, t.startsWith("woman sitting in the cafe."))
        listOf("She stirs her coffee", "INT. CAFE - DAY", "medium close-up, framed from the chest up", "eye-level camera",
            "85mm lens, portrait telephoto, soft background", "locked-off static camera", "subject on the left third",
            "steam catching the light", "late afternoon", "window light", "from frame left", "warm 3200K",
            "green raincoat", "chipped mug", "the frame should show she is waiting", "quiet melancholy",
            "Prefers negative space", "Vertical 9:16 frame").forEach { assertTrue("missing: $it\n$t", it in t) }
        assertEquals(StoryboardBrief.Orientation.PORTRAIT, b.orientation)
        assertTrue(t.length <= StoryboardBrief.MAX_CHARS)
    }

    @Test fun privateAndIdentifyingFieldsAreNeverSent() {
        val t = StoryboardBrief.build("", shot()).text
        assertFalse(t, "Priya" in t)          // talent names
        assertFalse(t, "SECRET SCRIPT" in t)  // script passages
        assertTrue("blank idea falls back to the shot title", t.startsWith("Hero closeup"))
    }

    @Test fun blankPlanStillGivesAUsableBriefAndStaysWithinTheServerLimit() {
        val b = StoryboardBrief.build("x".repeat(3000), JSONObject().put("title", "Wide"))
        assertTrue(b.text.length <= StoryboardBrief.MAX_CHARS)
        assertEquals(StoryboardBrief.Orientation.LANDSCAPE, b.orientation)
        assertTrue(b.lines.last().text.startsWith("Horizontal 16:9 frame"))
    }

    @Test fun cameraTermsAreExpanded() {
        assertEquals("wide shot, the full figure small within the location", StoryboardBrief.shotSize("ws"))
        assertEquals("Custom framing", StoryboardBrief.shotSize("Custom framing"))
        assertEquals("24mm lens, wide, deep space around the subject", StoryboardBrief.lens("24 mm prime"))
        assertEquals("200mm lens, long telephoto, compressed background", StoryboardBrief.lens("200mm"))
        assertEquals("anamorphic", StoryboardBrief.lens("anamorphic"))
        assertEquals("camera move: dolly in (show the starting frame)", StoryboardBrief.movement("dolly in"))
        assertEquals("low angle looking up", StoryboardBrief.angle("Low"))
        assertEquals(StoryboardBrief.Orientation.SQUARE, StoryboardBrief.orientation("1:1"))
        assertEquals(StoryboardBrief.Orientation.LANDSCAPE, StoryboardBrief.orientation("2.39:1"))
        assertEquals(StoryboardBrief.Orientation.PORTRAIT, StoryboardBrief.orientation("4:5"))
    }
}
