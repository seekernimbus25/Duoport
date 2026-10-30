package com.dualframe.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.min

/** A design you can start from: one of the built-in ones, or one you saved. */
class DesignTemplate(val id: String, val name: String, val json: JSONObject, val builtIn: Boolean)

/**
 * Saving and loading designs. A saved design keeps everything except the video itself, so it
 * works as a template for any other video. Photos, GIFs and background videos are kept as
 * links to the files on your phone (if you delete one, that part is just left out).
 */
object DesignTemplates {

    private fun dir(context: Context) = File(context.filesDir, "designs").apply { mkdirs() }

    fun all(context: Context): List<DesignTemplate> = builtIns() + saved(context)

    private fun saved(context: Context): List<DesignTemplate> =
        dir(context).listFiles { f -> f.extension == "json" }.orEmpty().sortedByDescending { it.lastModified() }.mapNotNull { f ->
            runCatching {
                val json = JSONObject(f.readText())
                DesignTemplate(f.nameWithoutExtension, json.optString("name", f.nameWithoutExtension), json, builtIn = false)
            }.getOrNull()
        }

    fun save(context: Context, design: Design, name: String) {
        val json = toJson(design).put("name", name)
        File(dir(context), "${System.currentTimeMillis()}.json").writeText(json.toString())
    }

    fun delete(context: Context, template: DesignTemplate) {
        if (!template.builtIn) File(dir(context), "${template.id}.json").delete()
    }

    // region JSON

    private fun Uri?.str() = this?.toString() ?: ""
    private fun JSONObject.uri(key: String): Uri? = optString(key).takeIf { it.isNotEmpty() }?.let(Uri::parse)

    fun toJson(d: Design): JSONObject = JSONObject().apply {
        put("canvas", d.canvas.name)
        put("background", d.background.name)
        put("bgColor", d.bgColor)
        put("bgColor2", d.bgColor2)
        put("bgImage", d.bgImage.str())
        put("bgVideo", d.bgVideo.str())
        put("bgVideoBlur", d.bgVideoBlur)
        val v = d.video
        put("video", JSONObject().apply {
            put("x", v.x.toDouble()); put("y", v.y.toDouble()); put("scale", v.scale.toDouble()); put("rotation", v.rotation.toDouble())
            put("mask", v.mask.name); put("corner", v.corner); put("border", v.border); put("borderColor", v.borderColor); put("cutout", v.cutout)
        })
        put("layers", JSONArray().apply {
            d.layers.forEach { l ->
                put(JSONObject().apply {
                    put("kind", l.kind.name); put("text", l.text); put("font", l.font); put("color", l.color); put("outline", l.outline)
                    put("imageUri", l.imageUri.str()); put("cutout", l.cutout); put("asset", l.asset ?: "")
                    put("x", l.x.toDouble()); put("y", l.y.toDouble()); put("scale", l.scale.toDouble()); put("rotation", l.rotation.toDouble())
                    put("opacity", l.opacity.toDouble()); put("startMs", l.startMs)
                    put("endMs", if (l.endMs == Long.MAX_VALUE) -1 else l.endMs)
                    put("animation", l.animation.name); put("motion", l.motion.name)
                })
            }
        })
        d.intro?.let { put("intro", cardJson(it)) }
        d.outro?.let { put("outro", cardJson(it)) }
    }

    private fun cardJson(c: Card) = JSONObject().apply {
        put("title", c.title); put("subtitle", c.subtitle); put("color1", c.color1); put("color2", c.color2)
        put("textColor", c.textColor); put("font", c.font); put("durationMs", c.durationMs); put("imageUri", c.imageUri.str())
    }

    private fun card(o: JSONObject) = Card(
        title = o.optString("title"), subtitle = o.optString("subtitle"),
        color1 = o.optInt("color1", Card().color1), color2 = o.optInt("color2", Card().color2),
        textColor = o.optInt("textColor", Color.WHITE), font = o.optString("font", Card().font),
        durationMs = o.optLong("durationMs", 2500), imageUri = o.uri("imageUri"),
    )

    private inline fun <reified T : Enum<T>> JSONObject.enumOf(key: String, default: T): T =
        enumValues<T>().firstOrNull { it.name == optString(key) } ?: default

    /** Puts a saved design onto [d] (the video stays the one you're editing). */
    fun applyTo(json: JSONObject, d: Design, newId: () -> Long) {
        d.canvas = json.enumOf("canvas", d.canvas)
        d.background = json.enumOf("background", BackgroundKind.BLUR)
        d.bgColor = json.optInt("bgColor", d.bgColor)
        d.bgColor2 = json.optInt("bgColor2", d.bgColor2)
        d.bgImage = json.uri("bgImage")
        d.bgVideo = json.uri("bgVideo")
        d.bgVideoBlur = json.optBoolean("bgVideoBlur")
        json.optJSONObject("video")?.let { o ->
            val v = d.video
            v.x = o.optDouble("x", 0.5).toFloat(); v.y = o.optDouble("y", 0.5).toFloat()
            v.scale = o.optDouble("scale", 1.0).toFloat(); v.rotation = o.optDouble("rotation", 0.0).toFloat()
            v.mask = o.enumOf("mask", VideoMask.NONE); v.corner = o.optInt("corner", 8); v.border = o.optInt("border", 0)
            v.borderColor = o.optInt("borderColor", Color.WHITE); v.cutout = o.optBoolean("cutout")
        }
        d.layers.clear()
        val layers = json.optJSONArray("layers") ?: JSONArray()
        for (i in 0 until layers.length()) {
            val o = layers.getJSONObject(i)
            d.layers += Layer(newId(), o.enumOf("kind", LayerKind.TEXT)).apply {
                text = o.optString("text"); font = o.optString("font", font); color = o.optInt("color", Color.WHITE)
                outline = o.optBoolean("outline", true); imageUri = o.uri("imageUri"); cutout = o.optBoolean("cutout")
                asset = o.optString("asset").takeIf { it.isNotEmpty() }
                x = o.optDouble("x", 0.5).toFloat(); y = o.optDouble("y", 0.5).toFloat()
                scale = o.optDouble("scale", 1.0).toFloat(); rotation = o.optDouble("rotation", 0.0).toFloat()
                opacity = o.optDouble("opacity", 1.0).toFloat(); startMs = o.optLong("startMs", 0)
                endMs = o.optLong("endMs", -1).let { if (it < 0) Long.MAX_VALUE else it }
                animation = o.enumOf("animation", LayerAnimation.NONE); motion = o.enumOf("motion", LayerMotion.NONE)
            }
        }
        d.intro = json.optJSONObject("intro")?.let(::card)
        d.outro = json.optJSONObject("outro")?.let(::card)
    }

    // endregion

    // region Built-in

    private fun builtIns(): List<DesignTemplate> {
        var id = 0L
        fun layer(kind: LayerKind, setup: Layer.() -> Unit) = Layer(id++, kind).apply(setup)

        // Dreamy heart: the video in a heart on a pastel sky, with floating sparkles and hearts.
        val dreamy = Design().apply {
            canvas = OutputFormat.PORTRAIT_9_16
            background = BackgroundKind.GRADIENT
            bgColor = 0xFFFFC7DE.toInt()
            bgColor2 = 0xFFC9B8FF.toInt()
            video.apply { y = 0.47f; scale = 0.95f; mask = VideoMask.HEART; border = 14; borderColor = Color.WHITE }
            layers += layer(LayerKind.TEXT) {
                text = "dream big"; font = "res:pacifico"; color = 0xFFFFFFFF.toInt(); outline = false
                y = 0.15f; scale = 1.4f; animation = LayerAnimation.POP; motion = LayerMotion.FLOAT
            }
            layers += layer(LayerKind.STICKER) { asset = "animated/2728"; x = 0.18f; y = 0.27f; scale = 0.8f; motion = LayerMotion.FLOAT }
            layers += layer(LayerKind.STICKER) { asset = "animated/1f496"; x = 0.82f; y = 0.7f; scale = 0.9f; motion = LayerMotion.BOUNCE }
            layers += layer(LayerKind.STICKER) { asset = "animated/2b50"; x = 0.2f; y = 0.8f; scale = 0.6f; motion = LayerMotion.WIGGLE }
        }

        // Scrapbook: a tilted snapshot with a thick white frame on butter yellow, doodled stickers.
        val scrapbook = Design().apply {
            canvas = OutputFormat.PORTRAIT_9_16
            background = BackgroundKind.GRADIENT
            bgColor = 0xFFFFF1B8.toInt()
            bgColor2 = 0xFFFFD6A5.toInt()
            video.apply { y = 0.46f; scale = 0.78f; rotation = -4f; mask = VideoMask.ROUNDED; corner = 4; border = 30; borderColor = Color.WHITE }
            layers += layer(LayerKind.TEXT) {
                text = "a little adventure"; font = "res:permanent_marker"; color = 0xFF3B2A4D.toInt(); outline = false
                y = 0.87f; rotation = -3f; scale = 1.1f; animation = LayerAnimation.SLIDE_UP
            }
            layers += layer(LayerKind.STICKER) { asset = "3d/rainbow"; x = 0.8f; y = 0.13f; scale = 0.9f; rotation = 12f; motion = LayerMotion.WIGGLE }
            layers += layer(LayerKind.STICKER) { asset = "3d/cherry_blossom"; x = 0.16f; y = 0.75f; scale = 0.7f; motion = LayerMotion.SPIN }
            layers += layer(LayerKind.STICKER) { asset = "animated/1f308"; x = 0.2f; y = 0.14f; scale = 0.6f; motion = LayerMotion.FLOAT }
        }

        return listOf(
            DesignTemplate("builtin_dreamy", "Dreamy heart", toJson(dreamy), builtIn = true),
            DesignTemplate("builtin_scrapbook", "Scrapbook", toJson(scrapbook), builtIn = true),
        )
    }

    // endregion

    /**
     * A small picture of what [template] will look like with your video ([frame], a still from
     * it), for the template picker. Shapes are close approximations; cutouts show the full frame.
     */
    fun thumbnail(context: Context, template: DesignTemplate, frame: Bitmap?, width: Int): Bitmap {
        val d = Design()
        var id = 0L
        applyTo(template.json, d) { id++ }
        val (cw, ch) = d.canvasSize
        val k = width / cw.toFloat()
        val h = (ch * k).toInt()
        val out = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val bg = DesignArt.background(context, d)
        when {
            bg != null -> c.drawBitmap(bg, null, Rect(0, 0, width, h), Paint(Paint.FILTER_BITMAP_FLAG))
            frame != null -> {
                c.drawBitmap(DesignArt.cover(frame, width, h), 0f, 0f, null)
                c.drawColor(Color.argb(110, 0, 0, 0))
            }
            else -> c.drawColor(Color.rgb(30, 30, 34))
        }

        // The video in its place and shape.
        val v = d.video
        val inAspect = frame?.let { it.width / it.height.toFloat() } ?: (9f / 16f)
        val canvasAspect = width / h.toFloat()
        val w0 = (if (inAspect > canvasAspect) width.toFloat() else h * inAspect) * v.scale
        val h0 = w0 / inAspect
        c.save()
        c.translate(v.x * width, v.y * h)
        c.rotate(v.rotation)
        val rect = RectF(-w0 / 2, -h0 / 2, w0 / 2, h0 / 2)
        val side = min(w0, h0)
        val path = Path().apply {
            when (v.mask) {
                VideoMask.NONE -> addRect(rect, Path.Direction.CW)
                VideoMask.ROUNDED -> addRoundRect(rect, v.corner / 100f * side, v.corner / 100f * side, Path.Direction.CW)
                VideoMask.CIRCLE -> addCircle(0f, 0f, side / 2, Path.Direction.CW)
                VideoMask.HEART -> {
                    val s = side / 2
                    moveTo(0f, s * 0.95f)
                    cubicTo(-s * 1.3f, s * 0.1f, -s * 0.75f, -s * 1.05f, 0f, -s * 0.45f)
                    cubicTo(s * 0.75f, -s * 1.05f, s * 1.3f, s * 0.1f, 0f, s * 0.95f)
                    close()
                }
                VideoMask.ARCH -> {
                    addRect(RectF(rect.left, rect.top + w0 / 2, rect.right, rect.bottom), Path.Direction.CW)
                    addCircle(0f, rect.top + w0 / 2, w0 / 2, Path.Direction.CW)
                }
            }
        }
        val borderPx = v.border / 1000f * side * 2f
        if (borderPx > 0f) {
            c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = v.borderColor })
            c.save()
            val sx = (w0 - borderPx * 2) / w0
            val sy = (h0 - borderPx * 2) / h0
            c.scale(sx, sy)
        }
        c.clipPath(path)
        if (frame != null) {
            c.drawBitmap(frame, null, rect, Paint(Paint.FILTER_BITMAP_FLAG))
        } else {
            c.drawColor(Color.rgb(90, 90, 96))
        }
        if (borderPx > 0f) c.restore()
        c.restore()

        // Layers.
        d.layers.forEach { l ->
            val art = DesignArt.layer(context, l, cw)?.frames?.firstOrNull() ?: return@forEach
            c.save()
            c.translate(l.x * width, l.y * h)
            c.rotate(l.rotation)
            val s = k * l.scale
            c.scale(s, s)
            c.drawBitmap(art, -art.width / 2f, -art.height / 2f, Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = (l.opacity * 255).toInt() })
            c.restore()
        }
        return out
    }
}
