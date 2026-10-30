package com.dualframe.camera

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.sqrt

/** Reading a video's sound: for captions (speech recognition) and for finding silences. */
object AudioScan {
    const val SAMPLE_RATE = 16_000

    /** The sound of [uri] as 16 kHz mono 16-bit PCM, up to [maxBytes]; null if it has no sound. */
    fun mono16k(context: Context, uri: Uri, maxBytes: Int = Int.MAX_VALUE): ByteArray? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
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
            try {
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
                        val ch = channels.coerceAtLeast(1)
                        val frames = shorts.remaining() / ch
                        val step = sampleRate / SAMPLE_RATE.toDouble()
                        while (position < frames) {
                            // Average the input samples this output sample covers (mono, low-passed).
                            val from = position.toInt()
                            val to = minOf(frames, maxOf(from + 1, (position + step).toInt()))
                            var sum = 0L
                            var count = 0
                            for (f in from until to) for (c in 0 until ch) {
                                sum += shorts.get(f * ch + c)
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
                        if (out.size() > maxBytes) outputDone = true
                    }
                }
            } finally {
                codec.stop()
                codec.release()
            }
            return out.toByteArray()
        } finally {
            extractor.release()
        }
    }

    /**
     * The parts worth keeping, in ms: everything except pauses longer than [minPauseMs].
     * "Quiet" is judged against the clip's own background noise, so a noisy room still works.
     * [padMs] of air is left either side of speech so cuts don't clip words.
     */
    fun speechRanges(pcm: ByteArray, minPauseMs: Long, padMs: Long = 150): List<LongRange> {
        val window = SAMPLE_RATE / 50 // 20 ms
        val windows = pcm.size / 2 / window
        if (windows == 0) return emptyList()
        val totalMs = windows * 20L
        val db = FloatArray(windows) { w ->
            var sum = 0.0
            for (i in 0 until window) {
                val idx = (w * window + i) * 2
                val s = ((pcm[idx + 1].toInt() shl 8) or (pcm[idx].toInt() and 0xFF)).toShort().toDouble()
                sum += s * s
            }
            val rms = sqrt(sum / window) / 32768.0
            (20 * log10(rms.coerceAtLeast(1e-6))).toFloat()
        }
        val floor = db.sorted()[windows / 10]
        val threshold = (floor + 12f).coerceIn(-50f, -28f)
        val keep = mutableListOf<LongRange>()
        var keepFrom = 0L
        var quietStart = -1
        for (w in 0..windows) {
            val quiet = w < windows && db[w] < threshold
            if (quiet && quietStart < 0) quietStart = w
            if (!quiet && quietStart >= 0) {
                val startMs = quietStart * 20L
                val endMs = w * 20L
                if (endMs - startMs >= minPauseMs) {
                    val cutFrom = if (quietStart == 0) 0L else startMs + padMs
                    val cutTo = if (w == windows) totalMs else endMs - padMs
                    if (cutFrom > keepFrom) keep += keepFrom until cutFrom
                    keepFrom = cutTo
                }
                quietStart = -1
            }
        }
        if (keepFrom < totalMs) keep += keepFrom until totalMs
        return keep.filter { it.last - it.first >= 100 }
    }
}
