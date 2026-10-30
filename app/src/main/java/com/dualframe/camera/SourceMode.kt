package com.dualframe.camera

import androidx.camera.core.AspectRatio

/**
 * What the camera actually records. Both outputs are cut from this one recording.
 *
 * - [WIDE] records 4:3. In portrait that is a 3:4 picture, so the 16:9 cut gets the
 *   full sensor width (a properly wide YouTube shot) and the 9:16 cut gets the full height.
 * - [SHARP] records 16:9 at up to 4K. The 9:16 video is the whole picture at the highest
 *   detail, but the 16:9 cut is a tighter, zoomed-in band from the middle.
 */
enum class SourceMode(
    @AspectRatio.Ratio val cameraRatio: Int,
    /** width / height of the recording as seen while holding the phone upright. */
    val portraitAspect: Float,
    val label: String,
) {
    WIDE(AspectRatio.RATIO_4_3, 3f / 4f, "Wide 4:3"),
    SHARP(AspectRatio.RATIO_16_9, 9f / 16f, "Sharp 16:9");

    fun next(): SourceMode = entries[(ordinal + 1) % entries.size]
}
