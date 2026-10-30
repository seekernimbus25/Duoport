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
    const val PORTRAIT = 9f / 16f
    const val LANDSCAPE = 16f / 9f

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
}

/** Where the user has placed the two frames inside the recorded picture. */
data class FramePlacement(
    val portraitCenterX: Float = 0.5f,
    val portraitCenterY: Float = 0.5f,
    val landscapeCenterX: Float = 0.5f,
    val landscapeCenterY: Float = 0.5f,
) {
    fun portraitRect(sourceAspect: Float) =
        CropMath.fit(sourceAspect, CropMath.PORTRAIT, portraitCenterX, portraitCenterY)

    fun landscapeRect(sourceAspect: Float) =
        CropMath.fit(sourceAspect, CropMath.LANDSCAPE, landscapeCenterX, landscapeCenterY)

    /** Re-centers both frames on their clamped positions so dragging past an edge doesn't "stick". */
    fun clamped(sourceAspect: Float): FramePlacement {
        val p = portraitRect(sourceAspect)
        val l = landscapeRect(sourceAspect)
        return FramePlacement(p.centerX, p.centerY, l.centerX, l.centerY)
    }
}

/** Where the small front/back camera inset goes when both cameras record together. */
data class PipComposition(val offsetX: Float, val offsetY: Float, val scale: Float)

object PipLayout {
    /**
     * The inset must survive both cuts, so it sits in the bottom-right of the area that the
     * centered 9:16 frame and the centered 16:9 frame share.
     *
     * CameraX composites the two cameras in the sensor's buffer orientation and applies rotation
     * afterwards, so the on-screen position is mapped back through [rotationDegrees] (the clockwise
     * rotation that turns the buffer upright) and expressed in OpenGL NDC (-1..1, +y is up).
     * The inset keeps the full picture's aspect ratio so the second camera isn't stretched.
     */
    fun secondaryCamera(sourceAspect: Float, rotationDegrees: Int): PipComposition {
        val portrait = CropMath.fit(sourceAspect, CropMath.PORTRAIT, 0.5f, 0.5f)
        val landscape = CropMath.fit(sourceAspect, CropMath.LANDSCAPE, 0.5f, 0.5f)
        val shared = NormRect(
            maxOf(portrait.left, landscape.left),
            maxOf(portrait.top, landscape.top),
            minOf(portrait.right, landscape.right),
            minOf(portrait.bottom, landscape.bottom),
        )

        val size = minOf(shared.width, shared.height) * 0.7f
        val marginY = shared.height * 0.06f
        val marginX = marginY / sourceAspect // same distance in pixels as marginY
        val centerX = shared.right - marginX - size / 2f
        val centerY = shared.bottom - marginY - size / 2f

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
