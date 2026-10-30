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
 * The design editor: put the video on any background (colour, gradient, your photo, or a blur
 * of itself), size / move / rotate it, give it a shape or cut out just the person, and add
 * text, stickers, photos (also cut out), your logo as a watermark, and intro / outro cards.
 * Everything previews live on the playing video; Save makes a new video in the library.
 */
@OptIn(UnstableApi::class)
class DesignActivity : AppCompatActivity() {

    private val design = Design()
    private lateinit var uri: Uri
    private var durationMs = 0L
    private var player: ExoPlayer? = null
    private var tab = 0
    /** null = the video is selected; otherwise a layer. */
    private var selectedLayer: Layer? = null
    private var nextId = 1L

    private lateinit var frame: AspectRatioFrameLayout
    private lateinit var canvasView: DesignCanvasView
    private lateinit var panel: LinearLayout
    private lateinit var tabs: ChipGroup
    private lateinit var progressPanel: LinearLayout
    private lateinit var progressBar: LinearProgressIndicator

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
        uri = intent.data ?: return finish()
        readVideo()
        buildUi()
        player = ExoPlayer.Builder(this).build().also { p ->
            findViewById<PlayerView>(PLAYER_ID).player = p
            p.repeatMode = Player.REPEAT_MODE_ALL
            p.setMediaItem(MediaItem.fromUri(uri))
            p.prepare()
            p.play()
        }
        applyEffects()
        buildPanel()
    }

    private fun readVideo() {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(this, uri)
            durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1080
            var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1920
            val rotation = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rotation % 180 != 0) w = h.also { h = w }
            // Start on the shape closest to the video, with the video filling it.
            val aspect = w.toFloat() / h
            design.canvas = OutputFormat.entries.minByOrNull { kotlin.math.abs(it.aspect - aspect) } ?: OutputFormat.PORTRAIT_9_16
            canvasVideoAspect = aspect
            videoFrame = runCatching {
                r.getScaledFrameAtTime(minOf(1_000_000L, durationMs * 500), MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 360, 640)
            }.getOrNull()
        } catch (e: Exception) {
            // Defaults.
        } finally {
            r.release()
        }
    }

    private var canvasVideoAspect = 9f / 16f

    // region Layout

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
        val playerView = PlayerView(this).apply {
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
                tab = if (layer == null) TAB_VIDEO else TAB_LAYER
                buildPanel()
            }
        }
        frame.addView(canvasView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        stage.addView(frame, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER).apply {
            setMargins(dp(12f), dp(8f), dp(12f), dp(8f))
        })
        column.addView(stage, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.5f))

        tabs = ChipGroup(this).apply {
            isSingleLine = true
            chipSpacingHorizontal = dp(8f)
            setPadding(dp(16f), dp(8f), dp(16f), dp(4f))
        }
        column.addView(android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(tabs)
        })

        val scroll = NestedScrollView(this)
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(4f), dp(20f), dp(40f))
        }
        scroll.addView(panel)
        column.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.5f))

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

    // endregion

    // region Preview

    /** Re-sends the effects (needed when layers are added or removed, or the canvas changes). */
    private fun applyEffects() {
        frame.setAspectRatio(design.canvas.aspect)
        canvasView.invalidate()
        val effects = mutableListOf<Effect>(DesignEffect(design))
        val overlays = design.layers.map { LayerOverlay(it) as TextureOverlay } + listOfNotNull(BrandKit.overlay(this))
        if (overlays.isNotEmpty()) effects += OverlayEffect(overlays)
        runCatching { player?.setVideoEffects(effects) }
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

    private fun buildPanel() {
        tabs.removeAllViews()
        TAB_NAMES.forEachIndexed { i, name ->
            if (i == TAB_LAYER && selectedLayer == null) return@forEachIndexed
            tabs.addView(Glass.pill(this, name, i == tab) {
                tab = i
                if (i != TAB_LAYER) {
                    selectedLayer = if (i == TAB_VIDEO) null else selectedLayer
                }
                buildPanel()
            })
        }
        panel.removeAllViews()
        when (tab) {
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
    }

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

    private fun save() {
        player?.pause()
        progressPanel.isVisible = true
        val name = "Design_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        val output = File(cacheDir, name)
        lifecycleScope.launch {
            try {
                val (w, h) = design.canvasSize
                val items = mutableListOf<EditedMediaItem>()
                val intro = design.intro
                val outro = design.outro
                withContext(Dispatchers.Default) {
                    intro?.let { items += cardItem(it, "intro") }
                    items += EditedMediaItem.Builder(MediaItem.fromUri(uri))
                        .setEffects(Effects(emptyList(), listOf(DesignEffect(design))))
                        .build()
                    outro?.let { items += cardItem(it, "outro") }
                }
                // Layers sit on the whole timeline, shifted by the intro so their times match the video.
                val offsetUs = (intro?.durationMs ?: 0L) * 1000
                val overlays = design.layers.map { LayerOverlay(it, offsetUs) as TextureOverlay } + listOfNotNull(BrandKit.overlay(this@DesignActivity))
                val builder = Composition.Builder(EditedMediaItemSequence(items))
                    .experimentalSetForceAudioTrack(true)
                    .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
                if (overlays.isNotEmpty()) builder.setEffects(Effects(emptyList(), listOf(OverlayEffect(overlays))))
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
                transformer.start(builder.build(), output.absolutePath)
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

    // endregion

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onStart() {
        super.onStart()
        player?.play()
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }

    companion object {
        private val PLAYER_ID = View.generateViewId()
        private const val TAB_TEMPLATES = 0
        private const val TAB_CANVAS = 1
        private const val TAB_BACKGROUND = 2
        private const val TAB_VIDEO = 3
        private const val TAB_TEXT = 4
        private const val TAB_STICKERS = 5
        private const val TAB_PHOTOS = 6
        private const val TAB_LOGO = 7
        private const val TAB_CARDS = 8
        private const val TAB_LAYER = 9
        private val TAB_NAMES = listOf("Templates", "Canvas", "Background", "Video", "Text", "Stickers", "Photos", "Logo", "Cards", "Layer")

        private val STICKERS = listOf(
            "🔥", "✨", "💯", "❤️", "😂", "😍", "🤯", "👀", "👉", "👇", "✅", "❌", "⚡", "💰", "🚀", "🎉",
            "🎬", "📸", "🎵", "⭐", "🌟", "💡", "📈", "🏆", "👑", "💪", "🙏", "🤝", "☕", "🍕", "🌈", "🌙",
            "☀️", "🌸", "🍃", "🎯", "🔔", "📌", "➡️", "⬆️", "🆕", "🔴", "💬", "🗣️", "🤔", "😎", "🥳", "🫶",
        )

        fun open(context: Context, video: Uri) {
            context.startActivity(Intent(context, DesignActivity::class.java).setData(video))
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
