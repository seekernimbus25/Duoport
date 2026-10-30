package com.dualframe.camera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/** A small glass histogram with a clipping warning on the right edge. */
class HistogramView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private var values = FloatArray(0)
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
        for (i in 1..3) {
            val x = left + (right - left) * i / 4f
            canvas.drawLine(x, top, x, bottom, guide)
        }
        if (values.isNotEmpty()) {
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
        if (clipped > 0.005f) canvas.drawRect(right - 3f * density, top, right, bottom, clip)
        canvas.drawRoundRect(rect, r, r, edge)
    }
}
