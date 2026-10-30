package com.dualframe.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** What fills the canvas behind the video. */
enum class BackgroundKind(val label: String) { BLUR("Blurred video"), COLOR("Colour"), GRADIENT("Gradient"), IMAGE("Photo") }

/** The shape the video is shown in. */
enum class VideoMask(val label: String, val code: Float) {
    NONE("Full", 0f), ROUNDED("Rounded", 1f), CIRCLE("Circle", 2f), HEART("Heart", 3f), ARCH("Arch", 4f)
}

enum class LayerKind { TEXT, STICKER, IMAGE }

/** How a layer comes in (and goes out) during its time on screen. */
enum class LayerAnimation(val label: String) { NONE("None"), FADE("Fade"), POP("Pop"), SLIDE_UP("Slide up") }

/**
 * One thing placed on the canvas: text, a sticker or a photo. Position and size are live so
 * dragging on the preview moves it in the next frame. [x], [y] are 0..1 of the canvas,
 * [scale] multiplies the layer's natural size, [rotation] is degrees clockwise.
 */
class Layer(val id: Long, val kind: LayerKind) {
    var text = ""
    var font = "res:montserrat"
    var color = Color.WHITE
    var outline = true
    var imageUri: Uri? = null
    /** Photo layers: cut the person out of the photo (transparent background). */
    var cutout = false
    @Volatile var x = 0.5f
    @Volatile var y = 0.5f
    @Volatile var scale = 1f
    @Volatile var rotation = 0f
    @Volatile var opacity = 1f
    var startMs = 0L
    var endMs = Long.MAX_VALUE
    var animation = LayerAnimation.NONE
    /** The layer drawn at its natural size for the canvas, ready to overlay. */
    @Volatile var bitmap: Bitmap? = null
}

/** Where and how the video sits on the canvas; read live by [DesignEffect]. */
class VideoPlacement {
    @Volatile var x = 0.5f
    @Volatile var y = 0.5f
    /** 1 = as big as fits the canvas. */
    @Volatile var scale = 1f
    @Volatile var rotation = 0f
    @Volatile var mask = VideoMask.NONE
    /** Rounded corners, % of the shorter side. */
    @Volatile var corner = 8
    /** Border width, % of the shorter side x10 (0..30). */
    @Volatile var border = 0
    @Volatile var borderColor = Color.WHITE
    /** Show only the person: the rest of the video is cut away to reveal the background. */
    @Volatile var cutout = false
}

/** An intro or outro card: a still with a title and subtitle on a gradient or photo. */
data class Card(
    val title: String = "",
    val subtitle: String = "",
    val color1: Int = 0xFF141418.toInt(),
    val color2: Int = 0xFF3A2C1C.toInt(),
    val textColor: Int = Color.WHITE,
    val font: String = "res:montserrat",
    val durationMs: Long = 2500,
    val imageUri: Uri? = null,
)

/** A whole design. The editor changes it; the effect and overlays read it. */
class Design {
    @Volatile var canvas = OutputFormat.PORTRAIT_9_16
    @Volatile var background = BackgroundKind.BLUR
    var bgColor = 0xFF101014.toInt()
    var bgColor2 = 0xFF3A2C1C.toInt()
    var bgImage: Uri? = null
    /** The colour / gradient / photo background, drawn at canvas size; null for the blurred video. */
    @Volatile var backgroundBitmap: Bitmap? = null
    val video = VideoPlacement()
    val layers = mutableListOf<Layer>()
    var intro: Card? = null
    var outro: Card? = null

    val canvasSize: Pair<Int, Int> get() = canvas.sizePx(OutputResolution.FHD)
}

/** Drawing the pieces of a design into bitmaps. */
object DesignArt {

    fun decode(context: Context, uri: Uri, maxSide: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }.getOrNull()

    /** Colour, gradient or photo (filling the canvas), or null for the blurred-video background. */
    fun background(context: Context, design: Design): Bitmap? {
        val (w, h) = design.canvasSize
        // Half size is plenty for a background and keeps memory down.
        val bw = w / 2
        val bh = h / 2
        return when (design.background) {
            BackgroundKind.BLUR -> null
            BackgroundKind.COLOR -> Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888).apply { eraseColor(design.bgColor) }
            BackgroundKind.GRADIENT -> Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888).also { b ->
                Canvas(b).drawRect(0f, 0f, bw.toFloat(), bh.toFloat(), Paint().apply {
                    shader = LinearGradient(0f, 0f, bw.toFloat(), bh.toFloat(), design.bgColor, design.bgColor2, Shader.TileMode.CLAMP)
                })
            }
            BackgroundKind.IMAGE -> design.bgImage?.let { decode(context, it, max(bw, bh)) }?.let { cover(it, bw, bh) }
        }
    }

    /** [src] scaled and centre-cropped to fill [w] x [h]. */
    fun cover(src: Bitmap, w: Int, h: Int): Bitmap {
        val scale = max(w / src.width.toFloat(), h / src.height.toFloat())
        val sw = (w / scale).roundToInt().coerceIn(1, src.width)
        val sh = (h / scale).roundToInt().coerceIn(1, src.height)
        val sx = (src.width - sw) / 2
        val sy = (src.height - sh) / 2
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, Rect(sx, sy, sx + sw, sy + sh), Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    /** A layer at its natural size on a canvas [canvasW] wide. */
    fun layer(context: Context, layer: Layer, canvasW: Int): Bitmap? = when (layer.kind) {
        LayerKind.TEXT -> text(context, layer, canvasW * 0.075f)
        LayerKind.STICKER -> text(context, layer, canvasW * 0.2f)
        LayerKind.IMAGE -> layer.imageUri?.let { decode(context, it, (canvasW * 0.6f).toInt()) }?.let { img ->
            val fitted = fitInside(img, (canvasW * 0.5f).toInt())
            if (layer.cutout) cutOutPerson(fitted) ?: fitted else fitted
        }
    }

    private fun fitInside(src: Bitmap, maxSide: Int): Bitmap {
        val s = min(1f, maxSide / max(src.width, src.height).toFloat())
        return if (s >= 1f) src else Bitmap.createScaledBitmap(src, (src.width * s).toInt().coerceAtLeast(1), (src.height * s).toInt().coerceAtLeast(1), true)
    }

    private fun text(context: Context, layer: Layer, size: Float): Bitmap? {
        val content = layer.text.ifEmpty { return null }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = CaptionFonts.typeface(context, layer.font)
            CaptionFonts.variation(layer.font)?.let { fontVariationSettings = it }
            textSize = size
        }
        val lines = content.split('\n')
        val widths = lines.map { paint.measureText(it) }
        val pad = size * 0.3f
        val lineH = size * 1.25f
        val w = ((widths.maxOrNull() ?: 0f) + pad * 2).toInt().coerceAtLeast(2)
        val h = (lineH * lines.size + pad * 2).toInt().coerceAtLeast(2)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        lines.forEachIndexed { i, line ->
            val x = (w - widths[i]) / 2f
            val y = pad + lineH * i + size
            if (layer.outline && layer.kind == LayerKind.TEXT) {
                paint.style = Paint.Style.STROKE
                paint.strokeJoin = Paint.Join.ROUND
                paint.strokeWidth = size * 0.1f
                paint.color = Color.BLACK
                c.drawText(line, x, y, paint)
            }
            paint.style = Paint.Style.FILL
            paint.color = layer.color
            paint.setShadowLayer(size * 0.08f, 0f, size * 0.04f, Color.argb(140, 0, 0, 0))
            c.drawText(line, x, y, paint)
            paint.clearShadowLayer()
        }
        return bmp
    }

    /** The person in [src] on a transparent background (on-device segmentation). */
    fun cutOutPerson(src: Bitmap): Bitmap? = runCatching {
        val segmenter = Segmentation.getClient(
            SelfieSegmenterOptions.Builder().setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE).build()
        )
        val mask = try {
            Tasks.await(segmenter.process(InputImage.fromBitmap(src, 0)))
        } finally {
            segmenter.close()
        }
        val mw = mask.width
        val mh = mask.height
        val values = FloatArray(mw * mh)
        mask.buffer.rewind()
        mask.buffer.order(ByteOrder.nativeOrder()).asFloatBuffer().get(values)
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val row = IntArray(out.width)
        for (y in 0 until out.height) {
            out.getPixels(row, 0, out.width, 0, y, out.width, 1)
            val my = (y * mh / out.height).coerceIn(0, mh - 1)
            for (x in 0 until out.width) {
                val mx = (x * mw / out.width).coerceIn(0, mw - 1)
                val a = ((values[my * mw + mx] - 0.35f) / 0.4f).coerceIn(0f, 1f)
                row[x] = (row[x] and 0x00FFFFFF) or ((Color.alpha(row[x]) * a).toInt() shl 24)
            }
            out.setPixels(row, 0, out.width, 0, y, out.width, 1)
        }
        out
    }.getOrNull()

    /** An intro / outro card at canvas size. */
    fun card(context: Context, card: Card, w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val photo = card.imageUri?.let { decode(context, it, max(w, h)) }
        if (photo != null) {
            c.drawBitmap(cover(photo, w, h), 0f, 0f, null)
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { color = Color.argb(110, 0, 0, 0) })
        } else {
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply {
                shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), card.color1, card.color2, Shader.TileMode.CLAMP)
            })
        }
        val short = min(w, h).toFloat()
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = CaptionFonts.typeface(context, card.font)
            CaptionFonts.variation(card.font)?.let { fontVariationSettings = it }
            textSize = short * 0.09f
            color = card.textColor
            textAlign = Paint.Align.CENTER
            setShadowLayer(short * 0.01f, 0f, short * 0.004f, Color.argb(120, 0, 0, 0))
        }
        val sub = Paint(title).apply {
            textSize = short * 0.045f
            alpha = 210
        }
        // Wrap the title to the central 80%.
        val lines = wrap(card.title, title, w * 0.8f)
        val total = lines.size * title.textSize * 1.15f + if (card.subtitle.isNotEmpty()) sub.textSize * 1.8f else 0f
        var y = h / 2f - total / 2f + title.textSize
        lines.forEach {
            c.drawText(it, w / 2f, y, title)
            y += title.textSize * 1.15f
        }
        if (card.subtitle.isNotEmpty()) c.drawText(card.subtitle, w / 2f, y + sub.textSize * 0.6f, sub)
        // A thin champagne rule under the title, the app's signature.
        val rule = Paint().apply { color = Color.argb(200, 216, 185, 138) }
        c.drawRect(RectF(w / 2f - short * 0.06f, y - title.textSize * 0.55f, w / 2f + short * 0.06f, y - title.textSize * 0.5f), rule)
        return bmp
    }

    private fun wrap(text: String, paint: Paint, maxWidth: Float): List<String> {
        val out = mutableListOf<String>()
        text.split('\n').forEach { para ->
            var line = ""
            para.split(' ').forEach { word ->
                val next = if (line.isEmpty()) word else "$line $word"
                if (paint.measureText(next) > maxWidth && line.isNotEmpty()) {
                    out += line
                    line = word
                } else {
                    line = next
                }
            }
            out += line
        }
        return out.filter { it.isNotEmpty() }.ifEmpty { listOf(" ") }
    }
}
