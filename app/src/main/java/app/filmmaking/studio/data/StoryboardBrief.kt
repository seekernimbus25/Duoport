package app.filmmaking.studio.data

import org.json.JSONObject

/**
 * Turns the director's short idea ("woman sitting in the cafe") plus the shot plan into a detailed,
 * reviewable image brief. Deterministic, offline and free: nothing is sent until the director confirms
 * the paid generation, and they see (and can edit) exactly this text first.
 *
 * Only creative plan fields are used. Talent names, script passages, account data and unapproved
 * Creative DNA are never included; the server adds the fixed storyboard style line itself.
 */
object StoryboardBrief {
    const val MAX_CHARS = 1500 // server limit for `prompt`

    enum class Orientation(val wire: String, val label: String) {
        LANDSCAPE("landscape", "horizontal"), PORTRAIT("portrait", "vertical"), SQUARE("square", "square")
    }

    data class Line(val label: String, val text: String)
    data class Brief(val lines: List<Line>, val orientation: Orientation) {
        /** What is sent: subject first (image models weight the start), then camera, light, mood. */
        val text: String get() = lines.joinToString(" ") { l -> l.text.trimEnd().let { if (it.endsWith(".")) it else "$it." } }.take(MAX_CHARS)
    }

    private val SHOT_SIZES = mapOf(
        "ECU" to "extreme close-up on a single detail", "XCU" to "extreme close-up on a single detail",
        "CU" to "close-up, the face filling most of the frame", "MCU" to "medium close-up, framed from the chest up",
        "MS" to "medium shot, framed from the waist up", "MLS" to "medium long shot, framed from the knees up",
        "MWS" to "medium wide shot, framed from the knees up", "LS" to "long shot, the full figure with space around it",
        "WS" to "wide shot, the full figure small within the location", "EWS" to "extreme wide establishing shot, the location dominating",
        "ELS" to "extreme wide establishing shot, the location dominating", "OTS" to "over-the-shoulder shot",
        "POV" to "point-of-view shot through the character's eyes", "INSERT" to "insert shot of an object detail",
        "2S" to "two-shot holding both characters", "TWO SHOT" to "two-shot holding both characters",
    )

    private val ANGLES = mapOf(
        "eye level" to "eye-level camera", "high" to "high angle looking down", "high angle" to "high angle looking down",
        "low" to "low angle looking up", "low angle" to "low angle looking up", "overhead" to "overhead top-down view",
        "bird's eye" to "overhead top-down view", "birds eye" to "overhead top-down view", "dutch" to "tilted Dutch angle",
        "dutch angle" to "tilted Dutch angle", "worm's eye" to "extreme low angle from the ground",
    )

    fun shotSize(raw: String): String {
        val k = raw.trim().uppercase()
        return SHOT_SIZES[k] ?: SHOT_SIZES.entries.firstOrNull { k.startsWith(it.key + " ") || k.startsWith(it.key + "(") }?.value ?: raw.trim()
    }

    fun angle(raw: String): String = ANGLES[raw.trim().lowercase()] ?: raw.trim().let { if (it.isBlank()) it else "$it angle".replace("angle angle", "angle") }

    /** "35mm" → "35mm lens, natural perspective". Unparseable values pass through unchanged. */
    fun lens(raw: String): String {
        val mm = Regex("(\\d{1,3})\\s*mm", RegexOption.IGNORE_CASE).find(raw)?.groupValues?.get(1)?.toIntOrNull() ?: return raw.trim()
        val feel = when {
            mm < 20 -> "ultra-wide, exaggerated depth"
            mm < 30 -> "wide, deep space around the subject"
            mm <= 60 -> "natural perspective"
            mm <= 100 -> "portrait telephoto, soft background"
            else -> "long telephoto, compressed background"
        }
        return "${mm}mm lens, $feel"
    }

    fun movement(raw: String): String {
        val m = raw.trim(); if (m.isBlank()) return ""
        return if (m.equals("static", true) || m.equals("locked off", true) || m.equals("locked-off", true)) "locked-off static camera"
            else "camera move: $m (show the starting frame)"
    }

    fun orientation(aspect: String): Orientation {
        val p = Regex("(\\d+(?:\\.\\d+)?)\\s*[:x×/]\\s*(\\d+(?:\\.\\d+)?)").find(aspect)
        val w = p?.groupValues?.get(1)?.toDoubleOrNull(); val h = p?.groupValues?.get(2)?.toDoubleOrNull()
        if (w == null || h == null || w <= 0 || h <= 0) return Orientation.LANDSCAPE
        return when { w / h > 1.1 -> Orientation.LANDSCAPE; w / h < 0.9 -> Orientation.PORTRAIT; else -> Orientation.SQUARE }
    }

    private fun JSONObject?.s(key: String): String = this?.optString(key).orEmpty().trim().take(240)
    private fun joinNonBlank(vararg parts: String, sep: String = ", ") = parts.map { it.trim() }.filter { it.isNotBlank() }.joinToString(sep)

    /**
     * [idea] is the director's own words (may be blank: the shot title/description is used).
     * [tasteNotes] are approved Creative DNA texts; only the first few short ones are used.
     */
    fun build(idea: String, shot: JSONObject, scene: JSONObject? = null, project: JSONObject? = null,
              tasteNotes: List<String> = emptyList()): Brief {
        val cam = shot.optJSONObject("camera"); val light = shot.optJSONObject("lighting"); val prod = shot.optJSONObject("production")
        val lines = mutableListOf<Line>()
        val subject = idea.trim().ifBlank { joinNonBlank(shot.s("title"), shot.s("description"), sep = ": ") }
        if (subject.isNotBlank()) lines += Line("Subject", subject.take(400))
        val action = shot.s("description").takeIf { it.isNotBlank() && !subject.contains(it, ignoreCase = true) }
        if (action != null) lines += Line("Action", action)
        joinNonBlank(scene.s("title"), scene.s("description"), sep = " — ").takeIf { it.isNotBlank() }
            ?.let { lines += Line("Setting", "Setting: $it") }
        val framing = joinNonBlank(shotSize(cam.s("shotSize")), angle(cam.s("angle")), lens(cam.s("lens")), movement(cam.s("movement")))
        if (framing.isNotBlank()) lines += Line("Camera", "Framing: $framing")
        joinNonBlank(cam.s("composition"), shot.s("visualNotes")).takeIf { it.isNotBlank() }?.let { lines += Line("Composition", "Composition: $it") }
        val lighting = joinNonBlank(light.s("timeOfDay"), light.s("source"), light.s("direction"), light.s("quality"),
            light.s("keyFillBack"), light.s("colourTemp"), light.s("practicals").let { if (it.isBlank()) it else "practicals: $it" })
        if (lighting.isNotBlank()) lines += Line("Light", "Lighting: $lighting")
        joinNonBlank(prod.s("wardrobe").let { if (it.isBlank()) it else "wardrobe: $it" }, prod.s("props").let { if (it.isBlank()) it else "props: $it" })
            .takeIf { it.isNotBlank() }?.let { lines += Line("Details", it.replaceFirstChar(Char::uppercase)) }
        joinNonBlank(shot.s("purpose").let { if (it.isBlank()) it else "the frame should $it".replace("should to ", "should ") },
            project.s("mood").let { if (it.isBlank()) it else "overall mood: $it" })
            .takeIf { it.isNotBlank() }?.let { lines += Line("Mood", "Story intent: $it") }
        tasteNotes.map { it.trim() }.filter { it.length in 3..160 }.take(3).takeIf { it.isNotEmpty() }
            ?.let { lines += Line("Taste", "Director's visual preferences: " + it.joinToString("; ")) }
        val o = orientation(cam.s("aspect"))
        lines += Line("Format", "${o.label.replaceFirstChar(Char::uppercase)} ${cam.s("aspect").ifBlank { "16:9" }} frame, cinematic composition, people rendered as anonymous figures")
        return Brief(lines, o)
    }
}
