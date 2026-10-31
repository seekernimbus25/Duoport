package com.dualframe.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** The scopes an editor shows next to the picture. */
enum class ScopeMode(val label: String) {
    WAVEFORM("Waveform"), PARADE("RGB parade"), VECTORSCOPE("Vectorscope"), HISTOGRAM("Histogram"), OFF("Off")
}

/**
 * Video scopes drawn the way DaVinci Resolve draws them: a luma waveform, an RGB parade, a
 * vectorscope with colour targets and the skin-tone line, and an RGB histogram, with 10-bit
 * style graticule numbers. Feed it frames with [analyse] (any thread); it redraws itself.
 */
class ScopeView(context: Context) : View(context) {
    @Volatile var mode = ScopeMode.WAVEFORM
        set(value) {
            field = value
            trace = null
            invalidate()
        }

    @Volatile private var trace: Bitmap? = null
    private val density = resources.displayMetrics.density
    private val bg = Paint().apply { color = Color.rgb(12, 12, 14) }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(70, 200, 200, 200)
        strokeWidth = 1f
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 220, 220, 220)
        textSize = 8.5f * density
    }
    private val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
    }
    private val skinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 230, 170, 120)
        strokeWidth = 1.2f * density
    }
    private val tracePaint = Paint(Paint.FILTER_BITMAP_FLAG)

    /** Builds the trace for one frame. Safe off the main thread. */
    fun analyse(frame: Bitmap) {
        val w = frame.width
        val h = frame.height
        if (w <= 0 || h <= 0) return
        val px = IntArray(w * h)
        frame.getPixels(px, 0, w, 0, 0, w, h)
        trace = when (mode) {
            ScopeMode.WAVEFORM -> waveform(px, w, h, parade = false)
            ScopeMode.PARADE -> waveform(px, w, h, parade = true)
            ScopeMode.VECTORSCOPE -> vectorscope(px)
            ScopeMode.HISTOGRAM -> histogram(px)
            ScopeMode.OFF -> null
        }
        postInvalidate()
    }

    // region Traces

    private fun glow(count: Int, scale: Float): Float = min(1f, sqrt(count / scale))

    private fun waveform(px: IntArray, w: Int, h: Int, parade: Boolean): Bitmap {
        val cols = if (parade) 3 * 128 else 256
        val rows = 128
        val counts = Array(if (parade) 3 else 1) { IntArray(cols * rows) }
        for (y in 0 until h) {
            for (x in 0 until w) {
                val c = px[y * w + x]
                val r = (c shr 16) and 255
                val g = (c shr 8) and 255
                val b = c and 255
                if (parade) {
                    val col = x * 128 / w
                    val values = intArrayOf(r, g, b)
                    for (ch in 0..2) {
                        val row = (255 - values[ch]) * (rows - 1) / 255
                        counts[ch][row * cols + ch * 128 + col]++
                    }
                } else {
                    val col = x * cols / w
                    val luma = (0.2126f * r + 0.7152f * g + 0.0722f * b).toInt()
                    val row = (255 - luma) * (rows - 1) / 255
                    counts[0][row * cols + col]++
                }
            }
        }
        // How many samples land in one cell when a column is evenly spread.
        val scale = (h.toFloat() / rows * (if (parade) 128f else 256f) / w * 6f).coerceAtLeast(1f)
        val out = IntArray(cols * rows)
        for (i in out.indices) {
            if (parade) {
                val ch = (i % cols) / 128
                val v = glow(counts[ch][i], scale)
                if (v > 0f) {
                    val a = (v * 255).toInt()
                    out[i] = when (ch) {
                        0 -> Color.argb(a, 255, 70, 70)
                        1 -> Color.argb(a, 80, 255, 110)
                        else -> Color.argb(a, 90, 140, 255)
                    }
                }
            } else {
                val v = glow(counts[0][i], scale)
                if (v > 0f) out[i] = Color.argb((v * 255).toInt(), 200, 255, 205)
            }
        }
        return Bitmap.createBitmap(out, cols, rows, Bitmap.Config.ARGB_8888)
    }

    private fun vectorscope(px: IntArray): Bitmap {
        val size = 256
        val counts = IntArray(size * size)
        val sumR = IntArray(size * size)
        val sumG = IntArray(size * size)
        val sumB = IntArray(size * size)
        val k = size / 2 * 0.92f / 0.5f
        for (c in px) {
            val r = (c shr 16) and 255
            val g = (c shr 8) and 255
            val b = c and 255
            val y = 0.2126f * r + 0.7152f * g + 0.0722f * b
            val cb = (b - y) / 1.8556f / 255f
            val cr = (r - y) / 1.5748f / 255f
            val x = (size / 2 + cb * k).toInt().coerceIn(0, size - 1)
            val yy = (size / 2 - cr * k).toInt().coerceIn(0, size - 1)
            val i = yy * size + x
            counts[i]++
            sumR[i] += r
            sumG[i] += g
            sumB[i] += b
        }
        val scale = (px.size / 4000f).coerceAtLeast(1f)
        val out = IntArray(size * size)
        for (i in out.indices) {
            val n = counts[i]
            if (n == 0) continue
            val v = glow(n, scale)
            // Each dot in the colour it came from, brightened so dark colours still show.
            val r = sumR[i] / n
            val g = sumG[i] / n
            val b = sumB[i] / n
            val m = maxOf(r, g, b, 1)
            val boost = 230f / m
            out[i] = Color.argb((v * 255).toInt(), (r * boost).toInt().coerceIn(0, 255), (g * boost).toInt().coerceIn(0, 255), (b * boost).toInt().coerceIn(0, 255))
        }
        return Bitmap.createBitmap(out, size, size, Bitmap.Config.ARGB_8888)
    }

    private fun histogram(px: IntArray): Bitmap {
        val bins = Array(3) { IntArray(256) }
        for (c in px) {
            bins[0][(c shr 16) and 255]++
            bins[1][(c shr 8) and 255]++
            bins[2][c and 255]++
        }
        val rows = 128
        val peak = bins.maxOf { b -> b.drop(2).dropLast(2).maxOrNull() ?: 1 }.coerceAtLeast(1)
        val out = IntArray(256 * rows)
        val colours = intArrayOf(Color.rgb(255, 60, 60), Color.rgb(60, 230, 90), Color.rgb(80, 130, 255))
        for (x in 0 until 256) {
            for (ch in 0..2) {
                val v = ln(1f + bins[ch][x]) / ln(1f + peak)
                val top = ((1f - v.coerceIn(0f, 1f)) * (rows - 1)).toInt()
                for (y in top until rows) {
                    val i = y * 256 + x
                    // Overlaps add up, like Resolve's (red + green = yellow, all three = white).
                    val prev = out[i]
                    val r = minOf(255, Color.red(prev) + Color.red(colours[ch]) * 3 / 5)
                    val g = minOf(255, Color.green(prev) + Color.green(colours[ch]) * 3 / 5)
                    val b = minOf(255, Color.blue(prev) + Color.blue(colours[ch]) * 3 / 5)
                    out[i] = Color.argb(200, r, g, b)
                }
            }
        }
        return Bitmap.createBitmap(out, 256, rows, Bitmap.Config.ARGB_8888)
    }

    // endregion

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, bg)
        when (mode) {
            ScopeMode.VECTORSCOPE -> drawVectorscope(canvas, w, h)
            ScopeMode.OFF -> Unit
            else -> drawWaveformLike(canvas, w, h)
        }
    }

    private fun drawWaveformLike(canvas: Canvas, w: Float, h: Float) {
        val left = 30f * density
        val top = 6f * density
        val area = RectF(left, top, w - 6f * density, h - 6f * density)
        // 10-bit graticule like Resolve: 0 … 1023.
        for (level in listOf(0, 128, 256, 384, 512, 640, 768, 896, 1023)) {
            val y = area.bottom - level / 1023f * area.height()
            gridPaint.alpha = if (level % 256 == 0 || level == 1023) 80 else 35
            canvas.drawLine(area.left, y, area.right, y, gridPaint)
            if (mode != ScopeMode.HISTOGRAM && (level % 256 == 0 || level == 1023)) {
                canvas.drawText("$level", 4f * density, y + labelPaint.textSize / 3f, labelPaint)
            }
        }
        if (mode == ScopeMode.PARADE) {
            for (i in 1..2) {
                val x = area.left + area.width() * i / 3f
                canvas.drawLine(x, area.top, x, area.bottom, gridPaint)
            }
        }
        trace?.let { canvas.drawBitmap(it, null, area, tracePaint) }
    }

    private fun drawVectorscope(canvas: Canvas, w: Float, h: Float) {
        val r = min(w, h) / 2f - 6f * density
        val cx = w / 2f
        val cy = h / 2f
        gridPaint.style = Paint.Style.STROKE
        gridPaint.alpha = 80
        canvas.drawCircle(cx, cy, r, gridPaint)
        gridPaint.alpha = 35
        canvas.drawCircle(cx, cy, r * 0.5f, gridPaint)
        canvas.drawLine(cx - r, cy, cx + r, cy, gridPaint)
        canvas.drawLine(cx, cy - r, cx, cy + r, gridPaint)
        gridPaint.style = Paint.Style.FILL
        trace?.let { canvas.drawBitmap(it, null, Rect((cx - r).toInt(), (cy - r).toInt(), (cx + r).toInt(), (cy + r).toInt()), tracePaint) }
        // Skin-tone line (the I line, about 123° from the Cb axis).
        val a = Math.toRadians(123.0)
        canvas.drawLine(cx, cy, cx + (cos(a) * r).toFloat(), cy - (sin(a) * r).toFloat(), skinPaint)
        // 75% colour targets.
        val k = r * 0.92f / 0.5f
        for ((name, rgb) in TARGETS) {
            val (rr, gg, bb) = rgb
            val y = 0.2126f * rr + 0.7152f * gg + 0.0722f * bb
            val tx = cx + (bb - y) / 1.8556f * k
            val ty = cy - (rr - y) / 1.5748f * k
            targetPaint.color = Color.argb(200, (rr * 255).toInt().coerceAtLeast(60), (gg * 255).toInt().coerceAtLeast(60), (bb * 255).toInt().coerceAtLeast(60))
            val s = 5f * density
            canvas.drawRect(tx - s, ty - s, tx + s, ty + s, targetPaint)
            canvas.drawText(name, tx + s + 2f * density, ty + labelPaint.textSize / 3f, labelPaint)
        }
    }

    private companion object {
        val TARGETS = listOf(
            "R" to Triple(0.75f, 0f, 0f), "Yl" to Triple(0.75f, 0.75f, 0f), "G" to Triple(0f, 0.75f, 0f),
            "Cy" to Triple(0f, 0.75f, 0.75f), "B" to Triple(0f, 0f, 0.75f), "Mg" to Triple(0.75f, 0f, 0.75f),
        )
    }
}
