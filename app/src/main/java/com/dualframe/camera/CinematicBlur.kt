package com.dualframe.camera

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES20
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
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.Segmenter
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Cinematic mode: keeps people sharp and blurs what's behind them, like a fast lens on a big
 * sensor. Phones don't give apps a depth map, so an on-device person segmentation model
 * (ML Kit selfie segmentation) decides what is subject and what is background. It works on
 * people only, not on objects or pets.
 */
object CinematicBlur {
    /** Blur radius as a fraction of the picture's width, for strength 1..3. */
    fun radiusFraction(strength: Int) = when (strength) {
        1 -> 0.008f
        2 -> 0.014f
        else -> 0.022f
    }

    /** Photo version: segment a small copy, blur by down/up-scaling, blend with the mask. */
    fun applyToPhoto(src: Bitmap, strength: Int): Bitmap {
        val w = src.width
        val h = src.height
        val segLong = 512f
        val segScale = segLong / max(w, h)
        val sw = max(1, (w * segScale).roundToInt())
        val sh = max(1, (h * segScale).roundToInt())
        val small = Bitmap.createScaledBitmap(src, sw, sh, true)
        val segmenter = Segmentation.getClient(
            SelfieSegmenterOptions.Builder().setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE).build()
        )
        val mask = try {
            Tasks.await(segmenter.process(InputImage.fromBitmap(small, 0)))
        } finally {
            segmenter.close()
        }
        val mw = mask.width
        val mh = mask.height
        val maskValues = FloatArray(mw * mh)
        val maskBuffer = mask.buffer
        maskBuffer.rewind()
        maskBuffer.order(ByteOrder.nativeOrder()).asFloatBuffer().get(maskValues)
        if (small != src) small.recycle()

        // Blur: shrink hard, then scale back up in two smooth steps.
        val step = max(2, (radiusFraction(strength) * w * 1.6f).roundToInt())
        val tiny = Bitmap.createScaledBitmap(src, max(1, w / step), max(1, h / step), true)
        val mid = Bitmap.createScaledBitmap(tiny, max(1, w / 4), max(1, h / 4), true)
        val blurred = Bitmap.createScaledBitmap(mid, w, h, true)
        tiny.recycle()
        mid.recycle()

        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val sharpRow = IntArray(w)
        val blurRow = IntArray(w)
        for (y in 0 until h) {
            out.getPixels(sharpRow, 0, w, 0, y, w, 1)
            blurred.getPixels(blurRow, 0, w, 0, y, w, 1)
            val my = ((y + 0.5f) / h * mh).toInt().coerceIn(0, mh - 1)
            for (x in 0 until w) {
                val mx = ((x + 0.5f) / w * mw).toInt().coerceIn(0, mw - 1)
                val subject = smoothstep(0.35f, 0.75f, maskValues[my * mw + mx])
                if (subject >= 0.999f) continue
                sharpRow[x] = mix(blurRow[x], sharpRow[x], subject)
            }
            out.setPixels(sharpRow, 0, w, 0, y, w, 1)
        }
        blurred.recycle()
        return out
    }

    private fun smoothstep(a: Float, b: Float, x: Float): Float {
        val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(shift: Int) = (((a shr shift) and 0xFF) * (1f - t) + ((b shr shift) and 0xFF) * t).toInt()
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}

/**
 * Video version, as a Media3 effect. Each frame: draw a small copy, read it back, segment it,
 * upload the mask, then a disc blur that only gathers background pixels (so people don't
 * leave a halo) is mixed with the sharp frame by the mask.
 */
@OptIn(UnstableApi::class)
class CinematicBlurEffect(private val strength: Int) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = Program(strength, useHdr)

    private class Program(strength: Int, useHdr: Boolean) :
        BaseGlShaderProgram(/* useHighPrecisionColorComponents= */ useHdr, /* texturePoolCapacity= */ 1) {

        private val radius = CinematicBlur.radiusFraction(strength)
        private val segmenter: Segmenter = Segmentation.getClient(
            SelfieSegmenterOptions.Builder().setDetectorMode(SelfieSegmenterOptions.STREAM_MODE).build()
        )

        private val copyProgram: GlProgram = program(COPY_FRAGMENT_SHADER)
        private val blurProgram: GlProgram = program(BLUR_FRAGMENT_SHADER)

        private var width = 1
        private var height = 1
        private var smallW = 1
        private var smallH = 1
        private var smallTexture = 0
        private var smallFbo = 0
        private var maskTexture = 0
        private var pixels: ByteBuffer = ByteBuffer.allocateDirect(4)
        private var maskBytes: ByteBuffer = ByteBuffer.allocateDirect(1)

        private fun program(fragment: String): GlProgram = try {
            GlProgram(VERTEX_SHADER, fragment).apply {
                setBufferAttribute(
                    "aFramePosition",
                    GlUtil.getNormalizedCoordinateBounds(),
                    GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
                )
            }
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            width = inputWidth
            height = inputHeight
            val scale = SEGMENT_LONG_SIDE.toFloat() / max(inputWidth, inputHeight)
            smallW = max(2, (inputWidth * scale).roundToInt())
            smallH = max(2, (inputHeight * scale).roundToInt())
            pixels = ByteBuffer.allocateDirect(smallW * smallH * 4).order(ByteOrder.nativeOrder())

            deleteGlObjects()
            val ids = IntArray(2)
            GLES20.glGenTextures(2, ids, 0)
            smallTexture = ids[0]
            maskTexture = ids[1]
            for (tex in ids) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            }
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, smallTexture)
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, smallW, smallH, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null,
            )
            val fbo = IntArray(1)
            GLES20.glGenFramebuffers(1, fbo, 0)
            smallFbo = fbo[0]
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, smallFbo)
            GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, smallTexture, 0,
            )
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            return Size(inputWidth, inputHeight)
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            try {
                // Remember where Media3 wants this frame drawn.
                val outFbo = IntArray(1)
                GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, outFbo, 0)
                val viewport = IntArray(4)
                GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, viewport, 0)

                // 1. Small upright copy, read back for the segmentation model.
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, smallFbo)
                GLES20.glViewport(0, 0, smallW, smallH)
                copyProgram.use()
                copyProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
                copyProgram.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                pixels.rewind()
                GLES20.glReadPixels(0, 0, smallW, smallH, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
                GlUtil.checkGlError()

                // 2. Who is a person?
                val bitmap = Bitmap.createBitmap(smallW, smallH, Bitmap.Config.ARGB_8888)
                pixels.rewind()
                bitmap.copyPixelsFromBuffer(pixels)
                val mask = Tasks.await(segmenter.process(InputImage.fromBitmap(bitmap, 0)))
                bitmap.recycle()
                uploadMask(mask.buffer, mask.width, mask.height)

                // 3. Blur the background into the real output.
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, outFbo[0])
                GLES20.glViewport(viewport[0], viewport[1], viewport[2], viewport[3])
                blurProgram.use()
                blurProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
                blurProgram.setSamplerTexIdUniform("uMask", maskTexture, 1)
                blurProgram.setFloatsUniform("uRadius", floatArrayOf(radius, radius * width / height))
                blurProgram.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                GlUtil.checkGlError()
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e, presentationTimeUs)
            } catch (e: Exception) {
                throw VideoFrameProcessingException(e, presentationTimeUs)
            }
        }

        private fun uploadMask(buffer: ByteBuffer, w: Int, h: Int) {
            if (maskBytes.capacity() < w * h) maskBytes = ByteBuffer.allocateDirect(w * h)
            maskBytes.clear()
            buffer.rewind()
            val floats: FloatBuffer = buffer.order(ByteOrder.nativeOrder()).asFloatBuffer()
            for (i in 0 until w * h) maskBytes.put((floats.get(i).coerceIn(0f, 1f) * 255f).toInt().toByte())
            maskBytes.rewind()
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTexture)
            GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, w, h, 0,
                GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, maskBytes,
            )
        }

        private fun deleteGlObjects() {
            if (smallFbo != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(smallFbo), 0)
            if (smallTexture != 0) GLES20.glDeleteTextures(2, intArrayOf(smallTexture, maskTexture), 0)
            smallFbo = 0
            smallTexture = 0
            maskTexture = 0
        }

        override fun release() {
            super.release()
            segmenter.close()
            try {
                deleteGlObjects()
                copyProgram.delete()
                blurProgram.delete()
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e)
            }
        }
    }

    private companion object {
        /** Segmentation runs on a copy this big; the model works at 256 px anyway. */
        const val SEGMENT_LONG_SIDE = 256

        const val VERTEX_SHADER = """
            attribute vec4 aFramePosition;
            varying vec2 vTexCoord;
            void main() {
              gl_Position = aFramePosition;
              vTexCoord = aFramePosition.xy * 0.5 + 0.5;
            }
        """

        // Flipped vertically so the read-back rows come out top first, like a Bitmap.
        const val COPY_FRAGMENT_SHADER = """
            precision mediump float;
            uniform sampler2D uTexSampler;
            varying vec2 vTexCoord;
            void main() {
              gl_FragColor = texture2D(uTexSampler, vec2(vTexCoord.x, 1.0 - vTexCoord.y));
            }
        """

        // The mask's first row is the top of the picture, hence the flipped lookup.
        const val BLUR_FRAGMENT_SHADER = """
            precision highp float;
            uniform sampler2D uTexSampler;
            uniform sampler2D uMask;
            uniform vec2 uRadius;
            varying vec2 vTexCoord;
            float subjectAt(vec2 p) {
              return smoothstep(0.35, 0.75, texture2D(uMask, vec2(p.x, 1.0 - p.y)).r);
            }
            void main() {
              vec4 sharp = texture2D(uTexSampler, vTexCoord);
              float subject = subjectAt(vTexCoord);
              if (subject > 0.99) {
                gl_FragColor = sharp;
                return;
              }
              vec4 sum = sharp * (1.0 - subject);
              float total = 1.0 - subject;
              for (int i = 1; i <= 32; i++) {
                float fi = float(i);
                float r = sqrt(fi / 32.0);
                float a = fi * 2.39996;
                vec2 p = clamp(vTexCoord + vec2(cos(a), sin(a)) * r * uRadius, 0.0, 1.0);
                float w = 1.0 - subjectAt(p);
                sum += texture2D(uTexSampler, p) * w;
                total += w;
              }
              vec4 background = total > 0.001 ? sum / total : sharp;
              gl_FragColor = mix(background, sharp, subject);
            }
        """
    }
}
