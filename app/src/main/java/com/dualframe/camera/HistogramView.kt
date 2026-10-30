package com.dualframe.camera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * The Pro scope: a histogram with a clipping warning, a waveform (brightness across the
 * picture, like a cinema monitor) or a vectorscope (colour and saturation, with a skin-tone line).
 */
class HistogramView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private var values = FloatArray(0)
    private var waveform: FloatArray? = null
    private var vectorscope: FloatArray? = null
    private val dot = Paint().apply { color = Color.WHITE }
    private val skinLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 216, 185, 138)
        strokeWidth = 1f * resources.displayMetrics.density
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(60, 255, 255, 255)
        strokeWidth = resources.displayMetrics.density
    }
    private var clipped = 0f
    private val density = resources.displayMetrics.density
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(115, 14, 14, 18) }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
        color = Color.argb(46, 255, 255, 255)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 244, 241, 234) }
    private val clip = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 69, 58) }
    private val guide = Paint().apply {
        color = Color.argb(40, 255, 255, 255)
        strokeWidth = density
    }
    private val rect = RectF()
    private val path = Path()

    fun update(stats: ExposureStats) {
        values = stats.histogram
        clipped = stats.clipped
        waveform = stats.waveform
        vectorscope = stats.vectorscope
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val r = 10f * density
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, r, r, bg)
        val pad = 6f * density
        val left = pad
        val right = width - pad
        val top = pad
        val bottom = height - pad
        val wave = waveform
        val scope = vectorscope
        if (wave != null) {
            drawWaveform(canvas, wave, left, top, right, bottom)
        } else if (scope != null) {
            drawVectorscope(canvas, scope)
        } else if (values.isNotEmpty()) {
            for (i in 1..3) {
                val x = left + (right - left) * i / 4f
                canvas.drawLine(x, top, x, bottom, guide)
            }
            path.reset()
            path.moveTo(left, bottom)
            val step = (right - left) / (values.size - 1)
            values.forEachIndexed { i, v ->
                // Square root lifts small bins so shadows and highlights stay readable.
                path.lineTo(left + i * step, bottom - kotlin.math.sqrt(v) * (bottom - top))
            }
            path.lineTo(right, bottom)
            path.close()
            canvas.drawPath(path, fill)
        }
        if (clipped > 0.005f && scope == null) canvas.drawRect(right - 3f * density, top, right, bottom, clip)
        canvas.drawRoundRect(rect, r, r, edge)
    }

    /** Each column's brightness spread, brighter where more of the picture sits. 0/50/100% guides. */
    private fun drawWaveform(canvas: Canvas, wave: FloatArray, left: Float, top: Float, right: Float, bottom: Float) {
        for (level in listOf(0f, 0.5f, 1f)) {
            val y = bottom - (bottom - top) * level
            canvas.drawLine(left, y, right, y, guide)
        }
        val cols = ExposureStats.WAVE_COLS
        val levels = ExposureStats.WAVE_LEVELS
        val cw = (right - left) / cols
        val lh = (bottom - top) / levels
        for (c in 0 until cols) {
            for (l in 0 until levels) {
                val v = wave[c * levels + l]
                if (v <= 0.01f) continue
                dot.alpha = (kotlin.math.sqrt(v) * 255).toInt().coerceIn(30, 255)
                dot.color = if (l >= levels - 2) Color.rgb(255, 69, 58) else Color.WHITE
                dot.alpha = (kotlin.math.sqrt(v) * 255).toInt().coerceIn(30, 255)
                val x = left + c * cw
                val y = bottom - (l + 1) * lh
                canvas.drawRect(x, y, x + cw, y + lh, dot)
            }
        }
    }

    /** Colour wheel in a square on the right: centre is grey, outward is saturation. */
    private fun drawVectorscope(canvas: Canvas, scope: FloatArray) {
        val size = ExposureStats.SCOPE_SIZE
        val pad = 4f * density
        val side = minOf(width, height) - 2 * pad
        val left = (width - side) / 2f
        val top = pad
        val cx = left + side / 2f
        val cy = top + side / 2f
        canvas.drawCircle(cx, cy, side * 0.45f, ring)
        canvas.drawCircle(cx, cy, side * 0.22f, ring)
        // Skin tones of every complexion fall near this line (about 123° from the Cb axis).
        val a = Math.toRadians(123.0)
        canvas.drawLine(cx, cy, cx + (kotlin.math.cos(a) * side * 0.47f).toFloat(), cy - (kotlin.math.sin(a) * side * 0.47f).toFloat(), skinLine)
        val cell = side / size
        for (row in 0 until size) {
            for (col in 0 until size) {
                val v = scope[row * size + col]
                if (v <= 0.002f) continue
                dot.color = Color.WHITE
                dot.alpha = (kotlin.math.sqrt(v) * 255).toInt().coerceIn(50, 255)
                val x = left + col * cell
                val y = top + row * cell
                canvas.drawRect(x, y, x + cell, y + cell, dot)
            }
        }
    }
}
