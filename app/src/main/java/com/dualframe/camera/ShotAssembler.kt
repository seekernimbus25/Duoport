package com.dualframe.camera

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Joins clips end to end at one size and saves the result to the library (shot-list assembly). */
@OptIn(UnstableApi::class)
object ShotAssembler {

    /** Must be called from the main thread. Returns the saved video. */
    suspend fun join(context: Context, uris: List<Uri>, width: Int, height: Int, baseName: String, onProgress: (Int) -> Unit): Uri {
        val name = baseName + "_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        val output = File(context.cacheDir, name)
        val items = uris.map { uri ->
            EditedMediaItem.Builder(MediaItem.fromUri(uri))
                .setEffects(
                    Effects(emptyList(), listOf(Presentation.createForWidthAndHeight(width, height, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP)))
                )
                .build()
        }
        try {
            export(context, items, width, height, output, toneMap = false, onProgress)
        } catch (e: ExportException) {
            // HDR and standard takes mixed, or a phone that can't keep HDR: make it all standard range.
            export(context, items, width, height, output, toneMap = true, onProgress)
        }
        return try {
            DualExporter.saveToGallery(context, output, name)
        } finally {
            output.delete()
        }
    }

    private suspend fun export(
        context: Context,
        items: List<EditedMediaItem>,
        width: Int,
        height: Int,
        output: File,
        toneMap: Boolean,
        onProgress: (Int) -> Unit,
    ) = coroutineScope {
        output.delete()
        val composition = Composition.Builder(EditedMediaItemSequence(items))
            .experimentalSetForceAudioTrack(true)
            .setHdrMode(if (toneMap) Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL else Composition.HDR_MODE_KEEP_HDR)
            .build()
        val settings = SettingsStore.load(context)
        val finished = CompletableDeferred<Unit>()
        val transformer = Transformer.Builder(context)
            .setVideoMimeType(settings.codec.mimeType)
            .setEncoderFactory(
                DefaultEncoderFactory.Builder(context)
                    .setRequestedVideoEncoderSettings(
                        VideoEncoderSettings.Builder().setBitrate(bitrateFor(settings.exportQuality, width, height)).build()
                    )
                    .build()
            )
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    finished.complete(Unit)
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    finished.completeExceptionally(exportException)
                }
            })
            .build()
        transformer.start(composition, output.absolutePath)
        val poller = launch {
            val holder = ProgressHolder()
            while (true) {
                if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress)
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
}
