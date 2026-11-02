package com.dualframe.camera

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.TextureOverlay
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
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.slider.RangeSlider
import com.google.android.material.slider.Slider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * The editor, laid out like Instagram's Edits: the preview on top, a timeline (clips, music and
 * voiceover, captions, layers) under it, and a toolbar of tools at the bottom. Clips can be
 * split, trimmed, sped up or slowed down, re-ordered and muted; music and voiceovers mix under
 * them; captions, grading (with wheels and scopes) and the design tools (background, shape,
 * cutout, text, stickers, photos, logo, cards, templates) all work on the whole edit. Save
 * makes a new video in the library; the originals are never changed.
 */
@OptIn(UnstableApi::class)
class DesignActivity : AppCompatActivity() {

    private val design = Design()
    private var player: ExoPlayer? = null
    private var tab = TAB_EDIT
    /** null = the video is selected; otherwise a layer. */
    private var selectedLayer: Layer? = null
    private var nextId = 1L

    // The edit: clips in order, audio under them, captions over them, and the grade.
    private val clips = mutableListOf<EditClip>()
    private val audioClips = mutableListOf<AudioClip>()
    private var selectedClip: EditClip? = null
    private var selectedAudio: AudioClip? = null
    private var captionWords: List<CaptionWord> = emptyList()
    private var captionLanguage = CaptionLanguage.ENGLISH
    private var grade = ColorGrade()
    private var lutId = Luts.NONE
    private var gradeCube: LutCube? = null
    /** What the preview's grade step reads every frame, so a change doesn't rebuild the pipeline. */
    private val liveGrade = LiveGrade()
    private var gradeJob: kotlinx.coroutines.Job? = null
    private val audioPlayers = mutableMapOf<Long, ExoPlayer>()
    private var recorder: VoiceRecorder? = null
    private var recordingStartMs = 0L

    /** Timeline time of what's on screen, for layer and caption timing in the preview. */
    @Volatile private var previewMs = 0L
    private val totalMs get() = TimelineMap(clips).totalMs
    private val durationMs get() = totalMs

    private lateinit var frame: AspectRatioFrameLayout
    private lateinit var canvasView: DesignCanvasView
    private lateinit var panel: LinearLayout
    private lateinit var tabs: LinearLayout
    private lateinit var timeline: TimelineView
    private lateinit var playButton: ImageButton
    private lateinit var timeText: android.widget.TextView
    private lateinit var playerView: PlayerView
    private lateinit var progressPanel: LinearLayout
    private lateinit var progressBar: LinearProgressIndicator
    private var scope: ScopeView? = null

    /** Where the next picked image goes. */
    private var imageTarget: (Uri) -> Unit = {}
    private val pickImage = registerForActivityResult(ActivityResultContracts.OpenDocument()) { picked ->
        if (picked != null) {
            keepAccess(picked)
            imageTarget(picked)
        }
    }
    private val pickVideo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { picked ->
        if (picked != null) {
            keepAccess(picked)
            design.bgVideo = picked
            design.background = BackgroundKind.VIDEO
            buildPanel()
        }
    }
    private val pickClip = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { picked ->
        if (picked.isNotEmpty()) {
            picked.forEach { keepAccess(it) }
            lifecycleScope.launch {
                val added = withContext(Dispatchers.IO) { picked.mapNotNull { readClip(it) } }
                clips += added
                added.forEach { loadThumbs(it) }
                rebuildPlaylist()
                buildPanel()
            }
        }
    }
    private val pickMusic = registerForActivityResult(ActivityResultContracts.OpenDocument()) { picked ->
        if (picked != null) {
            keepAccess(picked)
            lifecycleScope.launch {
                val clip = withContext(Dispatchers.IO) { readAudio(picked, AudioKind.MUSIC) }
                if (clip == null) {
                    Toast.makeText(this@DesignActivity, R.string.studio_audio_failed, Toast.LENGTH_LONG).show()
                } else {
                    audioClips.removeAll { it.kind == AudioKind.MUSIC }
                    audioClips += clip
                    selectedAudio = clip
                    syncAudioPlayers()
                    refreshTimeline()
                    buildPanel()
                }
            }
        }
    }
    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startVoiceover()
    }
    private var captionSheet: CaptionStyleSheet? = null
    private val pickFont = registerForActivityResult(ActivityResultContracts.OpenDocument()) { picked ->
        if (picked == null) return@registerForActivityResult
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val name = contentResolver.query(picked, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                    CaptionFonts.import(this@DesignActivity, picked, name)
                }
            }
            result.onSuccess { id -> captionSheet?.onFontImported(id) }
                .onFailure { Toast.makeText(this@DesignActivity, it.message ?: "Couldn't import that font", Toast.LENGTH_LONG).show() }
        }
    }

    /** Saved designs point at the files you picked, so keep permission to read them. */
    private fun keepAccess(uri: Uri) {
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    /** A still from the video, for the template previews. */
    private var videoFrame: android.graphics.Bitmap? = null
    private var stickerPack = StickerPack.THREE_D

    private fun dp(v: Float) = Glass.dp(this, v).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val uris = androidx.core.content.IntentCompat.getParcelableArrayListExtra(intent, EXTRA_URIS, Uri::class.java).orEmpty()
            .ifEmpty { listOfNotNull(intent.data) }
        clips += uris.mapNotNull { readClip(it) }
        if (clips.isEmpty()) return finish()
        startShape(clips.first())
        buildUi()
        clips.forEach { loadThumbs(it) }
        player = Players.create(this).also { p ->
            playerView.player = p
            p.repeatMode = Player.REPEAT_MODE_ALL
            p.addListener(object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = matchClip()
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    playButton.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow)
                    syncAudioPlayers()
                }
            })
        }
        rebuildPlaylist()
        applyEffects()
        buildPanel()
        startClock()
    }

    // region Clips and audio

    private fun readClip(uri: Uri): EditClip? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(this, uri)
            val duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return null
            var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1080
            var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1920
            val rotation = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rotation % 180 != 0) w = h.also { h = w }
            if (videoFrame == null) {
                videoFrame = runCatching {
                    r.getScaledFrameAtTime(minOf(1_000_000L, duration * 500), MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 360, 640)
                }.getOrNull()
            }
            EditClip(nextId++, uri, duration, w, h)
        } catch (e: Exception) {
            null
        } finally {
            r.release()
        }
    }

    private fun readAudio(uri: Uri, kind: AudioKind): AudioClip? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(this, uri)
            val duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return null
            val title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?: contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0)?.substringBeforeLast('.') else null
                }
                ?: getString(if (kind == AudioKind.MUSIC) R.string.studio_music else R.string.studio_voiceover)
            AudioClip(nextId++, uri, kind, duration, title)
        } catch (e: Exception) {
            null
        } finally {
            r.release()
        }
    }

    /** The canvas starts on the shape closest to the first clip, with the video filling it. */
    private fun startShape(first: EditClip) {
        val aspect = first.width.toFloat() / first.height
        design.canvas = OutputFormat.entries.minByOrNull { kotlin.math.abs(it.aspect - aspect) } ?: OutputFormat.PORTRAIT_9_16
        canvasVideoAspect = aspect
    }

    private fun loadThumbs(clip: EditClip) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { clip.loadThumbs(this@DesignActivity) }
            refreshTimeline()
        }
    }

    private fun mediaItem(c: EditClip): MediaItem = MediaItem.Builder()
        .setUri(c.uri)
        .setClippingConfiguration(
            MediaItem.ClippingConfiguration.Builder().setStartPositionMs(c.startMs).setEndPositionMs(c.endMs).build()
        )
        .build()

    /** Sends the clips to the preview, keeping the playhead where it was. */
    private fun rebuildPlaylist(atMs: Long = previewMs) {
        val p = player ?: return
        val wasPlaying = p.isPlaying
        p.setMediaItems(clips.map { mediaItem(it) })
        p.prepare()
        seekTimeline(atMs.coerceIn(0, (totalMs - 1).coerceAtLeast(0)))
        if (wasPlaying) p.play()
        refreshTimeline()
    }

    /** The current clip's speed and volume in the preview. */
    private fun matchClip() {
        val p = player ?: return
        val c = clips.getOrNull(p.currentMediaItemIndex) ?: return
        p.setPlaybackSpeed(c.speed)
        p.volume = c.volume.coerceIn(0f, 1f)
    }

    private fun currentTimelineMs(): Long {
        val p = player ?: return 0
        val i = p.currentMediaItemIndex
        val map = TimelineMap(clips)
        val c = clips.getOrNull(i) ?: return 0
        return map.offsets[i] + (p.currentPosition / c.speed).toLong()
    }

    private fun seekTimeline(t: Long) {
        val p = player ?: return
        val (i, pos) = TimelineMap(clips).locate(clips, t)
        if (i < p.mediaItemCount) p.seekTo(i, pos)
        previewMs = t
        matchClip()
        syncAudioPlayers()
    }

    /** Keeps the timeline, the time readout and the overlay clock following the playback. */
    private fun startClock() {
        lifecycleScope.launch {
            while (true) {
                delay(33)
                val p = player ?: continue
                if (p.isPlaying) {
                    previewMs = currentTimelineMs()
                    timeline.timeMs = previewMs
                }
                timeText.text = "${VideoLibrary.formatDuration(previewMs)} / ${VideoLibrary.formatDuration(totalMs)}"
                recorder?.let { if (previewMs >= totalMs - 100) stopVoiceover() }
            }
        }
    }

    /**
     * Paused, the player doesn't draw a new frame by itself, so a change to the shape, border,
     * background or a layer wouldn't show until play. After a touch anywhere (a pill, a slider
     * being dragged, the canvas), redraw the paused frame through the effects, at most every 120 ms.
     */
    private val redrawPaused = Runnable {
        val p = player ?: return@Runnable
        if (p.isPlaying || p.mediaItemCount == 0) return@Runnable
        // ExoPlayer ignores a seek to the position it's already at, so alternate between the
        // frame's time and 1 ms beside it (the same frame) for the seek to happen every time.
        val pos = p.currentPosition
        if (redrawAt < 0 || kotlin.math.abs(pos - redrawAt) > 1) {
            redrawAt = pos
            redrawNudged = false
        }
        redrawNudged = !redrawNudged
        val beside = if (redrawAt > 0) redrawAt - 1 else redrawAt + 1
        p.seekTo(p.currentMediaItemIndex, if (redrawNudged) beside else redrawAt)
    }
    private var redrawAt = -1L
    private var redrawNudged = false
    private var redrawPending = false

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        val handled = super.dispatchTouchEvent(ev)
        val action = ev.actionMasked
        if ((action == android.view.MotionEvent.ACTION_UP || action == android.view.MotionEvent.ACTION_MOVE) && !redrawPending) {
            redrawPending = true
            window.decorView.postDelayed({
                redrawPending = false
                redrawPaused.run()
            }, 120)
        }
        return handled
    }

    private fun togglePlay() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else p.play()
    }

    /** Music and voiceovers play along with the preview, from the same point. */
    private fun syncAudioPlayers() {
        val main = player ?: return
        audioPlayers.keys.filter { id -> audioClips.none { it.id == id } }.forEach { audioPlayers.remove(it)?.release() }
        audioClips.forEach { a ->
            val ap = audioPlayers.getOrPut(a.id) {
                Players.create(this).also {
                    it.setMediaItem(MediaItem.fromUri(a.uri))
                    it.prepare()
                }
            }
            ap.volume = a.volume.coerceIn(0f, 1f)
            val into = previewMs - a.timelineStartMs
            val pos = if (a.kind == AudioKind.MUSIC) a.sourceStartMs + into else into + a.paddedMs
            if (main.isPlaying && into >= 0 && pos < a.durationMs && recorder == null) {
                ap.seekTo(pos)
                ap.play()
            } else {
                ap.pause()
                if (into >= 0) ap.seekTo(pos.coerceAtLeast(0))
            }
        }
    }

    private fun refreshTimeline() {
        if (!::timeline.isInitialized) return
        timeline.clips = clips
        timeline.audio = audioClips
        timeline.layers = design.layers
        timeline.hasCaptions = captionWords.isNotEmpty()
        timeline.captionsLabel = captionWords.take(4).joinToString(" ") { it.text } + "…"
        timeline.selected = selectedClip ?: selectedAudio ?: selectedLayer ?: (if (tab == TAB_CAPTIONS && captionWords.isNotEmpty()) TimelineView.CAPTIONS else null)
        timeline.requestLayout()
        timeline.invalidate()
    }

    // endregion

    private var canvasVideoAspect = 9f / 16f

    // region Layout

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(ContextCompat.getColor(context, R.color.ink)) }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), dp(4f), dp(16f), dp(4f))
        }
        header.addView(ImageButton(this, null, 0, R.style.DF_IconButton).apply {
            setImageResource(R.drawable.ic_arrow_back)
            contentDescription = getString(R.string.back)
            setOnClickListener { finish() }
        })
        header.addView(
            Glass.body(this, getString(R.string.design_title)).apply {
                setTextAppearance(R.style.DF_Text_Title)
                setPadding(dp(8f), 0, 0, 0)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        header.addView(MaterialButton(this).apply {
            text = getString(R.string.editor_save)
            setOnClickListener { save() }
        })
        column.addView(header)

        // The canvas: the player shows the rendered design; the view on top handles touch.
        val stage = FrameLayout(this).apply { setBackgroundColor(Color.rgb(14, 14, 17)) }
        frame = AspectRatioFrameLayout(this).apply {
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setAspectRatio(design.canvas.aspect)
        }
        // Drawn into a TextureView so the Adjust scopes can read the picture back.
        playerView = (layoutInflater.inflate(R.layout.grade_player, frame, false) as PlayerView).apply {
            id = PLAYER_ID
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
            setShutterBackgroundColor(Color.TRANSPARENT)
        }
        frame.addView(playerView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        canvasView = DesignCanvasView(this).apply {
            this.design = this@DesignActivity.design
            videoAspect = canvasVideoAspect
            onSelect = { layer ->
                selectedLayer = layer
                selectedClip = null
                selectedAudio = null
                tab = if (layer == null) TAB_VIDEO else TAB_LAYER
                buildPanel()
            }
        }
        frame.addView(canvasView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        stage.addView(frame, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER).apply {
            setMargins(dp(12f), dp(4f), dp(12f), dp(4f))
        })
        column.addView(stage, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.46f))

        // Play / pause and the time, like Edits.
        val transport = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(16f), 0, dp(16f), 0)
        }
        playButton = ImageButton(this, null, 0, R.style.DF_IconButton).apply {
            setImageResource(R.drawable.ic_play_arrow)
            contentDescription = getString(R.string.studio_play)
            setOnClickListener { togglePlay() }
        }
        timeText = Glass.caption(this, "0:00 / 0:00")
        transport.addView(timeText)
        transport.addView(playButton, LinearLayout.LayoutParams(dp(44f), dp(44f)).apply { marginStart = dp(12f) })
        column.addView(transport)

        timeline = TimelineView(this).apply {
            onScrub = { t ->
                player?.pause()
                seekTimeline(t)
            }
            onSelect = { hit -> onTimelineSelect(hit) }
        }
        column.addView(timeline, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        refreshTimeline()

        val scroll = NestedScrollView(this)
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(2f), dp(20f), dp(24f))
        }
        scroll.addView(panel)
        column.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.54f))

        // The toolbar: every tool one tap away.
        tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8f), dp(4f), dp(8f), dp(4f))
        }
        column.addView(android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(Color.rgb(16, 16, 19))
            addView(tabs)
        })

        progressPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.argb(210, 10, 10, 12))
            isClickable = true
            visibility = View.GONE
        }
        progressBar = LinearProgressIndicator(this).apply {
            max = 100
            setIndicatorColor(ContextCompat.getColor(context, R.color.accent))
        }
        progressPanel.addView(Glass.body(this, getString(R.string.design_saving)).apply { gravity = Gravity.CENTER })
        progressPanel.addView(progressBar, LinearLayout.LayoutParams(dp(220f), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(16f)
        })
        root.addView(progressPanel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime()
            )
            column.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }
    }

    private fun onTimelineSelect(hit: Any?) {
        selectedClip = hit as? EditClip
        selectedAudio = hit as? AudioClip
        selectedLayer = hit as? Layer
        tab = when {
            selectedClip != null -> TAB_EDIT
            selectedAudio != null -> TAB_AUDIO
            selectedLayer != null -> TAB_LAYER
            hit === TimelineView.CAPTIONS -> TAB_CAPTIONS
            else -> tab
        }
        buildPanel()
    }

    // endregion

    // region Preview

    /** Re-sends the effects (needed when layers are added or removed, or the canvas changes). */
    private fun applyEffects() {
        frame.setAspectRatio(design.canvas.aspect)
        canvasView.invalidate()
        val effects = mutableListOf<Effect>()
        // The grade reads its values live (see refreshGrade), so it's always in the list.
        effects += liveGrade.effect()
        effects += DesignEffect(design)
        val clock: (Long) -> Long = { previewMs }
        val overlays = design.layers.map { LayerOverlay(it, clockMs = clock) as TextureOverlay } +
            listOfNotNull(captionOverlay(clock)) + listOfNotNull(BrandKit.overlay(this))
        if (overlays.isNotEmpty()) effects += OverlayEffect(overlays)
        runCatching { player?.setVideoEffects(effects) }
        // Paused, the player draws no new frame by itself: redraw this one through the new effects.
        redrawPaused.run()
        refreshTimeline()
    }

    private fun captionOverlay(clock: (Long) -> Long): TextureOverlay? {
        if (captionWords.isEmpty()) return null
        val style = CaptionStyle.load(this)
        val (w, h) = design.canvasSize
        val lines = Transcriber.lines(captionWords, if (h > w) 24 else 40, style.wordsPerLine)
        val renderer = CaptionRenderer(style, CaptionFonts.typeface(this, style.font), CaptionFonts.variation(style.font))
        return CaptionOverlay(lines, renderer, clock)
    }

    /** Re-bakes the grade's LUT and shows it. */
    private fun refreshGrade() {
        // A slider drag calls this many times a second: only the latest bake matters.
        gradeJob?.cancel()
        gradeJob = lifecycleScope.launch {
            val g = grade
            val id = lutId
            val cube = withContext(Dispatchers.Default) {
                if (g.colourIsNeutral && id == Luts.NONE) null
                else runCatching { Luts.cube(this@DesignActivity, id, RecordProfile.STANDARD, g) }.getOrNull()
            }
            gradeCube = cube
            liveGrade.set(cube, g)
            // Playing, the next frame picks it up; paused, redraw this one.
            redrawPaused.run()
        }
    }

    private fun refreshBackground() {
        lifecycleScope.launch {
            design.backgroundBitmap = withContext(Dispatchers.Default) { DesignArt.background(this@DesignActivity, design) }
        }
    }

    private fun renderLayer(layer: Layer, then: () -> Unit = {}) {
        lifecycleScope.launch {
            val (w, _) = design.canvasSize
            val art = withContext(Dispatchers.Default) { DesignArt.layer(this@DesignActivity, layer, w) }
            layer.frames = art?.takeIf { it.frames.size > 1 }
            layer.bitmap = art?.frames?.firstOrNull()
            canvasView.invalidate()
            refreshTimeline()
            then()
        }
    }

    // endregion

    // region Panels

    private fun flow() = ChipGroup(this).apply {
        chipSpacingHorizontal = dp(8f)
        chipSpacingVertical = dp(8f)
    }

    private fun section(text: String) = panel.addView(Glass.section(this, text))

    private fun note(text: String) = panel.addView(Glass.caption(this, text).apply { setPadding(0, dp(6f), 0, 0) })

    private fun <T> pills(options: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit) {
        val group = flow()
        options.forEach { o -> group.addView(Glass.pill(this, label(o), o == selected) { onPick(o) }) }
        panel.addView(group)
    }

    private fun slider(label: String, from: Float, to: Float, value: Float, format: (Float) -> String, onSet: (Float) -> Unit) {
        panel.addView(Glass.label(this, label).apply { setPadding(0, dp(10f), 0, 0) })
        panel.addView(Slider(this).apply {
            valueFrom = from
            valueTo = to
            this.value = value.coerceIn(from, to)
            setLabelFormatter { format(it) }
            trackActiveTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(context, R.color.accent))
            addOnChangeListener { _, v, fromUser -> if (fromUser) onSet(v) }
        })
    }

    private fun toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
        panel.addView(MaterialSwitch(this).apply {
            text = label
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            isChecked = value
            minHeight = dp(52f)
            setOnCheckedChangeListener { _, c -> onChange(c) }
        })
    }

    private fun colourRow(label: String, colour: Int, onPick: (Int) -> Unit) {
        panel.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10f), 0, 0)
            addView(View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(colour)
                    setStroke(dp(1f), Color.argb(150, 255, 255, 255))
                }
            }, LinearLayout.LayoutParams(dp(28f), dp(28f)))
            addView(Glass.body(context, label).apply { setPadding(dp(12f), 0, 0, 0) })
            isClickable = true
            setOnClickListener { ColorPicker.show(context, label, colour, onPick) }
        })
    }

    /** One toolbar button: an icon over its name, like Edits. */
    private fun toolButton(icon: String, name: String, selected: Boolean, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        minimumWidth = dp(62f)
        setPadding(dp(6f), dp(6f), dp(6f), dp(6f))
        isClickable = true
        isFocusable = true
        contentDescription = name
        val colour = ContextCompat.getColor(context, if (selected) R.color.accent else R.color.text_secondary)
        addView(android.widget.TextView(context).apply {
            text = icon
            textSize = 19f
            gravity = Gravity.CENTER
            setTextColor(colour)
        })
        addView(android.widget.TextView(context).apply {
            text = name
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(colour)
        })
        setOnClickListener { onClick() }
    }

    private fun buildPanel() {
        tabs.removeAllViews()
        TAB_NAMES.forEachIndexed { i, name ->
            if (i == TAB_LAYER && selectedLayer == null) return@forEachIndexed
            tabs.addView(toolButton(TAB_ICONS[i], name, i == tab) {
                tab = i
                if (i != TAB_LAYER) {
                    selectedLayer = if (i == TAB_VIDEO) null else selectedLayer
                }
                if (i != TAB_EDIT) selectedClip = null
                if (i != TAB_AUDIO) selectedAudio = null
                buildPanel()
            })
        }
        panel.removeAllViews()
        scope = null
        when (tab) {
            TAB_EDIT -> editPanel()
            TAB_AUDIO -> audioPanel()
            TAB_CAPTIONS -> captionsPanel()
            TAB_ADJUST -> adjustPanel()
            TAB_TEMPLATES -> templatesPanel()
            TAB_CANVAS -> canvasPanel()
            TAB_BACKGROUND -> backgroundPanel()
            TAB_VIDEO -> videoPanel()
            TAB_TEXT -> textPanel()
            TAB_STICKERS -> stickerPanel()
            TAB_PHOTOS -> photoPanel()
            TAB_LOGO -> logoPanel()
            TAB_CARDS -> cardsPanel()
            TAB_LAYER -> layerPanel()
        }
        refreshTimeline()
        // Changes made from a dialog (colours, text, templates) land here too: show them paused.
        window.decorView.removeCallbacks(redrawPaused)
        window.decorView.postDelayed(redrawPaused, 150)
    }

    private fun row(vararg buttons: Pair<String, () -> Unit>) {
        panel.addView(flow().apply {
            setPadding(0, dp(6f), 0, 0)
            buttons.forEach { (label, action) -> addView(Glass.pill(context, label) { action() }) }
        })
    }

    // region Edit (clips)

    private fun editPanel() {
        val clip = selectedClip
        if (clip == null) {
            section(getString(R.string.studio_clips))
            note(getString(R.string.studio_clips_note, clips.size, VideoLibrary.formatDuration(totalMs)))
            row(
                getString(R.string.studio_add_clip) to { pickClip.launch(arrayOf("video/*")) },
                getString(R.string.studio_split_here) to { splitAtPlayhead() },
                getString(R.string.editor_remove_silences) to { removeSilences() },
            )
            return
        }
        val index = clips.indexOf(clip)
        section(getString(R.string.studio_clip_title, index + 1, VideoLibrary.formatDuration(clip.lengthMs)))
        row(
            getString(R.string.studio_split_here) to { splitAtPlayhead() },
            getString(R.string.studio_duplicate) to {
                clips.add(index + 1, clip.copy(nextId++))
                rebuildPlaylist()
                buildPanel()
            },
            "◀" to {
                if (index > 0) {
                    clips.removeAt(index)
                    clips.add(index - 1, clip)
                    rebuildPlaylist()
                    buildPanel()
                }
            },
            "▶" to {
                if (index < clips.size - 1) {
                    clips.removeAt(index)
                    clips.add(index + 1, clip)
                    rebuildPlaylist()
                    buildPanel()
                }
            },
            getString(R.string.delete) to {
                if (clips.size > 1) {
                    clips.remove(clip)
                    selectedClip = null
                    rebuildPlaylist(0)
                    buildPanel()
                } else {
                    Toast.makeText(this, R.string.studio_keep_one, Toast.LENGTH_SHORT).show()
                }
            },
        )
        section(getString(R.string.studio_trim))
        panel.addView(RangeSlider(this).apply {
            // In tenths of a second. With a step, the range and the values have to land on whole
            // steps (Slider throws otherwise), and clips are rarely a whole number of tenths long.
            val tenths = ((clip.durationMs + 99) / 100).coerceAtLeast(1L).toFloat()
            valueFrom = 0f
            valueTo = tenths
            stepSize = 1f
            val start = (clip.startMs / 100).toFloat().coerceIn(0f, tenths)
            val end = ((clip.endMs + 50) / 100).toFloat().coerceIn(start, tenths)
            values = listOf(start, end)
            setMinSeparationValue(minOf(3f, end - start))
            setLabelFormatter { VideoLibrary.formatDuration((it * 100).toLong()) }
            contentDescription = getString(R.string.studio_trim)
            addOnChangeListener { s, _, fromUser ->
                if (fromUser) {
                    clip.startMs = (s.values[0] * 100).toLong()
                    clip.endMs = (s.values[1] * 100).toLong().coerceAtMost(clip.durationMs)
                }
            }
            addOnSliderTouchListener(object : RangeSlider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: RangeSlider) = Unit
                override fun onStopTrackingTouch(slider: RangeSlider) {
                    rebuildPlaylist(TimelineMap(clips).offsets[clips.indexOf(clip)])
                }
            })
        })
        section(getString(R.string.studio_speed))
        pills(listOf(0.25f, 0.5f, 0.75f, 1f, 1.5f, 2f, 3f, 4f), clip.speed, { (if (it % 1f == 0f) "${it.toInt()}" else "$it") + "×" }) {
            clip.speed = it
            rebuildPlaylist(TimelineMap(clips).offsets[clips.indexOf(clip)])
            buildPanel()
        }
        slider(getString(R.string.studio_volume), 0f, 2f, clip.volume, { "${(it * 100).toInt()}%" }) {
            clip.volume = it
            matchClip()
        }
        note(getString(R.string.studio_volume_note))
    }

    /** Cuts the clip under the playhead in two. */
    private fun splitAtPlayhead() {
        val t = previewMs
        val (i, pos) = TimelineMap(clips).locate(clips, t)
        val c = clips.getOrNull(i) ?: return
        val cut = c.startMs + pos
        if (cut - c.startMs < 300 || c.endMs - cut < 300) {
            Toast.makeText(this, R.string.studio_split_too_close, Toast.LENGTH_SHORT).show()
            return
        }
        val second = c.copy(nextId++, from = cut, to = c.endMs)
        c.endMs = cut
        clips.add(i + 1, second)
        selectedClip = second
        tab = TAB_EDIT
        rebuildPlaylist(t)
        buildPanel()
    }

    /** Finds the pauses in every clip and cuts them out, splitting clips where needed. */
    private fun removeSilences() {
        note(getString(R.string.editor_finding_pauses))
        lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) {
                clips.flatMap { c ->
                    val pcm = AudioScan.mono16k(this@DesignActivity, c.uri)
                    val ranges = pcm?.let { AudioScan.speechRanges(it, 600) }.orEmpty()
                        .map { maxOf(it.first, c.startMs)..minOf(it.last, c.endMs) }
                        .filter { it.last - it.first > 200 }
                    if (ranges.isEmpty()) listOf(c) else ranges.map { r -> c.copy(nextId++, r.first, r.last) }
                }
            }
            val before = totalMs
            clips.clear()
            clips += result
            selectedClip = null
            rebuildPlaylist(0)
            buildPanel()
            Toast.makeText(
                this@DesignActivity,
                getString(R.string.studio_silences_removed, VideoLibrary.formatDuration((before - totalMs).coerceAtLeast(0))),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    // endregion

    // region Audio

    private fun audioPanel() {
        section(getString(R.string.studio_audio))
        row(
            getString(R.string.studio_add_music) to { pickMusic.launch(arrayOf("audio/*")) },
            (if (recorder == null) getString(R.string.studio_record_voice) else getString(R.string.studio_stop_voice)) to {
                if (recorder == null) startVoiceover() else stopVoiceover()
            },
        )
        if (recorder != null) note(getString(R.string.studio_recording_voice))
        val allMuted = clips.all { it.volume == 0f }
        toggle(getString(R.string.studio_mute_clips), allMuted) { mute ->
            clips.forEach { it.volume = if (mute) 0f else 1f }
            matchClip()
            refreshTimeline()
        }
        val a = selectedAudio ?: audioClips.firstOrNull()
        if (a == null) {
            note(getString(R.string.studio_audio_note))
            return
        }
        selectedAudio = a
        section((if (a.kind == AudioKind.MUSIC) "♫  " else "🎙  ") + a.title)
        slider(getString(R.string.studio_volume), 0f, 1f, a.volume, { "${(it * 100).toInt()}%" }) {
            a.volume = it
            audioPlayers[a.id]?.volume = it
        }
        if (a.kind == AudioKind.MUSIC) {
            slider(getString(R.string.studio_song_start), 0f, (a.durationMs / 1000f).coerceAtLeast(1f), a.sourceStartMs / 1000f, { VideoLibrary.formatDuration((it * 1000).toLong()) }) {
                a.sourceStartMs = (it * 1000).toLong()
                syncAudioPlayers()
            }
        }
        slider(getString(R.string.studio_fade_in), 0f, 5f, a.fadeInMs / 1000f, { "%.1f s".format(it) }) { a.fadeInMs = (it * 1000).toLong() }
        slider(getString(R.string.studio_fade_out), 0f, 5f, a.fadeOutMs / 1000f, { "%.1f s".format(it) }) { a.fadeOutMs = (it * 1000).toLong() }
        row(getString(R.string.delete) to {
            audioClips.remove(a)
            selectedAudio = null
            syncAudioPlayers()
            buildPanel()
        })
        note(getString(R.string.studio_music_note))
    }

    private fun startVoiceover() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            askMic.launch(android.Manifest.permission.RECORD_AUDIO)
            return
        }
        val dir = File(filesDir, "voiceovers").apply { mkdirs() }
        val file = File(dir, "voice_${System.currentTimeMillis()}.wav")
        recordingStartMs = previewMs
        recorder = VoiceRecorder(file, recordingStartMs).also { it.start() }
        voiceFile = file
        player?.play()
        buildPanel()
    }

    private var voiceFile: File? = null

    private fun stopVoiceover() {
        val r = recorder ?: return
        recorder = null
        player?.pause()
        r.stop()
        val file = voiceFile ?: return
        readAudio(Uri.fromFile(file), AudioKind.VOICE)?.let { a ->
            a.timelineStartMs = recordingStartMs
            a.paddedMs = recordingStartMs
            audioClips += a
            selectedAudio = a
        }
        tab = TAB_AUDIO
        syncAudioPlayers()
        buildPanel()
    }

    // endregion

    // region Captions

    private fun captionsPanel() {
        section(getString(R.string.studio_captions))
        if (!Transcriber.isAvailable(this)) {
            note(getString(R.string.studio_captions_unavailable))
            return
        }
        pills(listOf(CaptionLanguage.ENGLISH, CaptionLanguage.HINGLISH), captionLanguage, { it.label }) {
            captionLanguage = it
            buildPanel()
        }
        row(
            (if (captionWords.isEmpty()) getString(R.string.caption_editor_make) else getString(R.string.studio_captions_again)) to { makeCaptions() },
        )
        if (captionWords.isNotEmpty()) {
            note(getString(R.string.studio_captions_count, captionWords.size))
            row(
                getString(R.string.studio_captions_edit) to { editCaptions() },
                getString(R.string.caption_editor_style) to {
                    captionSheet = CaptionStyleSheet(this, onImportFont = {
                        pickFont.launch(arrayOf("font/*", "application/x-font-ttf", "application/x-font-otf", "application/octet-stream"))
                    }, onClosed = { applyEffects() }).also { it.show() }
                },
                getString(R.string.studio_captions_remove) to {
                    captionWords = emptyList()
                    applyEffects()
                    buildPanel()
                },
            )
        }
        note(getString(R.string.studio_captions_note))
    }

    /** Transcribes every clip (only the parts used) and lays the words on the timeline. */
    private fun makeCaptions() {
        panel.removeAllViews()
        section(getString(R.string.studio_captions))
        note(getString(R.string.caption_editor_listening))
        lifecycleScope.launch {
            val map = TimelineMap(clips)
            val cache = mutableMapOf<Uri, List<CaptionWord>>()
            val words = mutableListOf<CaptionWord>()
            clips.forEachIndexed { i, c ->
                val all = cache.getOrPut(c.uri) { Transcriber.transcribe(this@DesignActivity, c.uri, captionLanguage) { } }
                all.filter { it.startMs >= c.startMs && it.endMs <= c.endMs + 200 }.forEach { w ->
                    val start = map.offsets[i] + ((w.startMs - c.startMs) / c.speed).toLong()
                    val end = map.offsets[i] + ((minOf(w.endMs, c.endMs) - c.startMs) / c.speed).toLong()
                    words += w.copy(startMs = start, endMs = maxOf(end, start + 80))
                }
            }
            captionWords = words
            if (words.isEmpty()) Toast.makeText(this@DesignActivity, R.string.caption_editor_no_speech, Toast.LENGTH_LONG).show()
            applyEffects()
            buildPanel()
        }
    }

    /** Opens the caption editor on these words; corrections and word looks come back here. */
    private fun editCaptions() {
        lifecycleScope.launch {
            CaptionEditorActivity.review(this@DesignActivity)
            captionWords = CaptionReview.review(captionWords)
            applyEffects()
            buildPanel()
        }
    }

    // endregion

    // region Adjust (grading)

    private fun adjustPanel() {
        section(getString(R.string.studio_adjust))
        val sc = ScopeView(this).apply { contentDescription = getString(R.string.grade_scopes) }
        scope = sc
        val modes = flow()
        fun drawModes() {
            modes.removeAllViews()
            ScopeMode.entries.forEach { m ->
                modes.addView(Glass.pill(this, m.label, m == sc.mode) {
                    sc.mode = m
                    sc.visibility = if (m == ScopeMode.OFF) View.GONE else View.VISIBLE
                    drawModes()
                })
            }
        }
        drawModes()
        panel.addView(modes)
        panel.addView(sc, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(120f)).apply { topMargin = dp(6f) })
        feedScope(sc)
        section(getString(R.string.grade_look))
        val looks = Luts.available(this, RecordProfile.STANDARD)
        pills(looks, looks.firstOrNull { it.id == lutId } ?: looks.first(), { it.name }) {
            lutId = it.id
            refreshGrade()
            buildPanel()
        }
        val controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(controls)
        GradeControls(this, controls, grade) {
            grade = it
            refreshGrade()
        }.build()
    }

    /** Reads the picture back a few times a second while the scope is showing. */
    private fun feedScope(sc: ScopeView) {
        lifecycleScope.launch {
            while (scope === sc) {
                delay(200)
                if (sc.mode == ScopeMode.OFF) continue
                val texture = playerView.videoSurfaceView as? android.view.TextureView ?: continue
                if (!texture.isAvailable || texture.width == 0) continue
                val w = 256 // one sample per waveform column, so it has no empty stripes
                val h = (w * texture.height / texture.width.toFloat()).toInt().coerceIn(32, 400)
                val bmp = runCatching { texture.getBitmap(w, h) }.getOrNull() ?: continue
                withContext(Dispatchers.Default) { sc.analyse(bmp) }
                bmp.recycle()
            }
        }
    }

    // endregion

    private fun canvasPanel() {
        section(getString(R.string.design_canvas))
        pills(OutputFormat.entries, design.canvas, { "${it.label}  ${it.use.substringBefore(" ·")}" }) {
            design.canvas = it
            refreshBackground()
            design.layers.forEach { l -> renderLayer(l) }
            applyEffects()
            buildPanel()
        }
        note(getString(R.string.design_canvas_note))
    }

    private fun backgroundPanel() {
        section(getString(R.string.design_background))
        pills(BackgroundKind.entries, design.background, { it.label }) {
            design.background = it
            if (it == BackgroundKind.IMAGE && design.bgImage == null) {
                pickImageFor { u -> design.bgImage = u; refreshBackground() }
            }
            if (it == BackgroundKind.VIDEO && design.bgVideo == null) pickVideo.launch(arrayOf("video/*"))
            refreshBackground()
            buildPanel()
        }
        when (design.background) {
            BackgroundKind.COLOR -> colourRow(getString(R.string.design_colour), design.bgColor) {
                design.bgColor = it
                refreshBackground()
                buildPanel()
            }
            BackgroundKind.GRADIENT -> {
                colourRow(getString(R.string.design_from), design.bgColor) { design.bgColor = it; refreshBackground(); buildPanel() }
                colourRow(getString(R.string.design_to), design.bgColor2) { design.bgColor2 = it; refreshBackground(); buildPanel() }
            }
            BackgroundKind.IMAGE -> panel.addView(Glass.pill(this, getString(R.string.design_pick_photo)) {
                pickImageFor { u -> design.bgImage = u; refreshBackground() }
            })
            BackgroundKind.BLUR -> note(getString(R.string.design_blur_note))
            BackgroundKind.GREEN -> note(getString(R.string.design_green_note))
            BackgroundKind.VIDEO -> {
                panel.addView(Glass.pill(this, getString(if (design.bgVideo == null) R.string.design_pick_video else R.string.design_change_video)) {
                    pickVideo.launch(arrayOf("video/*"))
                })
                toggle(getString(R.string.design_bg_video_blur), design.bgVideoBlur) { design.bgVideoBlur = it }
                note(getString(R.string.design_bg_video_note))
            }
        }
        note(getString(R.string.design_background_note))
    }

    private fun videoPanel() {
        val v = design.video
        section(getString(R.string.design_video))
        note(getString(R.string.design_video_note))
        slider(getString(R.string.design_size), 0.2f, 1.6f, v.scale, { "${(it * 100).toInt()}%" }) { v.scale = it; canvasView.invalidate() }
        slider(getString(R.string.design_rotate), -180f, 180f, v.rotation, { "${it.toInt()}°" }) { v.rotation = it; canvasView.invalidate() }
        section(getString(R.string.design_shape))
        pills(VideoMask.entries, v.mask, { it.label }) { v.mask = it; buildPanel() }
        if (v.mask == VideoMask.ROUNDED) slider(getString(R.string.design_corners), 0f, 50f, v.corner.toFloat(), { "${it.toInt()}%" }) { v.corner = it.toInt() }
        slider(getString(R.string.design_border), 0f, 30f, v.border.toFloat(), { "${it.toInt()}" }) { v.border = it.toInt() }
        colourRow(getString(R.string.design_border_colour), v.borderColor) { v.borderColor = it; buildPanel() }
        section(getString(R.string.design_cutout))
        toggle(getString(R.string.design_cutout_person), v.cutout) { v.cutout = it }
        note(getString(R.string.design_cutout_note))
        panel.addView(Glass.pill(this, getString(R.string.design_reset_video)) {
            v.x = 0.5f; v.y = 0.5f; v.scale = 1f; v.rotation = 0f
            canvasView.invalidate()
            buildPanel()
        })
    }

    private fun newLayer(kind: LayerKind, setup: (Layer) -> Unit) {
        val layer = Layer(nextId++, kind).apply { y = 0.35f + 0.05f * (design.layers.size % 5) }
        setup(layer)
        design.layers += layer
        selectedLayer = layer
        renderLayer(layer) {
            applyEffects()
            tab = TAB_LAYER
            buildPanel()
        }
    }

    private fun textPanel() {
        section(getString(R.string.design_text))
        val input = EditText(this).apply {
            hint = getString(R.string.design_text_hint)
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(context, R.color.text_tertiary))
            background = ContextCompat.getDrawable(context, R.drawable.bg_glass_card)
            setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
        }
        panel.addView(input)
        panel.addView(Glass.pill(this, getString(R.string.design_add_text)) {
            val t = input.text.toString().trim()
            if (t.isNotEmpty()) newLayer(LayerKind.TEXT) { it.text = t }
        }.apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10f) } })
        note(getString(R.string.design_text_note))
    }

    private fun stickerPanel() {
        section(getString(R.string.design_stickers))
        pills(StickerPack.entries, stickerPack, { it.label }) { stickerPack = it; buildPanel() }
        when (stickerPack) {
            StickerPack.EMOJI -> {
                val group = flow()
                STICKERS.forEach { emoji ->
                    group.addView(Glass.pill(this, emoji) { newLayer(LayerKind.STICKER) { it.text = emoji } }.apply { textSize = 22f })
                }
                panel.addView(group)
            }
            StickerPack.THREE_D, StickerPack.ANIMATED -> stickerGrid(Stickers.ids(this, stickerPack))
            StickerPack.YOURS -> {
                panel.addView(Glass.pill(this, getString(R.string.design_add_gif)) {
                    pickImageFor { u -> newLayer(LayerKind.IMAGE) { it.imageUri = u } }
                })
                note(getString(R.string.design_gif_note))
            }
        }
        note(getString(R.string.design_stickers_note))
    }

    /** Sticker thumbnails, loaded in the background; tap one to add it. */
    private fun stickerGrid(ids: List<String>) {
        val size = dp(58f)
        val grid = android.widget.GridLayout(this).apply { columnCount = 5 }
        val views = ids.map { id ->
            ImageView(this).apply {
                setPadding(dp(6f), dp(6f), dp(6f), dp(6f))
                background = ContextCompat.getDrawable(context, R.drawable.bg_glass_card)
                contentDescription = id.substringAfter('/').replace('_', ' ')
                setOnClickListener { newLayer(LayerKind.STICKER) { it.asset = id; it.scale = 0.9f } }
            }.also { grid.addView(it, android.widget.GridLayout.LayoutParams().apply { width = size; height = size; setMargins(dp(4f), dp(4f), dp(4f), dp(4f)) }) }
        }
        panel.addView(grid)
        lifecycleScope.launch {
            ids.forEachIndexed { i, id ->
                val thumb = withContext(Dispatchers.IO) { Stickers.thumbnail(this@DesignActivity, id) }
                views[i].setImageBitmap(thumb)
            }
        }
    }

    private fun templatesPanel() {
        section(getString(R.string.design_templates))
        note(getString(R.string.design_templates_note))
        val templates = DesignTemplates.all(this)
        val grid = android.widget.GridLayout(this).apply { columnCount = 3 }
        val thumbW = dp(96f)
        val views = templates.map { t ->
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(4f), dp(8f), dp(4f), dp(4f))
                addView(ImageView(context).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    background = ContextCompat.getDrawable(context, R.drawable.bg_glass_card)
                }, LinearLayout.LayoutParams(thumbW, thumbW * 16 / 9))
                addView(Glass.caption(context, t.name).apply { gravity = Gravity.CENTER; maxLines = 2 })
                isClickable = true
                setOnClickListener { applyTemplate(t) }
                if (!t.builtIn) setOnLongClickListener {
                    androidx.appcompat.app.AlertDialog.Builder(context)
                        .setTitle(getString(R.string.design_delete_template, t.name))
                        .setPositiveButton(R.string.delete) { _, _ -> DesignTemplates.delete(context, t); buildPanel() }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                    true
                }
            }.also { grid.addView(it, android.widget.GridLayout.LayoutParams().apply { width = thumbW + dp(12f) }) }
        }
        panel.addView(grid)
        lifecycleScope.launch {
            templates.forEachIndexed { i, t ->
                val thumb = withContext(Dispatchers.Default) {
                    runCatching { DesignTemplates.thumbnail(this@DesignActivity, t, videoFrame, thumbW) }.getOrNull()
                }
                ((views[i].getChildAt(0)) as ImageView).setImageBitmap(thumb)
            }
        }
        panel.addView(Glass.pill(this, getString(R.string.design_save_template)) { askTemplateName() }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14f) }
        })
        note(getString(R.string.design_save_template_note))
    }

    private fun askTemplateName() {
        val input = EditText(this).apply {
            hint = getString(R.string.design_template_name)
            setSingleLine()
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.design_save_template)
            .setView(FrameLayout(this).apply {
                setPadding(dp(20f), dp(8f), dp(20f), 0)
                addView(input)
            })
            .setPositiveButton(R.string.editor_save) { _, _ ->
                val name = input.text.toString().trim().ifEmpty { getString(R.string.design_template_default) }
                DesignTemplates.save(this, design, name)
                Toast.makeText(this, getString(R.string.design_template_saved, name), Toast.LENGTH_SHORT).show()
                buildPanel()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun applyTemplate(t: DesignTemplate) {
        DesignTemplates.applyTo(t.json, design) { nextId++ }
        selectedLayer = null
        refreshBackground()
        var pending = design.layers.size
        if (pending == 0) applyEffects()
        design.layers.forEach { l ->
            renderLayer(l) {
                pending--
                if (pending == 0) applyEffects()
            }
        }
        applyEffects()
        buildPanel()
    }

    private fun photoPanel() {
        section(getString(R.string.design_photos))
        panel.addView(Glass.pill(this, getString(R.string.design_add_photo)) {
            pickImageFor { u -> newLayer(LayerKind.IMAGE) { it.imageUri = u } }
        })
        panel.addView(Glass.pill(this, getString(R.string.design_add_cutout)) {
            pickImageFor { u -> newLayer(LayerKind.IMAGE) { it.imageUri = u; it.cutout = true } }
        }.apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8f) } })
        note(getString(R.string.design_photos_note))
    }

    private fun logoPanel() {
        val s = BrandKit.settings(this)
        section(getString(R.string.design_logo))
        BrandKit.logo(this)?.let { logo ->
            panel.addView(ImageView(this).apply {
                setImageBitmap(logo)
                adjustViewBounds = true
                maxHeight = dp(80f)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(80f)))
        }
        panel.addView(Glass.pill(this, getString(R.string.design_pick_logo)) {
            pickImageFor { u -> setLogo(u, cutout = false) }
        })
        panel.addView(Glass.pill(this, getString(R.string.design_pick_logo_cutout)) {
            pickImageFor { u -> setLogo(u, cutout = true) }
        }.apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8f) } })
        toggle(getString(R.string.design_logo_every_video), s.enabled) {
            BrandKit.save(this, BrandKit.settings(this).copy(enabled = it))
            applyEffects()
        }
        pills(Corner.entries, s.corner, { it.label }) {
            BrandKit.save(this, BrandKit.settings(this).copy(corner = it))
            applyEffects()
            buildPanel()
        }
        slider(getString(R.string.design_size), 6f, 40f, s.sizePct.toFloat(), { "${it.toInt()}%" }) {
            BrandKit.save(this, BrandKit.settings(this).copy(sizePct = it.toInt()))
            applyEffects()
        }
        slider(getString(R.string.design_opacity), 10f, 100f, s.opacity.toFloat(), { "${it.toInt()}%" }) {
            BrandKit.save(this, BrandKit.settings(this).copy(opacity = it.toInt()))
            applyEffects()
        }
        note(getString(R.string.design_logo_note))
    }

    private fun setLogo(u: Uri, cutout: Boolean) {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.Default) { runCatching { BrandKit.setLogo(this@DesignActivity, u, cutout) }.isSuccess }
            if (ok) BrandKit.save(this@DesignActivity, BrandKit.settings(this@DesignActivity).copy(enabled = true))
            applyEffects()
            buildPanel()
        }
    }

    private fun cardsPanel() {
        cardEditor(getString(R.string.design_intro), design.intro) { design.intro = it; buildPanel() }
        cardEditor(getString(R.string.design_outro), design.outro) { design.outro = it; buildPanel() }
        note(getString(R.string.design_cards_note))
    }

    private fun cardEditor(title: String, card: Card?, onChange: (Card?) -> Unit) {
        section(title)
        toggle(getString(R.string.design_card_on), card != null) { on -> onChange(if (on) Card(title = getString(R.string.design_card_default)) else null) }
        if (card == null) return
        val (w, h) = design.canvasSize
        panel.addView(ImageView(this).apply {
            setImageBitmap(DesignArt.card(context, card, w / 6, h / 6))
            adjustViewBounds = true
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(140f)).apply { topMargin = dp(8f) })
        fun field(hint: String, value: String, set: (String) -> Unit) {
            panel.addView(EditText(this).apply {
                setText(value)
                this.hint = hint
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                setHintTextColor(ContextCompat.getColor(context, R.color.text_tertiary))
                background = ContextCompat.getDrawable(context, R.drawable.bg_glass_card)
                setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8f) }
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                    override fun afterTextChanged(s: Editable?) = set(s.toString())
                })
            })
        }
        var current: Card = card
        field(getString(R.string.design_card_title), card.title) { current = current.copy(title = it); if (title == getString(R.string.design_intro)) design.intro = current else design.outro = current }
        field(getString(R.string.design_card_subtitle), card.subtitle) { current = current.copy(subtitle = it); if (title == getString(R.string.design_intro)) design.intro = current else design.outro = current }
        colourRow(getString(R.string.design_from), card.color1) { onChange(current.copy(color1 = it)) }
        colourRow(getString(R.string.design_to), card.color2) { onChange(current.copy(color2 = it)) }
        colourRow(getString(R.string.design_text_colour), card.textColor) { onChange(current.copy(textColor = it)) }
        pills(CaptionFonts.BUNDLED, CaptionFonts.BUNDLED.firstOrNull { it.id == card.font } ?: CaptionFonts.BUNDLED[0], { it.name }) {
            onChange(current.copy(font = it.id))
        }
        panel.addView(Glass.pill(this, getString(R.string.design_card_photo)) {
            pickImageFor { u -> onChange(current.copy(imageUri = u)) }
        })
        slider(getString(R.string.design_card_length), 1f, 6f, card.durationMs / 1000f, { "%.1f s".format(it) }) {
            current = current.copy(durationMs = (it * 1000).toLong())
            if (title == getString(R.string.design_intro)) design.intro = current else design.outro = current
        }
    }

    private fun layerPanel() {
        val layer = selectedLayer ?: return
        section(getString(R.string.design_layer))
        if (layer.kind == LayerKind.TEXT) {
            panel.addView(EditText(this).apply {
                setText(layer.text)
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                background = ContextCompat.getDrawable(context, R.drawable.bg_glass_card)
                setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                    override fun afterTextChanged(s: Editable?) {
                        layer.text = s.toString()
                        renderLayer(layer)
                    }
                })
            })
            colourRow(getString(R.string.design_text_colour), layer.color) { layer.color = it; renderLayer(layer); buildPanel() }
            pills(CaptionFonts.all(this), CaptionFonts.all(this).firstOrNull { it.id == layer.font } ?: CaptionFonts.BUNDLED[0], { it.name }) {
                layer.font = it.id
                renderLayer(layer)
                buildPanel()
            }
            toggle(getString(R.string.design_outline), layer.outline) { layer.outline = it; renderLayer(layer) }
        }
        if (layer.kind == LayerKind.IMAGE) {
            toggle(getString(R.string.design_cutout_person), layer.cutout) { layer.cutout = it; renderLayer(layer) }
        }
        slider(getString(R.string.design_size), 0.2f, 4f, layer.scale, { "${(it * 100).toInt()}%" }) { layer.scale = it; canvasView.invalidate() }
        slider(getString(R.string.design_rotate), -180f, 180f, layer.rotation, { "${it.toInt()}°" }) { layer.rotation = it; canvasView.invalidate() }
        slider(getString(R.string.design_opacity), 0.1f, 1f, layer.opacity, { "${(it * 100).toInt()}%" }) { layer.opacity = it }
        section(getString(R.string.design_when))
        if (durationMs > 0) {
            val total = durationMs / 1000f
            panel.addView(RangeSlider(this).apply {
                valueFrom = 0f
                valueTo = total
                values = listOf((layer.startMs / 1000f).coerceIn(0f, total), (layer.endMs.coerceAtMost(durationMs) / 1000f).coerceIn(0f, total))
                setLabelFormatter { VideoLibrary.formatDuration((it * 1000).toLong()) }
                addOnChangeListener { s, _, _ ->
                    layer.startMs = (s.values[0] * 1000).toLong()
                    layer.endMs = if (s.values[1] >= total) Long.MAX_VALUE else (s.values[1] * 1000).toLong()
                }
            })
        }
        pills(LayerAnimation.entries, layer.animation, { it.label }) { layer.animation = it; buildPanel() }
        section(getString(R.string.design_motion))
        pills(LayerMotion.entries, layer.motion, { it.label }) { layer.motion = it; buildPanel() }
        panel.addView(flow().apply {
            setPadding(0, dp(10f), 0, 0)
            addView(Glass.pill(context, getString(R.string.design_forward)) {
                val i = design.layers.indexOf(layer)
                if (i < design.layers.size - 1) {
                    design.layers.removeAt(i)
                    design.layers.add(i + 1, layer)
                    applyEffects()
                }
            })
            addView(Glass.pill(context, getString(R.string.design_backward)) {
                val i = design.layers.indexOf(layer)
                if (i > 0) {
                    design.layers.removeAt(i)
                    design.layers.add(i - 1, layer)
                    applyEffects()
                }
            })
            addView(Glass.pill(context, getString(R.string.delete)) {
                design.layers.remove(layer)
                selectedLayer = null
                tab = TAB_TEXT
                applyEffects()
                buildPanel()
            })
        })
        note(getString(R.string.design_layer_note))
    }

    private fun pickImageFor(target: (Uri) -> Unit) {
        imageTarget = target
        pickImage.launch(arrayOf("image/*"))
    }

    // endregion

    // region Save

    private fun cardItem(card: Card, name: String): EditedMediaItem {
        val (w, h) = design.canvasSize
        val file = File(cacheDir, "$name.png")
        file.outputStream().use { DesignArt.card(this, card, w, h).compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        return EditedMediaItem.Builder(
            MediaItem.Builder().setUri(Uri.fromFile(file)).setImageDurationMs(card.durationMs).build()
        )
            .setFrameRate(30)
            .setEffects(Effects(emptyList(), listOf(Presentation.createForWidthAndHeight(w, h, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP))))
            .build()
    }

    private fun gain(g: Float) = androidx.media3.common.audio.ChannelMixingAudioProcessor().apply {
        for (ch in 1..8) putChannelMixingMatrix(androidx.media3.common.audio.ChannelMixingMatrix.create(ch, ch).scaleBy(g))
    }

    /** One clip for the export: trimmed, at its speed and volume, graded, then placed on the canvas. */
    private fun clipItem(c: EditClip): EditedMediaItem {
        val audio = mutableListOf<androidx.media3.common.audio.AudioProcessor>()
        val video = mutableListOf<Effect>()
        if (c.speed != 1f) {
            audio += androidx.media3.common.audio.SonicAudioProcessor().apply {
                setSpeed(c.speed)
                setPitch(1f)
            }
            video += androidx.media3.effect.SpeedChangeEffect(c.speed)
        }
        if (c.volume != 1f) audio += gain(c.volume)
        gradeCube?.let { video += androidx.media3.effect.SingleColorLut.createFromCube(it.toArgbCube()) }
        if (grade.hasFinish) video += GradeFinishEffect(grade)
        video += DesignEffect(design)
        return EditedMediaItem.Builder(mediaItem(c)).setEffects(Effects(audio, video)).build()
    }

    /** Music or a voiceover as its own audio-only track, cut to the length of the video. */
    private fun audioItem(a: AudioClip, outputMs: Long, leadMs: Long): EditedMediaItem? {
        var uri = a.uri
        // Voiceovers are padded to the clips' start; an intro card needs a little more silence.
        if (a.kind == AudioKind.VOICE && leadMs > 0) {
            val src = File(uri.path ?: return null)
            val padded = File(cacheDir, "pad_${src.name}")
            padWav(src, padded, leadMs)
            uri = Uri.fromFile(padded)
        }
        val from = if (a.kind == AudioKind.MUSIC) a.sourceStartMs else 0L
        val fileLength = a.durationMs + (if (a.kind == AudioKind.VOICE) leadMs else 0L)
        val length = minOf(fileLength - from, outputMs)
        if (length <= 200) return null
        val item = MediaItem.Builder().setUri(uri).setClippingConfiguration(
            MediaItem.ClippingConfiguration.Builder().setStartPositionMs(from).setEndPositionMs(from + length).build()
        ).build()
        val processors = mutableListOf<androidx.media3.common.audio.AudioProcessor>(gain(a.volume))
        if (a.fadeInMs > 0 || a.fadeOutMs > 0) processors += FadeAudioProcessor(a.fadeInMs * 1000, a.fadeOutMs * 1000, length * 1000)
        return EditedMediaItem.Builder(item).setRemoveVideo(true).setEffects(Effects(processors, emptyList())).build()
    }

    private fun padWav(src: File, dst: File, leadMs: Long) {
        val bytes = src.readBytes()
        if (bytes.size < 44) return
        val rate = java.nio.ByteBuffer.wrap(bytes, 24, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
        val lead = (leadMs * rate / 1000 * 2).toInt()
        val data = bytes.size - 44 + lead
        val header = bytes.copyOf(44)
        java.nio.ByteBuffer.wrap(header).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
            putInt(4, 36 + data)
            putInt(40, data)
        }
        dst.outputStream().use {
            it.write(header)
            it.write(ByteArray(lead))
            it.write(bytes, 44, bytes.size - 44)
        }
    }

    private fun save() {
        player?.pause()
        if (recorder != null) stopVoiceover()
        progressPanel.isVisible = true
        val name = "Edit_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        val output = File(cacheDir, name)
        lifecycleScope.launch {
            try {
                val (w, h) = design.canvasSize
                val items = mutableListOf<EditedMediaItem>()
                val intro = design.intro
                val outro = design.outro
                withContext(Dispatchers.Default) {
                    intro?.let { items += cardItem(it, "intro") }
                    clips.forEach { items += clipItem(it) }
                    outro?.let { items += cardItem(it, "outro") }
                }
                val introMs = intro?.durationMs ?: 0L
                val outputMs = introMs + totalMs + (outro?.durationMs ?: 0L)
                val audioSequences = withContext(Dispatchers.IO) {
                    audioClips.mapNotNull { a -> audioItem(a, outputMs, introMs)?.let { EditedMediaItemSequence(listOf(it)) } }
                }
                // Layers and captions sit on the whole timeline, shifted by the intro so their times match the video.
                val offsetUs = introMs * 1000
                val overlays = design.layers.map { LayerOverlay(it, offsetUs) as TextureOverlay } +
                    listOfNotNull(captionOverlay { (it - offsetUs) / 1000 }) +
                    listOfNotNull(BrandKit.overlay(this@DesignActivity))
                fun composition(withAudio: Boolean): Composition {
                    val sequences = listOf(EditedMediaItemSequence(items)) + if (withAudio) audioSequences else emptyList()
                    val builder = Composition.Builder(sequences)
                        .experimentalSetForceAudioTrack(true)
                        .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
                    if (overlays.isNotEmpty()) builder.setEffects(Effects(emptyList(), listOf(OverlayEffect(overlays))))
                    return builder.build()
                }
                try {
                    export(composition(withAudio = true), output, w, h)
                } catch (e: ExportException) {
                    if (audioSequences.isEmpty()) throw e
                    // A phone that can't mix extra audio tracks: keep the video, tell the person.
                    Toast.makeText(this@DesignActivity, R.string.studio_music_failed, Toast.LENGTH_LONG).show()
                    export(composition(withAudio = false), output, w, h)
                }
                val saved = DualExporter.saveToGallery(this@DesignActivity, output, name)
                withContext(Dispatchers.IO) { output.delete() }
                Toast.makeText(this@DesignActivity, R.string.design_saved, Toast.LENGTH_LONG).show()
                startActivity(Intent(this@DesignActivity, PlayerActivity::class.java).setData(saved))
                finish()
            } catch (e: Exception) {
                progressPanel.isVisible = false
                Toast.makeText(this@DesignActivity, getString(R.string.editor_failed, e.message ?: e.javaClass.simpleName), Toast.LENGTH_LONG).show()
            }
        }
    }

    private suspend fun export(composition: Composition, output: File, w: Int, h: Int) = kotlinx.coroutines.coroutineScope {
        output.delete()
        val settings = SettingsStore.load(this@DesignActivity)
        val finished = CompletableDeferred<Unit>()
        val transformer = Transformer.Builder(this@DesignActivity)
            .setVideoMimeType(settings.codec.mimeType)
            .setEncoderFactory(
                DefaultEncoderFactory.Builder(this@DesignActivity)
                    .setRequestedVideoEncoderSettings(
                        VideoEncoderSettings.Builder().setBitrate(bitrateFor(settings.exportQuality, w, h)).build()
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
                if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    progressBar.setProgressCompat(holder.progress, true)
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
        if (recorder != null) stopVoiceover()
        player?.pause()
        audioPlayers.values.forEach { it.pause() }
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
        audioPlayers.values.forEach { it.release() }
        audioPlayers.clear()
    }

    companion object {
        private val PLAYER_ID = View.generateViewId()
        // The toolbar, in order. "Video" is the Mask tool (the video's shape, place and cutout).
        private const val TAB_EDIT = 0
        private const val TAB_AUDIO = 1
        private const val TAB_TEXT = 2
        private const val TAB_CAPTIONS = 3
        private const val TAB_STICKERS = 4
        private const val TAB_PHOTOS = 5
        private const val TAB_ADJUST = 6
        private const val TAB_VIDEO = 7
        private const val TAB_BACKGROUND = 8
        private const val TAB_CANVAS = 9
        private const val TAB_TEMPLATES = 10
        private const val TAB_LOGO = 11
        private const val TAB_CARDS = 12
        private const val TAB_LAYER = 13
        private val TAB_NAMES = listOf(
            "Edit", "Audio", "Text", "Captions", "Stickers", "Photos", "Adjust", "Mask",
            "Background", "Canvas", "Templates", "Logo", "Cards", "Layer",
        )
        private val TAB_ICONS = listOf("✂", "♫", "Aa", "CC", "☺", "▣", "◐", "◯", "▦", "⬚", "✦", "©", "▤", "☰")
        const val EXTRA_URIS = "uris"

        private val STICKERS = listOf(
            "🔥", "✨", "💯", "❤️", "😂", "😍", "🤯", "👀", "👉", "👇", "✅", "❌", "⚡", "💰", "🚀", "🎉",
            "🎬", "📸", "🎵", "⭐", "🌟", "💡", "📈", "🏆", "👑", "💪", "🙏", "🤝", "☕", "🍕", "🌈", "🌙",
            "☀️", "🌸", "🍃", "🎯", "🔔", "📌", "➡️", "⬆️", "🆕", "🔴", "💬", "🗣️", "🤔", "😎", "🥳", "🫶",
        )

        fun open(context: Context, video: Uri) {
            context.startActivity(Intent(context, DesignActivity::class.java).setData(video))
        }

        /** Several clips, edited together in this order. */
        fun openAll(context: Context, videos: List<Uri>) {
            context.startActivity(Intent(context, DesignActivity::class.java).putParcelableArrayListExtra(EXTRA_URIS, ArrayList(videos)))
        }
    }
}

/**
 * The touch layer over the design preview: tap to select the video or a layer, drag to move,
 * pinch to resize, twist with two fingers to rotate. Draws a dashed outline on the selection.
 */
class DesignCanvasView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var design: Design? = null
    var videoAspect = 9f / 16f
    var onSelect: (Layer?) -> Unit = {}
    private var selected: Layer? = null
    private var videoSelected = true

    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
        color = Color.rgb(216, 185, 138)
        pathEffect = DashPathEffect(floatArrayOf(14f, 10f), 0f)
    }

    /** Selected item's centre (0..1), size on screen (px) and rotation. */
    private data class Box(val x: Float, val y: Float, val w: Float, val h: Float, val rotation: Float)

    private fun videoBox(d: Design): Box {
        val (cw, ch) = d.canvasSize
        val canvasAspect = cw.toFloat() / ch
        val baseW = if (videoAspect > canvasAspect) width.toFloat() else height * videoAspect
        val w = baseW * d.video.scale
        return Box(d.video.x, d.video.y, w, w / videoAspect, d.video.rotation)
    }

    private fun layerBox(d: Design, l: Layer): Box? {
        val b = l.bitmap ?: return null
        val (cw, _) = d.canvasSize
        val k = width.toFloat() / cw * l.scale
        return Box(l.x, l.y, b.width * k, b.height * k, l.rotation)
    }

    private fun contains(box: Box, px: Float, py: Float): Boolean {
        // Undo the rotation, then a plain rectangle test.
        val cx = box.x * width
        val cy = box.y * height
        val a = Math.toRadians(-box.rotation.toDouble())
        val dx = px - cx
        val dy = py - cy
        val rx = (dx * kotlin.math.cos(a) - dy * kotlin.math.sin(a)).toFloat()
        val ry = (dx * kotlin.math.sin(a) + dy * kotlin.math.cos(a)).toFloat()
        return kotlin.math.abs(rx) <= box.w / 2 + 12 && kotlin.math.abs(ry) <= box.h / 2 + 12
    }

    override fun onDraw(canvas: Canvas) {
        val d = design ?: return
        val box = selected?.let { layerBox(d, it) } ?: if (videoSelected) videoBox(d) else null
        box ?: return
        canvas.save()
        canvas.rotate(box.rotation, box.x * width, box.y * height)
        canvas.drawRect(box.x * width - box.w / 2, box.y * height - box.h / 2, box.x * width + box.w / 2, box.y * height + box.h / 2, outline)
        canvas.restore()
    }

    private var lastX = 0f
    private var lastY = 0f
    private var lastDist = 0f
    private var lastAngle = 0f

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val d = design ?: return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Top-most layer under the finger, else the video.
                val hit = d.layers.lastOrNull { l -> layerBox(d, l)?.let { contains(it, e.x, e.y) } == true }
                selected = hit
                videoSelected = hit == null
                onSelect(hit)
                lastX = e.x
                lastY = e.y
                invalidate()
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount == 2) {
                lastDist = hypot(e.getX(1) - e.getX(0), e.getY(1) - e.getY(0))
                lastAngle = Math.toDegrees(atan2((e.getY(1) - e.getY(0)).toDouble(), (e.getX(1) - e.getX(0)).toDouble())).toFloat()
            }
            MotionEvent.ACTION_MOVE -> {
                val layer = selected
                if (e.pointerCount >= 2) {
                    val dist = hypot(e.getX(1) - e.getX(0), e.getY(1) - e.getY(0))
                    val angle = Math.toDegrees(atan2((e.getY(1) - e.getY(0)).toDouble(), (e.getX(1) - e.getX(0)).toDouble())).toFloat()
                    val factor = if (lastDist > 0f) dist / lastDist else 1f
                    var turn = angle - lastAngle
                    if (turn > 180f) turn -= 360f
                    if (turn < -180f) turn += 360f
                    if (layer != null) {
                        layer.scale = (layer.scale * factor).coerceIn(0.1f, 6f)
                        layer.rotation += turn
                    } else {
                        d.video.scale = (d.video.scale * factor).coerceIn(0.15f, 2f)
                        d.video.rotation += turn
                    }
                    lastDist = dist
                    lastAngle = angle
                } else {
                    val dx = (e.x - lastX) / width
                    val dy = (e.y - lastY) / height
                    if (layer != null) {
                        layer.x = (layer.x + dx).coerceIn(0f, 1f)
                        layer.y = (layer.y + dy).coerceIn(0f, 1f)
                    } else {
                        d.video.x = (d.video.x + dx).coerceIn(-0.2f, 1.2f)
                        d.video.y = (d.video.y + dy).coerceIn(-0.2f, 1.2f)
                    }
                    lastX = e.x
                    lastY = e.y
                }
                invalidate()
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // Carry on dragging with the finger that's left.
                val keep = if (e.actionIndex == 0) 1 else 0
                lastX = e.getX(keep)
                lastY = e.getY(keep)
                lastDist = 0f
            }
        }
        return true
    }
}
