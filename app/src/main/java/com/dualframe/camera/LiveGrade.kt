package com.dualframe.camera

import android.content.Context
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
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The grade for a preview that changes while it plays: the effect is built once and reads the
 * latest LUT and finish from here on every frame. Sending the player a new effect list instead
 * rebuilds its whole pipeline (in the editor that includes the cutout model), and a slider drag
 * sends dozens of those a second, which stalled playback.
 */
class LiveGrade {
    @Volatile var cube: LutCube = LutCube.IDENTITY
        private set
    @Volatile var grade: ColorGrade = ColorGrade()
        private set
    @Volatile var version = 0
        private set

    fun set(cube: LutCube?, grade: ColorGrade) {
        this.cube = cube ?: LutCube.IDENTITY
        this.grade = grade
        version++
    }

    /** LUT then vignette, grain and sharpening, in one pass. */
    @OptIn(UnstableApi::class)
    fun effect(): GlEffect = GlEffect { _, useHdr -> Program(this, useHdr) }

    @OptIn(UnstableApi::class)
    private class Program(private val live: LiveGrade, useHdr: Boolean) :
        BaseGlShaderProgram(/* useHighPrecisionColorComponents= */ useHdr, /* texturePoolCapacity= */ 1) {

        private val program: GlProgram = try {
            GlProgram(GradeFinishEffect.VERTEX, FRAGMENT).apply {
                setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
            }
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
        private var lutTexture = 0
        private var lutSize = 0
        private var uploaded = -1
        private var width = 1
        private var height = 1

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            width = inputWidth
            height = inputHeight
            return Size(inputWidth, inputHeight)
        }

        /**
         * The cube as a 2D texture (OpenGL ES 2 has no 3D ones): blue slices side by side, each
         * slice red across and green down, so it's [size]² wide and [size] high.
         */
        private fun upload(cube: LutCube) {
            val n = cube.size
            val bytes = ByteBuffer.allocateDirect(n * n * n * 4).order(ByteOrder.nativeOrder())
            for (g in 0 until n) for (b in 0 until n) for (r in 0 until n) {
                val i = ((r * n + g) * n + b) * 3
                for (c in 0 until 3) bytes.put((cube.rgb[i + c] * 255f + 0.5f).toInt().coerceIn(0, 255).toByte())
                bytes.put(-1)
            }
            bytes.position(0)
            if (lutTexture == 0) {
                val ids = IntArray(1)
                GLES20.glGenTextures(1, ids, 0)
                lutTexture = ids[0]
            }
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTexture)
            GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, n * n, n, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bytes)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            lutSize = n
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            try {
                val version = live.version
                val grade = live.grade
                if (version != uploaded) {
                    upload(live.cube)
                    uploaded = version
                }
                program.use()
                program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
                program.setSamplerTexIdUniform("uLut", lutTexture, 1)
                program.setFloatUniform("uLutSize", lutSize.toFloat())
                program.setFloatsUniform("uTexel", floatArrayOf(1f / width, 1f / height))
                program.setFloatUniform("uVignette", grade.vignette / 100f)
                program.setFloatUniform("uGrain", grade.grain / 100f)
                program.setFloatUniform("uSharpen", grade.sharpen / 100f)
                program.setFloatUniform("uSeed", (presentationTimeUs % 100_000L) / 100_000f)
                program.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                GlUtil.checkGlError()
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e, presentationTimeUs)
            }
        }

        override fun release() {
            super.release()
            try {
                program.delete()
                if (lutTexture != 0) GlUtil.deleteTexture(lutTexture)
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e)
            }
        }
    }

    companion object {
        /** GradeFinishEffect's shader with the LUT in front (on the neighbours too, for sharpening). */
        const val FRAGMENT = """
            precision highp float;
            uniform sampler2D uTexSampler;
            uniform sampler2D uLut;
            uniform float uLutSize;
            uniform vec2 uTexel;
            uniform float uVignette;
            uniform float uGrain;
            uniform float uSharpen;
            uniform float uSeed;
            varying vec2 vTexCoord;
            float rand(vec2 co) { return fract(sin(dot(co, vec2(12.9898, 78.233))) * 43758.5453); }
            vec3 lut(vec3 c) {
              c = clamp(c, 0.0, 1.0);
              float n = uLutSize;
              float slice = c.b * (n - 1.0);
              float s0 = floor(slice);
              float s1 = min(s0 + 1.0, n - 1.0);
              float x = c.r * (n - 1.0) + 0.5;
              float y = (c.g * (n - 1.0) + 0.5) / n;
              vec3 a = texture2D(uLut, vec2((s0 * n + x) / (n * n), y)).rgb;
              vec3 b = texture2D(uLut, vec2((s1 * n + x) / (n * n), y)).rgb;
              return mix(a, b, slice - s0);
            }
            vec3 at(vec2 uv) { return lut(texture2D(uTexSampler, uv).rgb); }
            void main() {
              vec4 src = texture2D(uTexSampler, vTexCoord);
              vec3 c = lut(src.rgb);
              if (uSharpen > 0.0) {
                vec3 blur = (at(vTexCoord + vec2(uTexel.x, 0.0)) + at(vTexCoord - vec2(uTexel.x, 0.0))
                  + at(vTexCoord + vec2(0.0, uTexel.y)) + at(vTexCoord - vec2(0.0, uTexel.y))) * 0.25;
                c = c + (c - blur) * uSharpen * 1.5;
              }
              if (uVignette != 0.0) {
                vec2 d = vTexCoord - 0.5;
                float v = smoothstep(0.85, 0.25, length(d * vec2(1.0, 1.0)));
                c = mix(c, c * v, uVignette);
              }
              if (uGrain > 0.0) {
                float n = rand(vTexCoord * 1000.0 + uSeed) - 0.5;
                c += n * uGrain * 0.12;
              }
              gl_FragColor = vec4(clamp(c, 0.0, 1.0), src.a);
            }
        """
    }
}
