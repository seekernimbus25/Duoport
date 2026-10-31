package com.dualframe.camera

import android.content.Context
import android.content.Intent
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
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.SingleColorLut
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
import com.google.android.material.chip.ChipGroup
import com.google.android.material.progressindicator.LinearProgressIndicator
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
 * Colour-grade a video that's already in the library: pick a look, correct and grade with the
 * same controls as Pro, watch it live on the video, then save a graded copy.
 */
@OptIn(UnstableApi::class)
class GradeActivity : AppCompatActivity() {

    private lateinit var uri: Uri
    private var grade = ColorGrade()
    private var lutId = Luts.NONE
    private var player: ExoPlayer? = null
    private var previewJob: Job? = null
    private var cube: LutCube? = null
    private var videoW = 1920
    private var videoH = 1080

    private lateinit var progressPanel: LinearLayout
    private lateinit var progressBar: LinearProgressIndicator

    private fun dp(v: Float) = Glass.dp(this, v).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        uri = intent.data ?: return finish()
        readSize()

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
            Glass.body(this, getString(R.string.grade_title)).apply {
                setTextAppearance(R.style.DF_Text_Title)
                setPadding(dp(8f), 0, 0, 0)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        header.addView(Glass.pill(this, getString(R.string.grade_save_look)) { saveAsLook() })
        header.addView(MaterialButton(this).apply {
            text = getString(R.string.editor_save)
            setOnClickListener { save() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(8f)
        })
        column.addView(header)

        val playerView = layoutInflater.inflate(R.layout.grade_player, column, false) as PlayerView
        column.addView(playerView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.36f))
        column.addView(scopeStrip())

        val scroll = NestedScrollView(this)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(4f), dp(20f), dp(40f))
        }
        scroll.addView(body)
        column.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.64f))

        body.addView(Glass.section(this, getString(R.string.grade_look)))
        looks = ChipGroup(this).apply {
            chipSpacingHorizontal = dp(8f)
            chipSpacingVertical = dp(8f)
        }
        buildLooks()
        body.addView(looks)
        val controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(controls)
        GradeControls(this, controls, grade) {
            grade = it
            refresh()
        }.build()

        progressPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(android.graphics.Color.argb(210, 10, 10, 12))
            isClickable = true
            visibility = View.GONE
        }
        progressBar = LinearProgressIndicator(this).apply {
            max = 100
            setIndicatorColor(ContextCompat.getColor(context, R.color.accent))
        }
        progressPanel.addView(Glass.body(this, getString(R.string.grade_saving)).apply { gravity = Gravity.CENTER })
        progressPanel.addView(progressBar, LinearLayout.LayoutParams(dp(220f), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(16f)
        })
        root.addView(progressPanel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            column.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }

        startScopes(playerView)
        player = ExoPlayer.Builder(this).build().also {
            playerView.player = it
            it.repeatMode = Player.REPEAT_MODE_ALL
            it.setMediaItem(MediaItem.fromUri(uri))
            it.prepare()
            it.play()
        }
    }

    // region Scopes

    private lateinit var scope: ScopeView
    private val scopePrefs by lazy { getSharedPreferences("grade_screen", MODE_PRIVATE) }

    /** Scope picker and the scope itself, between the picture and the controls. */
    private fun scopeStrip(): View {
        val strip = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12f), dp(6f), dp(12f), 0)
        }
        scope = ScopeView(this).apply {
            mode = ScopeMode.entries.firstOrNull { it.name == scopePrefs.getString("scope", null) } ?: ScopeMode.WAVEFORM
            contentDescription = getString(R.string.grade_scopes)
        }
        val pills = ChipGroup(this).apply {
            isSingleLine = true
            chipSpacingHorizontal = dp(6f)
        }
        fun drawPills() {
            pills.removeAllViews()
            ScopeMode.entries.forEach { m ->
                pills.addView(Glass.pill(this, m.label, m == scope.mode) {
                    scope.mode = m
                    scopePrefs.edit().putString("scope", m.name).apply()
                    scope.visibility = if (m == ScopeMode.OFF) View.GONE else View.VISIBLE
                    drawPills()
                }.apply { minHeight = dp(34f) })
            }
        }
        drawPills()
        strip.addView(android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(pills)
        })
        scope.visibility = if (scope.mode == ScopeMode.OFF) View.GONE else View.VISIBLE
        strip.addView(scope, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(132f)).apply { topMargin = dp(6f) })
        return strip
    }

    /** Reads the graded picture back from the player a few times a second and feeds the scope. */
    private fun startScopes(playerView: PlayerView) {
        lifecycleScope.launch {
            while (true) {
                delay(180)
                if (scope.mode == ScopeMode.OFF || !scope.isShown) continue
                val texture = playerView.videoSurfaceView as? android.view.TextureView ?: continue
                if (!texture.isAvailable || texture.width == 0) continue
                val w = 192
                val h = (w * texture.height / texture.width.toFloat()).toInt().coerceIn(32, 400)
                val frame = runCatching { texture.getBitmap(w, h) }.getOrNull() ?: continue
                withContext(Dispatchers.Default) { scope.analyse(frame) }
                frame.recycle()
            }
        }
    }

    // endregion

    private fun readSize() {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(this, uri)
            var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: return
            var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: return
            val rotation = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rotation % 180 != 0) w = h.also { h = w }
            videoW = w
            videoH = h
        } catch (e: Exception) {
            // Defaults are fine for the bitrate estimate.
        } finally {
            r.release()
        }
    }

    private fun effects(): List<Effect> {
        val list = mutableListOf<Effect>()
        cube?.let { list += SingleColorLut.createFromCube(it.toArgbCube()) }
        if (grade.hasFinish) list += GradeFinishEffect(grade)
        return list
    }

    /** Re-bakes the look a moment after the last change and shows it on the playing video. */
    private fun refresh() {
        previewJob?.cancel()
        previewJob = lifecycleScope.launch {
            delay(120)
            val g = grade
            val id = lutId
            cube = withContext(Dispatchers.Default) {
                runCatching { Luts.cube(this@GradeActivity, id, RecordProfile.STANDARD, g) }.getOrNull()
            }
            runCatching { player?.setVideoEffects(effects()) }
        }
    }

    /** Keeps this grade as a look of its own: it shows under Look here and can be exported as a .cube. */
    private lateinit var looks: ChipGroup

    /** The look pills; rebuilt after "Save as my look" so the new look shows up right away. */
    private fun buildLooks() {
        looks.removeAllViews()
        Luts.available(this, RecordProfile.STANDARD).forEach { lut ->
            looks.addView(Glass.pill(this, lut.name, lut.id == lutId).apply {
                setOnClickListener {
                    for (i in 0 until looks.childCount) looks.getChildAt(i).isSelected = false
                    isSelected = true
                    lutId = lut.id
                    refresh()
                }
            })
        }
    }

    private fun saveAsLook() {
        val input = android.widget.EditText(this).apply {
            setText(getString(R.string.grade_default_name))
            setSelection(text.length)
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.grade_save_look)
            .setView(input)
            .setPositiveButton(R.string.grade_save) { _, _ ->
                val g = grade
                val id = lutId
                lifecycleScope.launch {
                    val saved = withContext(Dispatchers.Default) {
                        runCatching {
                            val cube = Luts.cube(this@GradeActivity, id, RecordProfile.STANDARD, g) ?: LutCube.bake(33) { it }
                            Luts.saveCustom(this@GradeActivity, input.text.toString(), cube)
                        }.getOrNull()
                    }
                    Toast.makeText(
                        this@GradeActivity,
                        if (saved != null) R.string.grade_look_saved else R.string.grade_look_failed,
                        Toast.LENGTH_LONG,
                    ).show()
                    if (saved != null) buildLooks()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun save() {
        player?.pause()
        progressPanel.isVisible = true
        val name = "Graded_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        val output = File(cacheDir, name)
        lifecycleScope.launch {
            try {
                cube = withContext(Dispatchers.Default) {
                    runCatching { Luts.cube(this@GradeActivity, lutId, RecordProfile.STANDARD, grade) }.getOrNull()
                }
                val item = EditedMediaItem.Builder(MediaItem.fromUri(uri))
                    .setEffects(Effects(emptyList(), effects()))
                    .build()
                val settings = SettingsStore.load(this@GradeActivity)
                val finished = CompletableDeferred<Unit>()
                val transformer = Transformer.Builder(this@GradeActivity)
                    .setVideoMimeType(settings.codec.mimeType)
                    .setEncoderFactory(
                        DefaultEncoderFactory.Builder(this@GradeActivity)
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
                // LUTs work in standard range, so HDR videos are converted while grading.
                val composition = Composition.Builder(EditedMediaItemSequence(item))
                    .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
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
                }
                val saved = DualExporter.saveToGallery(this@GradeActivity, output, name)
                withContext(Dispatchers.IO) { output.delete() }
                Toast.makeText(this@GradeActivity, R.string.grade_saved_copy, Toast.LENGTH_LONG).show()
                startActivity(Intent(this@GradeActivity, PlayerActivity::class.java).setData(saved))
                finish()
            } catch (e: Exception) {
                progressPanel.isVisible = false
                Toast.makeText(this@GradeActivity, getString(R.string.editor_failed, e.message ?: e.javaClass.simpleName), Toast.LENGTH_LONG).show()
            }
        }
    }

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
        fun open(context: Context, video: Uri) {
            context.startActivity(Intent(context, GradeActivity::class.java).setData(video))
        }
    }
}
