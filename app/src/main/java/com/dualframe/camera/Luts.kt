package com.dualframe.camera

import android.content.Context
import android.hardware.camera2.params.TonemapCurve
import android.net.Uri
import java.io.File
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** A colour transform on RGB values in 0..1. */
private typealias Grade = (FloatArray) -> FloatArray

/** One LUT the user can pick: built-in or imported. */
data class LutInfo(val id: String, val name: String, val description: String)

/**
 * A baked 3D LUT: [size]^3 RGB entries, red changing slowest, in 0..1.
 * This one cube drives both the live preview (GPU) and the export (Media3 SingleColorLut).
 */
class LutCube(val size: Int, val rgb: FloatArray) {
    /** Media3's layout: cube[r][g][b] as ARGB ints. */
    fun toArgbCube(): Array<Array<IntArray>> = Array(size) { r ->
        Array(size) { g ->
            IntArray(size) { b ->
                val i = ((r * size + g) * size + b) * 3
                val rr = (rgb[i] * 255f + 0.5f).toInt().coerceIn(0, 255)
                val gg = (rgb[i + 1] * 255f + 0.5f).toInt().coerceIn(0, 255)
                val bb = (rgb[i + 2] * 255f + 0.5f).toInt().coerceIn(0, 255)
                (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
            }
        }
    }

    /** The .cube text format editors read (Resolve, Premiere, Final Cut): red changes fastest. */
    fun toCubeText(title: String, comments: List<String> = emptyList()): String = buildString {
        comments.forEach { append("# ").append(it).append('\n') }
        append("TITLE \"").append(title).append("\"\n")
        append("LUT_3D_SIZE ").append(size).append("\n\n")
        for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
            val i = ((r * size + g) * size + b) * 3
            append(String.format(java.util.Locale.US, "%.6f %.6f %.6f\n", rgb[i], rgb[i + 1], rgb[i + 2]))
        }
    }

    /** This LUT applied to every pixel of a photo, in a new bitmap (trilinear, like the GPU). */
    fun applyTo(bitmap: android.graphics.Bitmap): android.graphics.Bitmap {
        val out = bitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
        val w = out.width
        val row = IntArray(w)
        val n = size - 1
        for (y in 0 until out.height) {
            out.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val p = row[x]
                val fr = ((p shr 16) and 0xFF) / 255f * n
                val fg = ((p shr 8) and 0xFF) / 255f * n
                val fb = (p and 0xFF) / 255f * n
                val r0 = fr.toInt().coerceAtMost(n - 1)
                val g0 = fg.toInt().coerceAtMost(n - 1)
                val b0 = fb.toInt().coerceAtMost(n - 1)
                val dr = fr - r0
                val dg = fg - g0
                val db = fb - b0
                var argb = 0xFF shl 24
                for (c in 0 until 3) {
                    fun at(r: Int, g: Int, b: Int) = rgb[((r * size + g) * size + b) * 3 + c]
                    val c00 = at(r0, g0, b0) * (1 - db) + at(r0, g0, b0 + 1) * db
                    val c01 = at(r0, g0 + 1, b0) * (1 - db) + at(r0, g0 + 1, b0 + 1) * db
                    val c10 = at(r0 + 1, g0, b0) * (1 - db) + at(r0 + 1, g0, b0 + 1) * db
                    val c11 = at(r0 + 1, g0 + 1, b0) * (1 - db) + at(r0 + 1, g0 + 1, b0 + 1) * db
                    val v = (c00 * (1 - dg) + c01 * dg) * (1 - dr) + (c10 * (1 - dg) + c11 * dg) * dr
                    argb = argb or ((v * 255f + 0.5f).toInt().coerceIn(0, 255) shl (16 - 8 * c))
                }
                row[x] = argb
            }
            out.setPixels(row, 0, w, 0, y, w, 1)
        }
        return out
    }

    /** GL 3D texture layout: x = red (fastest), y = green, z = blue. */
    fun toGlBytes(): ByteArray {
        val out = ByteArray(size * size * size * 3)
        var o = 0
        for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
            val i = ((r * size + g) * size + b) * 3
            for (c in 0 until 3) out[o++] = (rgb[i + c] * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()
        }
        return out
    }

    /** Trilinear lookup, for chaining a custom LUT after a conversion. */
    fun sample(p: FloatArray): FloatArray {
        val n = size - 1
        val fx = p[0].coerceIn(0f, 1f) * n
        val fy = p[1].coerceIn(0f, 1f) * n
        val fz = p[2].coerceIn(0f, 1f) * n
        val x0 = fx.toInt().coerceAtMost(n - 1).coerceAtLeast(0)
        val y0 = fy.toInt().coerceAtMost(n - 1).coerceAtLeast(0)
        val z0 = fz.toInt().coerceAtMost(n - 1).coerceAtLeast(0)
        val dx = fx - x0
        val dy = fy - y0
        val dz = fz - z0
        val out = FloatArray(3)
        for (c in 0 until 3) {
            fun v(r: Int, g: Int, b: Int) = rgb[((r * size + g) * size + b) * 3 + c]
            val c00 = v(x0, y0, z0) * (1 - dx) + v(x0 + 1, y0, z0) * dx
            val c10 = v(x0, y0 + 1, z0) * (1 - dx) + v(x0 + 1, y0 + 1, z0) * dx
            val c01 = v(x0, y0, z0 + 1) * (1 - dx) + v(x0 + 1, y0, z0 + 1) * dx
            val c11 = v(x0, y0 + 1, z0 + 1) * (1 - dx) + v(x0 + 1, y0 + 1, z0 + 1) * dx
            out[c] = (c00 * (1 - dy) + c10 * dy) * (1 - dz) + (c01 * (1 - dy) + c11 * dy) * dz
        }
        return out
    }

    companion object {
        /** A LUT that changes nothing, for when no look or adjustment is set. */
        val IDENTITY by lazy { bake(2) { it } }

        fun bake(size: Int, grade: (FloatArray) -> FloatArray): LutCube {
            val data = FloatArray(size * size * size * 3)
            val step = 1f / (size - 1)
            for (r in 0 until size) for (g in 0 until size) for (b in 0 until size) {
                val out = grade(floatArrayOf(r * step, g * step, b * step))
                val i = ((r * size + g) * size + b) * 3
                data[i] = out[0].coerceIn(0f, 1f)
                data[i + 1] = out[1].coerceIn(0f, 1f)
                data[i + 2] = out[2].coerceIn(0f, 1f)
            }
            return LutCube(size, data)
        }
    }
}

object Luts {
    /** A LUT that changes nothing, for when only the monitoring aids need the shader. */
    val identity: LutCube by lazy { LutCube.bake(2) { it } }

    const val NONE = "none"
    /** Only offered with DF-Log: just turns the flat log picture back into normal contrast. */
    const val REC709 = "rec709"
    private const val CUSTOM_PREFIX = "custom:"
    private const val BAKE_SIZE = 33

    // region DF-Log, the camera-side log curve

    // The curve gets the camera's linear output, and with its own tone mapping off the Pixel
    // exposes that low: the scene's average comes out near 0.026, not 0.18 (its processing
    // lifts it afterwards). Measured on the 9a (PROF-01): with K = 30 DF-Log came out darker
    // than Standard, shadows crushed. K = 340 puts the average near 40 %, a log's usual
    // mid grey, and blacks near 8 %.
    private const val LOG_K = 340f
    private const val LOG_FLOOR = 0.035f
    private const val LOG_RANGE = 0.93f
    /** Where the camera's linear output puts mid grey (see above). */
    private const val CAMERA_MID = 0.03f
    /** Linear camera white, in scene terms where mid grey is 0.18. */
    private const val SCENE_WHITE = 0.18f / CAMERA_MID

    /** Linear camera light -> DF-Log code value. Lifted blacks, long highlight shoulder. */
    private fun linearToLog(x: Float) = LOG_FLOOR + LOG_RANGE * (ln(1f + LOG_K * x) / ln(1f + LOG_K))

    private fun logToLinear(v: Float): Float {
        val t = ((v - LOG_FLOOR) / LOG_RANGE).coerceIn(0f, 1f)
        return (exp(t * ln(1f + LOG_K)) - 1f) / LOG_K
    }

    /** The tone curve sent to the camera in DF-Log (Camera2 curves map linear input to output). */
    val logCurve: TonemapCurve by lazy {
        val points = 64
        val curve = FloatArray(points * 2)
        for (i in 0 until points) {
            val x = i / (points - 1f)
            curve[i * 2] = x
            curve[i * 2 + 1] = linearToLog(x)
        }
        TonemapCurve(curve, curve, curve)
    }

    /** DF-Log -> Rec.709 as a .cube file, so any editor can convert DF-Log footage the same way the app does. */
    fun logToRec709CubeText(): String = LutCube.bake(BAKE_SIZE, logTo709).toCubeText(
        "DF-Log to Rec.709",
        listOf(
            "DualFrame DF-Log to Rec.709 (BT.709 primaries, sRGB-style display gamma).",
            "DF-Log curve: v = $LOG_FLOOR + $LOG_RANGE * ln(1 + $LOG_K * x) / ln(1 + $LOG_K), x = linear light 0..1, mid grey at x = $CAMERA_MID.",
            "Apply to DF-Log clips as the first node / input LUT, then grade.",
        ),
    )

    /**
     * DF-Log -> Rec.709-style display picture: back to scene light, then a filmic curve (ACES
     * fit, Narkowicz) whose toe brings the lifted blacks back down and whose shoulder rolls the
     * highlights off. [VIEW_EXPOSURE] was matched on the 9a: DF-Log through this came out within
     * a few levels of the Standard profile on the same scene (blacks 38 vs 30, average 79 vs 69).
     */
    private val logTo709: Grade = { p ->
        FloatArray(3) { c ->
            val x = logToLinear(p[c]) * SCENE_WHITE * VIEW_EXPOSURE
            srgbEncode(x * (2.51f * x + 0.03f) / (x * (2.43f * x + 0.59f) + 0.14f))
        }
    }

    private const val VIEW_EXPOSURE = 0.3f

    private fun srgbEncode(x: Float): Float {
        val v = x.coerceIn(0f, 1f)
        return if (v <= 0.0031308f) v * 12.92f else 1.055f * v.pow(1f / 2.4f) - 0.055f
    }

    // endregion

    // region Building blocks for the built-in looks

    private fun luma(p: FloatArray) = 0.2126f * p[0] + 0.7152f * p[1] + 0.0722f * p[2]

    private fun saturation(amount: Float): Grade = { p ->
        val y = luma(p)
        FloatArray(3) { y + (p[it] - y) * amount }
    }

    private fun contrast(amount: Float, pivot: Float = 0.5f): Grade = { p ->
        FloatArray(3) { (p[it] - pivot) * amount + pivot }
    }

    /** S-curve: more punch in the mids without crushing the ends. */
    private fun sCurve(strength: Float): Grade = { p ->
        FloatArray(3) {
            val x = p[it].coerceIn(0f, 1f)
            val s = x * x * (3f - 2f * x)
            x + (s - x) * strength
        }
    }

    private fun gain(r: Float, g: Float, b: Float): Grade = { p -> floatArrayOf(p[0] * r, p[1] * g, p[2] * b) }

    /** Lift the blacks and lower the whites, like a print. */
    private fun fade(blacks: Float, whites: Float): Grade = { p ->
        FloatArray(3) { blacks + p[it] * (whites - blacks) }
    }

    /** Tint shadows and highlights toward two colours, weighted by brightness. */
    private fun splitTone(shadow: FloatArray, highlight: FloatArray, amount: Float): Grade = { p ->
        val y = luma(p).coerceIn(0f, 1f)
        val sw = (1f - y) * (1f - y) * amount
        val hw = y * y * amount
        FloatArray(3) { p[it] + (shadow[it] - 0.5f) * sw + (highlight[it] - 0.5f) * hw }
    }

    /** Pull every hue toward teal or orange depending on whether it is warm or cool. */
    private val tealOrangeShift: Grade = { p ->
        val warm = p[0] - p[2]
        val w = (abs(warm) * 1.6f).coerceAtMost(1f)
        val target = if (warm >= 0) floatArrayOf(1f, 0.62f, 0.35f) else floatArrayOf(0.25f, 0.62f, 0.68f)
        val y = luma(p)
        val toned = FloatArray(3) { y + (target[it] - 0.5f) * 0.55f }
        FloatArray(3) { p[it] + (toned[it] - p[it]) * 0.35f * w }
    }

    private fun mono(r: Float, g: Float, b: Float): Grade = { p ->
        val y = p[0] * r + p[1] * g + p[2] * b
        floatArrayOf(y, y, y)
    }

    private fun chain(vararg grades: Grade): Grade = { p -> grades.fold(p) { acc, f -> f(acc) } }

    // endregion

    private data class BuiltIn(val info: LutInfo, val grade: Grade)

    private val builtIns = listOf(
        BuiltIn(
            LutInfo("vivid", "Vivid", "Rich colour and punch, ready to post"),
            chain(saturation(1.25f), sCurve(0.25f), gain(1.02f, 1f, 0.98f)),
        ),
        BuiltIn(
            LutInfo("teal_orange", "Teal & Orange", "The blockbuster look: warm skin, cool shadows"),
            chain(tealOrangeShift, splitTone(floatArrayOf(0.35f, 0.55f, 0.62f), floatArrayOf(0.62f, 0.52f, 0.4f), 0.35f), sCurve(0.3f)),
        ),
        BuiltIn(
            LutInfo("film", "Film", "Soft print film: lifted blacks, warm highlights"),
            chain(sCurve(0.2f), splitTone(floatArrayOf(0.45f, 0.5f, 0.56f), floatArrayOf(0.58f, 0.52f, 0.42f), 0.25f), saturation(0.9f), fade(0.05f, 0.96f)),
        ),
        BuiltIn(
            LutInfo("golden", "Golden Hour", "Warm, glowing, nostalgic"),
            chain(gain(1.07f, 1.0f, 0.86f), splitTone(floatArrayOf(0.52f, 0.46f, 0.42f), floatArrayOf(0.62f, 0.54f, 0.4f), 0.3f), saturation(1.1f), fade(0.02f, 0.98f)),
        ),
        BuiltIn(
            LutInfo("moody", "Moody", "Cool, deep and quiet"),
            chain(saturation(0.75f), gain(0.95f, 1f, 1.06f), contrast(1.12f, 0.45f), splitTone(floatArrayOf(0.4f, 0.5f, 0.6f), floatArrayOf(0.5f, 0.5f, 0.5f), 0.3f)),
        ),
        BuiltIn(
            LutInfo("bleach", "Bleach Bypass", "Gritty, silvery, high contrast"),
            chain({ p -> val y = luma(p); FloatArray(3) { (p[it] + y) * 0.5f } }, contrast(1.25f), saturation(0.55f)),
        ),
        BuiltIn(
            LutInfo("noir", "Noir", "Classic black and white"),
            chain(mono(0.3f, 0.6f, 0.1f), sCurve(0.45f), fade(0.03f, 1f)),
        ),
    )

    /** Everything the picker shows for the given profile, in order. */
    fun available(context: Context, profile: RecordProfile): List<LutInfo> {
        val list = mutableListOf<LutInfo>()
        if (profile == RecordProfile.LOG) {
            list += LutInfo(NONE, "Log (no LUT)", "Keep the flat log picture for grading in an editor")
            list += LutInfo(REC709, "Rec.709", "Normal contrast and colour from DF-Log")
        } else {
            list += LutInfo(NONE, "None", "The camera's own picture")
        }
        list += builtIns.map { it.info }
        list += customLuts(context).map { LutInfo(CUSTOM_PREFIX + it.name, it.nameWithoutExtension, "Imported .cube") }
        return list
    }

    fun displayName(context: Context, id: String, profile: RecordProfile): String =
        available(context, profile).firstOrNull { it.id == id }?.name ?: "None"

    /**
     * The single cube to apply for [id] on footage recorded with [profile], plus the colour part of
     * [grade], or null for "no change". Order: DF-Log to Rec.709 (when a look is chosen), then the
     * grade (correction), then the look. With DF-Log every look first converts log to Rec.709, so
     * LUTs made for normal footage just work.
     */
    fun cube(context: Context, id: String, profile: RecordProfile, grade: ColorGrade = ColorGrade()): LutCube? {
        val fromLog = profile == RecordProfile.LOG
        val toDisplay: Grade? = if (fromLog && id != NONE) logTo709 else null
        val custom: LutCube? = if (id.startsWith(CUSTOM_PREFIX)) {
            runCatching { parseCube(File(lutDir(context), id.removePrefix(CUSTOM_PREFIX)).readText()) }.getOrNull()
        } else {
            null
        }
        val look: Grade? = builtIns.firstOrNull { it.info.id == id }?.grade ?: custom?.let { c -> { p: FloatArray -> c.sample(p) } }
        val correction: Grade? = if (grade.colourIsNeutral) null else { p -> grade.apply(p) }
        // An imported LUT on its own is used exactly as it is.
        if (custom != null && toDisplay == null && correction == null) return custom
        val parts = listOfNotNull(toDisplay, correction, look)
        if (parts.isEmpty()) return null
        return LutCube.bake(BAKE_SIZE, chain(*parts.toTypedArray()))
    }

    /** Saves [cube] as a custom look (it then shows up with the imported LUTs). Returns its id. */
    fun saveCustom(context: Context, name: String, cube: LutCube): String {
        val base = name.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifEmpty { "My grade" }
        var file = File(lutDir(context), "$base.cube")
        var n = 2
        while (file.exists()) file = File(lutDir(context), "$base $n.cube").also { n++ }
        file.writeText(cube.toCubeText(base))
        return CUSTOM_PREFIX + file.name
    }

    // region Imported .cube files

    private fun lutDir(context: Context) = File(context.filesDir, "luts").apply { mkdirs() }

    fun customLuts(context: Context): List<File> =
        lutDir(context).listFiles { f -> f.extension.equals("cube", ignoreCase = true) }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()

    /** Copies a .cube file into the app after checking it parses. Returns its id. */
    fun import(context: Context, uri: Uri, displayName: String?): String {
        val text = context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
            ?: error("Couldn't open the file")
        parseCube(text) // throws with a readable message if it isn't a valid 3D LUT
        val base = (displayName ?: "Custom LUT").substringBeforeLast('.').replace(Regex("[^A-Za-z0-9 _-]"), "").trim()
            .ifEmpty { "Custom LUT" }
        var file = File(lutDir(context), "$base.cube")
        var n = 2
        while (file.exists()) file = File(lutDir(context), "$base $n.cube").also { n++ }
        file.writeText(text)
        return CUSTOM_PREFIX + file.name
    }

    fun delete(context: Context, id: String) {
        if (id.startsWith(CUSTOM_PREFIX)) File(lutDir(context), id.removePrefix(CUSTOM_PREFIX)).delete()
    }

    /** Parses the Adobe/Resolve .cube format (3D LUTs only). */
    fun parseCube(text: String): LutCube {
        var size = 0
        val values = ArrayList<Float>()
        text.lineSequence().forEach { raw ->
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) return@forEach
            val parts = line.split(Regex("\\s+"))
            when (parts[0].uppercase()) {
                "TITLE" -> Unit
                "LUT_3D_SIZE" -> size = parts.getOrNull(1)?.toIntOrNull() ?: 0
                "LUT_1D_SIZE" -> error("This is a 1D LUT. Only 3D .cube LUTs are supported.")
                // The input domain is almost always 0..1 for video LUTs; other domains are rare
                // (HDR/scene-linear) and not meaningful for this camera's 8-bit pipeline.
                "DOMAIN_MIN", "DOMAIN_MAX", "LUT_3D_INPUT_RANGE" -> Unit
                else -> if (parts.size >= 3) {
                    parts.take(3).forEach { values += it.toFloatOrNull() ?: error("Unexpected line: $line") }
                }
            }
        }
        require(size in 2..65) { "LUT_3D_SIZE missing or unsupported (found $size)" }
        require(values.size == size * size * size * 3) {
            "Expected ${size * size * size} colours for a $size-point LUT, found ${values.size / 3}"
        }
        // .cube lists red fastest; LutCube stores red slowest.
        val data = FloatArray(values.size)
        var i = 0
        for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
            val o = ((r * size + g) * size + b) * 3
            for (c in 0 until 3) data[o + c] = min(1f, max(0f, values[i + c]))
            i += 3
        }
        return LutCube(size, data)
    }

    // endregion
}
