package com.dualframe.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A set of stickers in the design editor. */
enum class StickerPack(val label: String) {
    EMOJI("Emoji"), THREE_D("3D"), ANIMATED("Animated"), YOURS("Your GIF / PNG")
}

/** An animation as frames shown one after another, [frameMs] each, looping. */
class Frames(val frames: List<Bitmap>, val frameMs: Int) {
    val durationMs get() = frames.size * frameMs

    fun at(ms: Long): Bitmap = frames[((ms.coerceAtLeast(0) / frameMs) % frames.size).toInt()]
}

/**
 * The bundled sticker packs (assets/stickers): Fluent 3D emoji (Microsoft, MIT) as still
 * images and Noto animated emoji (Google, CC BY 4.0) as sprite strips of square frames,
 * described by animated/index.json. Also decodes your own animated GIFs.
 */
object Stickers {
    private const val THREE_D = "stickers/3d"
    private const val ANIMATED = "stickers/animated"
    private const val STRIP_FRAME = 128

    /** Asset ids for a pack, e.g. "3d/fire" or "animated/1f525". */
    fun ids(context: Context, pack: StickerPack): List<String> = when (pack) {
        StickerPack.THREE_D -> context.assets.list(THREE_D).orEmpty().filter { it.endsWith(".webp") }.sorted()
            .map { "3d/" + it.removeSuffix(".webp") }
        StickerPack.ANIMATED -> index(context).keys.sorted().map { "animated/$it" }
        else -> emptyList()
    }

    private var cachedIndex: Map<String, Pair<Int, Int>>? = null

    private fun index(context: Context): Map<String, Pair<Int, Int>> = cachedIndex ?: runCatching {
        val json = JSONObject(context.assets.open("$ANIMATED/index.json").bufferedReader().use { it.readText() })
        json.keys().asSequence().associateWith { k ->
            val o = json.getJSONObject(k)
            o.getInt("frames") to o.getInt("frameMs")
        }
    }.getOrDefault(emptyMap()).also { cachedIndex = it }

    fun isAnimated(id: String) = id.startsWith("animated/")

    private fun decodeAsset(context: Context, path: String): Bitmap? =
        runCatching { context.assets.open(path).use { BitmapFactory.decodeStream(it) } }.getOrNull()

    /** A small still for the picker (the first frame of an animated sticker). */
    fun thumbnail(context: Context, id: String): Bitmap? =
        if (isAnimated(id)) {
            decodeAsset(context, "$ANIMATED/${id.removePrefix("animated/")}.webp")?.let {
                Bitmap.createBitmap(it, 0, 0, min(STRIP_FRAME, it.width), min(STRIP_FRAME, it.height))
            }
        } else {
            decodeAsset(context, "$THREE_D/${id.removePrefix("3d/")}.webp")
        }

    /** A bundled sticker at [size] px: frames for an animated one, a single frame otherwise. */
    fun load(context: Context, id: String, size: Int): Frames? {
        if (!isAnimated(id)) {
            val still = decodeAsset(context, "$THREE_D/${id.removePrefix("3d/")}.webp") ?: return null
            return Frames(listOf(Bitmap.createScaledBitmap(still, size, size, true)), 1000)
        }
        val name = id.removePrefix("animated/")
        val (count, frameMs) = index(context)[name] ?: return null
        val strip = decodeAsset(context, "$ANIMATED/$name.webp") ?: return null
        val frame = strip.height
        val frames = (0 until min(count, strip.width / frame)).map { i ->
            Bitmap.createScaledBitmap(Bitmap.createBitmap(strip, i * frame, 0, frame, frame), size, size, true)
        }
        strip.recycle()
        return if (frames.isEmpty()) null else Frames(frames, frameMs.coerceAtLeast(20))
    }

    /** True when the file is a GIF (it may be animated). */
    fun isGif(context: Context, uri: Uri): Boolean = runCatching {
        context.contentResolver.openInputStream(uri)?.use { s ->
            val head = ByteArray(4)
            s.read(head) == 4 && String(head, Charsets.US_ASCII) == "GIF8"
        } ?: false
    }.getOrDefault(false)

    /**
     * Your own animated GIF as frames, fitted inside [maxSide] and sampled to at most 48 frames
     * (about 15 a second), so a long GIF doesn't use up the memory. Null if it isn't animated.
     */
    @Suppress("DEPRECATION")
    fun gif(context: Context, uri: Uri, maxSide: Int): Frames? = runCatching {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        val movie = android.graphics.Movie.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val duration = movie.duration()
        if (duration <= 0 || movie.width() <= 0 || movie.height() <= 0) return null
        val scale = min(1f, maxSide / max(movie.width(), movie.height()).toFloat())
        val w = (movie.width() * scale).roundToInt().coerceAtLeast(1)
        val h = (movie.height() * scale).roundToInt().coerceAtLeast(1)
        val count = (duration / 66).coerceIn(1, 48)
        val step = duration / count
        val frames = (0 until count).map { i ->
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b ->
                movie.setTime(i * step)
                val c = Canvas(b)
                c.scale(scale, scale)
                movie.draw(c, 0f, 0f)
            }
        }
        Frames(frames, step.coerceAtLeast(20))
    }.getOrNull()
}
