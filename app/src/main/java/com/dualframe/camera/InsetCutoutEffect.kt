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

/**
 * Cuts a transparent window into the main camera's video where the inset goes, with a thin
 * white frame around it.
 *
 * Media3's compositor puts whichever input finishes loading first on top, which isn't
 * predictable. With this window the inset shows through whether it is drawn above or below
 * the main video, so it lands in the chosen corner every time.
 *
 * [rect] is in normalized output coordinates (0..1, top-left origin).
 */
@OptIn(UnstableApi::class)
class InsetCutoutEffect(private val rect: NormRect, private val borderPx: Float) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        Program(rect, borderPx, useHdr)

    private class Program(
        private val rect: NormRect,
        private val borderPx: Float,
        useHdr: Boolean,
    ) : BaseGlShaderProgram(/* useHighPrecisionColorComponents= */ useHdr, /* texturePoolCapacity= */ 1) {

        private val glProgram: GlProgram = try {
            GlProgram(VERTEX_SHADER, FRAGMENT_SHADER).apply {
                setBufferAttribute(
                    "aFramePosition",
                    GlUtil.getNormalizedCoordinateBounds(),
                    GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
                )
            }
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }

        private var width = 1
        private var height = 1

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            width = inputWidth
            height = inputHeight
            return Size(inputWidth, inputHeight)
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            try {
                // Texture coordinates have their origin at the bottom-left.
                val inner = floatArrayOf(rect.left, 1f - rect.bottom, rect.right, 1f - rect.top)
                val bx = borderPx / width
                val by = borderPx / height
                val outer = floatArrayOf(inner[0] - bx, inner[1] - by, inner[2] + bx, inner[3] + by)
                glProgram.use()
                glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, /* texUnitIndex= */ 0)
                glProgram.setFloatsUniform("uInner", inner)
                glProgram.setFloatsUniform("uOuter", outer)
                glProgram.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, /* first= */ 0, /* count= */ 4)
                GlUtil.checkGlError()
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e, presentationTimeUs)
            }
        }

        override fun release() {
            super.release()
            try {
                glProgram.delete()
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e)
            }
        }
    }

    private companion object {
        const val VERTEX_SHADER = """
            attribute vec4 aFramePosition;
            varying vec2 vTexCoord;
            void main() {
              gl_Position = aFramePosition;
              vTexCoord = aFramePosition.xy * 0.5 + 0.5;
            }
        """

        const val FRAGMENT_SHADER = """
            precision mediump float;
            uniform sampler2D uTexSampler;
            uniform vec4 uInner; // left, bottom, right, top
            uniform vec4 uOuter;
            varying vec2 vTexCoord;
            bool inside(vec4 r, vec2 p) {
              return p.x >= r.x && p.x <= r.z && p.y >= r.y && p.y <= r.w;
            }
            void main() {
              if (inside(uInner, vTexCoord)) {
                gl_FragColor = vec4(0.0, 0.0, 0.0, 0.0);
              } else if (inside(uOuter, vTexCoord)) {
                gl_FragColor = vec4(1.0, 1.0, 1.0, 1.0);
              } else {
                gl_FragColor = texture2D(uTexSampler, vTexCoord);
              }
            }
        """
    }
}
