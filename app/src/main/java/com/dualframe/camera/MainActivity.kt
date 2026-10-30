package com.dualframe.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Size
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.CompositionSettings
import androidx.camera.core.ConcurrentCamera
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.MirrorMode
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity(), FrameOverlayView.Listener {

    private lateinit var previewView: PreviewView
    private lateinit var overlay: FrameOverlayView
    private lateinit var recordButton: ImageButton
    private lateinit var flipButton: ImageButton
    private lateinit var modeButton: MaterialButton
    private lateinit var dualButton: MaterialButton
    private lateinit var timerText: TextView
    private lateinit var infoText: TextView
    private lateinit var hintText: TextView
    private lateinit var permissionPanel: View
    private lateinit var exportPanel: View
    private lateinit var exportTitle: TextView
    private lateinit var exportPercent: TextView
    private lateinit var exportProgress: LinearProgressIndicator
    private lateinit var resultPanel: View

    private var camera: Camera? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var masterFile: File? = null
    private var baseName = ""
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var mode = SourceMode.WIDE
    /** Front and back cameras recording at the same time, the second one as an inset. */
    private var dual = false
    private var bound = false
    private var askedForPermission = false
    private var shownResult: ExportState? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refreshPermissionState()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        savedInstanceState?.let {
            mode = SourceMode.entries[it.getInt(KEY_MODE, 0)]
            lensFacing = it.getInt(KEY_LENS, CameraSelector.LENS_FACING_BACK)
            askedForPermission = it.getBoolean(KEY_ASKED, false)
            dual = it.getBoolean(KEY_DUAL, false)
        }

        previewView = findViewById(R.id.preview)
        overlay = findViewById(R.id.overlay)
        recordButton = findViewById(R.id.record_button)
        flipButton = findViewById(R.id.flip_button)
        modeButton = findViewById(R.id.mode_button)
        dualButton = findViewById(R.id.dual_button)
        timerText = findViewById(R.id.timer)
        infoText = findViewById(R.id.info)
        hintText = findViewById(R.id.hint)
        permissionPanel = findViewById(R.id.permission_panel)
        exportPanel = findViewById(R.id.export_panel)
        exportTitle = findViewById(R.id.export_title)
        exportPercent = findViewById(R.id.export_percent)
        exportProgress = findViewById(R.id.export_progress)
        resultPanel = findViewById(R.id.result_panel)

        overlay.listener = this
        overlay.sourceAspect = mode.portraitAspect
        modeButton.text = mode.label

        recordButton.setOnClickListener { if (recording == null) startRecording() else recording?.stop() }
        flipButton.setOnClickListener {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                CameraSelector.LENS_FACING_FRONT
            } else {
                CameraSelector.LENS_FACING_BACK
            }
            bindCamera()
        }
        modeButton.setOnClickListener {
            mode = mode.next()
            Toast.makeText(this, if (mode == SourceMode.WIDE) R.string.mode_wide_help else R.string.mode_sharp_help, Toast.LENGTH_LONG).show()
            bindCamera()
        }
        dualButton.setOnClickListener {
            if (!dual && !supportsFrontAndBack()) {
                dualButton.isChecked = false
                Toast.makeText(this, R.string.dual_unsupported, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            dual = !dual
            if (dual) Toast.makeText(this, R.string.dual_help, Toast.LENGTH_LONG).show()
            bindCamera()
        }
        findViewById<View>(R.id.grant_button).setOnClickListener { requestOrOpenSettings() }
        findViewById<View>(R.id.record_again).setOnClickListener { DualExporter.acknowledge() }

        applyInsets()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                DualExporter.state.collect { render(it) }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_MODE, mode.ordinal)
        outState.putInt(KEY_LENS, lensFacing)
        outState.putBoolean(KEY_ASKED, askedForPermission)
        outState.putBoolean(KEY_DUAL, dual)
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionState()
    }

    // region Permissions

    private fun has(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun refreshPermissionState() {
        val cameraOk = has(Manifest.permission.CAMERA)
        permissionPanel.isVisible = !cameraOk
        when {
            cameraOk && !bound -> bindCamera()
            !cameraOk && !askedForPermission -> requestPermissions()
        }
    }

    private fun requestPermissions() {
        askedForPermission = true
        permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
    }

    private fun requestOrOpenSettings() {
        if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
            requestPermissions()
        } else {
            // "Don't ask again" was chosen; the only way back is the system settings page.
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            )
        }
    }

    // endregion

    // region Camera

    private var provider: ProcessCameraProvider? = null

    private fun bindCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            this.provider = provider
            if (dual && frontAndBackPair(provider) == null) dual = false

            // Dual recording always uses the wide picture: the inset has to fit in both cuts.
            val ratio = if (dual) SourceMode.WIDE else mode
            val preview = Preview.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(
                            AspectRatioStrategy(ratio.cameraRatio, AspectRatioStrategy.FALLBACK_RULE_AUTO)
                        )
                        .build()
                )
                .build()
            preview.setSurfaceProvider(previewView.surfaceProvider)

            // Two cameras at once is limited to about 1080p on most phones.
            val qualities = if (dual) listOf(Quality.FHD, Quality.HD) else listOf(Quality.UHD, Quality.FHD, Quality.HD)
            val recorder = Recorder.Builder()
                .setAspectRatio(ratio.cameraRatio)
                .setQualitySelector(
                    QualitySelector.fromOrderedList(qualities, FallbackStrategy.lowerQualityOrHigherThan(Quality.HD))
                )
                .build()
            val capture = VideoCapture.Builder(recorder)
                .setMirrorMode(if (dual) MirrorMode.MIRROR_MODE_OFF else MirrorMode.MIRROR_MODE_ON_FRONT_ONLY)
                .build()

            flipButton.isVisible =
                provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) && provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)

            try {
                provider.unbindAll()
                camera = if (dual) {
                    bindFrontAndBack(provider, preview, capture)
                } else {
                    var selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
                    if (!provider.hasCamera(selector)) {
                        lensFacing = CameraSelector.LENS_FACING_BACK
                        selector = CameraSelector.DEFAULT_BACK_CAMERA
                    }
                    provider.bindToLifecycle(this, selector, preview, capture)
                }
                videoCapture = capture
                bound = true
            } catch (e: Exception) {
                bound = false
                if (dual) {
                    // The phone advertised concurrent cameras but couldn't open them; fall back.
                    dual = false
                    Toast.makeText(this, R.string.dual_unsupported, Toast.LENGTH_LONG).show()
                    bindCamera()
                } else {
                    Toast.makeText(this, getString(R.string.camera_error, e.message), Toast.LENGTH_LONG).show()
                }
                return@addListener
            }

            modeButton.text = mode.label
            modeButton.isVisible = !dual
            dualButton.isChecked = dual
            overlay.movable = !dual
            overlay.sourceAspect = ratio.portraitAspect
            hintText.setText(if (dual) R.string.hint_dual else R.string.hint_frames)
            infoText.text = ""
            pollResolution(capture, attemptsLeft = 20)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun frontAndBackPair(provider: ProcessCameraProvider): Pair<CameraInfo, CameraInfo>? {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_CONCURRENT)) return null
        return provider.availableConcurrentCameraInfos.firstNotNullOfOrNull { infos ->
            val back = infos.firstOrNull { it.lensFacing == CameraSelector.LENS_FACING_BACK }
            val front = infos.firstOrNull { it.lensFacing == CameraSelector.LENS_FACING_FRONT }
            if (back != null && front != null) back to front else null
        }
    }

    private fun supportsFrontAndBack(): Boolean = provider?.let { frontAndBackPair(it) != null } ?: false

    /**
     * Binds both cameras to the same preview and recorder. CameraX draws the second camera
     * on top of the first, so one recording already contains both, and the usual 9:16 and
     * 16:9 cuts work on it unchanged. The flip button decides which camera is the big one.
     */
    private fun bindFrontAndBack(
        provider: ProcessCameraProvider,
        preview: Preview,
        capture: VideoCapture<Recorder>,
    ): Camera {
        val (back, front) = frontAndBackPair(provider) ?: error("No front + back camera pair")
        val (main, inset) = if (lensFacing == CameraSelector.LENS_FACING_FRONT) front to back else back to front

        val group = UseCaseGroup.Builder().addUseCase(preview).addUseCase(capture).build()
        val pip = PipLayout.secondaryCamera(
            sourceAspect = SourceMode.WIDE.portraitAspect,
            rotationDegrees = main.getSensorRotationDegrees(Surface.ROTATION_0),
        )
        val mainConfig = ConcurrentCamera.SingleCameraConfig(
            main.cameraSelector,
            group,
            CompositionSettings.Builder().setAlpha(1f).setOffset(0f, 0f).setScale(1f, 1f).build(),
            this,
        )
        val insetConfig = ConcurrentCamera.SingleCameraConfig(
            inset.cameraSelector,
            group,
            CompositionSettings.Builder()
                .setAlpha(1f)
                .setOffset(pip.offsetX, pip.offsetY)
                .setScale(pip.scale, pip.scale)
                .build(),
            this,
        )
        return provider.bindToLifecycle(listOf(mainConfig, insetConfig)).cameras.first()
    }

    /** The real recording size is only known once the camera has started; show it when it is. */
    private fun pollResolution(capture: VideoCapture<Recorder>, attemptsLeft: Int) {
        if (capture != videoCapture || attemptsLeft <= 0) return
        val info = capture.resolutionInfo
        if (info == null) {
            previewView.postDelayed({ pollResolution(capture, attemptsLeft - 1) }, 250)
            return
        }
        val crop = info.cropRect
        var w = if (crop.isEmpty) info.resolution.width else crop.width()
        var h = if (crop.isEmpty) info.resolution.height else crop.height()
        if (info.rotationDegrees % 180 != 0) w = h.also { h = w }
        overlay.sourceAspect = w.toFloat() / h
        infoText.text = getString(R.string.recording_size, w, h)
    }

    @SuppressLint("MissingPermission") // audio is only enabled after checking the permission
    private fun startRecording() {
        val capture = videoCapture ?: return
        if (DualExporter.state.value !is ExportState.Idle) return

        baseName = "DualFrame_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(cacheDir, "$baseName.mp4")
        masterFile = file

        var pending = capture.output.prepareRecording(this, FileOutputOptions.Builder(file).build())
        if (has(Manifest.permission.RECORD_AUDIO)) pending = pending.withAudioEnabled()
        recording = pending.start(ContextCompat.getMainExecutor(this)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> setRecordingUi(true)
                is VideoRecordEvent.Status -> {
                    timerText.text = formatDuration(event.recordingStats.recordedDurationNanos)
                }
                is VideoRecordEvent.Finalize -> onRecordingFinished(event)
            }
        }
    }

    private fun onRecordingFinished(event: VideoRecordEvent.Finalize) {
        recording = null
        setRecordingUi(false)
        val file = masterFile ?: return
        masterFile = null
        // Some "errors" (camera closed, storage almost full) still leave a playable file behind.
        if (event.error != VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA && file.length() > 0) {
            DualExporter.start(this, file, overlay.placement, baseName)
        } else {
            file.delete()
            Toast.makeText(this, getString(R.string.record_failed, event.error), Toast.LENGTH_LONG).show()
        }
    }

    private fun setRecordingUi(isRecording: Boolean) {
        recordButton.setImageResource(if (isRecording) R.drawable.ic_stop else R.drawable.ic_record)
        recordButton.contentDescription =
            getString(if (isRecording) R.string.stop_recording else R.string.start_recording)
        timerText.isVisible = isRecording
        if (isRecording) timerText.text = formatDuration(0)
        modeButton.isEnabled = !isRecording
        flipButton.isEnabled = !isRecording
        dualButton.isEnabled = !isRecording
        hintText.isVisible = !isRecording
        overlay.locked = isRecording
    }

    private fun formatDuration(nanos: Long): String {
        val total = TimeUnit.NANOSECONDS.toSeconds(nanos)
        return String.format(Locale.US, "%02d:%02d:%02d", total / 3600, (total / 60) % 60, total % 60)
    }

    override fun onTapToFocus(x: Float, y: Float) {
        val cam = camera ?: return
        val point = previewView.meteringPointFactory.createPoint(x, y)
        cam.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
    }

    override fun onPinch(scaleFactor: Float) {
        val cam = camera ?: return
        val zoom = cam.cameraInfo.zoomState.value ?: return
        cam.cameraControl.setZoomRatio((zoom.zoomRatio * scaleFactor).coerceIn(zoom.minZoomRatio, zoom.maxZoomRatio))
    }

    override fun onPlacementChanged(placement: FramePlacement) = Unit

    // endregion

    // region Export results

    private fun render(state: ExportState) {
        exportPanel.isVisible = state is ExportState.Running
        resultPanel.isVisible = state is ExportState.Done
        recordButton.isEnabled = state is ExportState.Idle

        when (state) {
            is ExportState.Idle -> shownResult = null
            is ExportState.Running -> {
                exportTitle.text = getString(if (state.step == 1) R.string.exporting_portrait else R.string.exporting_landscape)
                exportProgress.setProgressCompat(state.progress, true)
                exportPercent.text = getString(R.string.percent, state.progress)
            }
            is ExportState.Done -> if (shownResult != state) {
                shownResult = state
                bindResult(state)
            }
            is ExportState.Failed -> if (shownResult != state) {
                shownResult = state
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.export_failed_title)
                    .setMessage(
                        getString(R.string.export_failed_message, state.message) +
                            if (state.savedOriginal != null) "\n\n" + getString(R.string.original_saved) else ""
                    )
                    .setPositiveButton(android.R.string.ok, null)
                    .setOnDismissListener { DualExporter.acknowledge() }
                    .show()
            }
        }
    }

    private fun bindResult(done: ExportState.Done) {
        loadThumbnail(done.portraitUri, findViewById(R.id.portrait_thumb))
        loadThumbnail(done.landscapeUri, findViewById(R.id.landscape_thumb))
        findViewById<View>(R.id.portrait_play).setOnClickListener { play(done.portraitUri) }
        findViewById<View>(R.id.landscape_play).setOnClickListener { play(done.landscapeUri) }
        findViewById<View>(R.id.portrait_thumb).setOnClickListener { play(done.portraitUri) }
        findViewById<View>(R.id.landscape_thumb).setOnClickListener { play(done.landscapeUri) }
        findViewById<View>(R.id.portrait_share).setOnClickListener { share(done.portraitUri, R.string.share_portrait) }
        findViewById<View>(R.id.landscape_share).setOnClickListener { share(done.landscapeUri, R.string.share_landscape) }
    }

    private fun loadThumbnail(uri: Uri, into: ImageView) {
        into.setImageDrawable(null)
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                runCatching { contentResolver.loadThumbnail(uri, Size(480, 480), null) }.getOrNull()
            }
            into.setImageBitmap(bitmap)
        }
    }

    private fun play(uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "video/mp4")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { startActivity(intent) }
            .onFailure { Toast.makeText(this, R.string.no_player, Toast.LENGTH_SHORT).show() }
    }

    private fun share(uri: Uri, titleRes: Int) {
        val send = Intent(Intent.ACTION_SEND)
            .setType("video/mp4")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, getString(titleRes)))
    }

    // endregion

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            findViewById<View>(R.id.top_bar).updatePadding(top = bars.top)
            findViewById<View>(R.id.bottom_bar).updatePadding(bottom = bars.bottom)
            listOf(exportPanel, resultPanel, permissionPanel).forEach {
                it.updatePadding(top = bars.top, bottom = bars.bottom)
            }
            insets
        }
    }

    private companion object {
        const val KEY_MODE = "mode"
        const val KEY_LENS = "lens"
        const val KEY_ASKED = "asked"
        const val KEY_DUAL = "dual"
    }
}
