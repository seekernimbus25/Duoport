package com.dualframe.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.opengl.GLES20
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Eye contact: someone reading a teleprompter looks a little away from the lens. The first
 * [EyeContactEffect.CALIBRATION_US] of the take they look into the lens (the app asks them to);
 * where their irises sit then, relative to the eye corners, is "looking at you". After that,
 * every frame's irises are moved back there, inside the eye's outline only. It stays off for
 * blinks and when they clearly look elsewhere, so it only ever undoes the small reading offset.
 *
 * Eyes are found with MediaPipe's face landmark model (478 points with the irises; Apache 2.0),
 * run on LiteRT like the cutout, on a face box from ML Kit's face detector.
 */
class EyeTracker(context: Context) : Closeable {
    /** One eye in picture pixels (+y down): centre between the corners, unit axis corner to corner. */
    class Eye(
        val cx: Float, val cy: Float, val ax: Float, val ay: Float, val width: Float,
        /** Lid gap / width. */
        val open: Float,
        /** Iris centre relative to [cx], [cy] in eye widths, along x and y of the picture. */
        val gx: Float, val gy: Float,
        /** Upper and lower lid above the centre (lower is negative), and the iris radius, in eye widths. */
        val upper: Float, val lower: Float, val irisR: Float,
    )

    private val interpreter: Interpreter
    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder().setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST).setMinFaceSize(0.08f).build()
    )
    private val input = ByteBuffer.allocateDirect(SIZE * SIZE * 3 * 4).order(ByteOrder.nativeOrder())
    private val points = Array(1) { Array(1) { Array(1) { FloatArray(POINTS * 3) } } }
    private val presence = Array(1) { Array(1) { Array(1) { FloatArray(1) } } }
    private val blend = Array(1) { FloatArray(1) }
    private val crop = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
    private val cropPixels = IntArray(SIZE * SIZE)
    /** Face box from the last frame's landmarks (left, top, right, bottom), or null to detect. */
    private var box: FloatArray? = null
    private var sinceDetect = 0
    private var misses = 0

    init {
        val fd = context.assets.openFd(MODEL)
        val model = fd.createInputStream().channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        interpreter = Interpreter(model, Interpreter.Options().setNumThreads(2))
    }

    /** Both eyes in [frame] (upright), or null when there's no face. */
    fun find(frame: Bitmap): Pair<Eye, Eye>? {
        // No face lately: look again only every few frames (a take of the ceiling ran the
        // detector on every frame and saving crawled).
        if (box == null && misses++ % REDETECT_EVERY != 0) return null
        if (box == null || ++sinceDetect >= REDETECT_EVERY) {
            sinceDetect = 0
            val faces = runCatching { Tasks.await(detector.process(InputImage.fromBitmap(frame, 0))) }.getOrNull()
            val face = faces?.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
            box = face?.boundingBox?.let { floatArrayOf(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat()) }
        }
        val b = box ?: return null
        misses = 0
        // A square around the face with a margin, as the model was trained on.
        val cx = (b[0] + b[2]) / 2f
        val cy = (b[1] + b[3]) / 2f
        val side = max(b[2] - b[0], b[3] - b[1]) * 1.5f
        if (side < 24f) return null
        val left = cx - side / 2f
        val top = cy - side / 2f
        val m = Matrix().apply {
            setTranslate(-left, -top)
            postScale(SIZE / side, SIZE / side)
        }
        crop.eraseColor(0)
        android.graphics.Canvas(crop).drawBitmap(frame, m, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
        crop.getPixels(cropPixels, 0, SIZE, 0, 0, SIZE, SIZE)
        input.rewind()
        for (p in cropPixels) {
            input.putFloat(((p shr 16) and 255) / 255f)
            input.putFloat(((p shr 8) and 255) / 255f)
            input.putFloat((p and 255) / 255f)
        }
        input.rewind()
        interpreter.runForMultipleInputsOutputs(arrayOf(input), mapOf(0 to points, 1 to presence, 2 to blend))
        val raw = points[0][0][0]
        val scale = side / SIZE
        fun px(i: Int) = left + raw[i * 3] * scale
        fun py(i: Int) = top + raw[i * 3 + 1] * scale
        // Next frame's box: around these landmarks.
        var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
        for (i in 0 until 468) {
            x0 = minOf(x0, px(i)); x1 = maxOf(x1, px(i)); y0 = minOf(y0, py(i)); y1 = maxOf(y1, py(i))
        }
        val faceW = x1 - x0
        // Landmarks that collapsed or wandered off the face: look for the face again next time.
        if (faceW < side * 0.3f || faceW > side * 1.2f) {
            box = null
            return null
        }
        box = floatArrayOf(x0, y0, x1, y1)
        fun eye(a: Int, b: Int, iris: Int, upper: Int, lower: Int): Eye {
            val w = hypot(px(b) - px(a), py(b) - py(a)).coerceAtLeast(1f)
            val ecx = (px(a) + px(b)) / 2f
            val ecy = (py(a) + py(b)) / 2f
            // Left to right in the picture, so "up" below is always towards the brow.
            val flip = if (px(b) < px(a)) -1f else 1f
            val ax = flip * (px(b) - px(a)) / w
            val ay = flip * (py(b) - py(a)) / w
            // Height above the centre line (the picture's y points down).
            fun above(i: Int) = ((px(i) - ecx) * ay - (py(i) - ecy) * ax) / w
            // The four iris edge points follow its centre in the model's output.
            val r = (1..4).map { hypot(px(iris + it) - px(iris), py(iris + it) - py(iris)) }.average().toFloat() / w
            return Eye(
                ecx, ecy, ax, ay, w,
                hypot(px(upper) - px(lower), py(upper) - py(lower)) / w,
                (px(iris) - ecx) / w, (py(iris) - ecy) / w,
                above(upper), above(lower), r,
            )
        }
        return eye(33, 133, 468, 159, 145) to eye(362, 263, 473, 386, 374)
    }

    override fun close() {
        interpreter.close()
        detector.close()
        crop.recycle()
    }

    private companion object {
        const val MODEL = "face_landmarks_detector.tflite"
        const val SIZE = 256
        const val POINTS = 478
        /** ML Kit looks for the face again this often even while the landmarks keep track. */
        const val REDETECT_EVERY = 15
    }
}

/** Export effect: moves the irises back to where they were while looking into the lens (see [EyeTracker]). */
@OptIn(UnstableApi::class)
class EyeContactEffect(
    /** The recording: the eyes are tracked once per take and the warp reused for every shape made from it. */
    private val take: String,
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = Program(context, take, useHdr)

    private class Program(context: Context, take: String, useHdr: Boolean) :
        BaseGlShaderProgram(/* useHighPrecisionColorComponents= */ useHdr, /* texturePoolCapacity= */ 1) {

        private var made: EyeTracker? = null
        /** Made on first use only: the shapes after the first reuse its warp and never need it. */
        private val tracker: EyeTracker
            get() = made ?: EyeTracker(appContext).also { made = it; Log.i(TAG, "Eye contact: tracking the eyes in this take") }
        private val appContext = context.applicationContext

        init {
            synchronized(shared) {
                if (sharedTake != take) {
                    shared.clear()
                    sharedTake = take
                }
            }
        }
        private val copyProgram = program(COPY_FRAGMENT)
        private val warpProgram = program(WARP_FRAGMENT)
        private var width = 1
        private var height = 1
        private var smallW = 1
        private var smallH = 1
        private var smallTexture = 0
        private var smallFbo = 0
        private var pixels: ByteBuffer = ByteBuffer.allocateDirect(4)

        // Calibration: each eye's iris offset and openness while looking into the lens.
        private val calX = mutableListOf<Float>()
        private val calY = mutableListOf<Float>()
        private val calOpen = mutableListOf<Float>()
        private var calibrated: FloatArray? = null
        /** The correction now applied, in eye widths (eased from frame to frame). */
        private var shiftX = 0f
        private var shiftY = 0f
        /** How far the upper lids are lifted back up, in eye widths (eased like the shift). */
        private var lift = 0f
        private var frames = 0

        private fun program(fragment: String): GlProgram = try {
            GlProgram(VERTEX, fragment).apply {
                setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
            }
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            width = inputWidth
            height = inputHeight
            val scale = minOf(1f, ANALYSIS_LONG_SIDE / max(inputWidth, inputHeight))
            smallW = max(2, (inputWidth * scale).roundToInt())
            smallH = max(2, (inputHeight * scale).roundToInt())
            pixels = ByteBuffer.allocateDirect(smallW * smallH * 4).order(ByteOrder.nativeOrder())
            if (smallFbo != 0) {
                GLES20.glDeleteFramebuffers(1, intArrayOf(smallFbo), 0)
                GLES20.glDeleteTextures(1, intArrayOf(smallTexture), 0)
            }
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            smallTexture = ids[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, smallTexture)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, smallW, smallH, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            val fbo = IntArray(1)
            GLES20.glGenFramebuffers(1, fbo, 0)
            smallFbo = fbo[0]
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, smallFbo)
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, smallTexture, 0)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            return Size(inputWidth, inputHeight)
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            try {
                val outFbo = IntArray(1)
                GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, outFbo, 0)
                val viewport = IntArray(4)
                GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, viewport, 0)

                val known = synchronized(shared) { shared[presentationTimeUs] }
                val uniforms = known ?: track(inputTexId, presentationTimeUs)
                    .also { synchronized(shared) { shared[presentationTimeUs] = it } }

                // 3. The warp, into Media3's output.
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, outFbo[0])
                GLES20.glViewport(viewport[0], viewport[1], viewport[2], viewport[3])
                warpProgram.use()
                warpProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
                warpProgram.setFloatsUniform("uSize", floatArrayOf(width.toFloat(), height.toFloat()))
                for (i in 0..1) {
                    warpProgram.setFloatsUniform("uEye$i", uniforms[i * 4])
                    warpProgram.setFloatsUniform("uLid$i", uniforms[i * 4 + 1])
                    warpProgram.setFloatsUniform("uIris$i", uniforms[i * 4 + 2])
                    warpProgram.setFloatsUniform("uShift$i", uniforms[i * 4 + 3])
                }
                warpProgram.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                GlUtil.checkGlError()
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e, presentationTimeUs)
            }
        }

        /** Finds the eyes in this frame and works out the warp (leaves Media3's framebuffer bound to the copy). */
        private fun track(inputTexId: Int, presentationTimeUs: Long): Array<FloatArray> {
                // 1. A small upright copy (top row first), read back for the landmark model.
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, smallFbo)
                GLES20.glViewport(0, 0, smallW, smallH)
                copyProgram.use()
                copyProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
                copyProgram.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                pixels.rewind()
                GLES20.glReadPixels(0, 0, smallW, smallH, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
                val bitmap = Bitmap.createBitmap(smallW, smallH, Bitmap.Config.ARGB_8888)
                pixels.rewind()
                bitmap.copyPixelsFromBuffer(pixels)
                val eyes = runCatching { tracker.find(bitmap) }.onFailure { Log.w(TAG, "Eye tracking failed", it) }.getOrNull()
                bitmap.recycle()

                // 2. How far to move the irises.
                return correction(eyes, presentationTimeUs)
        }

        /** Shader uniforms for each eye: (centre, axis), (width, lids, lift), (iris, radius), shift; GL pixels (+y up). */
        private fun correction(eyes: Pair<EyeTracker.Eye, EyeTracker.Eye>?, timeUs: Long): Array<FloatArray> {
            val still = arrayOf(floatArrayOf(0f, 0f, 1f, 0f), floatArrayOf(1f, 0f, 0f, 0f), floatArrayOf(0f, 0f, 1f, 0f), floatArrayOf(0f, 0f, 0f, 0f))
            val none = still + still
            var targetX = 0f
            var targetY = 0f
            var targetLift = 0f
            if (eyes != null) {
                val (l, r) = eyes
                val gx = (l.gx + r.gx) / 2f
                val gy = (l.gy + r.gy) / 2f
                val open = (l.open + r.open) / 2f
                if (timeUs < CALIBRATION_US) {
                    // Looking into the lens: learn where the irises sit.
                    calX += gx
                    calY += gy
                    calOpen += open
                } else {
                    val cal = calibrated ?: if (calX.size >= 3) {
                        floatArrayOf(calX.sorted()[calX.size / 2], calY.sorted()[calY.size / 2], calOpen.sorted()[calOpen.size / 2])
                            .also { calibrated = it; Log.i(TAG, "Calibrated: iris ${it[0]}, ${it[1]}, open ${it[2]} from ${calX.size} frames") }
                    } else {
                        null
                    }
                    if (cal != null) {
                        val dx = cal[0] - gx
                        val dy = cal[1] - gy
                        // Full correction for reading-sized offsets, none for a deliberate look away
                        // or a blink (lids closing make the iris jump).
                        val away = 1f - ((hypot(dx, dy) - AWAY_START) / (AWAY_END - AWAY_START)).coerceIn(0f, 1f)
                        val blink = ((open / cal[2] - 0.55f) / 0.2f).coerceIn(0f, 1f)
                        val strength = away * blink
                        targetX = (dx * IRIS_GAIN).coerceIn(-MAX_SHIFT, MAX_SHIFT) * strength
                        targetY = (dy * IRIS_GAIN).coerceIn(-MAX_SHIFT, MAX_SHIFT) * strength
                        // Reading below the lens also lowers the upper lids (T012: openness 0.40 at
                        // the lens, ~0.35 reading), so they are lifted back towards their opening.
                        targetLift = ((cal[2] - open) * LIFT_GAIN).coerceIn(0f, MAX_LIFT) * strength
                    }
                    if (++frames % 30 == 0) {
                        Log.i(TAG, "t=${timeUs / 1000} ms open $open gaze $gx,$gy -> shift $targetX,$targetY lift $targetLift")
                    }
                }
            }
            shiftX += (targetX - shiftX) * EASE
            shiftY += (targetY - shiftY) * EASE
            lift += (targetLift - lift) * EASE
            if (eyes == null || (shiftX == 0f && shiftY == 0f && lift == 0f)) return none
            val k = width.toFloat() / smallW
            fun of(e: EyeTracker.Eye): Array<FloatArray> {
                val w = e.width * k
                // The iris along the eye's axis and above its centre line.
                val iu = e.gx * e.ax + e.gy * e.ay
                val ih = e.gx * e.ay - e.gy * e.ax
                return arrayOf(
                    floatArrayOf(e.cx * k, height - e.cy * k, e.ax, -e.ay),
                    floatArrayOf(w, e.upper * w, e.lower * w, lift * w),
                    floatArrayOf(iu * w, ih * w, e.irisR * w, 0f),
                    floatArrayOf(shiftX * w, -shiftY * w, 0f, 0f),
                )
            }
            return of(eyes.first) + of(eyes.second)
        }

        override fun release() {
            super.release()
            made?.close()
            try {
                copyProgram.delete()
                warpProgram.delete()
                if (smallFbo != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(smallFbo), 0)
                if (smallTexture != 0) GLES20.glDeleteTextures(1, intArrayOf(smallTexture), 0)
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e)
            }
        }
    }

    companion object {
        private const val TAG = "EyeContact"
        /** The warp for each frame time of [sharedTake], filled by the first shape made, reused by the rest. */
        private val shared = HashMap<Long, Array<FloatArray>>()
        private var sharedTake: String? = null
        /** The take's first moments, while the app asks for a look into the lens. */
        const val CALIBRATION_US = 1_500_000L
        /** Eyes are found in a copy this big (long side). */
        private const val ANALYSIS_LONG_SIDE = 960f
        /**
         * Largest iris move, in eye widths. It stays below the iris's height over the lower lid
         * (~0.15), so the move never reaches past the lid for the skin below.
         */
        private const val MAX_SHIFT = 0.08f
        /**
         * The landmarks under-read how far the irises moved (T016 on build 189: corrected 1:1, the
         * reading frames still looked down), so the iris move is scaled up.
         */
        private const val IRIS_GAIN = 2.5f
        /**
         * The lids only partly: T027 on build 194 lifted them 2.5x with the whole eye, and the eye
         * looked raised with the hollow below it stretched.
         */
        private const val LIFT_GAIN = 1.5f
        /** Largest lid lift, in eye widths. */
        private const val MAX_LIFT = 0.08f
        /** Offsets from here (eye widths) fade the correction out, gone by [AWAY_END]: a real look away. */
        private const val AWAY_START = 0.12f
        private const val AWAY_END = 0.2f
        /** Share of the way to the new correction each frame, so the eyes don't jitter. */
        private const val EASE = 0.5f

        private const val VERTEX = """
            attribute vec4 aFramePosition;
            varying vec2 vTexCoord;
            void main() {
              gl_Position = aFramePosition;
              vTexCoord = aFramePosition.xy * 0.5 + 0.5;
            }
        """

        // Flipped so the read-back rows come out top first, like a Bitmap.
        private const val COPY_FRAGMENT = """
            precision mediump float;
            uniform sampler2D uTexSampler;
            varying vec2 vTexCoord;
            void main() {
              gl_FragColor = texture2D(uTexSampler, vec2(vTexCoord.x, 1.0 - vTexCoord.y));
            }
        """

        // Each eye, in its own frame (u along the corners, h up from the centre line), sampled
        // backwards (output p shows the picture at p - pull):
        // - the upper lid lifts by lid.w, stretching the opening up from the lower lid, which stays
        //   put with everything below it, and easing back to nothing towards the brow;
        // - the iris moves by the shift, fully at its own height, easing to nothing at the lower
        //   lid (so no skin is pulled in from below) and towards the corners, cut at the upper lid
        //   it slides under. The lids follow an arch, 1 in the middle and 0 at the corners.
        private const val WARP_FRAGMENT = """
            precision highp float;
            uniform sampler2D uTexSampler;
            uniform vec2 uSize;
            uniform vec4 uEye0;
            uniform vec4 uLid0;
            uniform vec4 uIris0;
            uniform vec4 uShift0;
            uniform vec4 uEye1;
            uniform vec4 uLid1;
            uniform vec4 uIris1;
            uniform vec4 uShift1;
            varying vec2 vTexCoord;
            // eye: centre, axis. lid: width, upper and lower lid height, lift. iris: u, h, radius.
            vec2 pull(vec2 p, vec4 eye, vec4 lid, vec4 iris, vec4 shift) {
              vec2 d = p - eye.xy;
              vec2 up = vec2(-eye.w, eye.z);
              float u = dot(d, eye.zw);
              float h = dot(d, up);
              float w = lid.x;
              float a = 2.0 * u / w;
              float arch = clamp(1.0 - a * a, 0.0, 1.0);
              float bot = lid.z * arch;
              float lift = lid.w * arch;
              float top = lid.y * arch + lift;
              if (h <= bot) return vec2(0.0);
              float s = h <= top
                  ? lift * (h - bot) / max(top - bot, 0.001)
                  : lift * (1.0 - smoothstep(0.0, 0.45 * w, h - top));
              float e = 0.04 * w;
              float rise = smoothstep(0.0, 1.0, (h - bot) / max(iris.y - lid.z, 0.001));
              float side = 1.0 - smoothstep(1.2 * iris.z, 2.6 * iris.z, abs(u - iris.x));
              float under = 1.0 - smoothstep(top - e, top, h);
              return up * s + shift.xy * rise * side * under;
            }
            void main() {
              vec2 p = vTexCoord * uSize;
              vec2 s = pull(p, uEye0, uLid0, uIris0, uShift0) + pull(p, uEye1, uLid1, uIris1, uShift1);
              gl_FragColor = texture2D(uTexSampler, (p - s) / uSize);
            }
        """
    }
}
