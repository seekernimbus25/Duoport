package com.dualframe.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.opengl.GLES20
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.effect.OverlaySettings
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Turns the video into the design's canvas: draws the background (colour, gradient, photo, or
 * a blurred copy of the video), then the video itself placed, sized, rotated and shaped (rounded,
 * circle, heart, arch, with a border) — or just the person, cut out with on-device segmentation.
 * Reads [design] every frame, so changes in the editor show up live.
 */
@OptIn(UnstableApi::class)
class DesignEffect(private val design: Design) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = Program(context.applicationContext, design, useHdr)

    private class Program(private val context: Context, private val design: Design, useHdr: Boolean) :
        BaseGlShaderProgram(/* useHighPrecisionColorComponents= */ useHdr, /* texturePoolCapacity= */ 1) {

        private val bgProgram = program(BG_FRAGMENT)
        private val videoProgram = program(VIDEO_FRAGMENT)
        private val copyProgram = program(COPY_FRAGMENT)
        private val bgVideoProgram = program(BG_VIDEO_FRAGMENT)
        private var bgVideo: BackgroundVideo? = null
        /** A background video that couldn't be opened, so we don't retry it every frame. */
        private var failedBgVideo: android.net.Uri? = null
        private var inW = 1
        private var inH = 1
        private var outW = 1
        private var outH = 1
        private var bgTexture = C.INDEX_UNSET
        private var uploadedBg: Bitmap? = null

        // Person cutout: a small copy is read back and segmented every frame; the shader then
        // snaps the mask's edge to the full-size picture.
        private var personMask: PersonMask? = null
        private var smallTex = 0
        private var smallFbo = 0
        private var maskTex = 0
        private val smallW = PersonMask.INPUT_WIDTH
        private var smallH = PersonMask.INPUT_WIDTH
        private var maskW = 1
        private var maskH = 1
        private var pixels: ByteBuffer = ByteBuffer.allocateDirect(4)
        private var maskBytes: ByteBuffer = ByteBuffer.allocateDirect(1)
        /** Last frame's mask, blended in a little so the edge doesn't flicker frame to frame. */
        private var previous: FloatArray? = null
        /** Frame time of the last mask; the model runs every [MASK_EVERY_US], the mask is reused between. */
        private var maskTimeUs = Long.MIN_VALUE

        private fun program(fragment: String): GlProgram = try {
            GlProgram(VERTEX, fragment)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            inW = inputWidth
            inH = inputHeight
            val (w, h) = design.canvasSize
            outW = w
            outH = h
            return Size(w, h)
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            try {
                val fbo = IntArray(1)
                GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, fbo, 0)
                val viewport = IntArray(4)
                GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, viewport, 0)
                val placement = design.video
                // The model takes ~0.4 s a frame on the CPU, so it runs about 10× a second of video
                // (and after any jump, e.g. a seek); frames in between reuse the last mask.
                if (placement.cutout) {
                    val gap = presentationTimeUs - maskTimeUs
                    if (maskTex == 0 || gap < 0 || gap >= MASK_EVERY_US) {
                        segment(inputTexId)
                        maskTimeUs = presentationTimeUs
                    }
                }
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0])
                GLES20.glViewport(viewport[0], viewport[1], viewport[2], viewport[3])

                if (!drawVideoBackground(presentationTimeUs)) drawBackground(inputTexId)

                // The video as a quad: fitted to the canvas, then scaled, moved and rotated.
                val inAspect = inW.toFloat() / inH
                val canvasAspect = outW.toFloat() / outH
                val baseW = if (inAspect > canvasAspect) outW.toFloat() else outH * inAspect
                val w = baseW * placement.scale
                val h = w / inAspect
                val cx = placement.x * outW
                val cy = placement.y * outH
                val a = Math.toRadians(placement.rotation.toDouble())
                val ca = cos(a).toFloat()
                val sa = sin(a).toFloat()
                // Strip order: bottom-left, bottom-right, top-left, top-right (y down in canvas pixels).
                val corners = listOf(-w / 2 to h / 2, w / 2 to h / 2, -w / 2 to -h / 2, w / 2 to -h / 2)
                val positions = FloatArray(16)
                corners.forEachIndexed { i, (dx, dy) ->
                    val px = cx + dx * ca - dy * sa
                    val py = cy + dx * sa + dy * ca
                    positions[i * 4] = px / outW * 2f - 1f
                    positions[i * 4 + 1] = 1f - py / outH * 2f
                    positions[i * 4 + 2] = 0f
                    positions[i * 4 + 3] = 1f
                }
                GLES20.glEnable(GLES20.GL_BLEND)
                GLES20.glBlendFuncSeparate(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA, GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
                videoProgram.use()
                videoProgram.setBufferAttribute("aFramePosition", positions, 4)
                videoProgram.setBufferAttribute("aTexCoord", TEX_COORDS, 4)
                videoProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
                videoProgram.setSamplerTexIdUniform("uMaskSampler", if (placement.cutout && maskTex != 0) maskTex else inputTexId, 1)
                videoProgram.setFloatUniform("uShape", placement.mask.code)
                videoProgram.setFloatUniform("uAspect", w / h)
                videoProgram.setFloatUniform("uRadius", placement.corner / 100f)
                videoProgram.setFloatUniform("uBorder", placement.border / 1000f)
                val bc = placement.borderColor
                videoProgram.setFloatsUniform("uBorderColor", floatArrayOf(Color.red(bc) / 255f, Color.green(bc) / 255f, Color.blue(bc) / 255f))
                videoProgram.setFloatUniform("uCutout", if (placement.cutout && maskTex != 0) 1f else 0f)
                videoProgram.setFloatsUniform("uMaskSize", floatArrayOf(maskW.toFloat(), maskH.toFloat()))
                videoProgram.setFloatUniform("uPixel", 1.5f / h)
                videoProgram.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                GLES20.glDisable(GLES20.GL_BLEND)
                GlUtil.checkGlError()
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e, presentationTimeUs)
            } catch (e: RuntimeException) {
                throw VideoFrameProcessingException(e, presentationTimeUs)
            }
        }

        private fun drawBackground(inputTexId: Int) {
            val bitmap = design.backgroundBitmap
            val blur = design.background == BackgroundKind.BLUR || bitmap == null
            if (!blur && bitmap != null && bitmap !== uploadedBg) {
                if (bgTexture == C.INDEX_UNSET) bgTexture = GlUtil.generateTexture()
                GlUtil.setTexture(bgTexture, bitmap)
                uploadedBg = bitmap
            }
            bgProgram.use()
            bgProgram.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), 4)
            bgProgram.setBufferAttribute("aTexCoord", TEX_COORDS, 4)
            bgProgram.setSamplerTexIdUniform("uTexSampler", if (blur) inputTexId else bgTexture, 0)
            bgProgram.setFloatUniform("uBlur", if (blur) 1f else 0f)
            // Cover-fit the video into the canvas for the blurred background.
            val inAspect = inW.toFloat() / inH
            val canvasAspect = outW.toFloat() / outH
            val sx = if (inAspect > canvasAspect) canvasAspect / inAspect else 1f
            val sy = if (inAspect > canvasAspect) 1f else inAspect / canvasAspect
            bgProgram.setFloatsUniform("uCover", floatArrayOf(sx, sy))
            bgProgram.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        /** The design's background video, if that's the background; false to fall back. */
        private fun drawVideoBackground(presentationTimeUs: Long): Boolean {
            val uri = design.bgVideo
            if (design.background != BackgroundKind.VIDEO || uri == null) {
                bgVideo?.release()
                bgVideo = null
                return false
            }
            if (bgVideo?.uri != uri) {
                bgVideo?.release()
                bgVideo = null
                if (uri == failedBgVideo) return false
                bgVideo = runCatching { BackgroundVideo(context, uri) }.getOrElse {
                    failedBgVideo = uri
                    return false
                }
            }
            val video = bgVideo ?: return false
            runCatching { video.update(presentationTimeUs) }
            if (!video.hasFrame) return false
            bgVideoProgram.use()
            bgVideoProgram.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), 4)
            bgVideoProgram.setBufferAttribute("aTexCoord", TEX_COORDS, 4)
            bgVideoProgram.setSamplerTexIdUniform("uTexSampler", video.textureId, 0)
            bgVideoProgram.setFloatsUniform("uTransform", video.transform)
            bgVideoProgram.setFloatUniform("uRotation", video.rotation.toFloat())
            bgVideoProgram.setFloatUniform("uBlur", if (design.bgVideoBlur) 1f else 0f)
            val bgAspect = video.width.toFloat() / video.height
            val canvasAspect = outW.toFloat() / outH
            val sx = if (bgAspect > canvasAspect) canvasAspect / bgAspect else 1f
            val sy = if (bgAspect > canvasAspect) 1f else bgAspect / canvasAspect
            bgVideoProgram.setFloatsUniform("uCover", floatArrayOf(sx, sy))
            bgVideoProgram.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            return true
        }

        /** Person mask for the cutout: small upright copy -> read back -> segmentation model. */
        private fun segment(inputTexId: Int) {
            if (smallFbo == 0) {
                smallH = (smallW * inH / inW.toFloat()).roundToInt().coerceAtLeast(2)
                val ids = IntArray(2)
                GLES20.glGenTextures(2, ids, 0)
                smallTex = ids[0]
                maskTex = ids[1]
                for (t in ids) {
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                }
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, smallTex)
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, smallW, smallH, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
                val f = IntArray(1)
                GLES20.glGenFramebuffers(1, f, 0)
                smallFbo = f[0]
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, smallFbo)
                GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, smallTex, 0)
                pixels = ByteBuffer.allocateDirect(smallW * smallH * 4).order(ByteOrder.nativeOrder())
                personMask = PersonMask(context)
            }
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, smallFbo)
            GLES20.glViewport(0, 0, smallW, smallH)
            copyProgram.use()
            copyProgram.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), 4)
            copyProgram.setBufferAttribute("aTexCoord", TEX_COORDS, 4)
            copyProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            copyProgram.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            pixels.rewind()
            GLES20.glReadPixels(0, 0, smallW, smallH, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
            val bitmap = Bitmap.createBitmap(smallW, smallH, Bitmap.Config.ARGB_8888)
            pixels.rewind()
            bitmap.copyPixelsFromBuffer(pixels)
            val mask = personMask!!.segment(bitmap)
            bitmap.recycle()
            val mw = mask.width
            val mh = mask.height
            val prev = previous?.takeIf { it.size == mw * mh }
            if (prev != null) for (i in mask.values.indices) mask.values[i] = mask.values[i] * (1f - CARRY) + prev[i] * CARRY
            previous = mask.values
            maskW = mw
            maskH = mh
            if (maskBytes.capacity() < mw * mh) maskBytes = ByteBuffer.allocateDirect(mw * mh)
            maskBytes.clear()
            for (v in mask.values) maskBytes.put((v.coerceIn(0f, 1f) * 255f).toInt().toByte())
            maskBytes.rewind()
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTex)
            GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, mw, mh, 0, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, maskBytes)
        }

        override fun release() {
            super.release()
            personMask?.close()
            try {
                bgVideo?.release()
                bgVideo = null
                bgVideoProgram.delete()
                bgProgram.delete()
                videoProgram.delete()
                copyProgram.delete()
                if (bgTexture != C.INDEX_UNSET) GlUtil.deleteTexture(bgTexture)
                if (smallFbo != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(smallFbo), 0)
                if (smallTex != 0) GLES20.glDeleteTextures(2, intArrayOf(smallTex, maskTex), 0)
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e)
            }
        }
    }

    companion object {
        /** How much of last frame's mask carries into this one (less flicker, little lag). */
        private const val CARRY = 0.3f

        /** How often the cutout model runs, in video time: 10 masks a second. */
        private const val MASK_EVERY_US = 95_000L

        /** Matches the strip order of [GlUtil.getNormalizedCoordinateBounds]. */
        private val TEX_COORDS = floatArrayOf(0f, 0f, 0f, 1f, 1f, 0f, 0f, 1f, 0f, 1f, 0f, 1f, 1f, 1f, 0f, 1f)

        private const val VERTEX = """
            attribute vec4 aFramePosition;
            attribute vec4 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
              gl_Position = aFramePosition;
              vTexCoord = aTexCoord.xy;
            }
        """

        // Backgrounds are Android bitmaps (first row = top), hence the flip; the blurred video
        // is cover-fitted and blurred hard, then dimmed so the real video stands out.
        private const val BG_FRAGMENT = """
            precision mediump float;
            uniform sampler2D uTexSampler;
            uniform float uBlur;
            uniform vec2 uCover;
            varying vec2 vTexCoord;
            void main() {
              if (uBlur < 0.5) {
                gl_FragColor = vec4(texture2D(uTexSampler, vec2(vTexCoord.x, 1.0 - vTexCoord.y)).rgb, 1.0);
                return;
              }
              vec2 uv = (vTexCoord - 0.5) * uCover + 0.5;
              vec3 sum = vec3(0.0);
              for (int i = 0; i < 24; i++) {
                float fi = float(i);
                float r = sqrt(fi / 24.0) * 0.06;
                float a = fi * 2.39996;
                sum += texture2D(uTexSampler, clamp(uv + vec2(cos(a), sin(a)) * r, 0.0, 1.0)).rgb;
              }
              gl_FragColor = vec4(sum / 24.0 * 0.7, 1.0);
            }
        """

        // The background video, straight from its decoder: upright it (the decoder hands over
        // frames as stored), cover-fit it to the canvas, optionally blur and dim it.
        private const val BG_VIDEO_FRAGMENT = """#extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTexSampler;
            uniform mat4 uTransform;
            uniform float uRotation;
            uniform float uBlur;
            uniform vec2 uCover;
            varying vec2 vTexCoord;
            vec3 sampleAt(vec2 d) {
              d = clamp(d, 0.0, 1.0);
              vec2 s = d;
              if (uRotation > 45.0 && uRotation < 135.0) s = vec2(1.0 - d.y, d.x);
              else if (uRotation >= 135.0 && uRotation < 225.0) s = vec2(1.0 - d.x, 1.0 - d.y);
              else if (uRotation >= 225.0) s = vec2(d.y, 1.0 - d.x);
              return texture2D(uTexSampler, (uTransform * vec4(s, 0.0, 1.0)).xy).rgb;
            }
            void main() {
              vec2 uv = (vTexCoord - 0.5) * uCover + 0.5;
              if (uBlur < 0.5) {
                gl_FragColor = vec4(sampleAt(uv), 1.0);
                return;
              }
              vec3 sum = vec3(0.0);
              for (int i = 0; i < 24; i++) {
                float fi = float(i);
                float r = sqrt(fi / 24.0) * 0.05;
                float a = fi * 2.39996;
                sum += sampleAt(uv + vec2(cos(a), sin(a)) * r);
              }
              gl_FragColor = vec4(sum / 24.0 * 0.8, 1.0);
            }
        """

        private const val COPY_FRAGMENT = """
            precision mediump float;
            uniform sampler2D uTexSampler;
            varying vec2 vTexCoord;
            void main() {
              gl_FragColor = texture2D(uTexSampler, vec2(vTexCoord.x, 1.0 - vTexCoord.y));
            }
        """

        // Shapes are signed distance fields in the quad's own space (units of its height).
        private const val VIDEO_FRAGMENT = """
            precision highp float;
            uniform sampler2D uTexSampler;
            uniform sampler2D uMaskSampler;
            uniform float uShape;
            uniform float uAspect;
            uniform float uRadius;
            uniform float uBorder;
            uniform vec3 uBorderColor;
            uniform float uCutout;
            uniform float uPixel;
            uniform vec2 uMaskSize;
            varying vec2 vTexCoord;
            // The cutout mask is coarse (256 px wide). Each pixel averages the 5×5 mask texels
            // around it, weighted by distance and by how alike the picture's colour is there, so
            // the edge follows the real outline of hair and clothes (joint bilateral upsampling;
            // PersonMask.refine is the same maths for photos). The mask's first row is the top.
            float personAt(vec2 uv, vec3 here) {
              vec2 m = vec2(uv.x, 1.0 - uv.y) * uMaskSize - 0.5;
              vec2 base = floor(m + 0.5);
              float acc = 0.0;
              float sum = 0.0;
              for (int dy = -2; dy <= 2; dy++) {
                for (int dx = -2; dx <= 2; dx++) {
                  vec2 t = base + vec2(float(dx), float(dy));
                  vec2 tuv = (t + 0.5) / uMaskSize;
                  float mv = texture2D(uMaskSampler, tuv).r;
                  vec3 c = texture2D(uTexSampler, vec2(tuv.x, 1.0 - tuv.y)).rgb - here;
                  vec2 d = t - m;
                  float w = exp(-dot(d, d) / 2.88 - dot(c, c) / 0.02);
                  acc += w * mv;
                  sum += w;
                }
              }
              return acc / max(sum, 1e-5);
            }
            float roundBox(vec2 p, vec2 b, float r) {
              vec2 q = abs(p) - b + r;
              return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
            }
            float dot2(vec2 v) { return dot(v, v); }
            float heart(vec2 p) {
              p.x = abs(p.x);
              if (p.y + p.x > 1.0) return sqrt(dot2(p - vec2(0.25, 0.75))) - sqrt(2.0) / 4.0;
              return sqrt(min(dot2(p - vec2(0.0, 1.0)), dot2(p - 0.5 * max(p.x + p.y, 0.0)))) * sign(p.x - p.y);
            }
            void main() {
              vec2 p = (vTexCoord - 0.5) * vec2(uAspect, 1.0);
              vec2 hs = vec2(uAspect * 0.5, 0.5);
              float side = min(hs.x, hs.y);
              float box = roundBox(p, hs, 0.0);
              float d;
              if (uShape < 0.5) d = box;
              else if (uShape < 1.5) d = roundBox(p, hs, uRadius * side * 2.0);
              else if (uShape < 2.5) d = length(p) - side;
              else if (uShape < 3.5) d = heart(vec2(p.x, p.y + side * 0.9) / (side * 1.6)) * side * 1.6;
              else {
                // Arch: a rectangle with a round top.
                vec2 c0 = vec2(0.0, hs.y - hs.x);
                d = p.y > c0.y ? max(length(p - c0) - hs.x, box) : box;
              }
              float alpha = 1.0 - smoothstep(-uPixel, uPixel, d);
              vec4 c = texture2D(uTexSampler, vTexCoord);
              if (uCutout > 0.5) {
                alpha *= smoothstep(0.3, 0.7, personAt(vTexCoord, c.rgb));
              } else if (uBorder > 0.0 && d > -uBorder * side * 2.0) {
                c.rgb = uBorderColor;
              }
              gl_FragColor = vec4(c.rgb, alpha);
            }
        """
    }
}

/**
 * A text / sticker / photo layer burned into the video, following the layer's live position,
 * size, rotation and opacity, shown between its start and end with its animation.
 * [offsetUs] shifts the layer's times when an intro card plays first.
 */
@OptIn(UnstableApi::class)
class LayerOverlay(
    private val layer: Layer,
    private val offsetUs: Long = 0,
    /** Timeline time in ms for a frame; the editor preview passes its own clock. */
    private val clockMs: ((Long) -> Long)? = null,
) : BitmapOverlay() {
    private fun ms(presentationTimeUs: Long) = clockMs?.invoke(presentationTimeUs) ?: ((presentationTimeUs - offsetUs) / 1000)

    private val empty = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val frames = layer.frames
        if (frames != null && frames.frames.size > 1) return frames.at(ms(presentationTimeUs) - layer.startMs)
        return layer.bitmap ?: empty
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
        val ms = ms(presentationTimeUs)
        val start = layer.startMs
        val end = layer.endMs
        val visible = ms in start until end
        val sinceStart = (ms - start).coerceAtLeast(0)
        val untilEnd = (end - ms).coerceAtLeast(0)
        val edge = min(1f, min(sinceStart, untilEnd) / 300f)
        var alpha = if (visible) layer.opacity else 0f
        var scale = layer.scale
        var y = layer.y
        var rotation = layer.rotation
        val t = sinceStart / 1000f
        val tau = (2 * Math.PI).toFloat()
        when (layer.motion) {
            LayerMotion.FLOAT -> y += 0.012f * sin(tau * t / 2.4f)
            LayerMotion.PULSE -> scale *= 1f + 0.07f * sin(tau * t / 1.1f)
            LayerMotion.WIGGLE -> rotation += 9f * sin(tau * t / 0.7f)
            LayerMotion.BOUNCE -> y -= 0.025f * kotlin.math.abs(sin(tau * t / 1.4f))
            LayerMotion.SPIN -> rotation += 360f * t / 3f
            LayerMotion.NONE -> Unit
        }
        when (layer.animation) {
            LayerAnimation.FADE -> alpha *= edge
            LayerAnimation.POP -> scale *= 0.6f + 0.4f * max(0f, min(1f, sinceStart / 220f))
            LayerAnimation.SLIDE_UP -> {
                alpha *= edge
                y += 0.06f * (1f - edge)
            }
            LayerAnimation.NONE -> Unit
        }
        return OverlaySettings.Builder()
            .setBackgroundFrameAnchor(layer.x * 2f - 1f, 1f - y * 2f)
            .setScale(scale, scale)
            .setRotationDegrees(-rotation)
            .setAlphaScale(alpha.coerceIn(0f, 1f))
            .build()
    }
}
