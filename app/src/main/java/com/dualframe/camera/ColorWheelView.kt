package com.dualframe.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * A colour-balance wheel like the ones in DaVinci Resolve: drag the puck towards a colour to
 * push the picture that way (further out = stronger), double-tap to reset. Colours sit where
 * they sit on a vectorscope (red upper left, blue right, green lower left), so the wheel and the
 * vectorscope move the same way.
 */
class ColorWheelView(context: Context) : View(context) {
    /** Hue 0..359 (HSV degrees) and strength 0..100. */
    var hue = 0
        private set
    var amount = 0
        private set
    var onChange: (hue: Int, amount: Int) -> Unit = { _, _ -> }

    private val density = resources.displayMetrics.density
    private val disc = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f * density
    }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(70, 255, 255, 255)
        strokeWidth = 1f * density
    }
    private val puckRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = Color.WHITE
    }
    private val puckFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 255, 255, 255)
        strokeWidth = 1.5f * density
    }

    private var cx = 0f
    private var cy = 0f
    private var radius = 0f

    // Puck position, -1..1 of the usable radius.
    private var px = 0f
    private var py = 0f

    fun set(hue: Int, amount: Int) {
        this.hue = hue
        this.amount = amount
        val a = HUE_ANGLE[((hue % 360) + 360) % 360]
        val r = amount.coerceIn(0, 100) / 100f
        px = cos(a) * r
        py = -sin(a) * r
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, w)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        cx = w / 2f
        cy = h / 2f
        radius = min(w, h) / 2f - 6f * density
        // Hue around the wheel at its vectorscope angle (screen angles run clockwise from +x).
        val stops = (0 until 360 step 6).map { h2 -> ((-HUE_ANGLE[h2] / (2 * Math.PI)).toFloat() % 1f + 1f) % 1f to h2 }
            .sortedBy { it.first }
        val colours = stops.map { Color.HSVToColor(floatArrayOf(it.second.toFloat(), 1f, 1f)) }.toIntArray()
        val positions = stops.map { it.first }.toFloatArray()
        val closedColours = colours + colours.first()
        val closedPositions = positions + 1f
        ring.shader = SweepGradient(cx, cy, closedColours, closedPositions)
        fill.shader = SweepGradient(cx, cy, closedColours, closedPositions)
        fill.alpha = 120
        shade.shader = RadialGradient(cx, cy, radius, intArrayOf(Color.rgb(24, 24, 28), Color.argb(150, 24, 24, 28), Color.TRANSPARENT), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        disc.color = Color.rgb(18, 18, 22)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawCircle(cx, cy, radius, disc)
        canvas.drawCircle(cx, cy, radius, fill)
        canvas.drawCircle(cx, cy, radius, shade)
        canvas.drawCircle(cx, cy, radius, ring)
        // Crosshair and a ring at half strength.
        canvas.drawLine(cx - radius * 0.92f, cy, cx + radius * 0.92f, cy, grid)
        canvas.drawLine(cx, cy - radius * 0.92f, cx, cy + radius * 0.92f, grid)
        grid.style = Paint.Style.STROKE
        canvas.drawCircle(cx, cy, radius * 0.45f, grid)
        grid.style = Paint.Style.FILL
        val usable = radius * 0.86f
        val x = cx + px * usable
        val y = cy + py * usable
        if (amount > 0) canvas.drawLine(cx, cy, x, y, line)
        puckFill.color = if (amount > 0) Color.HSVToColor(floatArrayOf(hue.toFloat(), amount / 100f, 1f)) else Color.argb(220, 230, 230, 230)
        canvas.drawCircle(x, y, 7f * density, puckFill)
        canvas.drawCircle(x, y, 8.5f * density, puckRing)
    }

    private var lastX = 0f
    private var lastY = 0f

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            set(hue, 0)
            onChange(hue, 0)
            return true
        }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        gestures.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                lastX = e.x
                lastY = e.y
            }
            MotionEvent.ACTION_MOVE -> {
                // Relative and slowed down, like a trackball: precise even with a thumb.
                val usable = radius * 0.86f
                px += (e.x - lastX) / usable * 0.6f
                py += (e.y - lastY) / usable * 0.6f
                lastX = e.x
                lastY = e.y
                val r = hypot(px, py)
                if (r > 1f) {
                    px /= r
                    py /= r
                }
                val angle = atan2(-py, px)
                hue = nearestHue(angle)
                amount = (min(1f, hypot(px, py)) * 100f).toInt()
                onChange(hue, amount)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    companion object {
        /** Each hue's angle on a vectorscope (Rec.709 Cb right, Cr up), in radians. */
        val HUE_ANGLE: FloatArray = FloatArray(360) { h ->
            val c = Color.HSVToColor(floatArrayOf(h.toFloat(), 1f, 1f))
            val r = Color.red(c) / 255f
            val g = Color.green(c) / 255f
            val b = Color.blue(c) / 255f
            val y = 0.2126f * r + 0.7152f * g + 0.0722f * b
            atan2((r - y) / 1.5748f, (b - y) / 1.8556f)
        }

        fun nearestHue(angle: Float): Int {
            var best = 0
            var bestD = Float.MAX_VALUE
            for (h in 0 until 360) {
                var d = kotlin.math.abs(HUE_ANGLE[h] - angle)
                if (d > Math.PI) d = (2 * Math.PI - d).toFloat()
                if (d < bestD) {
                    bestD = d
                    best = h
                }
            }
            return best
        }
    }
}
