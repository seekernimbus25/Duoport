package com.dualframe.camera

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Crop

/** A rectangle in normalized frame coordinates: 0..1, origin top-left, as the user sees it. */
data class NormRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    val centerX get() = (left + right) / 2f
    val centerY get() = (top + bottom) / 2f

    /** Media3 effects use OpenGL normalized device coordinates: -1..1, +y is up. */
    @OptIn(UnstableApi::class)
    fun toMedia3Crop(): Crop = Crop(
        /* left = */ left * 2f - 1f,
        /* right = */ right * 2f - 1f,
        /* bottom = */ 1f - bottom * 2f,
        /* top = */ 1f - top * 2f,
    )
}

object CropMath {
    /**
     * The largest rectangle of [targetAspect] (width / height) that fits inside a frame of
     * [sourceAspect], centered on ([centerX], [centerY]) but clamped so it never leaves the frame.
     */
    fun fit(sourceAspect: Float, targetAspect: Float, centerX: Float, centerY: Float): NormRect {
        val w: Float
        val h: Float
        if (targetAspect <= sourceAspect) {
            h = 1f
            w = targetAspect / sourceAspect
        } else {
            w = 1f
            h = sourceAspect / targetAspect
        }
        val cx = centerX.coerceIn(w / 2f, 1f - w / 2f)
        val cy = centerY.coerceIn(h / 2f, 1f - h / 2f)
        return NormRect(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
    }

    /** The area every one of [formats] covers when they are all centered. */
    fun sharedArea(sourceAspect: Float, formats: Collection<OutputFormat>): NormRect =
        formats.map { fit(sourceAspect, it.aspect, 0.5f, 0.5f) }.fold(NormRect(0f, 0f, 1f, 1f)) { a, b ->
            NormRect(maxOf(a.left, b.left), maxOf(a.top, b.top), minOf(a.right, b.right), minOf(a.bottom, b.bottom))
        }
}

/** Where the user has placed each format's frame inside the recorded picture (centers, 0..1). */
data class FramePlacement(val centers: Map<OutputFormat, Pair<Float, Float>> = emptyMap()) {

    fun rect(format: OutputFormat, sourceAspect: Float): NormRect {
        val (cx, cy) = centers[format] ?: (0.5f to 0.5f)
        return CropMath.fit(sourceAspect, format.aspect, cx, cy)
    }

    /** Moves one frame by a fraction of the picture, stopping at the edges. */
    fun moved(format: OutputFormat, dx: Float, dy: Float, sourceAspect: Float): FramePlacement {
        val r = rect(format, sourceAspect)
        val moved = CropMath.fit(sourceAspect, format.aspect, r.centerX + dx, r.centerY + dy)
        return FramePlacement(centers + (format to (moved.centerX to moved.centerY)))
    }

    /** Re-centers every frame on its clamped position so dragging past an edge doesn't "stick". */
    fun clamped(sourceAspect: Float) = FramePlacement(
        centers.mapValues { (format, _) -> rect(format, sourceAspect).let { it.centerX to it.centerY } }
    )
}

/**
 * Size and position of the second-camera inset inside a finished video. Shared by the on-screen
 * preview and the export so what you see while recording is where it ends up.
 */
object InsetGeometry {
    /** Inset width as a fraction of the video's shorter side. */
    private const val WIDTH_OF_SHORT_SIDE = 0.30f
    private const val MARGIN_OF_SHORT_SIDE = 0.04f
    /** The second camera records a 3:4 (portrait) picture. */
    const val INSET_ASPECT = 3f / 4f

    /** Inset size in pixels for a video of [width] x [height], rounded to even numbers for encoders. */
    fun sizePx(width: Int, height: Int): Pair<Int, Int> {
        val w = (minOf(width, height) * WIDTH_OF_SHORT_SIDE).toInt() and 1.inv()
        val h = (w / INSET_ASPECT).toInt() and 1.inv()
        return w to h
    }

    fun marginPx(width: Int, height: Int): Float = minOf(width, height) * MARGIN_OF_SHORT_SIDE

    /** The inset's rectangle inside a [width] x [height] video, normalized 0..1, top-left origin. */
    fun rect(width: Int, height: Int, corner: Corner): NormRect {
        val (iw, ih) = sizePx(width, height)
        val m = marginPx(width, height)
        val left = if (corner.isLeft) m else width - m - iw
        val top = if (corner.isTop) m else height - m - ih
        return NormRect(left / width, top / height, (left + iw) / width, (top + ih) / height)
    }
}

/** Where the inset goes when the phone can only give us both cameras already combined. */
data class PipComposition(val offsetX: Float, val offsetY: Float, val scale: Float)

object PipLayout {
    /**
     * Fallback for phones that can run both cameras only as one combined stream (CameraX
     * composition). The inset is baked into the recording, so it goes in the chosen corner of
     * the area the centered 9:16 and 16:9 frames share, which keeps it in the same corner
     * of both videos.
     *
     * CameraX composites in the sensor's buffer orientation and rotates afterwards, so the
     * on-screen position is mapped back through [rotationDegrees] (clockwise rotation that turns
     * the buffer upright) into OpenGL NDC (-1..1, +y up). The inset keeps the full picture's
     * aspect ratio so the second camera isn't stretched.
     */
    fun secondaryCamera(
        sourceAspect: Float,
        rotationDegrees: Int,
        corner: Corner,
        formats: Collection<OutputFormat>,
    ): PipComposition {
        val shared = CropMath.sharedArea(sourceAspect, formats)

        val size = minOf(shared.width, shared.height) * 0.7f
        val marginY = shared.height * 0.06f
        val marginX = marginY / sourceAspect // same distance in pixels as marginY
        val centerX = if (corner.isLeft) shared.left + marginX + size / 2f else shared.right - marginX - size / 2f
        val centerY = if (corner.isTop) shared.top + marginY + size / 2f else shared.bottom - marginY - size / 2f

        // Screen position (x right, y down, 0..1) -> buffer position before rotation.
        val (u, v) = when (((rotationDegrees % 360) + 360) % 360) {
            90 -> centerY to 1f - centerX
            180 -> 1f - centerX to 1f - centerY
            270 -> 1f - centerY to centerX
            else -> centerX to centerY
        }
        return PipComposition(offsetX = u * 2f - 1f, offsetY = 1f - v * 2f, scale = size)
    }
}
