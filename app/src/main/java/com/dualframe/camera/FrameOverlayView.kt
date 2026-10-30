package com.dualframe.camera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs

/**
 * Draws the 9:16 and 16:9 frames on top of the camera preview and handles touch:
 * horizontal drag moves the 9:16 frame, vertical drag moves the 16:9 frame,
 * double-tap re-centers both, single tap focuses, pinch zooms.
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
        fun onPlacementChanged(placement: FramePlacement)
    }

    var listener: Listener? = null

    /** width / height of the recorded picture in portrait. */
    var sourceAspect: Float = SourceMode.WIDE.portraitAspect
        set(value) {
            field = value
            placement = placement.clamped(value)
            invalidate()
        }

    var placement = FramePlacement()
        private set

    /** In front + back mode the frames stay centered so the camera inset lands in both videos. */
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

    private val density = resources.displayMetrics.density
    private val corner = 14f * density

    private val dimPaint = Paint().apply { color = Color.argb(150, 0, 0, 0) }
    private val portraitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        color = Color.rgb(255, 196, 46)
    }
    private val landscapePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        color = Color.WHITE
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 20f * density
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setShadowLayer(4f * density, 0f, 1f * density, Color.BLACK)
    }

    private val content = RectF()
    private val portraitBox = RectF()
    private val landscapeBox = RectF()

    fun resetPlacement() {
        placement = FramePlacement().clamped(sourceAspect)
        listener?.onPlacementChanged(placement)
        invalidate()
    }

    private fun updateContentRect() {
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
    }

    private fun NormRect.toView(out: RectF) {
        out.set(
            content.left + left * content.width(),
            content.top + top * content.height(),
            content.left + right * content.width(),
            content.top + bottom * content.height(),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        updateContentRect()
        placement.portraitRect(sourceAspect).toView(portraitBox)
        placement.landscapeRect(sourceAspect).toView(landscapeBox)

        // Darken whatever ends up in neither video.
        canvas.save()
        canvas.clipOutRect(portraitBox)
        canvas.clipOutRect(landscapeBox)
        canvas.drawRect(content, dimPaint)
        canvas.restore()

        val inset = portraitPaint.strokeWidth / 2f
        canvas.drawRoundRect(
            portraitBox.left + inset, portraitBox.top + inset,
            portraitBox.right - inset, portraitBox.bottom - inset,
            corner, corner, portraitPaint,
        )
        canvas.drawRoundRect(
            landscapeBox.left + inset, landscapeBox.top + inset,
            landscapeBox.right - inset, landscapeBox.bottom - inset,
            corner, corner, landscapePaint,
        )

        val pad = 12f * density
        labelPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("9:16", portraitBox.left + pad, portraitBox.top + pad + labelPaint.textSize, labelPaint)
        labelPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("16:9", landscapeBox.right - pad, landscapeBox.bottom - pad, labelPaint)
    }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            listener?.onTapToFocus(e.x, e.y)
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (!locked && movable) resetPlacement()
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (locked || !movable || scaling || content.width() <= 0f) return false
            val dx = -distanceX / content.width()
            val dy = -distanceY / content.height()
            placement = if (abs(dx) >= abs(dy)) {
                placement.copy(portraitCenterX = placement.portraitCenterX + dx)
            } else {
                placement.copy(landscapeCenterY = placement.landscapeCenterY + dy)
            }.clamped(sourceAspect)
            listener?.onPlacementChanged(placement)
            invalidate()
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
