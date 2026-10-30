package com.dualframe.camera

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Standard shot sizes and angles, with the short codes used on shot lists and in file names. */
enum class ShotType(val code: String, val label: String, val aliases: List<String>) {
    ESTABLISHING("EST", "Establishing", listOf("establishing shot", "establishing", "est")),
    EXTREME_WIDE("EWS", "Extreme wide", listOf("extreme wide shot", "extreme long shot", "extreme wide", "ews", "els", "xws")),
    WIDE("WS", "Wide", listOf("wide shot", "wide angle", "long shot", "wide", "ws", "ls")),
    FULL("FS", "Full", listOf("full shot", "fs")),
    MEDIUM_WIDE("MWS", "Medium wide", listOf("medium wide shot", "medium long shot", "cowboy shot", "medium wide", "mws", "mls")),
    MEDIUM("MS", "Medium", listOf("medium shot", "mid shot", "medium", "ms")),
    MEDIUM_CLOSE("MCU", "Medium close-up", listOf("medium close-up", "medium close up", "mcu")),
    CLOSE("CU", "Close-up", listOf("close-up", "close up", "closeup", "cu")),
    EXTREME_CLOSE("ECU", "Extreme close-up", listOf("extreme close-up", "extreme close up", "ecu", "xcu")),
    OVER_SHOULDER("OTS", "Over the shoulder", listOf("over the shoulder shot", "over-the-shoulder", "over the shoulder", "ots", "ots shot")),
    POV("POV", "Point of view", listOf("point of view", "pov shot", "pov")),
    TWO_SHOT("2S", "Two shot", listOf("two shot", "two-shot", "2 shot", "2s")),
    INSERT("INS", "Insert / cutaway", listOf("insert shot", "insert", "cutaway", "b-roll", "broll", "ins")),
    LOW_ANGLE("LOW", "Low angle", listOf("low angle shot", "low angle", "low-angle")),
    HIGH_ANGLE("HIGH", "High angle", listOf("high angle shot", "high angle", "high-angle")),
    TOP_DOWN("TOP", "Top-down", listOf("top-down", "top down", "overhead", "bird's eye", "birds eye", "flat lay")),
    TRACKING("TRK", "Tracking / moving", listOf("tracking shot", "tracking", "dolly shot", "dolly", "trk")),
    OTHER("SHOT", "Other", emptyList()),
}

/** One take of a shot: the videos it made (one per shape, e.g. 9:16 and 16:9). */
class ShotTake(
    val number: Int,
    val videos: MutableList<Pair<String, Uri>> = mutableListOf(),
    val createdAt: Long = System.currentTimeMillis(),
    /** true = good, false = no good, null = not rated yet. */
    var good: Boolean? = null,
)

class Shot(
    val id: Long,
    /** "3", "3A"… as written on the shot list. */
    var number: String,
    var type: ShotType = ShotType.MEDIUM,
    var description: String = "",
    /** Lines to say; shown on the teleprompter while this shot is up. */
    var script: String = "",
    var notes: String = "",
) {
    val takes = mutableListOf<ShotTake>()
    /** The take that goes in the edit; null = the best guess ([pick]). */
    var chosen: Int? = null
    /** Take numbers already handed out, including takes still being saved. */
    var takesStarted = 0

    /** The chosen take, else the latest good one, else the latest unrated one. */
    fun pick(): ShotTake? = takes.firstOrNull { it.number == chosen }
        ?: takes.lastOrNull { it.good == true }
        ?: takes.lastOrNull { it.good != false }

    val done get() = takes.any { it.good == true } || chosen != null

    fun title() = "$number · ${type.code}" + if (description.isNotBlank()) " · $description" else ""
}

class ShotProject(val id: String, var name: String) {
    val shots = mutableListOf<Shot>()
    /** The shapes (and their resolutions) every take is shot in; empty = whatever the camera has on. */
    val outputs = LinkedHashMap<OutputFormat, OutputResolution>()
    var current = 0
    val currentShot get() = shots.getOrNull(current.coerceIn(0, (shots.size - 1).coerceAtLeast(0)))
    fun nextId() = (shots.maxOfOrNull { it.id } ?: 0L) + 1
}

/**
 * Shot lists: plan a sequence shot by shot, shoot it with the camera guiding you through each
 * shot (takes numbered and named for you), pick the best take of each, and join them in order.
 */
object ShotLists {
    private const val PREFS = "shot_lists"
    private const val ACTIVE = "active"
    private const val PENDING = "pending"

    private fun dir(context: Context) = File(context.filesDir, "shotlists").apply { mkdirs() }
    private fun file(context: Context, id: String) = File(dir(context), "$id.json")
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(context: Context): List<ShotProject> =
        dir(context).listFiles { f -> f.extension == "json" }.orEmpty().sortedByDescending { it.lastModified() }
            .mapNotNull { f -> runCatching { fromJson(JSONObject(f.readText())) }.getOrNull() }

    @Synchronized
    fun load(context: Context, id: String): ShotProject? =
        runCatching { fromJson(JSONObject(file(context, id).readText())) }.getOrNull()

    @Synchronized
    fun save(context: Context, project: ShotProject) {
        file(context, project.id).writeText(toJson(project).toString())
    }

    /** A new shot list starts with the shapes the camera is set to now. */
    fun create(context: Context, name: String): ShotProject =
        ShotProject(System.currentTimeMillis().toString(), name).also {
            it.outputs.putAll(SettingsStore.load(context).outputs)
            save(context, it)
        }

    fun delete(context: Context, project: ShotProject) {
        file(context, project.id).delete()
        if (activeId(context) == project.id) setActive(context, null)
    }

    /** The shot list the camera is shooting, or null when the camera isn't in shot-list mode. */
    fun activeId(context: Context): String? = prefs(context).getString(ACTIVE, null)

    fun active(context: Context): ShotProject? = activeId(context)?.let { load(context, it) }

    fun setActive(context: Context, id: String?) {
        prefs(context).edit().putString(ACTIVE, id).apply()
    }

    // region Takes

    /** A name the shot can be found by in any file browser: Kitchen_S03_T02_MS_20261030_141502 */
    fun takeName(project: ShotProject, shot: Shot, take: Int): String {
        val safe = project.name.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().replace(' ', '_').ifEmpty { "Shots" }
        val number = shot.number.filter { it.isLetterOrDigit() }.ifEmpty { "0" }
        val padded = number.takeWhile { it.isDigit() }.padStart(2, '0') + number.dropWhile { it.isDigit() }
        return String.format(Locale.US, "%s_S%s_T%02d_%s_", safe, padded, take, shot.type.code) +
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    }

    /**
     * Starts a take of the current shot and returns the name to record it under. The take is
     * added to the shot once its videos are saved ([onSaved]), even if the camera closes first.
     */
    @Synchronized
    fun beginTake(context: Context, project: ShotProject): Pair<String, Int>? {
        val fresh = load(context, project.id) ?: return null
        fresh.current = project.current
        val shot = fresh.currentShot ?: return null
        shot.takesStarted = maxOf(shot.takesStarted, shot.takes.maxOfOrNull { it.number } ?: 0) + 1
        val take = shot.takesStarted
        save(context, fresh)
        val name = takeName(fresh, shot, take)
        val pending = pending(context).put(name, JSONObject().put("project", fresh.id).put("shot", shot.id).put("take", take))
        prefs(context).edit().putString(PENDING, pending.toString()).apply()
        return name to take
    }

    private fun pending(context: Context) =
        runCatching { JSONObject(prefs(context).getString(PENDING, null) ?: "{}") }.getOrDefault(JSONObject())

    /** Called by the exporter when a recording's videos are saved. Ignores non-shot-list takes. */
    @Synchronized
    fun onSaved(context: Context, baseName: String, videos: List<Pair<String, Uri>>) {
        val all = pending(context)
        val entry = all.optJSONObject(baseName) ?: return
        all.remove(baseName)
        prefs(context).edit().putString(PENDING, all.toString()).apply()
        if (videos.isEmpty()) return
        val project = load(context, entry.getString("project")) ?: return
        val shot = project.shots.firstOrNull { it.id == entry.getLong("shot") } ?: return
        val take = ShotTake(entry.getInt("take"), videos.toMutableList())
        if (entry.has("good")) take.good = entry.getBoolean("good")
        shot.takes.removeAll { it.number == take.number }
        shot.takes += take
        shot.takes.sortBy { it.number }
        if (take.good == true) shot.chosen = take.number
        save(context, project)
    }

    /** Rates a take, whether or not it has finished saving yet. Good takes become the chosen one. */
    @Synchronized
    fun rate(context: Context, projectId: String, shotId: Long, take: Int, good: Boolean) {
        val all = pending(context)
        all.keys().asSequence().toList().forEach { k ->
            val e = all.getJSONObject(k)
            if (e.getString("project") == projectId && e.getLong("shot") == shotId && e.getInt("take") == take) e.put("good", good)
        }
        prefs(context).edit().putString(PENDING, all.toString()).apply()
        val project = load(context, projectId) ?: return
        val shot = project.shots.firstOrNull { it.id == shotId } ?: return
        shot.takes.firstOrNull { it.number == take }?.good = good
        if (good) shot.chosen = take else if (shot.chosen == take) shot.chosen = null
        save(context, project)
    }

    // endregion

    // region Paste

    private val NUMBER = Regex("""^\s*(?:shot\s*)?#?\s*(\d+[A-Za-z]?)\s*[.):\-–—]?\s*""", RegexOption.IGNORE_CASE)

    /**
     * Reads a pasted shot list, one shot per line, e.g.
     *   1. WS - Kitchen, morning light
     *   2 MS: Girl putting sugar in her tea
     *   Script: "Every morning starts the same way."
     *   3) Close-up of the spoon
     * A shot size (WS, MS, close-up, over the shoulder, POV…) at the start of a line is picked up;
     * "Script:" or "> " lines become the previous shot's teleprompter lines; "Note:" lines its notes.
     */
    fun parse(text: String, startId: Long, startNumber: Int): List<Shot> {
        val shots = mutableListOf<Shot>()
        var id = startId
        var auto = startNumber
        text.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { raw ->
            val lower = raw.lowercase(Locale.US)
            val last = shots.lastOrNull()
            when {
                last != null && (lower.startsWith("script:") || raw.startsWith(">")) -> {
                    val line = (if (raw.startsWith(">")) raw.removePrefix(">") else raw.substringAfter(':'))
                        .trim().trim('"', '“', '”')
                    last.script = if (last.script.isEmpty()) line else last.script + "\n" + line
                }
                last != null && (lower.startsWith("note:") || lower.startsWith("notes:")) -> {
                    val line = raw.substringAfter(':').trim()
                    last.notes = if (last.notes.isEmpty()) line else last.notes + "\n" + line
                }
                else -> {
                    var rest = raw
                    val number = NUMBER.find(rest)?.let { m ->
                        rest = rest.substring(m.range.last + 1)
                        m.groupValues[1].uppercase(Locale.US)
                    } ?: (auto).toString()
                    auto = (number.takeWhile { it.isDigit() }.toIntOrNull() ?: auto) + 1
                    val (type, description) = typeOf(rest)
                    shots += Shot(id++, number, type, description)
                }
            }
        }
        return shots
    }

    /** Finds a shot size at the start of [text]; returns it and what's left. */
    fun typeOf(text: String): Pair<ShotType, String> {
        val lower = text.lowercase(Locale.US)
        // Longest alias first so "medium close-up" wins over "medium".
        val match = ShotType.entries.flatMap { t -> t.aliases.map { it to t } }.sortedByDescending { it.first.length }
            .firstOrNull { (alias, _) ->
                lower.startsWith(alias) && lower.getOrNull(alias.length)?.isLetterOrDigit() != true
            }
        return if (match == null) {
            ShotType.OTHER to text.trim()
        } else {
            match.second to text.substring(match.first.length).trim().trimStart('-', '–', '—', ':', ',', '.', ' ').trim()
        }
    }

    // endregion

    // region JSON

    private fun toJson(p: ShotProject): JSONObject = JSONObject().apply {
        put("id", p.id)
        put("name", p.name)
        put("current", p.current)
        put("outputs", p.outputs.entries.joinToString(",") { "${it.key.name}:${it.value.name}" })
        put("shots", JSONArray().apply {
            p.shots.forEach { s ->
                put(JSONObject().apply {
                    put("id", s.id); put("number", s.number); put("type", s.type.name); put("description", s.description)
                    put("script", s.script); put("notes", s.notes); put("chosen", s.chosen ?: -1); put("takesStarted", s.takesStarted)
                    put("takes", JSONArray().apply {
                        s.takes.forEach { t ->
                            put(JSONObject().apply {
                                put("number", t.number); put("createdAt", t.createdAt)
                                t.good?.let { put("good", it) }
                                put("videos", JSONArray().apply {
                                    t.videos.forEach { (label, uri) -> put(JSONObject().put("label", label).put("uri", uri.toString())) }
                                })
                            })
                        }
                    })
                })
            }
        })
    }

    private fun fromJson(o: JSONObject): ShotProject = ShotProject(o.getString("id"), o.optString("name", "Shot list")).apply {
        current = o.optInt("current")
        o.optString("outputs").split(',').filter { ':' in it }.forEach { pair ->
            val format = OutputFormat.entries.firstOrNull { it.name == pair.substringBefore(':') }
            val resolution = OutputResolution.entries.firstOrNull { it.name == pair.substringAfter(':') }
            if (format != null && resolution != null) outputs[format] = resolution
        }
        val arr = o.optJSONArray("shots") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val s = arr.getJSONObject(i)
            shots += Shot(
                s.getLong("id"), s.optString("number", "${i + 1}"),
                ShotType.entries.firstOrNull { it.name == s.optString("type") } ?: ShotType.OTHER,
                s.optString("description"), s.optString("script"), s.optString("notes"),
            ).apply {
                chosen = s.optInt("chosen", -1).takeIf { it >= 0 }
                takesStarted = s.optInt("takesStarted")
                val takes = s.optJSONArray("takes") ?: JSONArray()
                for (j in 0 until takes.length()) {
                    val t = takes.getJSONObject(j)
                    val videos = t.optJSONArray("videos") ?: JSONArray()
                    this.takes += ShotTake(
                        t.getInt("number"),
                        (0 until videos.length()).map { k ->
                            videos.getJSONObject(k).let { v -> v.optString("label") to Uri.parse(v.getString("uri")) }
                        }.toMutableList(),
                        t.optLong("createdAt"),
                        if (t.has("good")) t.getBoolean("good") else null,
                    )
                }
            }
        }
    }

    // endregion
}
