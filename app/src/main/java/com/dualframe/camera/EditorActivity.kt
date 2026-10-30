package com.dualframe.camera

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.exoplayer.ExoPlayer
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
import androidx.media3.ui.PlayerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.slider.RangeSlider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Trim and join: set in and out points on each clip, reorder or drop clips, and optionally
 * cut out every pause. The preview plays exactly what will be saved; Save makes one new
 * video in the library (the originals are left alone).
 */
@OptIn(UnstableApi::class)
class EditorActivity : AppCompatActivity() {

    private class Clip(val uri: Uri, val durationMs: Long, val width: Int, val height: Int) {
        var startMs = 0L
        var endMs = durationMs
        /** What's left after removing pauses (whole clip, in ms); null until analysed. */
        var speech: List<LongRange>? = null
        var speechPauseMs = 0L
    }

    private val clips = mutableListOf<Clip>()
    private var removeSilences = false
    private var pauseMs = 600L
    private var player: ExoPlayer? = null
    private var previewJob: Job? = null
    private var saving = false

    private lateinit var clipList: LinearLayout
    private lateinit var summary: TextView
    private lateinit var silenceNote: TextView
    private lateinit var saveButton: MaterialButton
    private lateinit var progressPanel: View
    private lateinit var progressBar: LinearProgressIndicator
    private lateinit var progressText: TextView

    private fun dp(v: Float) = Glass.dp(this, v).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val uris = androidx.core.content.IntentCompat.getParcelableArrayListExtra(intent, EXTRA_URIS, Uri::class.java).orEmpty()
        if (uris.isEmpty()) {
            finish()
            return
        }
        buildUi()
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) { uris.mapNotNull { load(it) } }
            if (loaded.isEmpty()) {
                Toast.makeText(this@EditorActivity, R.string.editor_load_failed, Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }
            clips += loaded
            renderClips()
            refreshPreview()
        }
    }

    private fun load(uri: Uri): Clip? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(this, uri)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return null
            var w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: return null
            var h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: return null
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rotation % 180 != 0) w = h.also { h = w }
            Clip(uri, duration, w, h)
        } catch (e: Exception) {
            null
        } finally {
            retriever.release()
        }
    }

    // region UI

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(ContextCompat.getColor(context, R.color.ink)) }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), dp(8f), dp(16f), dp(8f))
        }
        header.addView(ImageButton(this, null, 0, R.style.DF_IconButton).apply {
            setImageResource(R.drawable.ic_arrow_back)
            contentDescription = getString(R.string.back)
            setOnClickListener { finish() }
        })
        header.addView(
            Glass.body(this, getString(R.string.editor_title)).apply {
                setTextAppearance(R.style.DF_Text_Title)
                setPadding(dp(8f), 0, 0, 0)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        saveButton = MaterialButton(this).apply {
            text = getString(R.string.editor_save)
            setOnClickListener { save() }
        }
        header.addView(saveButton)
        column.addView(header)

        val playerView = PlayerView(this).apply {
            id = View.generateViewId()
            setShowNextButton(false)
            setShowPreviousButton(false)
            setBackgroundColor(android.graphics.Color.BLACK)
        }
        column.addView(playerView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.42f))

        val scroll = NestedScrollView(this)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(4f), dp(20f), dp(32f))
        }
        scroll.addView(body)
        column.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.58f))

        summary = Glass.caption(this, "").apply { setPadding(0, dp(12f), 0, 0) }
        body.addView(summary)

        body.addView(Glass.section(this, getString(R.string.editor_silences)))
        val silenceSwitch = MaterialSwitch(this).apply {
            text = getString(R.string.editor_remove_silences)
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            textSize = 15f
            minHeight = dp(52f)
            thumbTintList = null
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(ContextCompat.getColor(context, R.color.accent), 0x33FFFFFF),
            )
            setOnCheckedChangeListener { _, checked ->
                removeSilences = checked
                analyseSilences()
            }
        }
        body.addView(silenceSwitch)
        val pauses = com.google.android.material.chip.ChipGroup(this).apply {
            chipSpacingHorizontal = dp(8f)
        }
        listOf(300L to "0.3 s", 600L to "0.6 s", 1000L to "1 s", 2000L to "2 s").forEach { (ms, label) ->
            pauses.addView(Glass.pill(this, getString(R.string.editor_pause_longer, label), ms == pauseMs).apply {
                setOnClickListener {
                    for (i in 0 until pauses.childCount) pauses.getChildAt(i).isSelected = false
                    isSelected = true
                    pauseMs = ms
                    if (removeSilences) analyseSilences()
                }
            })
        }
        body.addView(pauses)
        silenceNote = Glass.caption(this, getString(R.string.editor_silence_note)).apply { setPadding(0, dp(6f), 0, 0) }
        body.addView(silenceNote)

        body.addView(Glass.section(this, getString(R.string.editor_clips)))
        clipList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(clipList)

        // Saving overlay.
        progressPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(android.graphics.Color.argb(200, 10, 10, 12))
            isClickable = true
            visibility = View.GONE
        }
        progressText = Glass.body(this, getString(R.string.editor_saving)).apply { gravity = Gravity.CENTER }
        progressBar = LinearProgressIndicator(this).apply {
            max = 100
            setIndicatorColor(ContextCompat.getColor(context, R.color.accent))
        }
        (progressPanel as LinearLayout).addView(progressText)
        (progressPanel as LinearLayout).addView(
            progressBar,
            LinearLayout.LayoutParams(dp(220f), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16f) },
        )
        root.addView(progressPanel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            column.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }

        player = ExoPlayer.Builder(this).build().also {
            playerView.player = it
            it.repeatMode = Player.REPEAT_MODE_ALL
        }
    }

    private fun renderClips() {
        clipList.removeAllViews()
        clips.forEachIndexed { index, clip ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = ContextCompat.getDrawable(context, R.drawable.bg_glass_card)
                setPadding(dp(16f), dp(10f), dp(8f), dp(10f))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(10f) }
            }
            val top = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val title = Glass.body(this, "")
            fun updateTitle() {
                title.text = getString(
                    R.string.editor_clip_title, index + 1,
                    VideoLibrary.formatDuration(clip.startMs), VideoLibrary.formatDuration(clip.endMs),
                )
            }
            updateTitle()
            top.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (clips.size > 1) {
                top.addView(Glass.pill(this, "↑") { move(index, -1) }.apply { isEnabled = index > 0 })
                top.addView(Glass.pill(this, "↓") { move(index, 1) }.apply { isEnabled = index < clips.size - 1 })
                top.addView(Glass.pill(this, "✕") {
                    clips.removeAt(index)
                    renderClips()
                    refreshPreview()
                }.apply { contentDescription = getString(R.string.editor_remove_clip) })
            }
            card.addView(top)

            val slider = RangeSlider(this).apply {
                valueFrom = 0f
                valueTo = (clip.durationMs / 100L).coerceAtLeast(1).toFloat() / 10f
                stepSize = 0.1f
                values = listOf(clip.startMs / 1000f, clip.endMs / 1000f).map { (it * 10).toInt() / 10f }
                setMinSeparationValue(0.5f)
                setLabelFormatter { v -> VideoLibrary.formatDuration((v * 1000).toLong()) }
                trackActiveTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.accent))
                thumbTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.text_primary))
                addOnChangeListener { s, _, _ ->
                    clip.startMs = (s.values[0] * 1000).toLong()
                    clip.endMs = (s.values[1] * 1000).toLong().coerceAtMost(clip.durationMs)
                    updateTitle()
                    updateSummary()
                    refreshPreview()
                }
            }
            card.addView(slider)
            clipList.addView(card)
        }
        updateSummary()
    }

    private fun move(index: Int, by: Int) {
        val to = index + by
        if (to !in clips.indices) return
        clips.add(to, clips.removeAt(index))
        renderClips()
        refreshPreview()
    }

    private fun updateSummary() {
        val total = segments().sumOf { (_, range) -> range.last - range.first }
        val raw = clips.sumOf { it.endMs - it.startMs }
        summary.text = if (removeSilences && raw > total) {
            getString(R.string.editor_summary_cut, VideoLibrary.formatDuration(total), VideoLibrary.formatDuration(raw - total))
        } else {
            getString(R.string.editor_summary, clips.size, VideoLibrary.formatDuration(total))
        }
    }

    // endregion

    // region Silences

    private fun analyseSilences() {
        if (!removeSilences) {
            updateSummary()
            refreshPreview()
            return
        }
        silenceNote.text = getString(R.string.editor_finding_pauses)
        lifecycleScope.launch {
            for (clip in clips.toList()) {
                if (clip.speech != null && clip.speechPauseMs == pauseMs) continue
                val pcm = withContext(Dispatchers.IO) { runCatching { AudioScan.mono16k(this@EditorActivity, clip.uri) }.getOrNull() }
                clip.speech = if (pcm == null) listOf(0L until clip.durationMs) else AudioScan.speechRanges(pcm, pauseMs)
                clip.speechPauseMs = pauseMs
            }
            silenceNote.text = getString(R.string.editor_silence_note)
            updateSummary()
            refreshPreview()
        }
    }

    /** The pieces that end up in the video, in order: (clip, range in ms). */
    private fun segments(): List<Pair<Clip, LongRange>> = clips.flatMap { clip ->
        val trim = clip.startMs until clip.endMs
        val speech = clip.speech
        if (!removeSilences || speech == null) {
            listOf(clip to trim)
        } else {
            speech.mapNotNull { r ->
                val from = maxOf(r.first, trim.first)
                val to = minOf(r.last, trim.last)
                if (to - from >= 100) clip to (from until to) else null
            }
        }
    }

    // endregion

    // region Preview and save

    private fun mediaItem(clip: Clip, range: LongRange): MediaItem =
        MediaItem.Builder()
            .setUri(clip.uri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(range.first)
                    .setEndPositionMs(range.last + 1)
                    .build()
            )
            .build()

    /** Rebuilds the preview a moment after the last change, so dragging a handle stays smooth. */
    private fun refreshPreview() {
        previewJob?.cancel()
        previewJob = lifecycleScope.launch {
            delay(250)
            val p = player ?: return@launch
            val items = segments().map { (clip, range) -> mediaItem(clip, range) }
            p.setMediaItems(items)
            p.prepare()
            p.play()
        }
    }

    private fun save() {
        if (saving) return
        val parts = segments()
        if (parts.isEmpty()) return
        saving = true
        player?.pause()
        progressPanel.isVisible = true
        progressBar.setProgressCompat(0, false)

        // Every piece is fitted to the first clip's shape so mixed shapes still join cleanly.
        val first = parts.first().first
        val width = first.width and 1.inv()
        val height = first.height and 1.inv()
        val items = parts.map { (clip, range) ->
            EditedMediaItem.Builder(mediaItem(clip, range))
                .setEffects(
                    Effects(
                        emptyList(),
                        listOf(Presentation.createForWidthAndHeight(width, height, Presentation.LAYOUT_SCALE_TO_FIT)),
                    )
                )
                .build()
        }
        val name = "Edit_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        val output = File(cacheDir, name)

        lifecycleScope.launch {
            try {
                export(items, width, height, output, toneMap = false)
            } catch (e: ExportException) {
                // A mix of HDR and standard clips: convert everything to standard range.
                export(items, width, height, output, toneMap = true)
            } catch (e: Exception) {
                fail(e)
                return@launch
            }
            try {
                val uri = DualExporter.saveToGallery(this@EditorActivity, output, name)
                output.delete()
                Toast.makeText(this@EditorActivity, R.string.editor_saved, Toast.LENGTH_LONG).show()
                startActivity(
                    Intent(this@EditorActivity, PlayerActivity::class.java)
                        .setData(uri)
                        .putExtra(PlayerActivity.EXTRA_TITLE, getString(R.string.editor_title))
                )
                finish()
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    private fun fail(e: Exception) {
        saving = false
        progressPanel.isVisible = false
        Toast.makeText(this, getString(R.string.editor_failed, e.message ?: e.javaClass.simpleName), Toast.LENGTH_LONG).show()
    }

    private suspend fun export(items: List<EditedMediaItem>, width: Int, height: Int, output: File, toneMap: Boolean) {
        output.delete()
        val composition = Composition.Builder(EditedMediaItemSequence(items))
            // Clips without sound get silence, so they can sit next to clips with sound.
            .experimentalSetForceAudioTrack(true)
            .setHdrMode(
                if (toneMap) Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL else Composition.HDR_MODE_KEEP_HDR
            )
            .build()
        val settings = SettingsStore.load(this)
        val finished = CompletableDeferred<Unit>()
        val transformer = Transformer.Builder(this)
            .setVideoMimeType(settings.codec.mimeType)
            .setEncoderFactory(
                DefaultEncoderFactory.Builder(this)
                    .setRequestedVideoEncoderSettings(
                        VideoEncoderSettings.Builder()
                            .setBitrate(bitrateFor(settings.exportQuality, width, height))
                            .build()
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
        val poller = lifecycleScope.launch {
            val holder = ProgressHolder()
            while (true) {
                if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    progressBar.setProgressCompat(holder.progress, true)
                    progressText.text = getString(R.string.editor_saving_percent, holder.progress)
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

    // endregion

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }

    companion object {
        const val EXTRA_URIS = "uris"

        fun open(context: Context, uris: List<Uri>) {
            context.startActivity(
                Intent(context, EditorActivity::class.java).putParcelableArrayListExtra(EXTRA_URIS, ArrayList(uris))
            )
        }
    }
}
