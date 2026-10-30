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
 * Shows the chosen LUT live in the viewfinder, plus the monitoring aids a cinema camera has:
 * zebra stripes, focus peaking and false colour. Camera frames arrive on an OpenGL texture, a
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
    private var lutTexture = 0
    private var lutSize = 0
    private var aPosition = 0
    private var aTexCoord = 0
    private var uTexMatrix = 0
    private var uLutScale = 0
    private var uLutOffset = 0
    private var uTexel = 0
    private var uZebra = 0
    private var uPeak = 0
    private var uPeakColor = 0
    private var uFalseColor = 0
    private var inputWidth = 1
    private var inputHeight = 1

    private var input: SurfaceTexture? = null

    /** Each camera input gets its own GL texture, so an old one being torn down never touches the new. */
    private val inputTextures = HashMap<SurfaceTexture, Int>()

    /** When a frame was last drawn to the screen (elapsedRealtime), so a stalled preview can be noticed. */
    @Volatile var lastDrawAt = 0L
        private set
    private val outputs = LinkedHashMap<SurfaceOutput, EGLSurface>()
    private val surfaceMatrix = FloatArray(16)
    private val textureMatrix = FloatArray(16)

    @Volatile private var pendingLut: LutCube? = null
    @Volatile private var monitoring = Monitoring(histogram = false)
    private var released = false

    init {
        handler.post { setUpGl() }
    }

    /** Swap the LUT; takes effect on the next frame. */
    fun setLut(cube: LutCube) {
        pendingLut = cube
    }

    /** Zebras / peaking / false colour; takes effect on the next frame. */
    fun setMonitoring(value: Monitoring) {
        monitoring = value
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
        EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)
        val textureId = createCameraTexture()
        val texture = SurfaceTexture(textureId)
        inputTextures[texture] = textureId
        texture.setDefaultBufferSize(request.resolution.width, request.resolution.height)
        inputWidth = request.resolution.width
        inputHeight = request.resolution.height
        val surface = Surface(texture)
        request.provideSurface(surface, executor) {
            texture.setOnFrameAvailableListener(null)
            if (input == texture) input = null
            texture.release()
            surface.release()
            inputTextures.remove(texture)?.let { id ->
                if (!released) {
                    EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)
                    GLES30.glDeleteTextures(1, intArrayOf(id), 0)
                }
            }
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
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            output.close()
            return
        }
        // Never wait for the screen to take a frame: if the preview surface is being swapped
        // out (camera restarting), a blocking swap would freeze this thread and the preview.
        if (EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) EGL14.eglSwapInterval(display, 0)
        outputs[output] = eglSurface
    }

    // endregion

    private fun drawFrame(texture: SurfaceTexture) {
        if (released || texture != input) return
        val textureId = inputTextures[texture] ?: return
        EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)
        try {
            texture.updateTexImage()
        } catch (e: RuntimeException) {
            // The surface was abandoned between the frame callback and now.
            return
        }
        texture.getTransformMatrix(surfaceMatrix)
        pendingLut?.let {
            uploadLut(it)
            pendingLut = null
        }
        // Never sit on a blank preview waiting for a LUT: draw through a do-nothing one.
        if (lutTexture == 0) uploadLut(Luts.identity)

        for ((output, eglSurface) in outputs) {
            if (!EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) continue
            output.updateTransformMatrix(textureMatrix, surfaceMatrix)
            GLES30.glViewport(0, 0, output.size.width, output.size.height)
            GLES30.glUseProgram(program)

            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTexture)
            GLES30.glUniformMatrix4fv(uTexMatrix, 1, false, textureMatrix, 0)
            // Sample LUT texel centres so the ends of the range map exactly.
            GLES30.glUniform1f(uLutScale, (lutSize - 1f) / lutSize)
            GLES30.glUniform1f(uLutOffset, 0.5f / lutSize)
            val m = monitoring
            GLES30.glUniform2f(uTexel, 1f / inputWidth, 1f / inputHeight)
            GLES30.glUniform1f(uZebra, if (m.zebra > 0) m.zebra.coerceAtMost(99) / 100f else 0f)
            GLES30.glUniform1f(uPeak, when (m.peaking) { 1 -> 0.32f; 2 -> 0.18f; else -> 0f })
            GLES30.glUniform3f(uPeakColor, m.peakColor.r, m.peakColor.g, m.peakColor.b)
            GLES30.glUniform1f(uFalseColor, if (m.falseColor) 1f else 0f)

            GLES30.glEnableVertexAttribArray(aPosition)
            GLES30.glVertexAttribPointer(aPosition, 2, GLES30.GL_FLOAT, false, 0, QUAD)
            GLES30.glEnableVertexAttribArray(aTexCoord)
            GLES30.glVertexAttribPointer(aTexCoord, 2, GLES30.GL_FLOAT, false, 0, QUAD_TEX)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

            EGLExt.eglPresentationTimeANDROID(display, eglSurface, texture.timestamp)
            if (EGL14.eglSwapBuffers(display, eglSurface)) lastDrawAt = android.os.SystemClock.elapsedRealtime()
        }
    }

    private fun createCameraTexture(): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ids[0])
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        return ids[0]
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
        uTexel = GLES30.glGetUniformLocation(program, "uTexel")
        uZebra = GLES30.glGetUniformLocation(program, "uZebra")
        uPeak = GLES30.glGetUniformLocation(program, "uPeak")
        uPeakColor = GLES30.glGetUniformLocation(program, "uPeakColor")
        uFalseColor = GLES30.glGetUniformLocation(program, "uFalseColor")
        GLES30.glUseProgram(program)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uCamera"), 0)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uLut"), 1)
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

        // Zebras and false colour judge the camera's own signal (before the LUT), like a
        // camera monitor. Peaking uses a Sobel edge filter on brightness.
        const val FRAGMENT_SHADER = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision mediump float;
uniform samplerExternalOES uCamera;
uniform mediump sampler3D uLut;
uniform float uLutScale;
uniform float uLutOffset;
uniform vec2 uTexel;
uniform float uZebra;
uniform float uPeak;
uniform vec3 uPeakColor;
uniform float uFalseColor;
in vec2 vTexCoord;
out vec4 outColor;

float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }
float lumaAt(vec2 offset) { return luma(texture(uCamera, vTexCoord + offset * uTexel).rgb); }

vec3 falseColor(float y) {
  if (y < 0.025) return vec3(0.45, 0.0, 0.65);           // crushed black
  if (y < 0.10) return vec3(0.05, 0.3, 1.0);             // deep shadow
  if (y > 0.38 && y < 0.44) return vec3(0.15, 0.8, 0.25); // middle grey
  if (y > 0.55 && y < 0.62) return vec3(1.0, 0.55, 0.68); // skin
  if (y > 0.97) return vec3(1.0, 0.12, 0.12);            // clipping
  if (y > 0.93) return vec3(1.0, 0.88, 0.15);            // about to clip
  return vec3(y * 0.85);
}

void main() {
  vec3 cam = clamp(texture(uCamera, vTexCoord).rgb, 0.0, 1.0);
  float y = luma(cam);
  vec3 c = uFalseColor > 0.5 ? falseColor(y) : texture(uLut, cam * uLutScale + uLutOffset).rgb;

  if (uPeak > 0.0) {
    float tl = lumaAt(vec2(-1.0, -1.0)); float t = lumaAt(vec2(0.0, -1.0)); float tr = lumaAt(vec2(1.0, -1.0));
    float l = lumaAt(vec2(-1.0, 0.0));                                       float r = lumaAt(vec2(1.0, 0.0));
    float bl = lumaAt(vec2(-1.0, 1.0)); float b = lumaAt(vec2(0.0, 1.0));  float br = lumaAt(vec2(1.0, 1.0));
    float gx = (tr + 2.0 * r + br) - (tl + 2.0 * l + bl);
    float gy = (bl + 2.0 * b + br) - (tl + 2.0 * t + tr);
    if (length(vec2(gx, gy)) > uPeak) c = mix(c, uPeakColor, 0.85);
  }

  if (uZebra > 0.0 && y >= uZebra) {
    float stripe = mod(gl_FragCoord.x + gl_FragCoord.y, 16.0);
    c = stripe < 6.0 ? mix(c, vec3(1.0), 0.8) : mix(c, vec3(0.0), 0.55);
  }
  outColor = vec4(c, 1.0);
}
"""
    }
}
