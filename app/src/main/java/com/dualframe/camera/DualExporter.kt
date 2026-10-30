package com.dualframe.camera

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.Presentation
import androidx.media3.effect.SingleColorLut
import androidx.media3.effect.VideoCompositorSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
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

sealed interface ExportState {
    data object Idle : ExportState
    data class Running(val step: Int, val total: Int, val format: OutputFormat, val progress: Int) : ExportState
    data class Done(val videos: List<SavedVideo>) : ExportState
    data class Failed(val message: String, val savedOriginal: Uri?) : ExportState
}

data class SavedVideo(val format: OutputFormat, val resolution: OutputResolution, val uri: Uri)

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
    /** The recording is HLG HDR: tone-map to standard range (LUTs and compositing are SDR). */
    val hdrSource: Boolean,
    val codec: VideoCodec,
    val quality: ExportQuality,
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
            try {
                val aspect = withContext(Dispatchers.IO) { displayAspect(job.main.file) }
                val saved = mutableListOf<SavedVideo>()
                job.outputs.forEachIndexed { index, (format, resolution) ->
                    val name = "${job.baseName}_${format.label.replace(':', 'x')}_${resolution.label}.mp4"
                    val file = File(app.cacheDir, name).also { made += it }
                    val (w, h) = format.sizePx(resolution)
                    export(app, job, file, w, h, job.placement.rect(format, aspect)) {
                        _state.value = ExportState.Running(index + 1, job.outputs.size, format, it)
                    }
                    saved += SavedVideo(format, resolution, saveToGallery(app, file, name))
                    withContext(Dispatchers.IO) { file.delete() }
                }
                if (job.keepOriginal) saveOriginals(app, job)
                _state.value = ExportState.Done(saved)
            } catch (e: Exception) {
                // Never lose the take: if cutting fails, keep the original recording instead.
                val original = runCatching { saveOriginals(app, job) }.getOrNull()
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

    private suspend fun saveOriginals(context: Context, job: ExportJob): Uri? {
        val main = job.main.file.takeIf { it.length() > 0 }?.let {
            saveToGallery(context, it, "${job.baseName}_original.mp4")
        }
        job.inset?.file?.takeIf { it.length() > 0 }?.let {
            saveToGallery(context, it, "${job.baseName}_original_second_camera.mp4")
        }
        return main
    }

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
        onProgress: (Int) -> Unit,
    ) {
        output.delete()
        // A fresh effect per input: SingleColorLut owns a GL texture tied to one export.
        fun lutEffects(): List<Effect> =
            job.lut?.let { listOf<Effect>(SingleColorLut.createFromCube(it.toArgbCube())) } ?: emptyList()
        val inset = job.inset

        val mainEffects = mutableListOf<Effect>(
            crop.toMedia3Crop(),
            Presentation.createForWidthAndHeight(width, height, Presentation.LAYOUT_SCALE_TO_FIT),
        )
        mainEffects += lutEffects()

        if (inset == null) {
            val item = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(job.main.file)))
                .setEffects(Effects(emptyList<AudioProcessor>(), mainEffects))
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
            .setEffects(Effects(emptyList<AudioProcessor>(), mainEffects))
            .build()
        val insetEffects = mutableListOf<Effect>(
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

    private fun hdrMode(job: ExportJob) =
        if (job.hdrSource) Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL else Composition.HDR_MODE_KEEP_HDR

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
        val transformer = Transformer.Builder(context)
            .setVideoMimeType(job.codec.mimeType)
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
            .build()
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

    private suspend fun saveToGallery(context: Context, file: File, displayName: String): Uri =
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
