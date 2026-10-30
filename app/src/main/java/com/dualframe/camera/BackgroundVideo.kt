package com.dualframe.camera

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.ConditionVariable
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface

/**
 * A second video decoded straight into a GL texture, for the design's video background. It
 * must be created, updated and released on the GL thread that draws it. [update] shows the
 * frame for a time on the main video's timeline; the background loops when it's shorter.
 */
class BackgroundVideo(context: Context, val uri: Uri) {
    private val extractor = MediaExtractor()
    private val codec: MediaCodec
    private val thread = HandlerThread("bg-video").apply { start() }
    private val frameReady = ConditionVariable()
    private val surfaceTexture: SurfaceTexture
    private val surface: Surface
    private val info = MediaCodec.BufferInfo()
    private var durationUs = 0L
    private var inputDone = false
    private var outputDone = false
    private var lastPts = Long.MIN_VALUE

    /** The external (OES) texture the frames land in. */
    val textureId: Int
    /** Display size, after the video's rotation. */
    val width: Int
    val height: Int
    /** Clockwise degrees the stored frames need to be shown upright. */
    val rotation: Int
    val transform = FloatArray(16)
    var hasFrame = false
        private set

    init {
        extractor.setDataSource(context, uri, null)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
        } ?: error("No video track")
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
        rotation = if (format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger(MediaFormat.KEY_ROTATION) else 0
        val w = format.getInteger(MediaFormat.KEY_WIDTH)
        val h = format.getInteger(MediaFormat.KEY_HEIGHT)
        width = if (rotation % 180 != 0) h else w
        height = if (rotation % 180 != 0) w else h

        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        textureId = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        surfaceTexture = SurfaceTexture(textureId)
        surfaceTexture.setOnFrameAvailableListener({ frameReady.open() }, Handler(thread.looper))
        surface = Surface(surfaceTexture)
        // Let the decoder work at its own pace; we pull frames as the main video needs them.
        format.setInteger(MediaFormat.KEY_PRIORITY, 1)
        codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, surface, null, 0)
        codec.start()
    }

    /** Brings the texture to the frame for [timeUs] of the main video. */
    fun update(timeUs: Long) {
        val target = if (durationUs > 0) Math.floorMod(timeUs, durationUs) else timeUs.coerceAtLeast(0)
        // Looped round, or jumped far ahead: seek instead of decoding everything in between.
        if (lastPts != Long.MIN_VALUE && (target < lastPts - 100_000 || target > lastPts + 2_000_000)) seek(target)
        if (hasFrame && target <= lastPts + 15_000) return
        var guard = 0
        while (!outputDone && guard++ < 400) {
            if (!inputDone) {
                val inIndex = codec.dequeueInputBuffer(2_000)
                if (inIndex >= 0) {
                    val size = extractor.readSampleData(codec.getInputBuffer(inIndex)!!, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val outIndex = codec.dequeueOutputBuffer(info, 5_000)
            if (outIndex < 0) continue
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                codec.releaseOutputBuffer(outIndex, false)
                outputDone = true
                break
            }
            val pts = info.presentationTimeUs
            if (pts + 20_000 < target) {
                // Not there yet: drop it without drawing.
                codec.releaseOutputBuffer(outIndex, false)
                continue
            }
            frameReady.close()
            codec.releaseOutputBuffer(outIndex, true)
            if (frameReady.block(250)) {
                surfaceTexture.updateTexImage()
                surfaceTexture.getTransformMatrix(transform)
                hasFrame = true
            }
            lastPts = pts
            break
        }
    }

    private fun seek(targetUs: Long) {
        extractor.seekTo(targetUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        codec.flush()
        inputDone = false
        outputDone = false
        lastPts = Long.MIN_VALUE
    }

    fun release() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { extractor.release() }
        surface.release()
        surfaceTexture.release()
        thread.quitSafely()
        GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
    }
}
