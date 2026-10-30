package com.dualframe.camera

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlin.math.ln

/** Brightness statistics of one frame. */
class ExposureStats(
    /** 64-bin luma histogram, normalized so the tallest bin is 1. */
    val histogram: FloatArray,
    /** Average brightness 0..1. */
    val mean: Float,
    /** Share of the picture that is blown out (>= 98%). */
    val clipped: Float,
) {
    /** How far from a well-exposed picture, in stops (+ = too bright). */
    fun offsetStops(target: Float): Float = (ln(mean.coerceAtLeast(0.01f) / target) / ln(2f))
}

/**
 * Reads the brightness channel of small preview-sized frames: feeds the histogram, the
 * exposure meter, and shutter/ISO priority. Runs on CameraX's analysis thread.
 */
class ExposureAnalyzer(private val onStats: (ExposureStats) -> Unit) : ImageAnalysis.Analyzer {

    private var lastAt = 0L

    override fun analyze(image: ImageProxy) {
        try {
            val now = System.nanoTime()
            if (now - lastAt < 120_000_000L) return // ~8 times a second is plenty
            lastAt = now

            val plane = image.planes[0]
            val buffer = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val w = image.width
            val h = image.height
            val bins = IntArray(BINS)
            var sum = 0L
            var count = 0
            var clipped = 0
            // Every 2nd pixel each way is accurate enough and four times cheaper.
            var y = 0
            while (y < h) {
                val row = y * rowStride
                var x = 0
                while (x < w) {
                    val v = buffer.get(row + x * pixelStride).toInt() and 0xFF
                    bins[v shr 2]++
                    sum += v
                    if (v >= 250) clipped++
                    count++
                    x += 2
                }
                y += 2
            }
            if (count == 0) return
            val max = bins.max().coerceAtLeast(1)
            onStats(
                ExposureStats(
                    histogram = FloatArray(BINS) { bins[it].toFloat() / max },
                    mean = sum.toFloat() / count / 255f,
                    clipped = clipped.toFloat() / count,
                )
            )
        } finally {
            image.close()
        }
    }

    private companion object {
        const val BINS = 64
    }
}
