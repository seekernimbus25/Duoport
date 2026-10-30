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
    /**
     * Waveform: for each of [WAVE_COLS] columns across the picture (as displayed), how much of
     * that column sits at each of [WAVE_LEVELS] brightness levels (bottom = black). 0..1.
     */
    val waveform: FloatArray? = null,
    /** Vectorscope: [SCOPE_SIZE]² counts of colour (Cb across, Cr up), 0..1. */
    val vectorscope: FloatArray? = null,
) {
    companion object {
        const val WAVE_COLS = 96
        const val WAVE_LEVELS = 64
        const val SCOPE_SIZE = 64
    }

    /** How far from a well-exposed picture, in stops (+ = too bright). */
    fun offsetStops(target: Float): Float = (ln(mean.coerceAtLeast(0.01f) / target) / ln(2f))
}

/**
 * Reads the brightness channel of small preview-sized frames: feeds the histogram, the
 * exposure meter, and shutter/ISO priority. Runs on CameraX's analysis thread.
 */
class ExposureAnalyzer(private val onStats: (ExposureStats) -> Unit) : ImageAnalysis.Analyzer {

    private var lastAt = 0L

    /** Which scope to compute as well: 0 = histogram only, 1 = waveform, 2 = vectorscope. */
    @Volatile var scope = 0

    override fun analyze(image: ImageProxy) {
        try {
            measure(image)
        } finally {
            image.close()
        }
    }

    /** Reads [image] without closing it, so another analysis can use the same frame. */
    fun measure(image: ImageProxy) {
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
                waveform = if (scope == 1) waveform(image) else null,
                vectorscope = if (scope == 2) vectorscope(image) else null,
            )
        )
    }

    /** Brightness per column, with columns taken across the picture the way it's displayed. */
    private fun waveform(image: ImageProxy): FloatArray {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val w = image.width
        val h = image.height
        val rotation = image.imageInfo.rotationDegrees
        val displayWidth = if (rotation % 180 == 0) w else h
        val cols = ExposureStats.WAVE_COLS
        val levels = ExposureStats.WAVE_LEVELS
        val counts = IntArray(cols * levels)
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val v = buffer.get(y * plane.rowStride + x * plane.pixelStride).toInt() and 0xFF
                val displayX = when (rotation) {
                    90 -> h - 1 - y
                    180 -> w - 1 - x
                    270 -> y
                    else -> x
                }
                val col = (displayX * cols / displayWidth).coerceIn(0, cols - 1)
                counts[col * levels + (v * levels shr 8)]++
                x += 2
            }
            y += 2
        }
        val max = counts.max().coerceAtLeast(1).toFloat()
        return FloatArray(counts.size) { counts[it] / max }
    }

    /** Colour of every 2nd chroma sample, binned: centre = no colour, distance = saturation. */
    private fun vectorscope(image: ImageProxy): FloatArray {
        val u = image.planes[1]
        val v = image.planes[2]
        val size = ExposureStats.SCOPE_SIZE
        val counts = IntArray(size * size)
        val cw = image.width / 2
        val ch = image.height / 2
        var y = 0
        while (y < ch) {
            var x = 0
            while (x < cw) {
                val cb = u.buffer.get(y * u.rowStride + x * u.pixelStride).toInt() and 0xFF
                val cr = v.buffer.get(y * v.rowStride + x * v.pixelStride).toInt() and 0xFF
                val col = cb * size shr 8
                val row = (255 - cr) * size shr 8 // Cr up
                counts[row * size + col]++
                x += 2
            }
            y += 2
        }
        val max = counts.max().coerceAtLeast(1).toFloat()
        return FloatArray(counts.size) { counts[it] / max }
    }

    private companion object {
        const val BINS = 64
    }
}
