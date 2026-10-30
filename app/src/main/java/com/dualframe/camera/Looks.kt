package com.dualframe.camera

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.hardware.camera2.params.TonemapCurve
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Contrast
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.RgbAdjustment
import kotlin.math.ln

/**
 * Colour looks.
 *
 * - NATURAL leaves the camera's own processing alone: what the phone's image pipeline produces
 *   for any app (auto exposure, white balance, noise reduction, sharpening, tone mapping).
 * - VIVID is applied while making the two videos, and previewed live with a matching colour
 *   filter, so what you see is close to what you get.
 * - FLAT happens in the camera itself: a log-style tone curve that keeps more shadow and
 *   highlight detail for grading later. Only on phones that allow custom tone curves.
 */
@OptIn(UnstableApi::class)
object Looks {

    private const val VIVID_SATURATION = 1.25f
    private const val VIVID_CONTRAST = 1.12f
    private const val VIVID_WARM_RED = 1.03f
    private const val VIVID_WARM_BLUE = 0.97f

    fun previewFilter(look: Look): ColorMatrixColorFilter? {
        if (look != Look.VIVID) return null
        val m = ColorMatrix().apply { setSaturation(VIVID_SATURATION) }
        val offset = (1f - VIVID_CONTRAST) * 0.5f * 255f
        m.postConcat(
            ColorMatrix(
                floatArrayOf(
                    VIVID_CONTRAST, 0f, 0f, 0f, offset,
                    0f, VIVID_CONTRAST, 0f, 0f, offset,
                    0f, 0f, VIVID_CONTRAST, 0f, offset,
                    0f, 0f, 0f, 1f, 0f,
                )
            )
        )
        m.postConcat(ColorMatrix().apply { setScale(VIVID_WARM_RED, 1f, VIVID_WARM_BLUE, 1f) })
        return ColorMatrixColorFilter(m)
    }

    fun exportEffects(look: Look): List<Effect> {
        if (look != Look.VIVID) return emptyList()
        // Media3's Contrast maps c to a slope of (1 + c) / (1 - c).
        val contrast = (VIVID_CONTRAST - 1f) / (VIVID_CONTRAST + 1f)
        return listOf(
            HslAdjustment.Builder().adjustSaturation((VIVID_SATURATION - 1f) * 100f).build(),
            Contrast(contrast),
            RgbAdjustment.Builder().setRedScale(VIVID_WARM_RED).setBlueScale(VIVID_WARM_BLUE).build(),
        )
    }

    /**
     * Log-style curve: lifted blacks, a gentle shoulder, mid-grey a touch above normal.
     * Camera2 curves map linear sensor values to output, so the curve also does the gamma.
     */
    val flatCurve: TonemapCurve by lazy {
        val points = 33
        val k = 20f
        val curve = FloatArray(points * 2)
        for (i in 0 until points) {
            val x = i / (points - 1f)
            curve[i * 2] = x
            curve[i * 2 + 1] = 0.04f + 0.92f * (ln(1f + k * x) / ln(1f + k))
        }
        TonemapCurve(curve, curve, curve)
    }
}
