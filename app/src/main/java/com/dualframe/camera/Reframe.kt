package com.dualframe.camera

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.MatrixTransformation
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

/** Where the subject (a face) is, 0..1 in the picture as recorded (upright), at [timeUs] into the take. */
data class SubjectSample(val timeUs: Long, val x: Float, val y: Float)

/**
 * Finds the main face in the small analysis frames. The largest face wins, so the person
 * nearest the camera is the one followed. Runs at whatever rate the detector keeps up with.
 */
class SubjectTracker {
    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setMinFaceSize(0.08f)
            .build()
    )

    /**
     * Looks for a face in [proxy]. [onDone] gets its centre (0..1, upright) or null, after which
     * the caller closes the frame.
     */
    @OptIn(ExperimentalGetImage::class)
    fun track(proxy: ImageProxy, onDone: (x: Float, y: Float, found: Boolean) -> Unit) {
        val media = proxy.image
        if (media == null) {
            onDone(0f, 0f, false)
            return
        }
        val rotation = proxy.imageInfo.rotationDegrees
        val w = (if (rotation % 180 == 0) proxy.width else proxy.height).toFloat()
        val h = (if (rotation % 180 == 0) proxy.height else proxy.width).toFloat()
        detect(InputImage.fromMediaImage(media, rotation), w, h, onDone)
    }

    /** Same, on an upright picture (a frame grabbed from the preview). */
    fun track(bitmap: Bitmap, onDone: (x: Float, y: Float, found: Boolean) -> Unit) =
        detect(InputImage.fromBitmap(bitmap, 0), bitmap.width.toFloat(), bitmap.height.toFloat(), onDone)

    private fun detect(image: InputImage, w: Float, h: Float, onDone: (x: Float, y: Float, found: Boolean) -> Unit) {
        detector.process(image)
            .addOnSuccessListener { faces ->
                val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                if (face == null) {
                    onDone(0f, 0f, false)
                } else {
                    onDone(face.boundingBox.exactCenterX() / w, face.boundingBox.exactCenterY() / h, true)
                }
            }
            .addOnFailureListener { onDone(0f, 0f, false) }
    }

    fun close() = detector.close()
}

/** A frame centre that changes over time: the path a virtual camera operator follows. */
class CropPath(private val timesUs: LongArray, private val xs: FloatArray, private val ys: FloatArray) {
    fun at(timeUs: Long): Pair<Float, Float> {
        if (timesUs.size == 1 || timeUs <= timesUs[0]) return xs[0] to ys[0]
        val last = timesUs.size - 1
        if (timeUs >= timesUs[last]) return xs[last] to ys[last]
        var lo = 0
        var hi = last
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (timesUs[mid] <= timeUs) lo = mid else hi = mid
        }
        val t = (timeUs - timesUs[lo]).toFloat() / (timesUs[hi] - timesUs[lo]).coerceAtLeast(1)
        return (xs[lo] + (xs[hi] - xs[lo]) * t) to (ys[lo] + (ys[hi] - ys[lo]) * t)
    }
}

object Reframe {
    /** Where the face sits from the top of each frame: a little above the middle, like a framed shot. */
    private const val EYE_LINE = 0.40f

    /** Movement smaller than this (fraction of the picture) is ignored, so the frame doesn't drift. */
    const val DEAD_ZONE = 0.035f

    /** The centre a frame of [format] needs so the face at ([x], [y]) is nicely placed. */
    fun target(format: OutputFormat, aspect: Float, x: Float, y: Float): Pair<Float, Float> {
        val size = CropMath.fit(aspect, format.aspect, 0.5f, 0.5f)
        val fitted = CropMath.fit(aspect, format.aspect, x, y + size.height * (0.5f - EYE_LINE))
        return fitted.centerX to fitted.centerY
    }

    /**
     * Turns the face positions recorded during a take into a smooth path for one format:
     * hold still while the face moves a little, glide when it moves more, and smooth both
     * forwards and backwards so the frame starts moving as the person does, not after.
     */
    fun path(samples: List<SubjectSample>, format: OutputFormat, aspect: Float, start: NormRect): CropPath {
        if (samples.isEmpty()) return CropPath(longArrayOf(0), floatArrayOf(start.centerX), floatArrayOf(start.centerY))
        val sorted = samples.sortedBy { it.timeUs }
        val n = sorted.size
        val t = LongArray(n) { sorted[it].timeUs }
        val x = FloatArray(n)
        val y = FloatArray(n)
        // Dead zone: only take a new target when the face has really moved.
        var cx = start.centerX
        var cy = start.centerY
        for (i in 0 until n) {
            val (tx, ty) = target(format, aspect, sorted[i].x, sorted[i].y)
            if (i == 0 || abs(tx - cx) > DEAD_ZONE) cx = tx
            if (i == 0 || abs(ty - cy) > DEAD_ZONE) cy = ty
            x[i] = cx
            y[i] = cy
        }
        smooth(t, x)
        smooth(t, y)
        return CropPath(t, x, y)
    }

    /** Zero-lag smoothing: exponential smoothing run forwards, then backwards (time constant ~0.6 s). */
    private fun smooth(t: LongArray, v: FloatArray) {
        val tau = 600_000f
        for (i in 1 until v.size) {
            val a = 1f - exp(-(t[i] - t[i - 1]) / tau)
            v[i] = v[i - 1] + (v[i] - v[i - 1]) * a
        }
        for (i in v.size - 2 downTo 0) {
            val a = 1f - exp(-(t[i + 1] - t[i]) / tau)
            v[i] = v[i + 1] + (v[i] - v[i + 1]) * a
        }
    }
}

/**
 * A crop of fixed size ([width] x [height], 0..1 of the picture) whose centre follows [path].
 * [speed] maps the output timeline back to the recording when time-lapse / slow motion ran first.
 */
@OptIn(UnstableApi::class)
class MovingCrop(
    private val width: Float,
    private val height: Float,
    private val path: CropPath,
    private val speed: Float,
) : MatrixTransformation {

    override fun configure(inputWidth: Int, inputHeight: Int): Size =
        Size(
            ((inputWidth * width).roundToInt() and 1.inv()).coerceAtLeast(2),
            ((inputHeight * height).roundToInt() and 1.inv()).coerceAtLeast(2),
        )

    override fun getMatrix(presentationTimeUs: Long): Matrix {
        val (cx, cy) = path.at((presentationTimeUs * speed).toLong())
        // Same maths as Media3's Crop, in normalized device coordinates (-1..1, +y up).
        val matrix = Matrix()
        matrix.postTranslate(-(cx * 2f - 1f), -(1f - cy * 2f))
        matrix.postScale(1f / width, 1f / height)
        return matrix
    }
}
