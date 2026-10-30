package com.dualframe.camera

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
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
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.TextureOverlay
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
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.ChipGroup
import com.google.android.material.progressindicator.LinearProgressIndicator
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

/**
 * The caption editor. Fix any word (retype, split by typing a space, delete, join), and give
 * any word its own colour, size and effect. Opens before a take's videos are made (review),
 * or from the library to add captions to any video.
 */
@OptIn(UnstableApi::class)
class CaptionEditorActivity : AppCompatActivity() {

    private var words = mutableListOf<CaptionWord>()
    private lateinit var style: CaptionStyle
    private var selected = -1
    private var reviewMode = false
    private var submitted = false
    private var videoUri: Uri? = null
    private var videoW = 1080
    private var videoH = 1920
    private var wide = false
    private var animating = false
    private val startedAt = System.currentTimeMillis()

    private lateinit var preview: ImageView
    private lateinit var status: TextView
    private lateinit var panel: LinearLayout
    private lateinit var linesBox: LinearLayout
    private lateinit var doneButton: MaterialButton
    private lateinit var progressPanel: LinearLayout
    private lateinit var progressBar: LinearProgressIndicator
    private lateinit var progressText: TextView
    private val pills = mutableMapOf<Int, TextView>()
    private var styleSheet: CaptionStyleSheet? = null

    private val importFont = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                    CaptionFonts.import(this@CaptionEditorActivity, uri, name)
                }
            }
            result.onSuccess { id -> styleSheet?.onFontImported(id) }
                .onFailure { Toast.makeText(this@CaptionEditorActivity, it.message, Toast.LENGTH_LONG).show() }
        }
    }

    private fun dp(v: Float) = Glass.dp(this, v).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        style = CaptionStyle.load(this)
        reviewMode = intent.getBooleanExtra(EXTRA_REVIEW, false)
        videoUri = intent.data
        buildUi()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = leave()
        })

        if (reviewMode) {
            words = CaptionReview.words.toMutableList()
            status.text = getString(R.string.caption_editor_review_hint)
            renderLines()
        } else {
            val uri = videoUri ?: return finish()
            loadVideoSize(uri)
            showLanguageChoice()
        }
        animating = true
        tick()
    }

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
            setImageResource(R.drawable.ic_close)
            contentDescription = getString(R.string.close)
            setOnClickListener { leave() }
        })
        header.addView(
            Glass.body(this, getString(R.string.caption_editor_title)).apply {
                setTextAppearance(R.style.DF_Text_Title)
                setPadding(dp(8f), 0, 0, 0)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        header.addView(Glass.pill(this, getString(R.string.caption_editor_style)) { openStyle() })
        doneButton = MaterialButton(this).apply {
            text = getString(R.string.caption_editor_done)
            setOnClickListener { done() }
        }
        header.addView(doneButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(8f)
        })
        column.addView(header)

        val frame = FrameLayout(this).apply {
            background = GradientDrawable().apply { setColor(Color.rgb(20, 20, 24)) }
        }
        preview = ImageView(this)
        frame.addView(preview, FrameLayout.LayoutParams(dp(160f), dp(240f), Gravity.CENTER))
        column.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(250f)))

        val scroll = NestedScrollView(this)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(8f), dp(20f), dp(40f))
        }
        scroll.addView(body)
        column.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        body.addView(flow().apply {
            val tall = Glass.pill(context, "Tall 9:16", true)
            val w = Glass.pill(context, "Wide 16:9", false)
            tall.setOnClickListener { wide = false; tall.isSelected = true; w.isSelected = false; layoutPreview() }
            w.setOnClickListener { wide = true; w.isSelected = true; tall.isSelected = false; layoutPreview() }
            addView(tall)
            addView(w)
        })
        status = Glass.caption(this, "").apply { setPadding(0, dp(8f), 0, 0) }
        body.addView(status)
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(context, R.drawable.bg_glass_card)
            setPadding(dp(16f), dp(12f), dp(16f), dp(14f))
            visibility = View.GONE
        }
        body.addView(panel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10f)
        })
        linesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(linesBox)

        progressPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.argb(210, 10, 10, 12))
            isClickable = true
            visibility = View.GONE
        }
        progressText = Glass.body(this, "").apply { gravity = Gravity.CENTER }
        progressBar = LinearProgressIndicator(this).apply {
            max = 100
            setIndicatorColor(ContextCompat.getColor(context, R.color.accent))
        }
        progressPanel.addView(progressText)
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
        layoutPreview()
    }

    private fun flow() = ChipGroup(this).apply {
        chipSpacingHorizontal = dp(8f)
        chipSpacingVertical = dp(8f)
    }

    private fun layoutPreview() {
        val h = dp(230f)
        val w = if (wide) dp(300f) else (h * 9 / 16f).toInt()
        val ph = if (wide) (w * 9 / 16f).toInt() else h
        preview.layoutParams = FrameLayout.LayoutParams(w, ph, Gravity.CENTER)
    }

    // endregion

    // region Adding captions to a library video

    private fun loadVideoSize(uri: Uri) {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(this, uri)
            var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1080
            var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1920
            val rotation = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rotation % 180 != 0) w = h.also { h = w }
            videoW = w
            videoH = h
            wide = w > h
            layoutPreview()
        } catch (e: Exception) {
            // Keep the defaults.
        } finally {
            r.release()
        }
    }

    private fun showLanguageChoice() {
        doneButton.isEnabled = false
        if (!Transcriber.isAvailable(this)) {
            status.text = getString(R.string.captions_unavailable)
            return
        }
        status.text = getString(R.string.caption_editor_pick_language)
        linesBox.removeAllViews()
        val saved = SettingsStore.load(this).captions.takeIf { it != CaptionLanguage.OFF } ?: CaptionLanguage.ENGLISH
        var language = saved
        val group = flow().apply { setPadding(0, dp(10f), 0, 0) }
        listOf(CaptionLanguage.ENGLISH, CaptionLanguage.HINGLISH).forEach { l ->
            group.addView(Glass.pill(this, l.label, l == language).apply {
                setOnClickListener {
                    for (i in 0 until group.childCount) group.getChildAt(i).isSelected = false
                    isSelected = true
                    language = l
                }
            })
        }
        linesBox.addView(group)
        linesBox.addView(MaterialButton(this).apply {
            text = getString(R.string.caption_editor_make)
            setOnClickListener { transcribe(language) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(14f)
        })
    }

    private fun transcribe(language: CaptionLanguage) {
        val uri = videoUri ?: return
        showProgress(getString(R.string.caption_editor_listening))
        lifecycleScope.launch {
            val found = Transcriber.transcribe(this@CaptionEditorActivity, uri, language) { p ->
                runOnUiThread { progressBar.setProgressCompat(p, true) }
            }
            hideProgress()
            if (found.isEmpty()) {
                Toast.makeText(this@CaptionEditorActivity, R.string.caption_editor_no_speech, Toast.LENGTH_LONG).show()
                Transcriber.prepare(this@CaptionEditorActivity, language) { }
                return@launch
            }
            words = found.toMutableList()
            doneButton.isEnabled = true
            status.text = getString(R.string.caption_editor_review_hint)
            renderLines()
        }
    }

    // endregion

    // region Lines and words

    /** Lines as they'll appear, with each word's index in [words]. */
    private fun lineIndices(): List<List<Int>> {
        val lines = Transcriber.lines(words, if (wide) 40 else 24, style.wordsPerLine)
        var next = 0
        return lines.map { line -> line.words.map { next++ } }
    }

    private fun renderLines() {
        linesBox.removeAllViews()
        pills.clear()
        lineIndices().forEachIndexed { n, line ->
            val first = words.getOrNull(line.firstOrNull() ?: -1)
            linesBox.addView(Glass.label(this, "${n + 1}  ·  ${first?.let { VideoLibrary.formatDuration(it.startMs) } ?: ""}").apply {
                setPadding(0, dp(14f), 0, dp(6f))
            })
            val group = flow()
            line.forEach { i ->
                val w = words[i]
                group.addView(Glass.pill(this, w.text, i == selected) { select(i) }.apply {
                    w.look?.color?.let { setTextColor(it) }
                    if (w.look?.effect != null && w.look.effect != WordEffect.NONE) text = "${w.text} ✦"
                    pills[i] = this
                })
            }
            linesBox.addView(group)
        }
    }

    private fun select(index: Int) {
        normalizeSelected()
        selected = if (index in words.indices) index else -1
        renderLines()
        buildPanel()
    }

    /** A word typed with spaces becomes several words; an emptied word is removed. */
    private fun normalizeSelected() {
        val i = selected
        if (i !in words.indices) return
        val w = words[i]
        val parts = w.text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        when {
            parts.isEmpty() -> words.removeAt(i)
            parts.size > 1 -> {
                val step = (w.endMs - w.startMs) / parts.size
                words.removeAt(i)
                parts.forEachIndexed { k, part ->
                    words.add(i + k, CaptionWord(w.startMs + step * k, w.startMs + step * (k + 1), part, w.look))
                }
            }
            else -> words[i] = w.copy(text = parts[0])
        }
        selected = -1
    }

    private fun update(i: Int, change: (CaptionWord) -> CaptionWord) {
        if (i !in words.indices) return
        words[i] = change(words[i])
        renderLines()
        buildPanel()
    }

    private fun setLook(i: Int, change: (WordLook) -> WordLook) = update(i) { w ->
        val look = change(w.look ?: WordLook())
        w.copy(look = look.takeUnless { it.isEmpty })
    }

    private fun buildPanel() {
        panel.removeAllViews()
        val i = selected
        if (i !in words.indices) {
            panel.isVisible = false
            return
        }
        panel.isVisible = true
        val word = words[i]

        panel.addView(Glass.label(this, getString(R.string.caption_editor_word)))
        panel.addView(EditText(this).apply {
            setText(word.text)
            setSelection(text.length)
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_DONE
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            background = ContextCompat.getDrawable(context, R.drawable.bg_glass_pill)
            setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    if (i in words.indices) words[i] = words[i].copy(text = s.toString())
                    pills[i]?.text = s.toString()
                }
            })
            setOnEditorActionListener { _, _, _ ->
                select(-1)
                true
            }
        })
        panel.addView(Glass.caption(this, getString(R.string.caption_editor_word_hint)).apply { setPadding(0, dp(4f), 0, 0) })

        panel.addView(flow().apply {
            setPadding(0, dp(8f), 0, 0)
            addView(Glass.pill(context, getString(R.string.delete)) {
                words.removeAt(i)
                selected = -1
                renderLines()
                buildPanel()
            })
            if (i < words.size - 1) {
                addView(Glass.pill(context, getString(R.string.caption_editor_join)) {
                    val next = words[i + 1]
                    words[i] = word.copy(text = word.text + " " + next.text, endMs = next.endMs)
                    words.removeAt(i + 1)
                    renderLines()
                    buildPanel()
                })
            }
            addView(Glass.pill(context, getString(R.string.caption_editor_apply_all, word.text)) {
                val key = word.text.lowercase(Locale.getDefault())
                words = words.map { if (it.text.lowercase(Locale.getDefault()) == key) it.copy(look = word.look) else it }.toMutableList()
                renderLines()
                Toast.makeText(context, R.string.caption_editor_applied, Toast.LENGTH_SHORT).show()
            })
        })

        panel.addView(Glass.label(this, getString(R.string.caption_editor_colour)).apply { setPadding(0, dp(12f), 0, dp(6f)) })
        panel.addView(flow().apply {
            addView(Glass.pill(context, "Auto", word.look?.color == null) { setLook(i) { it.copy(color = null) } })
            QUICK_COLOURS.forEach { c ->
                addView(View(context).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(c)
                        setStroke(dp(if (word.look?.color == c) 3f else 1f), Color.argb(200, 255, 255, 255))
                    }
                    setOnClickListener { setLook(i) { it.copy(color = c) } }
                }, ViewGroup.LayoutParams(dp(32f), dp(32f)))
            }
            addView(Glass.pill(context, getString(R.string.caption_editor_custom)) {
                ColorPicker.show(context, word.text, word.look?.color ?: style.textColor) { c -> setLook(i) { it.copy(color = c) } }
            })
        })

        panel.addView(Glass.label(this, getString(R.string.caption_editor_size)).apply { setPadding(0, dp(12f), 0, 0) })
        panel.addView(Slider(this).apply {
            valueFrom = 50f
            valueTo = 250f
            stepSize = 5f
            value = (word.look?.sizePct ?: 100).toFloat().coerceIn(50f, 250f)
            setLabelFormatter { "${it.toInt()}%" }
            addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) = Unit
                override fun onStopTrackingTouch(slider: Slider) {
                    val v = slider.value.toInt()
                    setLook(i) { it.copy(sizePct = v.takeIf { s -> s != 100 }) }
                }
            })
        })

        panel.addView(Glass.label(this, getString(R.string.caption_editor_effect)).apply { setPadding(0, dp(6f), 0, dp(6f)) })
        panel.addView(flow().apply {
            WordEffect.entries.forEach { e ->
                addView(Glass.pill(context, e.label, (word.look?.effect ?: WordEffect.NONE) == e) { setLook(i) { it.copy(effect = e) } })
            }
        })
    }

    // endregion

    // region Preview

    private val guide = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(110, 255, 255, 255)
        pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
        strokeWidth = 2f
    }
    private val bg = Paint()
    private var renderer: CaptionRenderer? = null
    private var rendererStyle: CaptionStyle? = null

    private fun tick() {
        if (!animating) return
        val vw = if (wide) 960 else 540
        val vh = if (wide) 540 else 960
        val now = System.currentTimeMillis() - startedAt
        if (rendererStyle != style) {
            renderer = CaptionRenderer(style, CaptionFonts.typeface(this, style.font), CaptionFonts.variation(style.font))
            rendererStyle = style
        }
        val frame = Bitmap.createBitmap(vw, vh, Bitmap.Config.ARGB_8888)
        val c = Canvas(frame)
        bg.shader = LinearGradient(0f, 0f, vw.toFloat(), vh.toFloat(), Color.rgb(64, 54, 44), Color.rgb(18, 22, 34), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, vw.toFloat(), vh.toFloat(), bg)
        val safeW = vw * if (wide) CaptionRenderer.SAFE_WIDTH_WIDE else CaptionRenderer.SAFE_WIDTH_VERTICAL
        c.drawRect((vw - safeW) / 2f, vh * 0.14f, (vw + safeW) / 2f, vh * 0.8f, guide)

        // Show the line with the selected word (as if it's being spoken), or cycle through the lines.
        val lines = lineIndices()
        val r = renderer
        if (lines.isNotEmpty() && r != null) {
            val lineNo = lines.indexOfFirst { selected in it }.takeIf { it >= 0 } ?: ((now / 1800) % lines.size).toInt()
            val line = lines[lineNo]
            val active = line.indexOf(selected).takeIf { it >= 0 } ?: ((now / 450) % line.size).toInt()
            val phase = ((now % 450) / CaptionRenderer.ENTRANCE_MS.toFloat()).coerceIn(0f, 1f)
            val caption = r.render(line.map { words[it].text }, active, phase, now, vw, vh, line.map { words[it].look })
            val centerY = (1f - r.anchorY(!wide)) / 2f * vh
            c.drawBitmap(caption, (vw - caption.width) / 2f, centerY - caption.height / 2f, null)
            caption.recycle()
        }
        val old = (preview.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
        preview.setImageBitmap(frame)
        old?.recycle()
        preview.postOnAnimationDelayed({ tick() }, 33)
    }

    private fun openStyle() {
        styleSheet = CaptionStyleSheet(this, onImportFont = {
            importFont.launch(arrayOf("font/*", "application/x-font-ttf", "application/x-font-otf", "application/octet-stream"))
        }, onClosed = {
            style = CaptionStyle.load(this)
            renderLines()
        }).also { it.show() }
    }

    // endregion

    // region Finish

    private fun done() {
        normalizeSelected()
        if (reviewMode) {
            submitted = true
            CaptionReview.submit(words.toList())
            finish()
        } else {
            burn()
        }
    }

    /** Leaving without Done: a take keeps its captions as recognized; a library video is left alone. */
    private fun leave() {
        if (reviewMode) {
            submitted = true
            CaptionReview.submit(null)
        }
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        animating = false
        if (reviewMode && !submitted && isFinishing) CaptionReview.submit(null)
    }

    private fun showProgress(text: String) {
        progressText.text = text
        progressBar.setProgressCompat(0, false)
        progressPanel.isVisible = true
    }

    private fun hideProgress() {
        progressPanel.isVisible = false
    }

    /** Library video: burn the edited captions into a new copy (the original is left as it is). */
    private fun burn() {
        val uri = videoUri ?: return
        if (words.isEmpty()) return
        showProgress(getString(R.string.caption_editor_saving))
        val name = "Captions_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        val output = File(cacheDir, name)
        lifecycleScope.launch {
            try {
                val lines = Transcriber.lines(words, if (videoW > videoH) 40 else 24, style.wordsPerLine)
                val renderer = CaptionRenderer(style, CaptionFonts.typeface(this@CaptionEditorActivity, style.font), CaptionFonts.variation(style.font))
                val item = EditedMediaItem.Builder(MediaItem.fromUri(uri))
                    .setEffects(Effects(emptyList(), listOf(OverlayEffect(listOf<TextureOverlay>(CaptionOverlay(lines, renderer))))))
                    .build()
                val settings = SettingsStore.load(this@CaptionEditorActivity)
                val finished = CompletableDeferred<Unit>()
                val transformer = Transformer.Builder(this@CaptionEditorActivity)
                    .setVideoMimeType(settings.codec.mimeType)
                    .setEncoderFactory(
                        DefaultEncoderFactory.Builder(this@CaptionEditorActivity)
                            .setRequestedVideoEncoderSettings(
                                VideoEncoderSettings.Builder().setBitrate(bitrateFor(settings.exportQuality, videoW, videoH)).build()
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
                transformer.start(Composition.Builder(EditedMediaItemSequence(item)).build(), output.absolutePath)
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
                val saved = DualExporter.saveToGallery(this@CaptionEditorActivity, output, name)
                withContext(Dispatchers.IO) {
                    output.delete()
                    runCatching { Transcriber.saveSrt(this@CaptionEditorActivity, name.replace(".mp4", ".srt"), Transcriber.srt(words)) }
                }
                Toast.makeText(this@CaptionEditorActivity, R.string.caption_editor_saved, Toast.LENGTH_LONG).show()
                startActivity(Intent(this@CaptionEditorActivity, PlayerActivity::class.java).setData(saved))
                finish()
            } catch (e: Exception) {
                hideProgress()
                Toast.makeText(
                    this@CaptionEditorActivity,
                    getString(R.string.editor_failed, e.message ?: e.javaClass.simpleName),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    // endregion

    companion object {
        private const val EXTRA_REVIEW = "review"

        private val QUICK_COLOURS = listOf(
            Color.WHITE, 0xFFFFE14D.toInt(), 0xFF39FF7A.toInt(), 0xFF4DE1FF.toInt(), 0xFFFF2BD6.toInt(),
            0xFFFF3B6B.toInt(), 0xFFFF8A3D.toInt(), 0xFFE8C88C.toInt(), 0xFF7C5CFF.toInt(), Color.BLACK,
        )

        /** Review a take's captions before its videos are made. */
        fun review(context: Context) {
            context.startActivity(Intent(context, CaptionEditorActivity::class.java).putExtra(EXTRA_REVIEW, true))
        }

        /** Add captions to a video in the library (saved as a new copy). */
        fun addTo(context: Context, video: Uri) {
            context.startActivity(Intent(context, CaptionEditorActivity::class.java).setData(video))
        }
    }
}
