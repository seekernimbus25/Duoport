package com.dualframe.camera

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import androidx.camera.core.CameraEffect
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.SurfaceRequest
import androidx.core.util.Consumer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Executor

/**
 * Shows the chosen LUT live in the viewfinder. Camera frames arrive on an OpenGL texture, a
 * shader looks each pixel up in a 3D LUT texture, and the result is drawn to the preview.
 * Only the preview goes through here: the recording stays untouched and the same LUT is
 * applied when the videos are made, so the original can always be re-graded.
 */
class LutPreviewEffect(processor: LutPreviewProcessor, onError: Consumer<Throwable>) :
    CameraEffect(CameraEffect.PREVIEW, processor.executor, processor, onError)

class LutPreviewProcessor : SurfaceProcessor {

    private val thread = HandlerThread("lut-preview").apply { start() }
    private val handler = Handler(thread.looper)
    val executor = Executor { handler.post(it) }

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE

    private var program = 0
    private var cameraTexture = 0
    private var lutTexture = 0
    private var lutSize = 0
    private var aPosition = 0
    private var aTexCoord = 0
    private var uTexMatrix = 0
    private var uLutScale = 0
    private var uLutOffset = 0

    private var input: SurfaceTexture? = null
    private val outputs = LinkedHashMap<SurfaceOutput, EGLSurface>()
    private val surfaceMatrix = FloatArray(16)
    private val textureMatrix = FloatArray(16)

    @Volatile private var pendingLut: LutCube? = null
    private var released = false

    init {
        handler.post { setUpGl() }
    }

    /** Swap the LUT; takes effect on the next frame. */
    fun setLut(cube: LutCube) {
        pendingLut = cube
    }

    fun release() {
        handler.post {
            if (released) return@post
            released = true
            outputs.forEach { (output, surface) ->
                EGL14.eglDestroySurface(display, surface)
                output.close()
            }
            outputs.clear()
            input?.release()
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (pbuffer != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, pbuffer)
                EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
            }
            thread.quitSafely()
        }
    }

    // region SurfaceProcessor (called on [executor])

    override fun onInputSurface(request: SurfaceRequest) {
        if (released) {
            request.willNotProvideSurface()
            return
        }
        val texture = SurfaceTexture(cameraTexture)
        texture.setDefaultBufferSize(request.resolution.width, request.resolution.height)
        val surface = Surface(texture)
        request.provideSurface(surface, executor) {
            texture.setOnFrameAvailableListener(null)
            if (input == texture) input = null
            texture.release()
            surface.release()
        }
        texture.setOnFrameAvailableListener({ drawFrame(it) }, handler)
        input = texture
    }

    override fun onOutputSurface(output: SurfaceOutput) {
        if (released) {
            output.close()
            return
        }
        val surface = output.getSurface(executor) {
            outputs.remove(output)?.let { EGL14.eglDestroySurface(display, it) }
            output.close()
        }
        val eglSurface = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
        outputs[output] = eglSurface
    }

    // endregion

    private fun drawFrame(texture: SurfaceTexture) {
        if (released || texture != input) return
        EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)
        texture.updateTexImage()
        texture.getTransformMatrix(surfaceMatrix)
        pendingLut?.let {
            uploadLut(it)
            pendingLut = null
        }
        if (lutTexture == 0) return

        for ((output, eglSurface) in outputs) {
            if (!EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) continue
            output.updateTransformMatrix(textureMatrix, surfaceMatrix)
            GLES30.glViewport(0, 0, output.size.width, output.size.height)
            GLES30.glUseProgram(program)

            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexture)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTexture)
            GLES30.glUniformMatrix4fv(uTexMatrix, 1, false, textureMatrix, 0)
            // Sample LUT texel centres so the ends of the range map exactly.
            GLES30.glUniform1f(uLutScale, (lutSize - 1f) / lutSize)
            GLES30.glUniform1f(uLutOffset, 0.5f / lutSize)

            GLES30.glEnableVertexAttribArray(aPosition)
            GLES30.glVertexAttribPointer(aPosition, 2, GLES30.GL_FLOAT, false, 0, QUAD)
            GLES30.glEnableVertexAttribArray(aTexCoord)
            GLES30.glVertexAttribPointer(aTexCoord, 2, GLES30.GL_FLOAT, false, 0, QUAD_TEX)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

            EGLExt.eglPresentationTimeANDROID(display, eglSurface, texture.timestamp)
            EGL14.eglSwapBuffers(display, eglSurface)
        }
    }

    private fun uploadLut(cube: LutCube) {
        if (lutTexture == 0) {
            val ids = IntArray(1)
            GLES30.glGenTextures(1, ids, 0)
            lutTexture = ids[0]
        }
        val bytes = cube.toGlBytes()
        val buffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes)
        buffer.position(0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTexture)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexImage3D(
            GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB8, cube.size, cube.size, cube.size, 0,
            GLES30.GL_RGB, GLES30.GL_UNSIGNED_BYTE, buffer,
        )
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)
        lutSize = cube.size
    }

    private fun setUpGl() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) && count[0] > 0) {
            "No suitable EGL config"
        }
        config = configs[0]
        context = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0,
        )
        pbuffer = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)

        program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aPosition = GLES30.glGetAttribLocation(program, "aPosition")
        aTexCoord = GLES30.glGetAttribLocation(program, "aTexCoord")
        uTexMatrix = GLES30.glGetUniformLocation(program, "uTexMatrix")
        uLutScale = GLES30.glGetUniformLocation(program, "uLutScale")
        uLutOffset = GLES30.glGetUniformLocation(program, "uLutOffset")
        GLES30.glUseProgram(program)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uCamera"), 0)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uLut"), 1)

        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        cameraTexture = ids[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexture)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
    }

    private fun buildProgram(vertex: String, fragment: String): Int {
        fun compile(type: Int, source: String): Int {
            val shader = GLES30.glCreateShader(type)
            GLES30.glShaderSource(shader, source)
            GLES30.glCompileShader(shader)
            val ok = IntArray(1)
            GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) {
                val log = GLES30.glGetShaderInfoLog(shader)
                Log.e(TAG, "Shader compile failed: $log")
                error("Shader compile failed: $log")
            }
            return shader
        }
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, compile(GLES30.GL_VERTEX_SHADER, vertex))
        GLES30.glAttachShader(p, compile(GLES30.GL_FRAGMENT_SHADER, fragment))
        GLES30.glLinkProgram(p)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "Program link failed: ${GLES30.glGetProgramInfoLog(p)}" }
        return p
    }

    private companion object {
        const val TAG = "LutPreview"
        const val EGL_RECORDABLE_ANDROID = 0x3142

        val QUAD: FloatBuffer = floatBuffer(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        val QUAD_TEX: FloatBuffer = floatBuffer(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)

        fun floatBuffer(vararg values: Float): FloatBuffer =
            ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(values)
                position(0)
            }

        const val VERTEX_SHADER = """#version 300 es
in vec4 aPosition;
in vec4 aTexCoord;
uniform mat4 uTexMatrix;
out vec2 vTexCoord;
void main() {
  gl_Position = aPosition;
  vTexCoord = (uTexMatrix * aTexCoord).xy;
}
"""

        const val FRAGMENT_SHADER = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision mediump float;
uniform samplerExternalOES uCamera;
uniform mediump sampler3D uLut;
uniform float uLutScale;
uniform float uLutOffset;
in vec2 vTexCoord;
out vec4 outColor;
void main() {
  vec3 c = clamp(texture(uCamera, vTexCoord).rgb, 0.0, 1.0);
  outColor = vec4(texture(uLut, c * uLutScale + uLutOffset).rgb, 1.0);
}
"""
    }
}
