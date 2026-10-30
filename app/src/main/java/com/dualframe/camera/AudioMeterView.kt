package com.dualframe.camera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.log10

/**
 * A dBFS level meter with peak hold, like a camera's audio meter: green to -12, amber to -6,
 * red above. The tick on the right shows the loudest moment of the last 1.5 s.
 */
class AudioMeterView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private var level = 0f
    private var peak = 0f
    private var peakAt = 0L
    private val density = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(46, 255, 255, 255) }
    private val green = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(120, 200, 140) }
    private val amber = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(216, 185, 138) }
    private val red = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 69, 58) }
    private val tick = Paint().apply { color = Color.WHITE }
    private val rect = RectF()

    /** [amplitude] 0..1 as reported by the recorder. */
    fun update(amplitude: Double) {
        level = position(amplitude)
        val now = SystemClock.elapsedRealtime()
        if (level >= peak || now - peakAt > 1500) {
            peak = level
            peakAt = now
        }
        invalidate()
    }

    /** -60 dBFS .. 0 dBFS -> 0..1 */
    private fun position(amplitude: Double): Float {
        if (amplitude <= 0.0) return 0f
        val db = 20 * log10(amplitude)
        return ((db + 60) / 60).toFloat().coerceIn(0f, 1f)
    }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        val w = width.toFloat()
        val r = h / 2
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, r, r, track)
        val amberStart = w * (48f / 60f) // -12 dBFS
        val redStart = w * (54f / 60f)   // -6 dBFS
        val x = w * level
        canvas.save()
        canvas.clipRect(0f, 0f, x, h)
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, r, r, green)
        canvas.drawRect(amberStart, 0f, w, h, amber)
        canvas.drawRect(redStart, 0f, w, h, red)
        canvas.restore()
        val px = (w * peak).coerceIn(1.5f * density, w - 1.5f * density)
        canvas.drawRect(px - density, 0f, px + density, h, tick)
    }
}
