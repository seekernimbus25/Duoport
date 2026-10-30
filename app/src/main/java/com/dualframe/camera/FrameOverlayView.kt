package com.dualframe.camera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

/**
 * Draws one frame per chosen video format on top of the camera preview and handles touch:
 * drag moves the [selected] frame, double-tap re-centers all of them, single tap focuses,
 * pinch zooms.
 *
 * In front + back mode it also marks where the second camera will sit in each video.
 *
 * The preview must use FIT_CENTER so the whole recorded picture is visible and
 * [contentRect] lines up with it.
 */
class FrameOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    interface Listener {
        fun onTapToFocus(x: Float, y: Float)
        fun onPinch(scaleFactor: Float)
        /** Long-press: lock exposure and focus on that point. */
        fun onLongPress(x: Float, y: Float)
        /** The frames moved or were laid out again (also fires after every draw-affecting change). */
        fun onFramesChanged()
    }

    var listener: Listener? = null

    /** width / height of the recorded picture in portrait. */
    var sourceAspect: Float = CameraFrame.FULL_4_3.portraitAspect
        set(value) {
            field = value
            placement = placement.clamped(value)
            changed()
        }

    var placement = FramePlacement()
        private set

    /** The video formats being made, drawn in this order. */
    var formats: List<OutputFormat> = listOf(OutputFormat.PORTRAIT_9_16, OutputFormat.LANDSCAPE_16_9)
        set(value) {
            field = value
            if (selected !in value) selected = value.firstOrNull()
            changed()
        }

    /** The frame that dragging moves. */
    var selected: OutputFormat? = OutputFormat.PORTRAIT_9_16
        set(value) {
            field = value
            changed()
        }

    /** False when the second camera is baked into the recording and the frames must stay centered. */
    var movable = true
        set(value) {
            field = value
            if (!value) resetPlacement()
        }

    /** While recording the frames are locked in place. */
    var locked = false
        set(value) {
            field = value
            invalidate()
        }

    var grid = false
        set(value) {
            field = value
            invalidate()
        }

    /** Small cross in the centre of the selected frame. */
    var centerMark = false
        set(value) {
            field = value
            invalidate()
        }

    /** 90% "safe area" of the selected frame, where text and faces won't be cropped by apps. */
    var safeArea = false
        set(value) {
            field = value
            invalidate()
        }

    /** Phone roll in degrees for the horizon level, or null to hide it. */
    var levelDegrees: Float? = null
        set(value) {
            field = value
            invalidate()
        }

    /** Where the second camera goes, or null when it isn't recorded separately. */
    var insetCorner: Corner? = null
        set(value) {
            field = value
            changed()
        }

    private val density = resources.displayMetrics.density
    private val corner = 14f * density

    private val dimPaint = Paint().apply { color = Color.argb(150, 0, 0, 0) }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val insetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = Color.WHITE
        pathEffect = DashPathEffect(floatArrayOf(6f * density, 4f * density), 0f)
    }
    private val insetFill = Paint().apply { color = Color.argb(90, 255, 255, 255) }
    private val gridPaint = Paint().apply {
        color = Color.argb(90, 255, 255, 255)
        strokeWidth = 1f * density
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 20f * density
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setShadowLayer(4f * density, 0f, 1f * density, Color.BLACK)
    }

    private val content = RectF()
    private val boxes = mutableMapOf<OutputFormat, RectF>()
    private val insets = mutableMapOf<OutputFormat, RectF>()

    /** Where the second camera sits inside [format]'s on-screen frame, in this view's pixels. */
    fun insetOnScreen(format: OutputFormat): RectF? {
        if (insetCorner == null || width == 0) return null
        layoutBoxes()
        return insets[format]?.let { RectF(it) }
    }

    fun resetPlacement() {
        placement = FramePlacement().clamped(sourceAspect)
        changed()
    }

    private fun changed() {
        invalidate()
        listener?.onFramesChanged()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        post { listener?.onFramesChanged() }
    }

    private fun layoutBoxes() {
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 0f || vh <= 0f) return
        if (vw / vh > sourceAspect) {
            val w = vh * sourceAspect
            content.set((vw - w) / 2f, 0f, (vw + w) / 2f, vh)
        } else {
            val h = vw / sourceAspect
            content.set(0f, (vh - h) / 2f, vw, (vh + h) / 2f)
        }
        boxes.keys.retainAll(formats.toSet())
        insets.clear()
        for (format in formats) {
            val box = boxes.getOrPut(format) { RectF() }
            placement.rect(format, sourceAspect).mapInto(content, box)
            insetCorner?.let { c ->
                val (w, h) = format.sizePx(OutputResolution.FHD)
                insets[format] = RectF().also { InsetGeometry.rect(w, h, c).mapInto(box, it) }
            }
        }
    }

    private fun NormRect.mapInto(frame: RectF, out: RectF) {
        out.set(
            frame.left + left * frame.width(),
            frame.top + top * frame.height(),
            frame.left + right * frame.width(),
            frame.top + bottom * frame.height(),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        layoutBoxes()

        // Darken whatever ends up in none of the videos.
        canvas.save()
        boxes.values.forEach { canvas.clipOutRect(it) }
        canvas.drawRect(content, dimPaint)
        canvas.restore()

        val active = selected
        if (grid && active != null) {
            val box = boxes[active]
            if (box != null) {
                for (i in 1..2) {
                    val x = box.left + box.width() * i / 3f
                    val y = box.top + box.height() * i / 3f
                    canvas.drawLine(x, box.top, x, box.bottom, gridPaint)
                    canvas.drawLine(box.left, y, box.right, y, gridPaint)
                }
            }
        }

        // Where the second camera will be in every video. The live preview covers one of them.
        insets.values.forEach {
            canvas.drawRect(it, insetFill)
            canvas.drawRect(it, insetPaint)
        }

        if (active != null) {
            val box = boxes[active]
            if (box != null) {
                if (safeArea) {
                    val dx = box.width() * 0.05f
                    val dy = box.height() * 0.05f
                    canvas.drawRect(box.left + dx, box.top + dy, box.right - dx, box.bottom - dy, guidePaint)
                }
                if (centerMark) {
                    val cx = box.centerX()
                    val cy = box.centerY()
                    val arm = 12f * density
                    canvas.drawLine(cx - arm, cy, cx + arm, cy, markPaint)
                    canvas.drawLine(cx, cy - arm, cx, cy + arm, markPaint)
                }
            }
        }

        levelDegrees?.let { roll -> drawLevel(canvas, roll) }

        val pad = 10f * density
        formats.forEachIndexed { index, format ->
            val box = boxes[format] ?: return@forEachIndexed
            val isActive = format == active
            framePaint.color = format.color
            framePaint.strokeWidth = (if (isActive) 3.5f else 1.5f) * density
            framePaint.alpha = if (isActive || active == null) 255 else 170
            val half = framePaint.strokeWidth / 2f
            canvas.drawRoundRect(
                box.left + half, box.top + half, box.right - half, box.bottom - half,
                corner, corner, framePaint,
            )

            // Labels alternate between corners so overlapping frames stay readable, and keep
            // clear of the corner the inset is in.
            val c = insetCorner
            val top = index % 2 == 0
            var left = index % 4 < 2
            if (c != null && c.isTop == top && c.isLeft == left) left = !left
            labelPaint.color = format.color
            labelPaint.textAlign = if (left) Paint.Align.LEFT else Paint.Align.RIGHT
            val x = if (left) box.left + pad else box.right - pad
            val y = if (top) box.top + pad + labelPaint.textSize * (1 + index / 4) else box.bottom - pad
            canvas.drawText(format.label, x, y, labelPaint)
        }
    }

    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = Color.argb(120, 255, 255, 255)
        pathEffect = DashPathEffect(floatArrayOf(4f * density, 4f * density), 0f)
    }
    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1.5f * density
        color = Color.argb(200, 255, 255, 255)
    }
    private val levelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
    }

    /**
     * A horizon line through the centre that tilts with the phone and turns champagne when
     * it is level (within half a degree), with fixed ticks to line it up against.
     */
    private fun drawLevel(canvas: Canvas, roll: Float) {
        val cx = content.centerX()
        val cy = content.centerY()
        val half = content.width() * 0.18f
        val levelled = kotlin.math.abs(roll) < 0.5f
        levelPaint.color = if (levelled) Color.rgb(216, 185, 138) else Color.argb(220, 255, 255, 255)
        canvas.drawLine(cx - half - 26 * density, cy, cx - half - 10 * density, cy, levelPaint)
        canvas.drawLine(cx + half + 10 * density, cy, cx + half + 26 * density, cy, levelPaint)
        canvas.save()
        canvas.rotate(-roll, cx, cy)
        canvas.drawLine(cx - half, cy, cx + half, cy, levelPaint)
        canvas.restore()
    }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            listener?.onTapToFocus(e.x, e.y)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (!scaling) listener?.onLongPress(e.x, e.y)
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (!locked && movable) resetPlacement()
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            val format = selected
            if (locked || !movable || scaling || format == null || content.width() <= 0f) return false
            placement = placement.moved(format, -distanceX / content.width(), -distanceY / content.height(), sourceAspect)
            changed()
            return true
        }
    })

    private var scaling = false

    private val scaleGestures = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            scaling = true
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            listener?.onPinch(detector.scaleFactor)
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            scaling = false
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleGestures.onTouchEvent(event)
        if (event.pointerCount == 1 && !scaling) gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
