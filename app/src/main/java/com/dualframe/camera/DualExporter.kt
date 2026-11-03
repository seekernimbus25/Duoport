package com.dualframe.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.container.XmpData
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.TextureOverlay
import androidx.media3.effect.Presentation
import androidx.media3.effect.SingleColorLut
import androidx.media3.effect.SpeedChangeEffect
import androidx.media3.effect.VideoCompositorSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.InAppMuxer
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.pow

sealed interface ExportState {
    data object Idle : ExportState
    data class Running(
        val step: Int,
        val total: Int,
        val format: OutputFormat,
        val progress: Int,
        /** Shown instead of "Making 9:16…" for steps that aren't a video (captions). */
        val label: String? = null,
    ) : ExportState
    data class Done(val videos: List<SavedVideo>, val note: String? = null) : ExportState
    data class Failed(val message: String, val savedOriginal: Uri?) : ExportState
    /** Waiting for the captions to be reviewed in the caption editor. */
    data class ReviewCaptions(val token: Long) : ExportState
}

data class SavedVideo(
    val format: OutputFormat,
    val resolution: OutputResolution,
    val uri: Uri,
    /** A still photo rather than a video; [width] x [height] is its real size. */
    val photo: Boolean = false,
    val width: Int = 0,
    val height: Int = 0,
)

/** A finished camera recording. */
data class Take(val file: File, val durationNs: Long)

/** Everything needed to turn one take into the two finished videos. */
data class ExportJob(
    val main: Take,
    /** The second camera's separate recording in front + back mode, if there is one. */
    val inset: Take?,
    val insetCorner: Corner,
    val outputs: List<Pair<OutputFormat, OutputResolution>>,
    val placement: FramePlacement,
    /** Colour LUT to bake into every video, or null. */
    val lut: LutCube?,
    /** The recording is HLG 10-bit HDR. */
    val hdrSource: Boolean,
    /** With an HDR source: keep the cut videos 10-bit HDR (HEVC, no LUT) instead of tone-mapping. */
    val keepHdr: Boolean = false,
    val codec: VideoCodec,
    /** 1 = real time; above 1 time-lapse / hyperlapse, below 1 slow motion. Sound is dropped unless 1. */
    val speed: Float = 1f,
    val quality: ExportQuality,
    val keepOriginal: Boolean,
    val baseName: String,
    /** Sound level change in dB (only for real-time videos, which keep their sound). */
    val audioGainDb: Int = 0,
    /** Cinematic background blur strength, 0 = off. */
    val cinematic: Int = 0,
    /** Move the irises back to the lens (calibrated on the take's first moments). */
    val eyeContact: Boolean = false,
    /** Anamorphic squeeze to undo, 1 = none. */
    val anamorphic: Float = 1f,
    /** Wall-clock time the recording started, written as time-of-day timecode; null = none. */
    val timecodeStartMs: Long? = null,
    /** Frame rate label for the timecode ("23.98", "30"…). */
    val timecodeRate: String = "30",
    /** Face positions through the take for auto-reframe; null = frames stay where they were placed. */
    val track: List<SubjectSample>? = null,
    /** Quarter turns of the phone during the take, to turn the picture back upright; empty = none. */
    val turns: List<TurnSample> = emptyList(),
    /** Burn in captions in this language (real-time videos only). */
    val captions: CaptionLanguage = CaptionLanguage.OFF,
    val captionStyle: CaptionStyle = CaptionStyle(),
    /** Open the caption editor before making the videos. */
    val captionReview: Boolean = false,
    /** The grade's vignette / grain / sharpening (its colour part is already in [lut]). */
    val grade: ColorGrade = ColorGrade(),
    /** Paused stretches (ms from the start) and the whole time from start to stop, so a pause
     *  the camera left in the file can be cut out. */
    val pauses: List<LongRange> = emptyList(),
    val wallMs: Long = 0,
)

/** One still photo to cut into every chosen shape. */
data class PhotoJob(
    val image: Bitmap,
    /** Clockwise rotation that makes [image] upright. */
    val rotationDegrees: Int,
    val mirror: Boolean,
    val outputs: List<OutputFormat>,
    val placement: FramePlacement,
    val lut: LutCube?,
    val anamorphic: Float,
    val cinematic: Int,
    val keepOriginal: Boolean,
    val baseName: String,
)

/**
 * Turns one recording into the two finished videos. Lives at process scope (not in the
 * Activity) so an export keeps going if the screen is recreated.
 */
@OptIn(UnstableApi::class)
object DualExporter {

    private const val ALBUM = "DualFrame"
    private const val INSET_SEQUENCE = 1
    private const val CAPTIONS_LABEL = "Writing captions…"
    private const val NO_CAPTIONS_NOTE =
        "No captions: no speech was recognized, or this phone's speech pack isn't downloaded yet " +
            "(Settings → Captions starts the free download)."
    private const val CINEMATIC_FALLBACK_NOTE =
        "Cinematic blur couldn't run on this phone, so the videos were made without it."
    private const val HDR_FALLBACK_NOTE =
        "This phone can't save edited HDR video, so the cut videos are standard range. The 10-bit original is saved too."

    private val scope = MainScope()
    private val _state = MutableStateFlow<ExportState>(ExportState.Idle)
    val state: StateFlow<ExportState> = _state.asStateFlow()

    fun acknowledge() {
        if (_state.value is ExportState.Done || _state.value is ExportState.Failed) {
            _state.value = ExportState.Idle
        }
    }

    fun start(context: Context, job: ExportJob) {
        val app = context.applicationContext
        val first = job.outputs.firstOrNull() ?: return
        _state.value = ExportState.Running(step = 1, total = job.outputs.size, format = first.first, progress = 0)
        scope.launch {
            val made = mutableListOf<File>()
            // Everything after this (captions, cuts, the kept original) works on the closed-up take.
            val job = closePauses(app, job)
            try {
                val aspect = withContext(Dispatchers.IO) { displayAspect(job.main.file) }
                val saved = mutableListOf<SavedVideo>()
                var current = job
                var note: String? = null

                // Captions first: one transcription serves every format, plus an .srt file.
                var words: List<CaptionWord> = emptyList()
                if (job.captions != CaptionLanguage.OFF && job.speed == 1f) {
                    _state.value = ExportState.Running(0, job.outputs.size, first.first, 0, CAPTIONS_LABEL)
                    words = Transcriber.transcribe(app, Uri.fromFile(job.main.file), job.captions) { p ->
                        _state.value = ExportState.Running(0, job.outputs.size, first.first, p, CAPTIONS_LABEL)
                    }
                    if (words.isNotEmpty() && job.captionReview) {
                        _state.value = ExportState.ReviewCaptions(System.nanoTime())
                        words = CaptionReview.review(words)
                        // The review screen has its own Style button: burn in what was picked
                        // there, not the style from when recording stopped.
                        current = current.copy(captionStyle = CaptionStyle.load(app))
                    }
                    if (words.isEmpty()) {
                        note = NO_CAPTIONS_NOTE
                    } else {
                        withContext(Dispatchers.IO) {
                            runCatching { Transcriber.saveSrt(app, "${job.baseName}.srt", Transcriber.srt(words)) }
                        }
                    }
                }
                job.outputs.forEachIndexed { index, (format, resolution) ->
                    val name = "${job.baseName}_${format.label.replace(':', 'x')}_${resolution.label}.mp4"
                    val file = File(app.cacheDir, name).also { made += it }
                    val (w, h) = format.sizePx(resolution)
                    val sceneAspect = desqueezed(aspect, job.anamorphic)
                    val crop = job.placement.rect(format, sceneAspect)
                    val path = job.track?.takeIf { it.isNotEmpty() }?.let { Reframe.path(it, format, sceneAspect, crop) }
                    val progress = { p: Int -> _state.value = ExportState.Running(index + 1, job.outputs.size, format, p) }
                    // If a phone can't do one of the optional extras, drop that one and try again
                    // rather than failing the whole take.
                    while (true) {
                        try {
                            export(app, current, file, w, h, crop, path, words, progress)
                            break
                        } catch (e: ExportException) {
                            current = when {
                                current.timecodeStartMs != null -> current.copy(timecodeStartMs = null)
                                current.hdrSource && current.keepHdr -> {
                                    // The 10-bit original is saved so nothing is lost.
                                    note = HDR_FALLBACK_NOTE
                                    current.copy(keepHdr = false, keepOriginal = true)
                                }
                                current.cinematic > 0 -> {
                                    note = CINEMATIC_FALLBACK_NOTE
                                    current.copy(cinematic = 0)
                                }
                                else -> throw e
                            }
                        }
                    }
                    saved += SavedVideo(format, resolution, saveToGallery(app, file, name))
                    withContext(Dispatchers.IO) { file.delete() }
                }
                if (current.keepOriginal) saveOriginals(app, job)
                // A shot-list take: file it under its shot.
                runCatching { ShotLists.onSaved(app, job.baseName, saved.map { it.format.label to it.uri }) }
                _state.value = ExportState.Done(saved, note)
            } catch (e: Exception) {
                // Never lose the take: if cutting fails, keep the original recording instead.
                val original = runCatching { saveOriginals(app, job) }.getOrNull()
                runCatching { ShotLists.onSaved(app, job.baseName, listOfNotNull(original?.let { "Original" to it })) }
                _state.value = ExportState.Failed(e.message ?: e.javaClass.simpleName, original)
            } finally {
                withContext(Dispatchers.IO) {
                    made.forEach { it.delete() }
                    job.main.file.delete()
                    job.inset?.file?.delete()
                }
            }
        }
    }

    /** A photo becomes one picture per shape, each cut at the full resolution the sensor gave. */
    fun startPhoto(context: Context, job: PhotoJob) {
        val app = context.applicationContext
        val first = job.outputs.firstOrNull() ?: return
        _state.value = ExportState.Running(step = 1, total = job.outputs.size, format = first, progress = 0)
        scope.launch {
            try {
                val saved = withContext(Dispatchers.Default) { makePhotos(app, job) }
                _state.value = ExportState.Done(saved)
            } catch (e: Exception) {
                _state.value = ExportState.Failed(e.message ?: e.javaClass.simpleName, null)
            }
        }
    }

    private suspend fun makePhotos(context: Context, job: PhotoJob): List<SavedVideo> {
        val upright = uprightPhoto(job)
        val aspect = upright.width.toFloat() / upright.height
        val saved = mutableListOf<SavedVideo>()
        var blurFailed = false
        job.outputs.forEachIndexed { index, format ->
            _state.value = ExportState.Running(index + 1, job.outputs.size, format, 30)
            val r = job.placement.rect(format, aspect)
            val x = (r.left * upright.width).toInt().coerceIn(0, upright.width - 2)
            val y = (r.top * upright.height).toInt().coerceIn(0, upright.height - 2)
            val w = (r.width * upright.width).toInt().coerceIn(1, upright.width - x)
            val h = (r.height * upright.height).toInt().coerceIn(1, upright.height - y)
            var cut = Bitmap.createBitmap(upright, x, y, w, h)
            if (job.cinematic > 0 && !blurFailed) {
                val blurred = runCatching { CinematicBlur.applyToPhoto(context, cut, job.cinematic) }.getOrNull()
                if (blurred == null) blurFailed = true else cut = replace(cut, blurred, upright)
            }
            job.lut?.let { lut -> cut = replace(cut, lut.applyTo(cut), upright) }
            if (BrandKit.settings(context).enabled) {
                cut = replace(cut, BrandKit.stamp(context, if (cut === upright) cut.copy(Bitmap.Config.ARGB_8888, true) else cut), upright)
            }
            _state.value = ExportState.Running(index + 1, job.outputs.size, format, 80)
            val name = "${job.baseName}_${format.label.replace(':', 'x')}.jpg"
            saved += SavedVideo(
                format, OutputResolution.FHD, savePhoto(context, cut, name),
                photo = true, width = cut.width, height = cut.height,
            )
            if (cut !== upright) cut.recycle()
        }
        if (job.keepOriginal) savePhoto(context, upright, "${job.baseName}_original.jpg")
        upright.recycle()
        return saved
    }

    private fun replace(old: Bitmap, new: Bitmap, keep: Bitmap): Bitmap {
        if (old !== new && old !== keep) old.recycle()
        return new
    }

    /** Rotated upright, mirrored for selfies if asked, and anamorphic squeeze undone. */
    private fun uprightPhoto(job: PhotoJob): Bitmap {
        val src = job.image
        val matrix = Matrix()
        matrix.postRotate(job.rotationDegrees.toFloat())
        if (job.mirror) matrix.postScale(-1f, 1f)
        val turned = Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
        if (turned !== src) src.recycle()
        if (job.anamorphic == 1f) return turned
        val wide = turned.width >= turned.height
        val w = if (wide) (turned.width * job.anamorphic).toInt() else turned.width
        val h = if (wide) turned.height else (turned.height * job.anamorphic).toInt()
        val stretched = Bitmap.createScaledBitmap(turned, w, h, true)
        if (stretched !== turned) turned.recycle()
        return stretched
    }

    private suspend fun savePhoto(context: Context, bitmap: Bitmap, displayName: String): Uri =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ALBUM")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("Couldn't create $displayName in the gallery")
            try {
                resolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            uri
        }

    private suspend fun saveOriginals(context: Context, job: ExportJob): Uri? {
        val main = job.main.file.takeIf { it.length() > 0 }?.let {
            saveToGallery(context, it, "${job.baseName}_original.mp4")
        }
        job.inset?.file?.takeIf { it.length() > 0 }?.let {
            saveToGallery(context, it, "${job.baseName}_original_second_camera.mp4")
        }
        return main
    }

    /**
     * Pause / resume normally leaves the paused time out of the file. If a camera kept recording
     * through it (the file is about as long as the whole time from start to stop), the paused
     * stretches are cut out here. If anything goes wrong the take is used as it is.
     */
    private suspend fun closePauses(context: Context, job: ExportJob): ExportJob {
        val pausedMs = job.pauses.sumOf { it.last - it.first }
        if (pausedMs < 500 || job.wallMs <= 0) return job
        suspend fun close(take: Take): Take {
            val fileMs = withContext(Dispatchers.IO) { durationMs(take.file) }
            // Already closed up by the camera: about wallMs - pausedMs long.
            if (fileMs <= 0 || fileMs < job.wallMs - pausedMs / 2) return take
            val keep = mutableListOf<LongRange>()
            var from = 0L
            job.pauses.sortedBy { it.first }.forEach { p ->
                if (p.first - from > 100) keep += from..p.first
                from = maxOf(from, p.last)
            }
            if (fileMs - from > 100) keep += from..fileMs
            if (keep.isEmpty()) return take
            val out = File(take.file.parentFile, take.file.nameWithoutExtension + "_joined.mp4")
            val items = keep.map { r ->
                EditedMediaItem.Builder(
                    MediaItem.Builder()
                        .setUri(Uri.fromFile(take.file))
                        .setClippingConfiguration(
                            MediaItem.ClippingConfiguration.Builder()
                                .setStartPositionMs(r.first)
                                .setEndPositionMs(r.last)
                                .build()
                        )
                        .build()
                ).build()
            }
            val bitrate = withContext(Dispatchers.IO) { bitrateOf(take.file) }
            val encoders = DefaultEncoderFactory.Builder(context).apply {
                if (bitrate > 0) setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build())
            }.build()
            val finished = CompletableDeferred<Unit>()
            withContext(Dispatchers.Main) {
                Transformer.Builder(context)
                    .setEncoderFactory(encoders)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            finished.complete(Unit)
                        }

                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            finished.completeExceptionally(exportException)
                        }
                    })
                    .build()
                    .start(Composition.Builder(EditedMediaItemSequence(items)).build(), out.absolutePath)
            }
            finished.await()
            if (out.length() == 0L) error("joining the take produced no file")
            withContext(Dispatchers.IO) { take.file.delete() }
            return Take(out, take.durationNs)
        }
        return runCatching {
            job.copy(main = close(job.main), inset = job.inset?.let { close(it) })
        }.getOrElse { job }
    }

    private fun durationMs(file: File): Long = runCatching {
        MediaMetadataRetriever().run {
            try {
                setDataSource(file.absolutePath)
                extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } finally {
                release()
            }
        }
    }.getOrDefault(0L)

    private fun bitrateOf(file: File): Int = runCatching {
        MediaMetadataRetriever().run {
            try {
                setDataSource(file.absolutePath)
                extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull() ?: 0
            } finally {
                release()
            }
        }
    }.getOrDefault(0)

    /** width / height of the video as it is meant to be displayed (rotation applied). */
    private fun displayAspect(file: File): Float {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toFloatOrNull()
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toFloatOrNull()
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (w == null || h == null || w <= 0f || h <= 0f) error("Recording has no video track")
            return if (rotation % 180 == 0) w / h else h / w
        } finally {
            retriever.release()
        }
    }

    /**
     * The two cameras' recordings start a few milliseconds apart but are stopped together, so
     * they are lined up by their ends: the longer one skips its extra start.
     */
    private fun clippedItem(take: Take, otherDurationNs: Long): MediaItem {
        val skipMs = ((take.durationNs - otherDurationNs) / 1_000_000).coerceAtLeast(0)
        return MediaItem.Builder()
            .setUri(Uri.fromFile(take.file))
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder().setStartPositionMs(skipMs).build()
            )
            .build()
    }

    private suspend fun export(
        context: Context,
        job: ExportJob,
        output: File,
        width: Int,
        height: Int,
        crop: NormRect,
        path: CropPath?,
        words: List<CaptionWord>,
        onProgress: (Int) -> Unit,
    ) {
        output.delete()
        // A fresh effect per input: SingleColorLut owns a GL texture tied to one export.
        // LUTs are made for standard-range pictures, so they're skipped while keeping HDR.
        val lut = job.lut.takeIf { !keepsHdr(job) }
        fun lutEffects(): List<Effect> =
            lut?.let { listOf<Effect>(SingleColorLut.createFromCube(it.toArgbCube())) } ?: emptyList()
        val inset = job.inset

        // Speed first (time-lapse or slow motion), so later effects see the new timeline.
        fun speedEffects(): List<Effect> =
            if (job.speed != 1f) listOf(SpeedChangeEffect(job.speed)) else emptyList()

        val mainEffects = mutableListOf<Effect>()
        mainEffects += speedEffects()
        // On the whole picture, before the crop, so the eyes are found the same in every shape.
        // Real time only: its calibration is the take's first 1.5 s.
        if (job.eyeContact && job.speed == 1f) mainEffects += EyeContactEffect(job.main.file.path)
        // Stretching (not fitting) the cut to the output size is what undoes an anamorphic squeeze.
        val layout = if (job.anamorphic != 1f) Presentation.LAYOUT_STRETCH_TO_FIT else Presentation.LAYOUT_SCALE_TO_FIT
        // Auto-reframe moves the crop over time; otherwise it stays where it was placed.
        // A phone turned mid-take also turns the picture back (not with an anamorphic squeeze,
        // which would turn sideways with it).
        val turns = job.turns.takeIf { list -> list.any { it.degrees != 0 } && job.anamorphic == 1f }
        val cropEffect: Effect = when {
            turns != null -> TurnedCrop(
                crop.width, crop.height,
                { t -> path?.at(t) ?: (crop.centerX to crop.centerY) },
                TurnPath(turns), job.speed,
            )
            path != null -> MovingCrop(crop.width, crop.height, path, job.speed)
            else -> crop.toMedia3Crop()
        }
        mainEffects += listOf(
            cropEffect,
            Presentation.createForWidthAndHeight(width, height, layout),
        )
        if (job.cinematic > 0) mainEffects += CinematicBlurEffect(job.cinematic)
        mainEffects += lutEffects()
        if (job.grade.hasFinish && !keepsHdr(job)) mainEffects += GradeFinishEffect(job.grade)
        // Captions go on last so the LUT doesn't tint them. Tall videos get shorter lines.
        if (words.isNotEmpty()) {
            val style = job.captionStyle
            val lines = Transcriber.lines(words, if (height > width) 24 else 40, style.wordsPerLine)
            val renderer = CaptionRenderer(style, CaptionFonts.typeface(context, style.font), CaptionFonts.variation(style.font))
            mainEffects += OverlayEffect(listOf<TextureOverlay>(CaptionOverlay(lines, renderer)))
        }
        // Your watermark, on every video (set in Design > Logo).
        BrandKit.overlay(context)?.let { mainEffects += OverlayEffect(listOf<TextureOverlay>(it)) }

        if (inset == null) {
            val item = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(job.main.file)))
                .setRemoveAudio(job.speed != 1f)
                .setEffects(Effects(audioEffects(job), mainEffects))
                .build()
            val composition = Composition.Builder(EditedMediaItemSequence(item))
                .setHdrMode(hdrMode(job))
                .build()
            runTransformer(context, job, width, height, output, onProgress) { it.start(composition, output.absolutePath) }
            return
        }

        // Front + back: the main camera fills the frame and the second camera sits in the
        // chosen corner, sized and placed exactly as the preview showed it.
        val insetRect = InsetGeometry.rect(width, height, job.insetCorner)
        val (insetW, insetH) = InsetGeometry.sizePx(width, height)
        val border = minOf(width, height) * 0.004f
        mainEffects += InsetCutoutEffect(insetRect, border)

        val mainItem = EditedMediaItem.Builder(clippedItem(job.main, inset.durationNs))
            .setRemoveAudio(job.speed != 1f)
            .setEffects(Effects(audioEffects(job), mainEffects))
            .build()
        val insetEffects = mutableListOf<Effect>()
        insetEffects += speedEffects()
        insetEffects += listOf<Effect>(
            Presentation.createForWidthAndHeight(insetW, insetH, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP),
        )
        insetEffects += lutEffects()
        val insetItem = EditedMediaItem.Builder(clippedItem(inset, job.main.durationNs))
            .setRemoveAudio(true)
            .setEffects(Effects(emptyList<AudioProcessor>(), insetEffects))
            .build()

        // Pin the inset's corner to the same corner of the video, pulled in by the margin.
        // OverlaySettings use NDC: -1..1 with +y up.
        val sx = if (job.insetCorner.isLeft) -1f else 1f
        val sy = if (job.insetCorner.isTop) 1f else -1f
        val margin = InsetGeometry.marginPx(width, height)
        val insetOverlay = OverlaySettings.Builder()
            .setOverlayFrameAnchor(sx, sy)
            .setBackgroundFrameAnchor(sx * (1f - 2f * margin / width), sy * (1f - 2f * margin / height))
            .build()
        val fullFrame = OverlaySettings.Builder().build()
        val compositorSettings = object : VideoCompositorSettings {
            override fun getOutputSize(inputSizes: List<Size>): Size = Size(width, height)

            override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): OverlaySettings =
                if (inputId == INSET_SEQUENCE) insetOverlay else fullFrame
        }

        val composition = Composition.Builder(
            listOf(
                EditedMediaItemSequence(listOf(mainItem)),
                EditedMediaItemSequence(listOf(insetItem)),
            )
        )
            .setVideoCompositorSettings(compositorSettings)
            .setHdrMode(hdrMode(job))
            .build()
        runTransformer(context, job, width, height, output, onProgress) { it.start(composition, output.absolutePath) }
    }

    private fun keepsHdr(job: ExportJob) = job.hdrSource && job.keepHdr

    /** Volume change as a plain gain on every channel (mono, stereo or more). */
    private fun audioEffects(job: ExportJob): List<AudioProcessor> {
        if (job.audioGainDb == 0 || job.speed != 1f) return emptyList()
        val gain = 10.0.pow(job.audioGainDb / 20.0).toFloat()
        val mixer = ChannelMixingAudioProcessor()
        for (channels in 1..8) mixer.putChannelMixingMatrix(ChannelMixingMatrix.create(channels, channels).scaleBy(gain))
        return listOf(mixer)
    }

    /**
     * Time-of-day start timecode as XMP (xmpDM:startTimeCode), which editors such as Premiere
     * read. Android's own MP4 writer can't add a timecode track, so this uses Media3's muxer.
     */
    private fun timecodeXmp(startMs: Long, rate: String): ByteArray {
        val fps = rate.toFloatOrNull()?.let { kotlin.math.round(it).toInt() } ?: 30
        val day = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(startMs))
        val frames = ((startMs % 1000) * fps / 1000).toInt()
        val tc = String.format(Locale.US, "%s:%02d", day, frames)
        val format = when (rate) {
            "23.98" -> "23976Timecode"
            "29.97" -> "2997NonDropTimecode"
            "59.94" -> "5994NonDropTimecode"
            "24" -> "24Timecode"
            "25" -> "25Timecode"
            "50" -> "50Timecode"
            "60" -> "60Timecode"
            else -> "30Timecode"
        }
        val created = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date(startMs))
        val xmp = """<?xpacket begin="${'\uFEFF'}" id="W5M0MpCehiHzreSzNTczkc9d"?>
<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
<rdf:Description rdf:about="" xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmlns:xmpDM="http://ns.adobe.com/xmp/1.0/DynamicMedia/">
<xmp:CreateDate>$created</xmp:CreateDate>
<xmpDM:startTimeCode rdf:parseType="Resource"><xmpDM:timeFormat>$format</xmpDM:timeFormat><xmpDM:timeValue>$tc</xmpDM:timeValue></xmpDM:startTimeCode>
<xmpDM:altTimecode rdf:parseType="Resource"><xmpDM:timeFormat>$format</xmpDM:timeFormat><xmpDM:timeValue>$tc</xmpDM:timeValue></xmpDM:altTimecode>
</rdf:Description></rdf:RDF></x:xmpmeta>
<?xpacket end="w"?>"""
        return xmp.toByteArray(Charsets.UTF_8)
    }

    private fun hdrMode(job: ExportJob) =
        if (job.hdrSource && !job.keepHdr) Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL else Composition.HDR_MODE_KEEP_HDR

    private suspend fun runTransformer(
        context: Context,
        job: ExportJob,
        width: Int,
        height: Int,
        output: File,
        onProgress: (Int) -> Unit,
        start: (Transformer) -> Unit,
    ) = coroutineScope {
        val encoderFactory = DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(
                VideoEncoderSettings.Builder().setBitrate(bitrateFor(job.quality, width, height)).build()
            )
            .build()

        val finished = CompletableDeferred<Unit>()
        val builder = Transformer.Builder(context)
            // 10-bit HDR needs HEVC; H.264 encoders on phones are 8-bit.
            .setVideoMimeType(if (keepsHdr(job)) VideoCodec.HEVC.mimeType else job.codec.mimeType)
            .setEncoderFactory(encoderFactory)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    finished.complete(Unit)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException,
                ) {
                    finished.completeExceptionally(exportException)
                }
            })
        // Timecode only makes sense for real-time videos.
        val startMs = job.timecodeStartMs
        if (startMs != null && job.speed == 1f) {
            val xmp = timecodeXmp(startMs, job.timecodeRate)
            builder.setMuxerFactory(
                InAppMuxer.Factory.Builder()
                    .setMetadataProvider { entries -> entries.add(XmpData(xmp)) }
                    .build()
            )
        }
        val transformer = builder.build()
        start(transformer)

        val poller = launch {
            val holder = ProgressHolder()
            while (true) {
                if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    onProgress(holder.progress)
                }
                delay(250)
            }
        }
        try {
            finished.await()
            if (!output.exists() || output.length() == 0L) error("Export produced no file")
        } finally {
            poller.cancel()
            if (!finished.isCompleted) transformer.cancel()
        }
    }

    suspend fun saveToGallery(context: Context, file: File, displayName: String): Uri =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$ALBUM")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("Couldn't create $displayName in the gallery")
            try {
                resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
                resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            uri
        }
}
