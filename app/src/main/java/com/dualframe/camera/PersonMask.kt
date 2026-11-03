package com.dualframe.camera

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.io.Closeable
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * Who's the person in a picture, for the cutouts. Google's selfie multiclass model sorts
 * every pixel into background, hair, skin, clothes or "other" (headwear, glasses, things held);
 * everything but background is the person, so hair and accessories stay on. ML Kit's selfie
 * model, used before, only knew person/not-person and dropped hair edges and hats.
 *
 * The model sees a 256 × 256 copy; [refine] (and the same step in DesignEffect's and
 * CinematicBlurEffect's shaders) then snaps the mask's edge to the real edges in the full
 * picture. Cinematic mode uses it too. It runs on LiteRT (TensorFlow Lite) directly: through MediaPipe the 9a's GPU refused it ("Batch size mismatch, expected 1 but
 * got 8") and the CPU path took ~0.4 s a frame.
 *
 * With [matte] ("Fine edges" in the editor) a second model, MODNet (portrait matting,
 * Apache 2.0), also runs on a 512 px copy: it gives a soft alpha that keeps hair strands and
 * thin headwear, but sometimes lets bright background through (a ceiling beam above a head).
 * So its alpha is kept only near the person the first model found ([gate]).
 */
class PersonMask(
    context: Context,
    private val precise: Boolean = true,
    private val matte: Boolean = false,
) : Closeable {
    /**
     * One mask: [width] × [height] values, 0 = background, 1 = person, first row = top. A [soft]
     * one is a finished alpha (the matte), to use as it is rather than sharpened at 0.5.
     */
    class Mask(val width: Int, val height: Int, val values: FloatArray, val soft: Boolean = false)

    /** The interpreter (and a GPU delegate, which must stay on the thread that made it) lives here. */
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var interpreter: Interpreter? = null
    private var gpu: GpuDelegate? = null
    private var onGpu = false
    private val input = ByteBuffer.allocateDirect(SIZE * SIZE * 3 * 4).order(ByteOrder.nativeOrder())
    private val output = ByteBuffer.allocateDirect(SIZE * SIZE * CLASSES * 4).order(ByteOrder.nativeOrder())
    private val pixels = IntArray(SIZE * SIZE)
    private var frames = 0
    private var totalMs = 0L

    private var matteInterpreter: Interpreter? = null
    private var matteGpu: GpuDelegate? = null
    private var matteFrames = 0
    private lateinit var matteModel: java.nio.MappedByteBuffer
    private val matteIn by lazy { ByteBuffer.allocateDirect(MATTE * MATTE * 3 * 4).order(ByteOrder.nativeOrder()) }
    private val matteOut by lazy { ByteBuffer.allocateDirect(MATTE * MATTE * 4).order(ByteOrder.nativeOrder()) }

    init {
        onWorker {
            val fd = context.assets.openFd(MODEL)
            val model = fd.createInputStream().channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            this.model = model
            // The phone's GPU runs the model many times faster; the CPU (4 threads) if it can't.
            // TFLite's CompatibilityList doesn't list the 9a's GPU, so it's tried regardless and
            // the first frame is checked against the CPU (see [infer]). [precise] keeps 32-bit
            // floats: by default the GPU rounds to 16-bit ones, faster, but that tips thin,
            // uncertain parts (antlers, hair strands) from person to background.
            interpreter = runCatching {
                val delegate = GpuDelegate(GpuDelegate.Options().setPrecisionLossAllowed(!precise))
                gpu = delegate
                Interpreter(model, Interpreter.Options().addDelegate(delegate)).also { onGpu = true }
            }.onFailure {
                Log.w(TAG, "No GPU segmenter", it)
                gpu?.close()
                gpu = null
            }.getOrNull() ?: cpuInterpreter()
            if (matte) {
                runCatching {
                    val mfd = context.assets.openFd(MATTE_MODEL)
                    matteModel = mfd.createInputStream().channel.map(FileChannel.MapMode.READ_ONLY, mfd.startOffset, mfd.declaredLength)
                    matteInterpreter = runCatching {
                        val delegate = GpuDelegate(GpuDelegate.Options().setPrecisionLossAllowed(true))
                        matteGpu = delegate
                        Interpreter(matteModel, Interpreter.Options().addDelegate(delegate))
                    }.onFailure {
                        Log.w(TAG, "No GPU matte", it)
                        matteGpu?.close()
                        matteGpu = null
                    }.getOrNull() ?: Interpreter(matteModel, Interpreter.Options().setNumThreads(4))
                }.onFailure { Log.w(TAG, "No matte model; fine edges fall back to the segmenter", it) }
            }
        }
    }

    private lateinit var model: java.nio.MappedByteBuffer

    private fun cpuInterpreter() = Interpreter(model, Interpreter.Options().setNumThreads(4))

    /** Runs the model; on the GPU's first frame, also on the CPU, and stays on the CPU if they disagree. */
    private fun infer() {
        val first = onGpu && frames == 0
        interpreter!!.run(input, output)
        if (!first) return
        val gpuOut = FloatArray(SIZE * SIZE * CLASSES)
        output.rewind()
        output.asFloatBuffer().get(gpuOut)
        val cpu = cpuInterpreter()
        input.rewind()
        output.rewind()
        cpu.run(input, output)
        val cpuOut = FloatArray(gpuOut.size)
        output.rewind()
        output.asFloatBuffer().get(cpuOut)
        // Compare the decision, pixel by pixel: an average hides a thin part going missing.
        val gpuPerson = person(gpuOut)
        val cpuPerson = person(cpuOut)
        var flipped = 0
        var people = 0
        for (i in gpuPerson.indices) {
            if (cpuPerson[i] >= 0.5f) people++
            if ((gpuPerson[i] >= 0.5f) != (cpuPerson[i] >= 0.5f)) flipped++
        }
        val share = flipped.toFloat() / people.coerceAtLeast(1)
        Log.i(TAG, "GPU vs CPU on the first frame: $flipped pixels decided differently (${"%.2f".format(share * 100)}% of the person)")
        // Fine: almost no difference allowed. Quick accepts the rounding; only a GPU that gets
        // it badly wrong falls back.
        if (share > if (precise) 0.005f else 0.05f) {
            Log.w(TAG, "GPU result differs from the CPU; using the CPU")
            interpreter?.close()
            gpu?.close()
            gpu = null
            onGpu = false
            interpreter = cpu
        } else {
            cpu.close()
        }
    }

    private fun <T> onWorker(block: () -> T): T =
        try {
            worker.submit<T> { block() }.get()
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e
        }

    /**
     * The person in [small] (upright, any size) as a [SIZE]² mask; with [largestOnly], just the
     * largest connected shape (one subject for a cutout), otherwise everyone in the picture.
     */
    fun segment(small: Bitmap, largestOnly: Boolean = true): Mask {
        val start = SystemClock.elapsedRealtime()
        // The model takes the whole picture stretched to 256 × 256, colours scaled to -1..1.
        val square = Bitmap.createScaledBitmap(small, SIZE, SIZE, true)
        square.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        if (square !== small) square.recycle()
        input.rewind()
        for (p in pixels) {
            input.putFloat(((p shr 16) and 255) / 127.5f - 1f)
            input.putFloat(((p shr 8) and 255) / 127.5f - 1f)
            input.putFloat((p and 255) / 127.5f - 1f)
        }
        input.rewind()
        output.rewind()
        onWorker { infer() }
        output.rewind()
        val scores = FloatArray(SIZE * SIZE * CLASSES)
        output.asFloatBuffer().get(scores)
        val values = person(scores)
        if (largestOnly) keepLargest(values, SIZE, SIZE)
        val fine = if (matteInterpreter != null) runCatching { matteOf(small, values) }
            .onFailure { Log.w(TAG, "Matte failed", it) }.getOrNull() else null
        val ms = SystemClock.elapsedRealtime() - start
        frames++
        totalMs += ms
        if (frames == 1 || frames % 60 == 0) {
            Log.i(TAG, "segment $ms ms, average ${totalMs / frames} ms over $frames (${if (onGpu) "GPU" else "CPU"}${if (fine != null) ", matte" else ""})")
        }
        return fine ?: Mask(SIZE, SIZE, values)
    }

    /**
     * MODNet's alpha for [small], letterboxed into its 512 × 512 input (so tall and wide
     * pictures get the same detail), returned at the picture's part of it, kept only near the
     * person in [person] (the segmenter's [SIZE]² mask).
     */
    private fun matteOf(small: Bitmap, person: FloatArray): Mask {
        val scale = MATTE / maxOf(small.width, small.height).toFloat()
        val w = (small.width * scale).roundToInt().coerceIn(2, MATTE)
        val h = (small.height * scale).roundToInt().coerceIn(2, MATTE)
        val ox = (MATTE - w) / 2
        val oy = (MATTE - h) / 2
        val fitted = Bitmap.createScaledBitmap(small, w, h, true)
        val px = IntArray(w * h)
        fitted.getPixels(px, 0, w, 0, 0, w, h)
        if (fitted !== small) fitted.recycle()
        // Colours to -1..1; the bars stay 0 (mid grey).
        matteIn.rewind()
        for (y in 0 until MATTE) for (x in 0 until MATTE) {
            val inside = x in ox until ox + w && y in oy until oy + h
            val p = if (inside) px[(y - oy) * w + (x - ox)] else 0
            matteIn.putFloat(if (inside) ((p shr 16) and 255) / 127.5f - 1f else 0f)
            matteIn.putFloat(if (inside) ((p shr 8) and 255) / 127.5f - 1f else 0f)
            matteIn.putFloat(if (inside) (p and 255) / 127.5f - 1f else 0f)
        }
        onWorker { inferMatte() }
        matteOut.rewind()
        val all = matteOut.asFloatBuffer()
        val alpha = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) alpha[y * w + x] = all.get((y + oy) * MATTE + x + ox).coerceIn(0f, 1f)
        val gate = gate(person, w, h)
        for (i in alpha.indices) alpha[i] *= gate[i]
        // MODNet leaves a haze of half-transparent background around the person (tester: "a lot
        // of excessive area captured"). Snap the alpha to the picture's own edges (guided
        // filter), then drop the faint values and firm up the near-solid ones.
        val snapped = guided(px, alpha, w, h, (w / 135f).roundToInt().coerceAtLeast(1))
        for (i in alpha.indices) {
            val t = ((snapped[i] - 0.25f) / 0.6f).coerceIn(0f, 1f)
            alpha[i] = t * t * (3f - 2f * t)
        }
        return Mask(w, h, alpha, soft = true)
    }

    /** Runs the matte; on the GPU's first frame, also on the CPU, and stays there if they disagree. */
    private fun inferMatte() {
        val first = matteGpu != null && matteFrames++ == 0
        matteIn.rewind()
        matteOut.rewind()
        matteInterpreter!!.run(matteIn, matteOut)
        if (!first) return
        val gpuOut = FloatArray(MATTE * MATTE)
        matteOut.rewind()
        matteOut.asFloatBuffer().get(gpuOut)
        val cpu = Interpreter(matteModel, Interpreter.Options().setNumThreads(4))
        matteIn.rewind()
        matteOut.rewind()
        cpu.run(matteIn, matteOut)
        val cpuOut = FloatArray(MATTE * MATTE)
        matteOut.rewind()
        matteOut.asFloatBuffer().get(cpuOut)
        var diff = 0f
        for (i in gpuOut.indices) diff += abs(gpuOut[i] - cpuOut[i])
        diff /= gpuOut.size
        Log.i(TAG, "Matte GPU vs CPU on the first frame: mean difference ${"%.4f".format(diff)}")
        if (diff > 0.02f) {
            Log.w(TAG, "Matte GPU result differs from the CPU; using the CPU")
            matteInterpreter?.close()
            matteGpu?.close()
            matteGpu = null
            matteInterpreter = cpu
        } else {
            cpu.close()
        }
    }

    /**
     * Where the matte may keep anything: the segmenter's person (at [w] × [h]), widened by
     * 1.2 % of the width so hair strands and antler tips just outside it stay, then softened
     * (2.5 % let a halo of wall through).
     */
    private fun gate(person: FloatArray, w: Int, h: Int): FloatArray {
        val inside = FloatArray(w * h)
        for (y in 0 until h) {
            val my = ((y + 0.5f) / h * SIZE).toInt().coerceIn(0, SIZE - 1)
            for (x in 0 until w) {
                val mx = ((x + 0.5f) / w * SIZE).toInt().coerceIn(0, SIZE - 1)
                inside[y * w + x] = if (person[my * SIZE + mx] >= 0.5f) 1f else 0f
            }
        }
        val r = (w * 0.012f).roundToInt().coerceAtLeast(1)
        return boxBlur(maxFilter(inside, w, h, r), w, h, r)
    }

    /**
     * Guided filter (He et al.): [p] made locally a linear function of the picture's brightness,
     * so its edges land on the picture's edges. Box means over a (2r+1)² window via running sums.
     */
    private fun guided(px: IntArray, p: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val n = w * h
        val g = FloatArray(n) { i -> val c = px[i]; (((c shr 16) and 255) * 0.299f + ((c shr 8) and 255) * 0.587f + (c and 255) * 0.114f) / 255f }
        val gp = FloatArray(n) { g[it] * p[it] }
        val gg = FloatArray(n) { g[it] * g[it] }
        val mg = boxMean(g, w, h, r)
        val mp = boxMean(p, w, h, r)
        val mgp = boxMean(gp, w, h, r)
        val mgg = boxMean(gg, w, h, r)
        val a = FloatArray(n)
        val b = FloatArray(n)
        for (i in 0 until n) {
            a[i] = (mgp[i] - mg[i] * mp[i]) / (mgg[i] - mg[i] * mg[i] + 1e-3f)
            b[i] = mp[i] - a[i] * mg[i]
        }
        val ma = boxMean(a, w, h, r)
        val mb = boxMean(b, w, h, r)
        return FloatArray(n) { (ma[it] * g[it] + mb[it]).coerceIn(0f, 1f) }
    }

    private fun boxMean(v: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val a = FloatArray(v.size)
        for (y in 0 until h) {
            var sum = 0f
            for (x in -r..r) sum += v[y * w + x.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                a[y * w + x] = sum / (2 * r + 1)
                sum += v[y * w + (x + r + 1).coerceAtMost(w - 1)] - v[y * w + (x - r).coerceAtLeast(0)]
            }
        }
        val b = FloatArray(v.size)
        for (x in 0 until w) {
            var sum = 0f
            for (y in -r..r) sum += a[y.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                b[y * w + x] = sum / (2 * r + 1)
                sum += a[(y + r + 1).coerceAtMost(h - 1) * w + x] - a[(y - r).coerceAtLeast(0) * w + x]
            }
        }
        return b
    }

    private fun maxFilter(v: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val a = FloatArray(v.size)
        for (y in 0 until h) for (x in 0 until w) {
            var m = 0f
            for (dx in maxOf(0, x - r)..minOf(w - 1, x + r)) if (v[y * w + dx] > m) m = v[y * w + dx]
            a[y * w + x] = m
        }
        val b = FloatArray(v.size)
        for (y in 0 until h) for (x in 0 until w) {
            var m = 0f
            for (dy in maxOf(0, y - r)..minOf(h - 1, y + r)) if (a[dy * w + x] > m) m = a[dy * w + x]
            b[y * w + x] = m
        }
        return b
    }

    private fun boxBlur(v: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val a = FloatArray(v.size)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f
            var n = 0
            for (dx in maxOf(0, x - r)..minOf(w - 1, x + r)) { s += v[y * w + dx]; n++ }
            a[y * w + x] = s / n
        }
        val b = FloatArray(v.size)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f
            var n = 0
            for (dy in maxOf(0, y - r)..minOf(h - 1, y + r)) { s += a[dy * w + x]; n++ }
            b[y * w + x] = s / n
        }
        return b
    }

    /**
     * Six scores a pixel (background, hair, body skin, face skin, clothes, other); the person
     * is everything but background: 1 - softmax(background).
     */
    private fun person(scores: FloatArray): FloatArray {
        val values = FloatArray(SIZE * SIZE)
        val l = FloatArray(CLASSES)
        for (i in values.indices) {
            var top = Float.NEGATIVE_INFINITY
            for (c in 0 until CLASSES) {
                l[c] = scores[i * CLASSES + c]
                if (l[c] > top) top = l[c]
            }
            var sum = 0f
            var bg = 0f
            for (c in 0 until CLASSES) {
                val e = exp(l[c] - top)
                sum += e
                if (c == 0) bg = e
            }
            values[i] = 1f - bg / sum
        }
        return values
    }

    override fun close() {
        runCatching {
            onWorker {
                interpreter?.close()
                gpu?.close()
                matteInterpreter?.close()
                matteGpu?.close()
            }
        }
        worker.shutdown()
    }

    companion object {
        private const val TAG = "PersonMask"
        private const val MODEL = "selfie_multiclass_256x256.tflite"
        /** The model's input and output size. */
        private const val SIZE = 256
        private const val CLASSES = 6
        private const val MATTE_MODEL = "modnet_matte_512.tflite"
        /** The matte model's input and output size (a square; pictures are letterboxed into it). */
        const val MATTE = 512
        /** How wide a copy callers pass to [segment] (it's stretched to 256 × 256 here). */
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
