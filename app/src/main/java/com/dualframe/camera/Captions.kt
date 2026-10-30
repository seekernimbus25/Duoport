package com.dualframe.camera

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.speech.RecognitionListener
import android.speech.RecognitionPart
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlaySettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

/** Caption language. Hinglish uses Android's Indian-English model, which writes Hindi words in Roman script. */
enum class CaptionLanguage(val label: String, val tag: String) {
    OFF("Off", ""),
    ENGLISH("English", "en-US"),
    HINGLISH("Hinglish", "en-IN"),
}

data class CaptionWord(val startMs: Long, val endMs: Long, val text: String)

data class CaptionLine(val startMs: Long, val endMs: Long, val words: List<CaptionWord>)

/**
 * Speech to text with Android's own on-device recognizer: free, offline, nothing leaves the
 * phone. The recording's sound is decoded, turned into 16 kHz mono and streamed to the
 * recognizer (Android 13+). Android 14+ returns a time for every word; on 13 the times are
 * estimated from how far through the audio each phrase was recognized.
 */
object Transcriber {

    fun isAvailable(context: Context) =
        Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    enum class PackStatus { READY, DOWNLOADING, UNSUPPORTED, UNKNOWN }

    /** Makes sure Android has the speech pack for [language], starting the free download if not. */
    fun prepare(context: Context, language: CaptionLanguage, onStatus: (PackStatus) -> Unit) {
        if (language == CaptionLanguage.OFF || !isAvailable(context) || Build.VERSION.SDK_INT < 33) return
        prepare33(context, language, onStatus)
    }

    @RequiresApi(33)
    private fun prepare33(context: Context, language: CaptionLanguage, onStatus: (PackStatus) -> Unit) {
        val recognizer = runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(context) }.getOrNull() ?: return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, language.tag)
        fun has(list: List<String>) = list.any { it.equals(language.tag, ignoreCase = true) }
        recognizer.checkRecognitionSupport(intent, context.mainExecutor, object : RecognitionSupportCallback {
            override fun onSupportResult(support: RecognitionSupport) {
                when {
                    has(support.installedOnDeviceLanguages) -> onStatus(PackStatus.READY)
                    has(support.pendingOnDeviceLanguages) -> onStatus(PackStatus.DOWNLOADING)
                    has(support.supportedOnDeviceLanguages) -> {
                        runCatching { recognizer.triggerModelDownload(intent) }
                        onStatus(PackStatus.DOWNLOADING)
                    }
                    else -> onStatus(PackStatus.UNSUPPORTED)
                }
                recognizer.destroy()
            }

            override fun onError(error: Int) {
                onStatus(PackStatus.UNKNOWN)
                recognizer.destroy()
            }
        })
    }

    /** Every spoken word in [file] with its time. Call on the main thread. Empty if none / unavailable. */
    suspend fun transcribe(
        context: Context,
        file: File,
        language: CaptionLanguage,
        onProgress: (Int) -> Unit,
    ): List<CaptionWord> {
        if (language == CaptionLanguage.OFF || !isAvailable(context) || Build.VERSION.SDK_INT < 33) return emptyList()
        val pcm = withContext(Dispatchers.IO) { runCatching { decodeMono16k(file) }.getOrNull() } ?: return emptyList()
        if (pcm.isEmpty()) return emptyList()
        return recognize33(context, pcm, language, onProgress)
    }

    @RequiresApi(33)
    private suspend fun recognize33(
        context: Context,
        pcm: ByteArray,
        language: CaptionLanguage,
        onProgress: (Int) -> Unit,
    ): List<CaptionWord> = suspendCancellableCoroutine { cont ->
        val recognizer = runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(context) }.getOrNull()
        if (recognizer == null) {
            cont.resume(emptyList())
            return@suspendCancellableCoroutine
        }
        val pipe = ParcelFileDescriptor.createPipe()
        val read = pipe[0]
        val write = pipe[1]
        val fed = AtomicLong(0)
        val words = mutableListOf<CaptionWord>()
        var segmentStartMs = 0L
        var finished = false

        fun finish() {
            if (finished) return
            finished = true
            runCatching { recognizer.destroy() }
            runCatching { read.close() }
            if (cont.isActive) cont.resume(words.toList())
        }

        fun addResult(bundle: Bundle) {
            // The recognizer reads through a ~2 s pipe, so "now" in the audio is a little behind
            // what has been written.
            val heardUpToMs = (fed.get() / BYTES_PER_MS - 1500).coerceAtLeast(segmentStartMs)
            val parts = if (Build.VERSION.SDK_INT >= 34) {
                bundle.getParcelableArrayList(SpeechRecognizer.RECOGNITION_PARTS, RecognitionPart::class.java)
            } else {
                null
            }
            if (parts != null && parts.isNotEmpty() && parts.any { it.timestampMillis > 0 }) {
                parts.forEachIndexed { i, part ->
                    val text = (part.formattedText ?: part.rawText).trim()
                    if (text.isEmpty()) return@forEachIndexed
                    val start = part.timestampMillis
                    val end = parts.getOrNull(i + 1)?.timestampMillis?.takeIf { it > start }
                        ?: (start + 120L * text.length).coerceAtLeast(start + 250)
                    words += CaptionWord(start, end, text)
                }
                segmentStartMs = words.lastOrNull()?.endMs ?: segmentStartMs
                return
            }
            val text = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
            if (text.isNullOrEmpty()) return
            // No word times: spread the phrase's words over the stretch of audio it came from.
            val tokens = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
            val start = segmentStartMs
            val end = maxOf(heardUpToMs, start + 300L * tokens.size)
            val totalChars = tokens.sumOf { it.length + 1 }.toFloat()
            var t = start.toFloat()
            for (token in tokens) {
                val d = (end - start) * (token.length + 1) / totalChars
                words += CaptionWord(t.toLong(), (t + d).toLong(), token)
                t += d
            }
            segmentStartMs = end
        }

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onSegmentResults(segmentResults: Bundle) = addResult(segmentResults)

            override fun onEndOfSegmentedSession() = finish()

            override fun onResults(results: Bundle?) {
                results?.let { addResult(it) }
                finish()
            }

            override fun onError(error: Int) = finish()
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language.tag)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, read)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, SAMPLE_RATE)
            putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
            putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING, RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY)
            if (Build.VERSION.SDK_INT >= 34) putExtra(RecognizerIntent.EXTRA_REQUEST_WORD_TIMING, true)
        }
        recognizer.startListening(intent)

        Thread {
            runCatching {
                ParcelFileDescriptor.AutoCloseOutputStream(write).use { out ->
                    var pos = 0
                    var lastPct = -1
                    while (pos < pcm.size) {
                        val n = minOf(CHUNK, pcm.size - pos)
                        out.write(pcm, pos, n)
                        pos += n
                        fed.set(pos.toLong())
                        val pct = pos * 100 / pcm.size
                        if (pct != lastPct) {
                            lastPct = pct
                            onProgress(pct)
                        }
                    }
                }
            }
        }.start()

        cont.invokeOnCancellation { android.os.Handler(context.mainLooper).post { finish() } }
    }

    /** The recording's sound as 16 kHz mono 16-bit PCM (what speech recognizers expect). */
    private fun decodeMono16k(file: File): ByteArray? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val out = ByteArrayOutputStream()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var position = 0.0 // fractional input frame for the next output sample
            while (!outputDone) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buffer = codec.getInputBuffer(i)!!
                        val n = extractor.readSampleData(buffer, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    sampleRate = codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                } else if (o >= 0) {
                    val buffer = codec.getOutputBuffer(o)!!
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    val shorts = buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    val frames = shorts.remaining() / channels.coerceAtLeast(1)
                    val step = sampleRate / SAMPLE_RATE.toDouble()
                    while (position < frames) {
                        // Average the input samples this output sample covers (mono, low-passed).
                        val from = position.toInt()
                        val to = minOf(frames, maxOf(from + 1, (position + step).toInt()))
                        var sum = 0L
                        var count = 0
                        for (f in from until to) for (c in 0 until channels) {
                            sum += shorts.get(f * channels + c)
                            count++
                        }
                        val sample = (sum / count.coerceAtLeast(1)).toInt()
                        out.write(sample and 0xFF)
                        out.write((sample shr 8) and 0xFF)
                        position += step
                    }
                    position -= frames
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    if (out.size() > MAX_BYTES) outputDone = true
                }
            }
            codec.stop()
            codec.release()
            return out.toByteArray()
        } finally {
            extractor.release()
        }
    }

    /** Words grouped into short lines: a few words at a time, broken at pauses and sentence ends. */
    fun lines(words: List<CaptionWord>, maxChars: Int): List<CaptionLine> {
        val lines = mutableListOf<CaptionLine>()
        var current = mutableListOf<CaptionWord>()
        var chars = 0
        fun flush() {
            if (current.isEmpty()) return
            lines += CaptionLine(current.first().startMs, current.last().endMs + 300, current)
            current = mutableListOf()
            chars = 0
        }
        for (word in words) {
            val pause = current.lastOrNull()?.let { word.startMs - it.endMs > 700 } ?: false
            if (current.isNotEmpty() && (chars + word.text.length + 1 > maxChars || pause)) flush()
            current += word
            chars += word.text.length + 1
            if (word.text.endsWith(".") || word.text.endsWith("?") || word.text.endsWith("!")) flush()
        }
        flush()
        // A line stays up until the next one starts (but no longer than it needs).
        return lines.mapIndexed { i, line ->
            val next = lines.getOrNull(i + 1)?.startMs ?: Long.MAX_VALUE
            line.copy(endMs = minOf(line.endMs, next))
        }
    }

    /** Standard .srt subtitles, for YouTube and editors. */
    fun srt(words: List<CaptionWord>): String = buildString {
        lines(words, 42).forEachIndexed { i, line ->
            append(i + 1).append('\n')
            append(srtTime(line.startMs)).append(" --> ").append(srtTime(line.endMs)).append('\n')
            append(line.words.joinToString(" ") { it.text }).append("\n\n")
        }
    }

    private fun srtTime(ms: Long) = String.format(
        Locale.US, "%02d:%02d:%02d,%03d", ms / 3_600_000, (ms / 60_000) % 60, (ms / 1000) % 60, ms % 1000,
    )

    /** Saves the .srt next to your other downloads (Download/DualFrame). */
    fun saveSrt(context: Context, name: String, text: String) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/x-subrip")
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/DualFrame")
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
        context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
    }

    private const val SAMPLE_RATE = 16_000
    private const val BYTES_PER_MS = SAMPLE_RATE * 2 / 1000
    private const val CHUNK = 3200
    /** About 20 minutes of speech audio; longer takes get captions for the first 20 minutes. */
    private const val MAX_BYTES = 20 * 60 * SAMPLE_RATE * 2
}

/**
 * Word-by-word captions burned into a video: the current line in bold white on a soft dark
 * pill, the word being spoken in champagne. Sits low in wide videos and higher in tall ones,
 * clear of the buttons apps put along the bottom of Reels and Shorts.
 */
@OptIn(UnstableApi::class)
class CaptionOverlay(private val lines: List<CaptionLine>) : BitmapOverlay() {
    private var videoW = 1080
    private var videoH = 1920
    private val empty = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
    private var cachedKey = -1L
    private var cached: Bitmap = empty
    private var lineIndex = 0

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        color = Color.WHITE
    }
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(140, 10, 10, 12) }

    private val vertical get() = videoH > videoW

    override fun configure(videoSize: Size) {
        super.configure(videoSize)
        videoW = videoSize.width
        videoH = videoSize.height
        cachedKey = -1
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings =
        OverlaySettings.Builder()
            .setBackgroundFrameAnchor(0f, if (vertical) -0.38f else -0.72f)
            .build()

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val ms = presentationTimeUs / 1000
        if (lines.isEmpty()) return empty
        // Frames come in order, so walk forward from the last line instead of searching.
        if (lineIndex >= lines.size || lines[lineIndex].startMs > ms) lineIndex = 0
        while (lineIndex < lines.size - 1 && lines[lineIndex].endMs <= ms) lineIndex++
        val line = lines[lineIndex]
        if (ms < line.startMs || ms >= line.endMs) return empty
        val wordIndex = line.words.indexOfLast { it.startMs <= ms }.coerceAtLeast(0)
        val key = lineIndex * 1000L + wordIndex
        if (key == cachedKey) return cached
        cachedKey = key
        // Old bitmaps aren't recycled: Media3 may still be reading the previous one.
        cached = draw(line, wordIndex)
        return cached
    }

    private fun draw(line: CaptionLine, active: Int): Bitmap {
        val short = minOf(videoW, videoH).toFloat()
        textPaint.textSize = short * if (vertical) 0.066f else 0.052f
        val space = textPaint.measureText(" ")
        val maxWidth = videoW * 0.84f
        // Wrap into at most two rows.
        val rows = mutableListOf(mutableListOf<Int>())
        var rowWidth = 0f
        line.words.forEachIndexed { i, w ->
            val width = textPaint.measureText(w.text)
            if (rows.last().isNotEmpty() && rowWidth + space + width > maxWidth && rows.size < 2) {
                rows += mutableListOf<Int>()
                rowWidth = 0f
            }
            rowWidth += (if (rows.last().isEmpty()) 0f else space) + width
            rows.last() += i
        }
        val lineHeight = textPaint.textSize * 1.25f
        val padX = textPaint.textSize * 0.55f
        val padY = textPaint.textSize * 0.32f
        val widths = rows.map { row -> row.sumOf { textPaint.measureText(line.words[it].text).toDouble() }.toFloat() + space * (row.size - 1) }
        val boxW = (widths.maxOrNull() ?: 0f) + padX * 2
        val boxH = lineHeight * rows.size + padY * 2
        val bitmap = Bitmap.createBitmap(videoW and 1.inv(), (boxH.toInt() + 2) and 1.inv(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val left = (bitmap.width - boxW) / 2f
        canvas.drawRoundRect(RectF(left, 0f, left + boxW, boxH), boxH * 0.22f, boxH * 0.22f, boxPaint)
        rows.forEachIndexed { r, row ->
            var x = (bitmap.width - widths[r]) / 2f
            val baseline = padY + lineHeight * r + textPaint.textSize
            for (i in row) {
                val word = line.words[i].text
                textPaint.color = if (i == active) Color.rgb(232, 200, 140) else Color.WHITE
                textPaint.setShadowLayer(textPaint.textSize * 0.08f, 0f, textPaint.textSize * 0.04f, Color.argb(160, 0, 0, 0))
                canvas.drawText(word, x, baseline, textPaint)
                x += textPaint.measureText(word) + space
            }
        }
        return bitmap
    }
}
