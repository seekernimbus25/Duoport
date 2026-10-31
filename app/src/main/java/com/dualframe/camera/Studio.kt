package com.dualframe.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.net.Uri
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** One piece of video on the editor's timeline: part of a file, at a speed and a volume. */
class EditClip(val id: Long, val uri: Uri, val durationMs: Long, val width: Int, val height: Int) {
    var startMs = 0L
    var endMs = durationMs
    var speed = 1f
    /** 0..2 (100% = as recorded). */
    var volume = 1f
    @Volatile var thumbs: List<Pair<Long, Bitmap>> = emptyList()

    /** How long it lasts on the timeline. */
    val lengthMs get() = ((endMs - startMs) / speed).toLong().coerceAtLeast(1)

    fun copy(newId: Long, from: Long = startMs, to: Long = endMs) = EditClip(newId, uri, durationMs, width, height).also {
        it.startMs = from
        it.endMs = to
        it.speed = speed
        it.volume = volume
        it.thumbs = thumbs
    }

    /** Frames along the clip for the timeline strip (about one every 1.5 s, at most 24). */
    fun loadThumbs(context: Context) {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            val count = (durationMs / 1500).toInt().coerceIn(1, 24)
            thumbs = (0 until count).mapNotNull { i ->
                val t = durationMs * i / count
                r.getScaledFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 120, 120)?.let { t to it }
            }
        } catch (e: Exception) {
            // No thumbnails; the strip shows a plain block.
        } finally {
            r.release()
        }
    }

    fun thumbAt(sourceMs: Long): Bitmap? = thumbs.minByOrNull { abs(it.first - sourceMs) }?.second
}

enum class AudioKind { MUSIC, VOICE }

/** Music or a voiceover under the video. */
class AudioClip(val id: Long, val uri: Uri, val kind: AudioKind, val durationMs: Long, val title: String) {
    /** Where it starts on the timeline (voiceovers: where you started talking). */
    var timelineStartMs = 0L
    /** Which part of the song plays first. */
    var sourceStartMs = 0L
    var volume = 1f
    var fadeInMs = 0L
    var fadeOutMs = 0L
    /** For voiceovers: silence recorded into the file before the voice, equal to [timelineStartMs]. */
    var paddedMs = 0L
}

/** Where things are on the timeline. */
class TimelineMap(clips: List<EditClip>) {
    val offsets: List<Long>
    val totalMs: Long

    init {
        var t = 0L
        offsets = clips.map { c -> t.also { t += c.lengthMs } }
        totalMs = t
    }

    /** Clip index and position inside the clip's own (clipped) media for timeline time [t]. */
    fun locate(clips: List<EditClip>, t: Long): Pair<Int, Long> {
        if (clips.isEmpty()) return 0 to 0L
        val tt = t.coerceIn(0, max(0, totalMs - 1))
        val i = offsets.indexOfLast { it <= tt }.coerceAtLeast(0)
        return i to ((tt - offsets[i]) * clips[i].speed).toLong()
    }
}

/**
 * The timeline under the preview, like Instagram's Edits: a playhead fixed in the middle,
 * the clips as film strips, then lanes for music / voiceover, captions and layers. Drag to
 * scrub, pinch to zoom, tap something to select it.
 */
class TimelineView(context: Context) : View(context) {
    var clips: List<EditClip> = emptyList()
    var audio: List<AudioClip> = emptyList()
    var layers: List<Layer> = emptyList()
    var hasCaptions = false
    var captionsLabel = "Captions"
    var selected: Any? = null
    /** Current time in ms (the playhead). */
    var timeMs = 0L
        set(value) {
            field = value
            invalidate()
        }

    var onScrub: (Long) -> Unit = {}
    var onScrubEnd: () -> Unit = {}
    var onSelect: (Any?) -> Unit = {}

    private val d = resources.displayMetrics.density
    private var pxPerMs = 0.06f * d
    private val map get() = TimelineMap(clips)

    private val rulerH = 18f * d
    private val clipH = 54f * d
    private val laneH = 22f * d
    private val gap = 4f * d

    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(90, 255, 255, 255); strokeWidth = 1f * d }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(170, 255, 255, 255); textSize = 9f * d }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 10.5f * d }
    private val blockPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val selectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * d
        color = Color.rgb(216, 185, 138)
    }
    private val playhead = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeWidth = 2f * d }
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    /** Every hit area drawn in the last frame, for taps. */
    private val hits = mutableListOf<Pair<RectF, Any>>()

    private fun xOf(t: Long) = width / 2f + (t - timeMs) * pxPerMs

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val rows = layerRows().size.coerceIn(0, 3)
        val h = rulerH + clipH + gap + laneH + gap + (if (hasCaptions) laneH + gap else 0f) + rows * (laneH + gap) + 6f * d
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), h.toInt())
    }

    /** Layers packed into rows so bars don't overlap. */
    private fun layerRows(): List<List<Layer>> {
        val rows = mutableListOf<MutableList<Layer>>()
        val total = map.totalMs
        layers.sortedBy { it.startMs }.forEach { l ->
            val end = min(l.endMs, total)
            val row = rows.firstOrNull { r -> r.all { o -> min(o.endMs, total) <= l.startMs || end <= o.startMs } }
            if (row != null) row += l else rows += mutableListOf(l)
        }
        return rows
    }

    override fun onDraw(canvas: Canvas) {
        hits.clear()
        val m = map
        val total = m.totalMs
        // Ruler: a tick every second, a label every 5 (closer when zoomed in).
        val step = if (pxPerMs * 1000 > 60 * d) 1000L else if (pxPerMs * 1000 > 20 * d) 2000L else 5000L
        var t = 0L
        while (t <= total) {
            val x = xOf(t)
            if (x in -50f..width + 50f) {
                canvas.drawLine(x, rulerH - 5 * d, x, rulerH - 1 * d, tickPaint)
                if (t % (step * 2) == 0L || step >= 5000L) canvas.drawText(VideoLibrary.formatDuration(t), x + 2 * d, rulerH - 7 * d, textPaint)
            }
            t += step
        }
        var y = rulerH
        // Clips as film strips.
        clips.forEachIndexed { i, c ->
            val x1 = xOf(m.offsets[i]) + 1 * d
            val x2 = xOf(m.offsets[i] + c.lengthMs) - 1 * d
            if (x2 < 0 || x1 > width) return@forEachIndexed
            val r = RectF(x1, y, x2, y + clipH)
            canvas.save()
            canvas.clipRect(r)
            blockPaint.color = Color.rgb(40, 40, 46)
            canvas.drawRect(r, blockPaint)
            val tile = clipH
            var tx = x1
            while (tx < x2) {
                val tl = m.offsets[i] + ((tx - x1) / pxPerMs).toLong()
                val src = c.startMs + ((tl - m.offsets[i]) * c.speed).toLong()
                c.thumbAt(src)?.let { canvas.drawBitmap(it, null, RectF(tx, y, tx + tile, y + clipH), bmpPaint) }
                tx += tile
            }
            canvas.restore()
            if (c.speed != 1f || c.volume != 1f) {
                val tag = (if (c.speed != 1f) "${trim(c.speed)}×" else "") + (if (c.volume == 0f) " 🔇" else "")
                blockPaint.color = Color.argb(160, 0, 0, 0)
                canvas.drawRect(x1, y, x1 + labelPaint.measureText(tag) + 8 * d, y + 14 * d, blockPaint)
                canvas.drawText(tag, x1 + 4 * d, y + 11 * d, labelPaint)
            }
            if (selected === c) canvas.drawRect(r, selectPaint)
            hits += r to c
        }
        y += clipH + gap
        // Music and voiceovers.
        audio.forEach { a ->
            val len = if (a.kind == AudioKind.MUSIC) min(a.durationMs - a.sourceStartMs, total - a.timelineStartMs) else min(a.durationMs - a.paddedMs, total - a.timelineStartMs)
            bar(canvas, a.timelineStartMs, a.timelineStartMs + max(len, 300), y, if (a.kind == AudioKind.MUSIC) Color.rgb(124, 92, 255) else Color.rgb(255, 138, 61),
                (if (a.kind == AudioKind.MUSIC) "♫ " else "🎙 ") + a.title, a)
        }
        if (audio.isEmpty()) canvas.drawText("+ Music", xOf(0) + 4 * d, y + laneH * 0.7f, textPaint)
        y += laneH + gap
        if (hasCaptions) {
            bar(canvas, 0, total, y, Color.rgb(46, 160, 120), "CC  $captionsLabel", CAPTIONS)
            y += laneH + gap
        }
        layerRows().take(3).forEach { row ->
            row.forEach { l ->
                val name = when (l.kind) {
                    LayerKind.TEXT -> "Aa " + l.text.lineSequence().firstOrNull().orEmpty()
                    LayerKind.STICKER -> "☺ " + (l.asset?.substringAfter('/')?.replace('_', ' ') ?: l.text)
                    LayerKind.IMAGE -> "▣ Photo"
                }
                bar(canvas, l.startMs, min(l.endMs, total), y, Color.rgb(70, 110, 170), name, l)
            }
            y += laneH + gap
        }
        canvas.drawLine(width / 2f, 0f, width / 2f, height.toFloat(), playhead)
    }

    private fun bar(canvas: Canvas, from: Long, to: Long, y: Float, colour: Int, label: String, tag: Any) {
        val r = RectF(xOf(from), y, xOf(to), y + laneH)
        if (r.right < 0 || r.left > width) return
        blockPaint.color = colour
        canvas.drawRoundRect(r, 5 * d, 5 * d, blockPaint)
        canvas.save()
        canvas.clipRect(r)
        canvas.drawText(label, max(r.left, 0f) + 6 * d, y + laneH * 0.7f, labelPaint)
        canvas.restore()
        if (selected === tag) canvas.drawRoundRect(r, 5 * d, 5 * d, selectPaint)
        hits += r to tag
    }

    private fun trim(f: Float) = if (f % 1f == 0f) f.toInt().toString() else f.toString()

    // region Touch

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var moved = false
    private var scaling = false

    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            pxPerMs = (pxPerMs * detector.scaleFactor).coerceIn(0.005f * d, 0.6f * d)
            scaling = true
            invalidate()
            return true
        }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaler.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = e.x
                downY = e.y
                lastX = e.x
                moved = false
                scaling = false
            }
            MotionEvent.ACTION_MOVE -> if (!scaling && e.pointerCount == 1) {
                if (abs(e.x - downX) > 8 * d) moved = true
                if (moved) {
                    val dt = ((lastX - e.x) / pxPerMs).toLong()
                    timeMs = (timeMs + dt).coerceIn(0, map.totalMs)
                    onScrub(timeMs)
                }
                lastX = e.x
            }
            MotionEvent.ACTION_UP -> {
                if (!moved && !scaling) {
                    val hit = hits.lastOrNull { it.first.contains(e.x, e.y) }?.second
                    selected = if (hit === selected) null else hit
                    onSelect(selected)
                    invalidate()
                } else if (moved) {
                    onScrubEnd()
                }
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    // endregion

    companion object {
        /** Selection tag for the captions lane. */
        val CAPTIONS = Any()
    }
}

/**
 * Fades audio in and out (16-bit PCM). [totalUs] is how long the piece plays, so the fade-out
 * ends exactly at its end.
 */
@OptIn(UnstableApi::class)
class FadeAudioProcessor(private val fadeInUs: Long, private val fadeOutUs: Long, private val totalUs: Long) : BaseAudioProcessor() {
    private var frames = 0L

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        return if (fadeInUs <= 0 && fadeOutUs <= 0) AudioProcessor.AudioFormat.NOT_SET else inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val channels = inputAudioFormat.channelCount
        val rate = inputAudioFormat.sampleRate
        val out = replaceOutputBuffer(inputBuffer.remaining())
        val src = inputBuffer.order(ByteOrder.nativeOrder())
        while (src.remaining() >= 2 * channels) {
            val tUs = frames * 1_000_000L / rate
            var gain = 1f
            if (fadeInUs > 0 && tUs < fadeInUs) gain *= tUs.toFloat() / fadeInUs
            if (fadeOutUs > 0 && tUs > totalUs - fadeOutUs) gain *= ((totalUs - tUs).toFloat() / fadeOutUs).coerceIn(0f, 1f)
            for (c in 0 until channels) out.putShort((src.short * gain).toInt().toShort())
            frames++
        }
        out.flip()
    }

    override fun onFlush() {
        frames = 0
    }
}

/**
 * Records a voiceover as a WAV file (44.1 kHz mono), starting with [leadMs] of silence so it
 * lines up with the timeline when mixed from the start.
 */
class VoiceRecorder(private val file: File, private val leadMs: Long) {
    @Volatile private var running = false
    private var thread: Thread? = null
    var level = 0f
        private set

    @SuppressLint("MissingPermission")
    fun start() {
        val rate = 44100
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(min, 8192))
        running = true
        thread = Thread {
            RandomAccessFile(file, "rw").use { raf ->
                raf.setLength(0)
                raf.write(ByteArray(44))
                var bytes = 0L
                val silence = ByteArray(4096)
                var lead = leadMs * rate / 1000 * 2
                while (lead > 0) {
                    val n = min(lead, silence.size.toLong()).toInt()
                    raf.write(silence, 0, n)
                    bytes += n
                    lead -= n
                }
                val buf = ByteArray(4096)
                record.startRecording()
                while (running) {
                    val n = record.read(buf, 0, buf.size)
                    if (n > 0) {
                        raf.write(buf, 0, n)
                        bytes += n
                        var peak = 0
                        for (i in 0 until n step 2) peak = max(peak, abs(((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 255)).toShort().toInt()))
                        level = peak / 32768f
                    }
                }
                record.stop()
                record.release()
                raf.seek(0)
                raf.write(wavHeader(bytes, rate))
            }
        }.also { it.start() }
    }

    fun stop() {
        running = false
        thread?.join(2000)
    }

    private fun wavHeader(dataBytes: Long, rate: Int): ByteArray {
        val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt((36 + dataBytes).toInt()); b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()); b.putInt(16); b.putShort(1); b.putShort(1); b.putInt(rate); b.putInt(rate * 2); b.putShort(2); b.putShort(16)
        b.put("data".toByteArray()); b.putInt(dataBytes.toInt())
        return b.array()
    }
}
