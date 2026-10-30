package com.dualframe.camera

import android.graphics.Bitmap
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
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.Segmenter
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
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

    // Live cinematic blur: a small copy of each frame is read back and segmented off the GL
    // thread; the newest person mask is used to blur the background as frames are drawn.
    @Volatile private var cinematic = 0
    private var segmenter: Segmenter? = null
    private var copyProgram = 0
    private var copyTexMatrix = 0
    private var copyFlip = 0
    private var smallFbo = 0
    private var smallTexture = 0
    private var maskTexture = 0
    private var smallW = 0
    private var smallH = 0
    private var readBuffer: ByteBuffer? = null
    private var segBitmap: Bitmap? = null
    private var hasMask = false
    private var lastSegmentAt = 0L
    @Volatile private var segmenting = false
    @Volatile private var pendingMask: Triple<ByteBuffer, Int, Int>? = null
    private var uMask = 0
    private var uBlur = 0
    private var uOutSize = 0

    @Volatile private var finish = ColorGrade()
    private var uVignette = 0
    private var uGrain = 0
    private var uSharpen = 0
    private var uSeed = 0

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

    /** Vignette, grain and sharpening from the colour grade. */
    fun setFinish(grade: ColorGrade) {
        finish = grade
    }

    /** Cinematic background blur strength for the live picture, 0 = off. */
    fun setCinematic(strength: Int) {
        cinematic = strength
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
            segmenter?.close()
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

        val strength = cinematic
        if (strength > 0) {
            outputs.keys.firstOrNull()?.let { segmentFrame(textureId, it) }
            pendingMask?.let { (buffer, w, h) ->
                pendingMask = null
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, maskTexture)
                GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
                GLES30.glTexImage2D(
                    GLES30.GL_TEXTURE_2D, 0, GLES30.GL_LUMINANCE, w, h, 0,
                    GLES30.GL_LUMINANCE, GLES30.GL_UNSIGNED_BYTE, buffer,
                )
                hasMask = true
            }
        } else {
            hasMask = false
        }
        val blur = if (strength > 0 && hasMask) CinematicBlur.radiusFraction(strength) else 0f

        for ((output, eglSurface) in outputs) {
            if (!EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) continue
            output.updateTransformMatrix(textureMatrix, surfaceMatrix)
            GLES30.glViewport(0, 0, output.size.width, output.size.height)
            GLES30.glUseProgram(program)

            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTexture)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, maskTexture)
            GLES30.glUniform1f(uBlur, blur)
            val f = finish
            GLES30.glUniform1f(uVignette, f.vignette / 100f)
            GLES30.glUniform1f(uGrain, f.grain / 100f)
            GLES30.glUniform1f(uSharpen, f.sharpen / 100f)
            GLES30.glUniform1f(uSeed, (texture.timestamp / 1_000_000L % 1000L) / 1000f)
            GLES30.glUniform2f(uOutSize, output.size.width.toFloat(), output.size.height.toFloat())
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

    /**
     * Every ~70 ms (and only when the last one is done): draw the frame small and upright the
     * way [output] shows it, read it back and hand it to the person segmentation model.
     */
    private fun segmentFrame(textureId: Int, output: SurfaceOutput) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (segmenting || now - lastSegmentAt < 70) return
        lastSegmentAt = now
        val outW = output.size.width
        val outH = output.size.height
        val scale = 256f / maxOf(outW, outH)
        val w = maxOf(2, (outW * scale).toInt())
        val h = maxOf(2, (outH * scale).toInt())
        if (w != smallW || h != smallH) {
            smallW = w
            smallH = h
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, smallTexture)
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
            readBuffer = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
            segBitmap?.recycle()
            segBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        }
        output.updateTransformMatrix(textureMatrix, surfaceMatrix)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, smallFbo)
        GLES30.glViewport(0, 0, w, h)
        GLES30.glUseProgram(copyProgram)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES30.glUniformMatrix4fv(copyTexMatrix, 1, false, textureMatrix, 0)
        // Flipped so the read-back rows come out top first, like a Bitmap.
        GLES30.glUniform1f(copyFlip, -1f)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, QUAD)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 0, QUAD_TEX)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        val pixels = readBuffer ?: return
        pixels.rewind()
        GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)

        val bitmap = segBitmap ?: return
        pixels.rewind()
        bitmap.copyPixelsFromBuffer(pixels)
        val model = segmenter ?: Segmentation.getClient(
            SelfieSegmenterOptions.Builder().setDetectorMode(SelfieSegmenterOptions.STREAM_MODE).build()
        ).also { segmenter = it }
        segmenting = true
        model.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { mask ->
                val mw = mask.width
                val mh = mask.height
                val floats = mask.buffer.order(ByteOrder.nativeOrder()).asFloatBuffer()
                val bytes = ByteBuffer.allocateDirect(mw * mh)
                for (i in 0 until mw * mh) bytes.put((floats.get(i).coerceIn(0f, 1f) * 255f).toInt().toByte())
                bytes.rewind()
                pendingMask = Triple(bytes, mw, mh)
                segmenting = false
            }
            .addOnFailureListener { segmenting = false }
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
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uMask"), 2)
        uBlur = GLES30.glGetUniformLocation(program, "uBlur")
        uOutSize = GLES30.glGetUniformLocation(program, "uOutSize")
        uVignette = GLES30.glGetUniformLocation(program, "uVignette")
        uGrain = GLES30.glGetUniformLocation(program, "uGrain")
        uSharpen = GLES30.glGetUniformLocation(program, "uSharpen")
        uSeed = GLES30.glGetUniformLocation(program, "uSeed")

        // Small read-back target and the person mask (starts as "all subject": no blur).
        copyProgram = buildProgram(COPY_VERTEX_SHADER, COPY_FRAGMENT_SHADER)
        copyTexMatrix = GLES30.glGetUniformLocation(copyProgram, "uTexMatrix")
        copyFlip = GLES30.glGetUniformLocation(copyProgram, "uFlip")
        GLES30.glUseProgram(copyProgram)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(copyProgram, "uCamera"), 0)
        val ids = IntArray(2)
        GLES30.glGenTextures(2, ids, 0)
        smallTexture = ids[0]
        maskTexture = ids[1]
        for (tex in ids) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, maskTexture)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_LUMINANCE, 1, 1, 0,
            GLES30.GL_LUMINANCE, GLES30.GL_UNSIGNED_BYTE, ByteBuffer.allocateDirect(1).put(0, 255.toByte()),
        )
        val fbo = IntArray(1)
        GLES30.glGenFramebuffers(1, fbo, 0)
        smallFbo = fbo[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, smallTexture)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, 2, 2, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, smallFbo)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, smallTexture, 0,
        )
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
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

        // Locations 0 and 1 are fixed so the copy pass can set them without looking them up.
        const val COPY_VERTEX_SHADER = """#version 300 es
layout(location = 0) in vec4 aPosition;
layout(location = 1) in vec4 aTexCoord;
uniform mat4 uTexMatrix;
uniform float uFlip;
out vec2 vTexCoord;
void main() {
  gl_Position = vec4(aPosition.x, aPosition.y * uFlip, 0.0, 1.0);
  vTexCoord = (uTexMatrix * aTexCoord).xy;
}
"""

        const val COPY_FRAGMENT_SHADER = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision mediump float;
uniform samplerExternalOES uCamera;
in vec2 vTexCoord;
out vec4 outColor;
void main() {
  outColor = vec4(texture(uCamera, vTexCoord).rgb, 1.0);
}
"""

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
uniform sampler2D uMask;
uniform float uBlur;
uniform vec2 uOutSize;
uniform float uVignette;
uniform float uGrain;
uniform float uSharpen;
uniform float uSeed;
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

  // Cinematic: blur what isn't a person. The mask's first row is the top of the screen.
  if (uBlur > 0.0) {
    vec2 maskPos = vec2(gl_FragCoord.x / uOutSize.x, 1.0 - gl_FragCoord.y / uOutSize.y);
    float subject = smoothstep(0.35, 0.75, texture(uMask, maskPos).r);
    if (subject < 0.99) {
      vec3 sum = cam;
      for (int i = 1; i <= 16; i++) {
        float fi = float(i);
        float r = sqrt(fi / 16.0);
        float a = fi * 2.39996;
        sum += clamp(texture(uCamera, vTexCoord + vec2(cos(a), sin(a)) * r * uBlur).rgb, 0.0, 1.0);
      }
      cam = mix(sum / 17.0, cam, subject);
    }
  }
  // Sharpening (from the grade) works on the camera picture before the look.
  if (uSharpen > 0.0) {
    vec3 blurred = (clamp(texture(uCamera, vTexCoord + vec2(uTexel.x, 0.0)).rgb, 0.0, 1.0)
      + clamp(texture(uCamera, vTexCoord - vec2(uTexel.x, 0.0)).rgb, 0.0, 1.0)
      + clamp(texture(uCamera, vTexCoord + vec2(0.0, uTexel.y)).rgb, 0.0, 1.0)
      + clamp(texture(uCamera, vTexCoord - vec2(0.0, uTexel.y)).rgb, 0.0, 1.0)) * 0.25;
    cam = clamp(cam + (cam - blurred) * uSharpen * 1.5, 0.0, 1.0);
  }
  vec3 c = uFalseColor > 0.5 ? falseColor(y) : texture(uLut, cam * uLutScale + uLutOffset).rgb;
  if (uFalseColor < 0.5) {
    vec2 screen = gl_FragCoord.xy / uOutSize;
    if (uVignette != 0.0) {
      float v = smoothstep(0.85, 0.25, length(screen - 0.5));
      c = mix(c, c * v, uVignette);
    }
    if (uGrain > 0.0) {
      float n = fract(sin(dot(gl_FragCoord.xy + uSeed * 1000.0, vec2(12.9898, 78.233))) * 43758.5453) - 0.5;
      c = clamp(c + n * uGrain * 0.12, 0.0, 1.0);
    }
  }

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
