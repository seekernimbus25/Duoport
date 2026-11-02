package com.dualframe.camera

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter
import java.io.Closeable
import kotlin.math.exp

/**
 * Who's the person in a picture, for the cutouts. MediaPipe's selfie multiclass model sorts
 * every pixel into background, hair, skin, clothes or "other" (headwear, glasses, things held);
 * everything but background is the person, so hair and accessories stay on. ML Kit's selfie
 * model, used before, only knew person/not-person and dropped hair edges and hats.
 *
 * The model sees a 256 px wide copy; [refine] (and the same step in DesignEffect's shader)
 * then snaps the mask's edge to the real edges in the full picture.
 */
class PersonMask(context: Context) : Closeable {
    /** One mask: [width] × [height] values, 0 = background, 1 = person, first row = top. */
    class Mask(val width: Int, val height: Int, val values: FloatArray)

    /**
     * All MediaPipe calls run on this one thread. On Media3's GL thread (which has its own EGL
     * context current) the GPU graph failed to start ("CalculatorGraph::Run() failed").
     */
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val segmenter: ImageSegmenter
    private val delegate: Delegate
    private var frames = 0
    private var totalMs = 0L

    init {
        // The phone's GPU runs the model ~10× faster; fall back to the CPU if it can't.
        var made: ImageSegmenter? = null
        var used = Delegate.GPU
        for (d in listOf(Delegate.GPU, Delegate.CPU)) {
            made = runCatching { onWorker { create(context, d) } }.onFailure { Log.w(TAG, "No $d segmenter", it) }.getOrNull()
            if (made != null) {
                used = d
                break
            }
        }
        segmenter = made ?: throw IllegalStateException("No segmenter")
        delegate = used
    }

    private fun <T> onWorker(block: () -> T): T =
        try {
            worker.submit<T> { block() }.get()
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e
        }

    private fun create(context: Context, d: Delegate): ImageSegmenter =
        ImageSegmenter.createFromOptions(
            context,
            ImageSegmenter.ImageSegmenterOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL).setDelegate(d).build())
                .setRunningMode(RunningMode.IMAGE)
                .setOutputConfidenceMasks(true)
                .setOutputCategoryMask(false)
                .build(),
        )

    /** The person in [small] (about [INPUT_WIDTH] wide, upright), largest connected shape only. */
    fun segment(small: Bitmap): Mask {
        val start = SystemClock.elapsedRealtime()
        val result = onWorker { segmenter.segment(BitmapImageBuilder(small).build()) }
        val background = result.confidenceMasks().get()[0]
        val w = background.width
        val h = background.height
        val buffer = com.google.mediapipe.framework.image.ByteBufferExtractor.extract(background)
            .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
        val values = FloatArray(w * h) { 1f - buffer.get(it) }
        result.confidenceMasks().get().forEach { it.close() }
        keepLargest(values, w, h)
        val ms = SystemClock.elapsedRealtime() - start
        frames++
        totalMs += ms
        if (frames == 1 || frames % 60 == 0) Log.i(TAG, "segment ${ms} ms, average ${totalMs / frames} ms over $frames ($delegate)")
        return Mask(w, h, values)
    }

    override fun close() {
        runCatching { onWorker { segmenter.close() } }
        worker.shutdown()
    }

    companion object {
        private const val TAG = "PersonMask"
        private const val MODEL = "selfie_multiclass_256x256.tflite"
        const val INPUT_WIDTH = 256

        /**
         * Zeroes everything but the largest blob of person (stray specks the model sometimes
         * marks in the background), keeping the soft edge of the blob that stays.
         */
        fun keepLargest(values: FloatArray, w: Int, h: Int) {
            val label = IntArray(w * h)
            val stack = IntArray(w * h)
            var best = 0
            var bestSize = 0
            var next = 0
            for (start in values.indices) {
                if (values[start] < 0.5f || label[start] != 0) continue
                next++
                var size = 0
                var top = 0
                stack[top++] = start
                label[start] = next
                while (top > 0) {
                    val i = stack[--top]
                    size++
                    val x = i % w
                    val y = i / w
                    if (x > 0) push(values, label, stack, i - 1, next)?.let { stack[top++] = it }
                    if (x < w - 1) push(values, label, stack, i + 1, next)?.let { stack[top++] = it }
                    if (y > 0) push(values, label, stack, i - w, next)?.let { stack[top++] = it }
                    if (y < h - 1) push(values, label, stack, i + w, next)?.let { stack[top++] = it }
                }
                if (size > bestSize) {
                    bestSize = size
                    best = next
                }
            }
            if (next <= 1) return
            for (i in values.indices) {
                val l = label[i]
                if (l != 0 && l != best) values[i] = 0f
            }
            // Soft edge texels (below 0.5) have no label: keep them only next to the person.
            for (i in values.indices) {
                if (label[i] != 0 || values[i] == 0f) continue
                val x = i % w
                val y = i / w
                val near = (x > 0 && label[i - 1] == best) || (x < w - 1 && label[i + 1] == best) ||
                    (y > 0 && label[i - w] == best) || (y < h - 1 && label[i + w] == best)
                if (!near) values[i] = 0f
            }
        }

        private fun push(values: FloatArray, label: IntArray, stack: IntArray, i: Int, l: Int): Int? {
            if (values[i] < 0.5f || label[i] != 0) return null
            label[i] = l
            return i
        }

        /**
         * The mask at [picture]'s size, its edge snapped to the picture's own edges (joint
         * bilateral upsampling: each pixel averages nearby mask values, weighted by distance and
         * by how alike their colours are). Same maths as DesignEffect's shader. 0..1 per pixel.
         */
        fun refine(mask: Mask, picture: Bitmap): FloatArray {
            val w = picture.width
            val h = picture.height
            val px = IntArray(w * h)
            picture.getPixels(px, 0, w, 0, 0, w, h)
            val out = FloatArray(w * h)
            val mw = mask.width
            val mh = mask.height
            for (y in 0 until h) {
                val my = (y + 0.5f) / h * mh - 0.5f
                val by = Math.round(my)
                for (x in 0 until w) {
                    val mx = (x + 0.5f) / w * mw - 0.5f
                    val bx = Math.round(mx)
                    val here = px[y * w + x]
                    var acc = 0f
                    var sum = 0f
                    for (dy in -2..2) for (dx in -2..2) {
                        val tx = bx + dx
                        val ty = by + dy
                        val m = mask.values[ty.coerceIn(0, mh - 1) * mw + tx.coerceIn(0, mw - 1)]
                        val sx = ((tx + 0.5f) / mw * w).toInt().coerceIn(0, w - 1)
                        val sy = ((ty + 0.5f) / mh * h).toInt().coerceIn(0, h - 1)
                        val c = px[sy * w + sx]
                        val dr = (((c shr 16) and 255) - ((here shr 16) and 255)) / 255f
                        val dg = (((c shr 8) and 255) - ((here shr 8) and 255)) / 255f
                        val db = ((c and 255) - (here and 255)) / 255f
                        val ddx = tx - mx
                        val ddy = ty - my
                        val wt = exp(-(ddx * ddx + ddy * ddy) / SPATIAL - (dr * dr + dg * dg + db * db) / RANGE)
                        acc += wt * m
                        sum += wt
                    }
                    val v = acc / sum.coerceAtLeast(1e-6f)
                    val t = ((v - 0.3f) / 0.4f).coerceIn(0f, 1f)
                    out[y * w + x] = t * t * (3 - 2 * t)
                }
            }
            return out
        }

        /** 2σ² for the distance (σ = 1.2 mask texels) and the colour (σ = 0.1) weights. */
        const val SPATIAL = 2.88f
        const val RANGE = 0.02f
    }
}
