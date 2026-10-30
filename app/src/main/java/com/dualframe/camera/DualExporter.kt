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
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
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
    data class Running(val step: Int, val progress: Int) : ExportState
    data class Done(val portraitUri: Uri, val landscapeUri: Uri) : ExportState
    data class Failed(val message: String, val savedOriginal: Uri?) : ExportState
}

/**
 * Turns one recording into the two finished videos. Lives at process scope (not in the
 * Activity) so an export keeps going if the screen is recreated.
 */
@OptIn(UnstableApi::class)
object DualExporter {

    private const val OUTPUT_LONG = 1920
    private const val OUTPUT_SHORT = 1080
    private const val BITRATE = 16_000_000
    private const val ALBUM = "DualFrame"

    private val scope = MainScope()
    private val _state = MutableStateFlow<ExportState>(ExportState.Idle)
    val state: StateFlow<ExportState> = _state.asStateFlow()

    fun acknowledge() {
        if (_state.value is ExportState.Done || _state.value is ExportState.Failed) {
            _state.value = ExportState.Idle
        }
    }

    fun start(context: Context, master: File, placement: FramePlacement, baseName: String) {
        val app = context.applicationContext
        _state.value = ExportState.Running(step = 1, progress = 0)
        scope.launch {
            val portraitFile = File(app.cacheDir, "${baseName}_9x16.mp4")
            val landscapeFile = File(app.cacheDir, "${baseName}_16x9.mp4")
            try {
                val aspect = withContext(Dispatchers.IO) { displayAspect(master) }
                transform(app, master, portraitFile, placement.portraitRect(aspect), OUTPUT_SHORT, OUTPUT_LONG) {
                    _state.value = ExportState.Running(step = 1, progress = it)
                }
                transform(app, master, landscapeFile, placement.landscapeRect(aspect), OUTPUT_LONG, OUTPUT_SHORT) {
                    _state.value = ExportState.Running(step = 2, progress = it)
                }
                val portraitUri = saveToGallery(app, portraitFile, "${baseName}_9x16.mp4")
                val landscapeUri = saveToGallery(app, landscapeFile, "${baseName}_16x9.mp4")
                withContext(Dispatchers.IO) { master.delete() }
                _state.value = ExportState.Done(portraitUri, landscapeUri)
            } catch (e: Exception) {
                // Never lose the take: if cutting fails, keep the original recording instead.
                val original = runCatching {
                    if (master.length() > 0) saveToGallery(app, master, "${baseName}_original.mp4") else null
                }.getOrNull()
                _state.value = ExportState.Failed(e.message ?: e.javaClass.simpleName, original)
            } finally {
                withContext(Dispatchers.IO) {
                    portraitFile.delete()
                    landscapeFile.delete()
                }
            }
        }
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

    private suspend fun transform(
        context: Context,
        input: File,
        output: File,
        crop: NormRect,
        width: Int,
        height: Int,
        onProgress: (Int) -> Unit,
    ) = coroutineScope {
        output.delete()
        val item = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(input)))
            .setEffects(
                Effects(
                    /* audioProcessors = */ emptyList<AudioProcessor>(),
                    /* videoEffects = */ listOf<Effect>(
                        crop.toMedia3Crop(),
                        Presentation.createForWidthAndHeight(width, height, Presentation.LAYOUT_SCALE_TO_FIT),
                    ),
                )
            )
            .build()

        val encoderFactory = DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(BITRATE).build())
            .build()

        val finished = CompletableDeferred<Unit>()
        val transformer = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
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
        transformer.start(item, output.absolutePath)

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
