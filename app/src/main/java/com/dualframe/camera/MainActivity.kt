package com.dualframe.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Range
import android.util.Size
import android.util.TypedValue
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.CompositionSettings
import androidx.camera.core.ConcurrentCamera
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.MirrorMode
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ZoomState
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
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.slider.Slider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(ExperimentalCamera2Interop::class)
class MainActivity : AppCompatActivity(), FrameOverlayView.Listener {

    private enum class Param(val caption: String, val title: String) {
        FPS("FPS", "Frame rate"),
        SHUTTER("SHUTTER", "Shutter speed"),
        ISO("ISO", "ISO"),
        WB("WB", "White balance"),
        FOCUS("FOCUS", "Focus"),
        EV("EXP", "Exposure"),
    }

    /** How the second camera is being captured in front + back mode. */
    private enum class DualKind { NONE, SEPARATE, COMPOSITED }

    private lateinit var previewView: PreviewView
    private lateinit var insetFrame: FrameLayout
    private lateinit var insetPreview: PreviewView
    private lateinit var frameOverlay: FrameOverlayView
    private lateinit var teleprompter: TeleprompterView
    private lateinit var recordButton: ImageButton
    private lateinit var flipButton: ImageButton
    private lateinit var autoButton: TextView
    private lateinit var readoutRow: LinearLayout
    private lateinit var formatChips: ChipGroup
    private lateinit var timerRow: View
    private lateinit var timerText: TextView
    private lateinit var audioMeter: LinearProgressIndicator
    private lateinit var infoText: TextView
    private lateinit var hintText: TextView
    private lateinit var controlPanel: LinearLayout
    private lateinit var cornerRow: View
    private lateinit var cornerChips: ChipGroup
    private lateinit var zoomChips: ChipGroup
    private lateinit var zoomLabel: TextView
    private lateinit var dualButton: MaterialButton
    private lateinit var scriptButton: MaterialButton
    private lateinit var settingsButton: MaterialButton
    private lateinit var permissionPanel: View
    private lateinit var exportPanel: View
    private lateinit var exportTitle: TextView
    private lateinit var exportPercent: TextView
    private lateinit var exportProgress: LinearProgressIndicator
    private lateinit var resultPanel: View
    private lateinit var resultList: LinearLayout

    private var settings = CaptureSettings()
    private var manual = ManualState()
    private var caps: CameraCaps? = null
    private var lastReadout: Readout? = null
    @Volatile private var lastReadoutAt = 0L
    private var openParam: Param? = null
    private val readoutValues = mutableMapOf<Param, TextView>()

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var observedCamera: Camera? = null
    private var mainCapture: VideoCapture<Recorder>? = null
    private var insetCapture: VideoCapture<Recorder>? = null
    private var dualKind = DualKind.NONE
    private var bound = false
    private var askedForPermission = false
    private var shownResult: ExportState? = null

    private var mainRecording: Recording? = null
    private var insetRecording: Recording? = null
    private var mainTake: Take? = null
    private var insetTake: Take? = null
    private var pendingFinalizes = 0
    private var baseName = ""
    private val isRecording get() = mainRecording != null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refreshPermissionState()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        settings = SettingsStore.load(this)
        askedForPermission = savedInstanceState?.getBoolean(KEY_ASKED, false) ?: false

        previewView = findViewById(R.id.preview)
        insetFrame = findViewById(R.id.inset_frame)
        insetPreview = findViewById(R.id.inset_preview)
        frameOverlay = findViewById(R.id.overlay)
        teleprompter = findViewById(R.id.teleprompter)
        recordButton = findViewById(R.id.record_button)
        flipButton = findViewById(R.id.flip_button)
        autoButton = findViewById(R.id.auto_button)
        readoutRow = findViewById(R.id.readouts)
        formatChips = findViewById(R.id.format_chips)
        timerRow = findViewById(R.id.timer_row)
        timerText = findViewById(R.id.timer)
        audioMeter = findViewById(R.id.audio_meter)
        infoText = findViewById(R.id.info)
        hintText = findViewById(R.id.hint)
        controlPanel = findViewById(R.id.control_panel)
        cornerRow = findViewById(R.id.corner_row)
        cornerChips = findViewById(R.id.corner_chips)
        zoomChips = findViewById(R.id.zoom_chips)
        zoomLabel = findViewById(R.id.zoom_label)
        dualButton = findViewById(R.id.dual_button)
        scriptButton = findViewById(R.id.script_button)
        settingsButton = findViewById(R.id.settings_button)
        permissionPanel = findViewById(R.id.permission_panel)
        exportPanel = findViewById(R.id.export_panel)
        exportTitle = findViewById(R.id.export_title)
        exportPercent = findViewById(R.id.export_percent)
        exportProgress = findViewById(R.id.export_progress)
        resultPanel = findViewById(R.id.result_panel)
        resultList = findViewById(R.id.result_list)

        frameOverlay.listener = this
        buildReadouts()
        buildCornerChips()
        applySettingsToUi()

        recordButton.setOnClickListener { if (isRecording) stopRecording() else startRecording() }
        flipButton.setOnClickListener {
            val facing = if (settings.lensFacing == CameraSelector.LENS_FACING_BACK) {
                CameraSelector.LENS_FACING_FRONT
            } else {
                CameraSelector.LENS_FACING_BACK
            }
            updateSettings(settings.copy(lensFacing = facing), rebind = true)
        }
        dualButton.setOnClickListener {
            val turningOn = !settings.dual
            if (turningOn && !supportsFrontAndBack()) {
                dualButton.isChecked = false
                Toast.makeText(this, R.string.dual_unsupported, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (turningOn) Toast.makeText(this, R.string.dual_help, Toast.LENGTH_LONG).show()
            updateSettings(settings.copy(dual = turningOn), rebind = true)
        }
        scriptButton.setOnClickListener {
            teleprompter.isVisible = !teleprompter.isVisible
            if (!teleprompter.isVisible) teleprompter.pause()
            scriptButton.isChecked = teleprompter.isVisible
        }
        settingsButton.setOnClickListener { showSettings() }
        autoButton.setOnClickListener {
            manual = ManualState()
            closeControlPanel()
            applyManual()
            Toast.makeText(this, R.string.all_auto, Toast.LENGTH_SHORT).show()
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
        outState.putBoolean(KEY_ASKED, askedForPermission)
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionState()
    }

    override fun onStop() {
        super.onStop()
        // CameraX closes the camera now; it is opened again (with the manual settings) on resume.
        bound = false
    }

    private fun updateSettings(new: CaptureSettings, rebind: Boolean) {
        settings = new
        SettingsStore.save(this, new)
        applySettingsToUi()
        if (rebind) bindCamera() else applyManual()
    }

    /** Everything on screen that depends on [settings] but not on the camera being open. */
    private fun applySettingsToUi() {
        dualButton.isChecked = settings.dual
        cornerRow.isVisible = settings.dual
        (cornerChips.getChildAt(settings.insetCorner.ordinal) as? Chip)?.isChecked = true
        frameOverlay.grid = settings.grid
        frameOverlay.formats = settings.orderedFormats
        buildFormatChips()

        val filter = Looks.previewFilter(settings.look)
        listOf(previewView, insetPreview).forEach { view ->
            if (filter == null) {
                view.setLayerType(View.LAYER_TYPE_NONE, null)
            } else {
                view.setLayerType(View.LAYER_TYPE_HARDWARE, Paint().apply { colorFilter = filter })
            }
        }
        updateReadouts()
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

    // region Camera setup

    private fun bindCamera() {
        if (!has(Manifest.permission.CAMERA) || isRecording) return
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({ bindWith(future.get()) }, ContextCompat.getMainExecutor(this))
    }

    private fun bindWith(provider: ProcessCameraProvider) {
        this.provider = provider
        provider.unbindAll()
        camera = null
        mainCapture = null
        insetCapture = null
        dualKind = DualKind.NONE
        flipButton.isVisible =
            provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) && provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)

        if (settings.dual) {
            val pair = frontAndBackPair(provider)
            if (pair != null && (tryBindSeparate(provider, pair) || tryBindComposited(provider, pair))) {
                onCameraBound()
                return
            }
            Toast.makeText(this, R.string.dual_unsupported, Toast.LENGTH_LONG).show()
            settings = settings.copy(dual = false)
            SettingsStore.save(this, settings)
            applySettingsToUi()
        }

        if (bindSingle(provider)) onCameraBound() else bound = false
    }

    private fun qualitiesFrom(top: CameraResolution): List<Quality> =
        CameraResolution.entries.drop(top.ordinal).map { it.quality }

    private fun buildPreview(frame: CameraFrame, fps: Int?, target: PreviewView, readouts: Boolean): Preview {
        val builder = Preview.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy(frame.cameraRatio, AspectRatioStrategy.FALLBACK_RULE_AUTO))
                    .build()
            )
        if (fps != null) builder.setTargetFrameRate(Range(fps, fps))
        if (readouts) Camera2Interop.Extender(builder).setSessionCaptureCallback(readoutCallback)
        return builder.build().also { it.setSurfaceProvider(target.surfaceProvider) }
    }

    private fun buildCapture(
        qualities: List<Quality>,
        frame: CameraFrame,
        fps: Int?,
        stabilization: Boolean,
    ): VideoCapture<Recorder> {
        val recorder = Recorder.Builder()
            .setAspectRatio(frame.cameraRatio)
            .setQualitySelector(
                QualitySelector.fromOrderedList(qualities, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))
            )
            .build()
        val builder = VideoCapture.Builder(recorder).setMirrorMode(MirrorMode.MIRROR_MODE_ON_FRONT_ONLY)
        if (fps != null) builder.setTargetFrameRate(Range(fps, fps))
        if (stabilization) builder.setVideoStabilizationEnabled(true)
        return builder.build()
    }

    /** One camera. If the chosen frame rate or stabilization can't be combined, back off gracefully. */
    private fun bindSingle(provider: ProcessCameraProvider): Boolean {
        var selector = CameraSelector.Builder().requireLensFacing(settings.lensFacing).build()
        if (!provider.hasCamera(selector)) selector = CameraSelector.DEFAULT_BACK_CAMERA
        val info = provider.getCameraInfo(selector)

        val fpsOk = info.supportedFrameRateRanges.any { settings.fps in it.lower..it.upper }
        val stabilizationOk = settings.stabilization &&
            Recorder.getVideoCapabilities(info).isStabilizationSupported
        if (settings.stabilization && !stabilizationOk) {
            Toast.makeText(this, R.string.stabilization_unsupported, Toast.LENGTH_SHORT).show()
        }

        val attempts = listOf(
            Triple(if (fpsOk) settings.fps else null, stabilizationOk, settings.cameraResolution),
            Triple(null, false, settings.cameraResolution),
            Triple(null, false, CameraResolution.FHD),
        )
        for ((fps, stabilize, resolution) in attempts) {
            try {
                val preview = buildPreview(settings.cameraFrame, fps, previewView, readouts = true)
                val capture = buildCapture(qualitiesFrom(resolution), settings.cameraFrame, fps, stabilize)
                camera = provider.bindToLifecycle(this, selector, preview, capture)
                mainCapture = capture
                if (fps != settings.fps && settings.fps != 30) {
                    Toast.makeText(this, getString(R.string.fps_fallback, settings.fps), Toast.LENGTH_LONG).show()
                }
                return true
            } catch (e: Exception) {
                provider.unbindAll()
            }
        }
        Toast.makeText(this, getString(R.string.camera_error, "no supported setup"), Toast.LENGTH_LONG).show()
        return false
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

    private fun mainAndInset(pair: Pair<CameraInfo, CameraInfo>): Pair<CameraInfo, CameraInfo> {
        val (back, front) = pair
        return if (settings.lensFacing == CameraSelector.LENS_FACING_FRONT) front to back else back to front
    }

    /**
     * Front + back as two separate recordings. The inset is laid into each video afterwards,
     * so it lands in the chosen corner of every format. Phones that support concurrent
     * cameras guarantee about 1440p per camera, so this records 1080p / 720p.
     */
    private fun tryBindSeparate(provider: ProcessCameraProvider, pair: Pair<CameraInfo, CameraInfo>): Boolean {
        val (main, inset) = mainAndInset(pair)
        return try {
            val mainPreview = buildPreview(CameraFrame.FULL_4_3, null, previewView, readouts = true)
            val mainCap = buildCapture(listOf(Quality.FHD, Quality.HD), CameraFrame.FULL_4_3, null, false)
            val insetPrev = buildPreview(CameraFrame.FULL_4_3, null, insetPreview, readouts = false)
            val insetCap = buildCapture(listOf(Quality.HD, Quality.SD), CameraFrame.FULL_4_3, null, false)
            val concurrent = provider.bindToLifecycle(
                listOf(
                    ConcurrentCamera.SingleCameraConfig(
                        main.cameraSelector,
                        UseCaseGroup.Builder().addUseCase(mainPreview).addUseCase(mainCap).build(),
                        this,
                    ),
                    ConcurrentCamera.SingleCameraConfig(
                        inset.cameraSelector,
                        UseCaseGroup.Builder().addUseCase(insetPrev).addUseCase(insetCap).build(),
                        this,
                    ),
                )
            )
            camera = concurrent.cameras.first()
            mainCapture = mainCap
            insetCapture = insetCap
            dualKind = DualKind.SEPARATE
            true
        } catch (e: Exception) {
            provider.unbindAll()
            false
        }
    }

    /**
     * Fallback: CameraX draws the second camera into the main one, so a single recording holds
     * both. The inset is baked in, so frames stay centered and it sits in the chosen corner of
     * the area every format shares.
     */
    private fun tryBindComposited(provider: ProcessCameraProvider, pair: Pair<CameraInfo, CameraInfo>): Boolean {
        val (main, inset) = mainAndInset(pair)
        return try {
            val preview = buildPreview(CameraFrame.FULL_4_3, null, previewView, readouts = true)
            val capture = buildCapture(listOf(Quality.FHD, Quality.HD), CameraFrame.FULL_4_3, null, false)
            val group = UseCaseGroup.Builder().addUseCase(preview).addUseCase(capture).build()
            val pip = PipLayout.secondaryCamera(
                sourceAspect = CameraFrame.FULL_4_3.portraitAspect,
                rotationDegrees = main.getSensorRotationDegrees(Surface.ROTATION_0),
                corner = settings.insetCorner,
                formats = settings.orderedFormats,
            )
            val concurrent = provider.bindToLifecycle(
                listOf(
                    ConcurrentCamera.SingleCameraConfig(
                        main.cameraSelector,
                        group,
                        CompositionSettings.Builder().setAlpha(1f).setOffset(0f, 0f).setScale(1f, 1f).build(),
                        this,
                    ),
                    ConcurrentCamera.SingleCameraConfig(
                        inset.cameraSelector,
                        group,
                        CompositionSettings.Builder()
                            .setAlpha(1f)
                            .setOffset(pip.offsetX, pip.offsetY)
                            .setScale(pip.scale, pip.scale)
                            .build(),
                        this,
                    ),
                )
            )
            camera = concurrent.cameras.first()
            mainCapture = capture
            dualKind = DualKind.COMPOSITED
            true
        } catch (e: Exception) {
            provider.unbindAll()
            false
        }
    }

    private fun onCameraBound() {
        val cam = camera ?: return
        bound = true
        val c = CameraCaps(cam)
        caps = c
        lastReadout = null

        val frame = if (dualKind == DualKind.NONE) settings.cameraFrame else CameraFrame.FULL_4_3
        frameOverlay.sourceAspect = frame.portraitAspect
        frameOverlay.movable = dualKind != DualKind.COMPOSITED
        frameOverlay.insetCorner = if (dualKind == DualKind.SEPARATE) settings.insetCorner else null
        insetFrame.isVisible = dualKind == DualKind.SEPARATE
        hintText.setText(if (dualKind == DualKind.COMPOSITED) R.string.hint_dual_fixed else R.string.hint_frames)
        positionInsetPreview()

        observedCamera?.cameraInfo?.zoomState?.removeObservers(this)
        observedCamera = cam
        zoomStopsShown = emptyList()
        cam.cameraInfo.zoomState.observe(this) { updateZoomChips(it) }

        // Manual values this camera can't do are dropped rather than silently ignored.
        manual = manual.copy(
            iso = manual.iso?.takeIf { c.manualSensor },
            shutterNs = manual.shutterNs?.takeIf { c.manualSensor },
            focusDiopters = manual.focusDiopters?.takeIf { c.manualFocus },
            whiteBalance = manual.whiteBalance.takeIf { it in c.whiteBalances } ?: WhiteBalance.AUTO,
        )
        applyManual()
        infoText.text = ""
        mainCapture?.let { pollResolution(it, attemptsLeft = 20) }
    }

    private fun applyManual() {
        val cam = camera ?: return
        val c = caps ?: return
        ProControls.apply(cam, c, manual, settings.look, settings.fps, lastReadout)
        updateReadouts()
        openParam?.let { buildControlPanel(it) }
    }

    /** Live ISO / shutter / focus from the camera, a few times a second. */
    private val readoutCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastReadoutAt < 200) return
            lastReadoutAt = now
            val readout = ProControls.readout(result)
            runOnUiThread {
                lastReadout = readout
                updateReadouts()
            }
        }
    }

    /** The real recording size is only known once the camera has started; show it when it is. */
    private fun pollResolution(capture: VideoCapture<Recorder>, attemptsLeft: Int) {
        if (capture != mainCapture || attemptsLeft <= 0) return
        val info = capture.resolutionInfo
        if (info == null) {
            previewView.postDelayed({ pollResolution(capture, attemptsLeft - 1) }, 250)
            return
        }
        val crop = info.cropRect
        var w = if (crop.isEmpty) info.resolution.width else crop.width()
        var h = if (crop.isEmpty) info.resolution.height else crop.height()
        if (info.rotationDegrees % 180 != 0) w = h.also { h = w }
        frameOverlay.sourceAspect = w.toFloat() / h
        val label = if (dualKind == DualKind.NONE) settings.cameraFrame.label else getString(R.string.front_and_back)
        infoText.text = getString(R.string.recording_size, w, h, label)
    }

    // endregion

    // region Recording

    @SuppressLint("MissingPermission") // audio is only enabled after checking the permission
    private fun startOne(
        capture: VideoCapture<Recorder>,
        file: File,
        isMain: Boolean,
        onDone: (Take?) -> Unit,
    ): Recording {
        pendingFinalizes++
        var pending = capture.output.prepareRecording(this, FileOutputOptions.Builder(file).build())
        if (isMain && has(Manifest.permission.RECORD_AUDIO)) pending = pending.withAudioEnabled()
        return pending.start(ContextCompat.getMainExecutor(this)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    if (isMain) setRecordingUi(true)
                }
                is VideoRecordEvent.Status -> {
                    if (isMain) {
                        timerText.text = formatDuration(event.recordingStats.recordedDurationNanos)
                        val level = event.recordingStats.audioStats.audioAmplitude
                        audioMeter.setProgressCompat((level * 100).toInt().coerceIn(0, 100), false)
                    }
                }
                is VideoRecordEvent.Finalize -> {
                    // Some "errors" (camera closed, storage almost full) still leave a playable file.
                    val ok = event.error != VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA && file.length() > 0
                    if (ok) {
                        onDone(Take(file, event.recordingStats.recordedDurationNanos))
                    } else {
                        file.delete()
                        onDone(null)
                    }
                    pendingFinalizes--
                    if (pendingFinalizes == 0) onAllRecordingsFinished(event.error)
                }
            }
        }
    }

    private fun startRecording() {
        val main = mainCapture ?: return
        if (DualExporter.state.value !is ExportState.Idle) return
        closeControlPanel()

        baseName = "DualFrame_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        mainTake = null
        insetTake = null
        pendingFinalizes = 0
        mainRecording = startOne(main, File(cacheDir, "$baseName.mp4"), isMain = true) { mainTake = it }
        val inset = insetCapture
        if (dualKind == DualKind.SEPARATE && inset != null) {
            insetRecording = startOne(inset, File(cacheDir, "${baseName}_second.mp4"), isMain = false) { insetTake = it }
        }
        if (teleprompter.isVisible) {
            teleprompter.restart()
            teleprompter.play()
        }
    }

    private fun stopRecording() {
        mainRecording?.stop()
        insetRecording?.stop()
    }

    private fun onAllRecordingsFinished(lastError: Int) {
        mainRecording = null
        insetRecording = null
        setRecordingUi(false)
        teleprompter.pause()

        val main = mainTake
        if (main == null) {
            insetTake?.file?.delete()
            Toast.makeText(this, getString(R.string.record_failed, lastError), Toast.LENGTH_LONG).show()
            return
        }
        DualExporter.start(
            this,
            ExportJob(
                main = main,
                inset = insetTake,
                insetCorner = settings.insetCorner,
                outputs = settings.orderedFormats.map { it to settings.outputs.getValue(it) },
                placement = frameOverlay.placement,
                look = settings.look,
                quality = settings.exportQuality,
                keepOriginal = settings.keepOriginal,
                baseName = baseName,
            ),
        )
    }

    private fun setRecordingUi(recording: Boolean) {
        recordButton.setImageResource(if (recording) R.drawable.ic_stop else R.drawable.ic_record)
        recordButton.contentDescription = getString(if (recording) R.string.stop_recording else R.string.start_recording)
        timerRow.isVisible = recording
        if (recording) timerText.text = formatDuration(0)
        listOf<View>(flipButton, dualButton, settingsButton).forEach { it.isEnabled = !recording }
        for (i in 0 until cornerChips.childCount) cornerChips.getChildAt(i).isEnabled = !recording
        hintText.isVisible = !recording
        frameOverlay.locked = recording
    }

    private fun formatDuration(nanos: Long): String {
        val total = TimeUnit.NANOSECONDS.toSeconds(nanos)
        return String.format(Locale.US, "%02d:%02d:%02d", total / 3600, (total / 60) % 60, total % 60)
    }

    // endregion

    // region Touch on the preview

    override fun onTapToFocus(x: Float, y: Float) {
        if (openParam != null) {
            closeControlPanel()
            return
        }
        val cam = camera ?: return
        if (manual.focusDiopters != null) return // manual focus wins
        val point = previewView.meteringPointFactory.createPoint(x, y)
        cam.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
    }

    override fun onPinch(scaleFactor: Float) {
        val cam = camera ?: return
        val zoom = cam.cameraInfo.zoomState.value ?: return
        cam.cameraControl.setZoomRatio((zoom.zoomRatio * scaleFactor).coerceIn(zoom.minZoomRatio, zoom.maxZoomRatio))
    }

    override fun onFramesChanged() {
        positionInsetPreview()
        for (i in 0 until formatChips.childCount) {
            val chip = formatChips.getChildAt(i) as? Chip ?: continue
            if (chip.tag is OutputFormat) chip.isChecked = chip.tag == frameOverlay.selected
        }
    }

    /** The live second-camera preview sits exactly where the inset goes in the selected format. */
    private fun positionInsetPreview() {
        if (dualKind != DualKind.SEPARATE) return
        val format = frameOverlay.selected ?: return
        val r = frameOverlay.insetOnScreen(format) ?: return
        val lp = insetFrame.layoutParams as FrameLayout.LayoutParams
        lp.width = r.width().roundToInt()
        lp.height = r.height().roundToInt()
        lp.gravity = Gravity.TOP or Gravity.START
        lp.leftMargin = r.left.roundToInt()
        lp.topMargin = r.top.roundToInt()
        insetFrame.layoutParams = lp
    }

    // endregion

    // region Pro controls (readouts + control panel)

    private fun dp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    private fun buildReadouts() {
        Param.entries.forEach { param ->
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                minimumWidth = dp(56f).toInt()
                minimumHeight = dp(48f).toInt()
                setPadding(dp(6f).toInt(), dp(2f).toInt(), dp(6f).toInt(), dp(2f).toInt())
                isClickable = true
                isFocusable = true
                background = ContextCompat.getDrawable(context, R.drawable.bg_readout)
                contentDescription = param.title
                setOnClickListener { toggleControlPanel(param) }
            }
            cell.addView(TextView(this).apply {
                text = param.caption
                setTextColor(Color.argb(170, 255, 255, 255))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                letterSpacing = 0.08f
            })
            val value = TextView(this).apply {
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTypeface(typeface, Typeface.BOLD)
                fontFeatureSettings = "tnum"
            }
            cell.addView(value)
            readoutValues[param] = value
            readoutRow.addView(
                cell,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { marginEnd = dp(4f).toInt() },
            )
        }
    }

    private fun updateReadouts() {
        if (readoutValues.isEmpty()) return
        val r = lastReadout
        val accent = ContextCompat.getColor(this, R.color.accent)
        fun set(param: Param, text: String, isManual: Boolean) {
            readoutValues[param]?.apply {
                this.text = text
                setTextColor(if (isManual) accent else Color.WHITE)
            }
        }
        set(Param.FPS, "${settings.fps}", false)
        set(Param.SHUTTER, Labels.shutter(manual.shutterNs ?: r?.shutterNs), manual.shutterNs != null)
        set(Param.ISO, Labels.iso(manual.iso ?: r?.iso), manual.iso != null)
        val wb = if (manual.whiteBalance == WhiteBalance.AUTO) "AWB" else manual.whiteBalance.label
        set(
            Param.WB,
            if (manual.whiteBalanceLocked) "$wb 🔒" else wb,
            manual.whiteBalance != WhiteBalance.AUTO || manual.whiteBalanceLocked,
        )
        set(
            Param.FOCUS,
            if (manual.focusDiopters != null) Labels.focus(manual.focusDiopters) else "AF",
            manual.focusDiopters != null,
        )
        val step = camera?.cameraInfo?.exposureState?.exposureCompensationStep?.toFloat() ?: 0f
        set(Param.EV, if (manual.manualExposure) "M" else Labels.ev(manual.evIndex, step), manual.evIndex != 0)

        autoButton.isSelected = manual.isAllAuto
        autoButton.setTextColor(if (manual.isAllAuto) Color.BLACK else Color.WHITE)
    }

    private fun toggleControlPanel(param: Param) {
        if (openParam == param) closeControlPanel() else buildControlPanel(param)
    }

    private fun closeControlPanel() {
        openParam = null
        controlPanel.removeAllViews()
        controlPanel.isVisible = false
    }

    private fun buildControlPanel(param: Param) {
        val c = caps ?: return
        openParam = param
        controlPanel.removeAllViews()
        controlPanel.isVisible = true

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            TextView(this).apply {
                text = param.title
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTypeface(typeface, Typeface.BOLD)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        if (param != Param.FPS) {
            val isAuto = when (param) {
                Param.SHUTTER -> manual.shutterNs == null
                Param.ISO -> manual.iso == null
                Param.WB -> manual.whiteBalance == WhiteBalance.AUTO && !manual.whiteBalanceLocked
                Param.FOCUS -> manual.focusDiopters == null
                else -> manual.evIndex == 0
            }
            header.addView(chip("Auto", checked = isAuto) {
                manual = when (param) {
                    Param.SHUTTER -> manual.copy(shutterNs = null)
                    Param.ISO -> manual.copy(iso = null)
                    Param.WB -> manual.copy(whiteBalance = WhiteBalance.AUTO, whiteBalanceLocked = false)
                    Param.FOCUS -> manual.copy(focusDiopters = null)
                    else -> manual.copy(evIndex = 0)
                }
                applyManual()
            })
        }
        header.addView(MaterialButton(this).apply {
            text = "✕"
            backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            setTextColor(Color.WHITE)
            minWidth = dp(44f).toInt()
            minimumWidth = dp(44f).toInt()
            contentDescription = "Close"
            setOnClickListener { closeControlPanel() }
        })
        controlPanel.addView(header)

        when (param) {
            Param.FPS -> {
                val supported = camera?.cameraInfo?.supportedFrameRateRanges.orEmpty()
                val group = chipGroup()
                listOf(24, 25, 30, 60)
                    .filter { fps -> supported.isEmpty() || supported.any { fps in it.lower..it.upper } }
                    .forEach { fps ->
                        group.addView(chip("$fps", checked = fps == settings.fps) {
                            if (fps != settings.fps) updateSettings(settings.copy(fps = fps), rebind = true)
                        })
                    }
                controlPanel.addView(group)
                if (dualKind != DualKind.NONE) note("Front + back records at the camera's standard rate.")
            }
            Param.SHUTTER -> {
                val stops = c.shutterStops(settings.fps)
                if (!c.manualSensor || stops.size < 2) {
                    note("This camera doesn't allow manual shutter speed.")
                } else {
                    val current = manual.shutterNs ?: lastReadout?.shutterNs ?: (1_000_000_000L / (settings.fps * 2))
                    val index = stops.indices.minByOrNull { abs(1_000_000_000L / stops[it] - current) } ?: 0
                    slider(stops.size, index, { "1/${stops[it]}" }) { i ->
                        manual = manual.copy(shutterNs = 1_000_000_000L / stops[i])
                        applyManual()
                    }
                    note("Tip: 1/${settings.fps * 2} gives the most natural motion at ${settings.fps} fps. Setting shutter or ISO fixes both.")
                }
            }
            Param.ISO -> {
                val stops = c.isoStops
                if (!c.manualSensor || stops.size < 2) {
                    note("This camera doesn't allow manual ISO.")
                } else {
                    val current = manual.iso ?: lastReadout?.iso ?: 100
                    val index = stops.indices.minByOrNull { abs(stops[it] - current) } ?: 0
                    slider(stops.size, index, { "${stops[it]}" }) { i ->
                        manual = manual.copy(iso = stops[i])
                        applyManual()
                    }
                    note("Lower ISO = cleaner picture, higher = brighter but grainier.")
                }
            }
            Param.WB -> {
                val group = chipGroup()
                c.whiteBalances.forEach { wb ->
                    val label = if (wb == WhiteBalance.AUTO) "Auto" else "${wb.label} ${wbName(wb)}"
                    group.addView(chip(label, checked = wb == manual.whiteBalance) {
                        manual = manual.copy(whiteBalance = wb)
                        applyManual()
                    })
                }
                controlPanel.addView(group)
                controlPanel.addView(MaterialSwitch(this).apply {
                    text = "Lock white balance (stops colour shifting mid-shot)"
                    setTextColor(Color.WHITE)
                    isChecked = manual.whiteBalanceLocked
                    setOnCheckedChangeListener { _, checked ->
                        manual = manual.copy(whiteBalanceLocked = checked)
                        applyManual()
                    }
                })
            }
            Param.FOCUS -> {
                if (!c.manualFocus) {
                    note("This camera has fixed focus or doesn't allow manual focus.")
                } else {
                    val steps = 100
                    val current = manual.focusDiopters ?: lastReadout?.focusDiopters ?: 0f
                    val index = ((current / c.minFocusDiopters) * steps).roundToInt().coerceIn(0, steps)
                    slider(steps + 1, index, { Labels.focus(c.minFocusDiopters * it / steps) }) { i ->
                        manual = manual.copy(focusDiopters = c.minFocusDiopters * i / steps)
                        applyManual()
                    }
                    note("Left = far (∞), right = close.")
                }
            }
            Param.EV -> {
                val state = camera?.cameraInfo?.exposureState
                val range = state?.exposureCompensationRange
                if (manual.manualExposure) {
                    note("Exposure is set by your ISO and shutter. Set both to Auto to use this.")
                } else if (state == null || range == null || !state.isExposureCompensationSupported || range.upper <= range.lower) {
                    note("This camera doesn't support exposure compensation.")
                } else {
                    val step = state.exposureCompensationStep.toFloat()
                    val count = range.upper - range.lower + 1
                    slider(count, manual.evIndex - range.lower, { Labels.ev(it + range.lower, step) }) { i ->
                        manual = manual.copy(evIndex = i + range.lower)
                        applyManual()
                    }
                }
            }
        }
    }

    private fun wbName(wb: WhiteBalance) = when (wb) {
        WhiteBalance.AUTO -> ""
        WhiteBalance.TUNGSTEN -> "Indoor bulb"
        WhiteBalance.FLUORESCENT -> "Tube light"
        WhiteBalance.DAYLIGHT -> "Sunny"
        WhiteBalance.CLOUDY -> "Cloudy"
        WhiteBalance.SHADE -> "Shade"
    }

    private fun note(text: String) {
        controlPanel.addView(TextView(this).apply {
            this.text = text
            setTextColor(Color.argb(190, 255, 255, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(4f).toInt(), 0, 0)
        })
    }

    /** A stepped slider over [count] positions with a live value label. */
    private fun slider(count: Int, index: Int, label: (Int) -> String, onChange: (Int) -> Unit) {
        val start = index.coerceIn(0, count - 1)
        val valueText = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            gravity = Gravity.CENTER
            text = label(start)
        }
        val slider = Slider(this).apply {
            valueFrom = 0f
            valueTo = (count - 1).toFloat()
            stepSize = 1f
            value = start.toFloat()
            setLabelFormatter { label(it.roundToInt()) }
            addOnChangeListener { _, v, fromUser ->
                val i = v.roundToInt()
                valueText.text = label(i)
                if (fromUser) onChange(i)
            }
        }
        controlPanel.addView(valueText)
        controlPanel.addView(slider)
    }

    private fun chipGroup() = ChipGroup(this).apply {
        isSingleSelection = true
        isSelectionRequired = true
    }

    private fun chip(label: String, checked: Boolean, onClick: () -> Unit) = Chip(this).apply {
        text = label
        isCheckable = true
        isChecked = checked
        isCheckedIconVisible = false
        setOnClickListener { onClick() }
    }

    // endregion

    // region Format chips, corner chips, zoom chips

    private fun buildFormatChips() {
        formatChips.removeAllViews()
        settings.orderedFormats.forEach { format ->
            formatChips.addView(Chip(this).apply {
                text = "${format.label} · ${settings.outputs[format]?.label}"
                tag = format
                isCheckable = true
                isCheckedIconVisible = false
                isChecked = format == frameOverlay.selected
                chipStrokeWidth = dp(1.5f)
                chipStrokeColor = ColorStateList.valueOf(format.color)
                setOnClickListener { frameOverlay.selected = format }
            })
        }
        formatChips.addView(Chip(this).apply {
            text = getString(R.string.edit_formats)
            setOnClickListener { showSettings() }
        })
    }

    private fun buildCornerChips() {
        Corner.entries.forEach { corner ->
            cornerChips.addView(Chip(this).apply {
                text = corner.label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                isCheckable = true
                isCheckedIconVisible = false
                contentDescription = corner.name.lowercase().replace('_', ' ')
                setOnClickListener {
                    if (corner != settings.insetCorner) {
                        // The fallback mode bakes the inset in, so it needs the camera reopened.
                        updateSettings(settings.copy(insetCorner = corner), rebind = dualKind == DualKind.COMPOSITED)
                        if (dualKind == DualKind.SEPARATE) frameOverlay.insetCorner = corner
                    }
                }
            })
        }
    }

    private var zoomStopsShown: List<Float> = emptyList()

    private fun zoomStops(state: ZoomState): List<Float> {
        val stops = mutableListOf<Float>()
        if (state.minZoomRatio < 0.95f) stops += (state.minZoomRatio * 10).roundToInt() / 10f
        listOf(1f, 2f, 3f, 5f, 10f).filterTo(stops) { it <= state.maxZoomRatio + 0.01f }
        return stops
    }

    private fun updateZoomChips(state: ZoomState) {
        val stops = zoomStops(state)
        if (stops != zoomStopsShown) {
            zoomStopsShown = stops
            zoomChips.removeAllViews()
            stops.forEach { ratio ->
                zoomChips.addView(Chip(this).apply {
                    text = if (ratio % 1f == 0f) "${ratio.toInt()}×" else "$ratio×"
                    isCheckable = true
                    isCheckedIconVisible = false
                    tag = ratio
                    setOnClickListener {
                        camera?.cameraControl?.setZoomRatio(ratio.coerceAtLeast(state.minZoomRatio))
                    }
                })
            }
        }
        for (i in 0 until zoomChips.childCount) {
            val chip = zoomChips.getChildAt(i) as Chip
            chip.isChecked = abs((chip.tag as Float) - state.zoomRatio) < 0.05f
        }
        val focal = caps?.equivalentFocalMm
        zoomLabel.text = String.format(Locale.US, "%.1f×", state.zoomRatio) +
            (focal?.let { " · ${(it * state.zoomRatio).roundToInt()}mm" } ?: "")
    }

    // endregion

    // region Settings sheet

    private fun showSettings() {
        if (isRecording) return
        val c = caps
        var draft = settings
        val sheet = BottomSheetDialog(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(20f).toInt()
            setPadding(p, p / 2, p, p * 2)
        }

        fun section(title: String, subtitle: String? = null) {
            root.addView(TextView(this).apply {
                text = title
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(18f).toInt(), 0, dp(4f).toInt())
            })
            if (subtitle != null) {
                root.addView(TextView(this).apply {
                    text = subtitle
                    setTextColor(Color.argb(170, 255, 255, 255))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                })
            }
        }

        fun noteView(initial: String) = TextView(this).apply {
            setTextColor(Color.argb(170, 255, 255, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            text = initial
        }

        fun <T> choices(
            options: List<T>,
            selected: T,
            label: (T) -> String,
            enabled: (T) -> Boolean = { true },
            onPick: (T) -> Unit,
        ) {
            val group = chipGroup()
            options.forEach { option ->
                group.addView(chip(label(option), checked = option == selected) { onPick(option) }.apply {
                    isEnabled = enabled(option)
                })
            }
            root.addView(group)
        }

        fun toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
            root.addView(MaterialSwitch(this).apply {
                text = label
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                isChecked = value
                minHeight = dp(48f).toInt()
                setOnCheckedChangeListener { _, checked -> onChange(checked) }
            })
        }

        section("Videos to make", "Every ticked shape becomes its own video from the same recording. Pick a resolution for each.")
        OutputFormat.entries.forEach { format ->
            var chosenResolution = draft.outputs[format] ?: OutputResolution.FHD
            val resolutions = chipGroup()
            OutputResolution.entries.forEach { res ->
                val (w, h) = format.sizePx(res)
                resolutions.addView(chip(res.label, checked = chosenResolution == res) {
                    chosenResolution = res
                    if (format in draft.outputs) draft = draft.copy(outputs = draft.outputs + (format to res))
                }.apply { contentDescription = "${res.label}, $w by $h" })
            }
            val check = MaterialCheckBox(this).apply {
                text = "${format.label}   ${format.use}"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                buttonTintList = ColorStateList.valueOf(format.color)
                isChecked = format in draft.outputs
            }
            resolutions.isVisible = check.isChecked
            check.setOnCheckedChangeListener { box, checked ->
                if (!checked && draft.outputs.keys == setOf(format)) {
                    box.isChecked = true
                    Toast.makeText(this, R.string.keep_one_format, Toast.LENGTH_SHORT).show()
                    return@setOnCheckedChangeListener
                }
                draft = draft.copy(
                    outputs = if (checked) draft.outputs + (format to chosenResolution) else draft.outputs - format
                )
                resolutions.isVisible = checked
            }
            root.addView(check)
            root.addView(resolutions)
        }

        section("Video quality", "Higher keeps more detail but makes bigger files.")
        choices(ExportQuality.entries, draft.exportQuality, { it.label }) { draft = draft.copy(exportQuality = it) }

        section("Camera recording", "What the camera captures before your videos are cut from it.")
        val frameNote = noteView(draft.cameraFrame.description)
        choices(CameraFrame.entries, draft.cameraFrame, { it.label }) {
            draft = draft.copy(cameraFrame = it)
            frameNote.text = it.description
        }
        root.addView(frameNote)
        choices(CameraResolution.entries, draft.cameraResolution, { it.label }) {
            draft = draft.copy(cameraResolution = it)
        }
        toggle("Stabilization (smoother handheld video, slightly tighter picture)", draft.stabilization) {
            draft = draft.copy(stabilization = it)
        }
        if (draft.dual) root.addView(noteView("Front + back always records full sensor 4:3 at up to 1080p."))

        section("Look", "Natural is your phone's own colour processing.")
        val lookNote = noteView(draft.look.description)
        choices(
            Look.entries,
            draft.look,
            { it.label },
            enabled = { it != Look.FLAT || c?.flatLookSupported != false },
        ) {
            draft = draft.copy(look = it)
            lookNote.text = it.description
        }
        root.addView(lookNote)

        section("Extras")
        toggle("Grid lines", draft.grid) { draft = draft.copy(grid = it) }
        toggle("Also keep the original uncut recording", draft.keepOriginal) { draft = draft.copy(keepOriginal = it) }

        sheet.setContentView(NestedScrollView(this).apply { addView(root) })
        sheet.setOnDismissListener {
            val old = settings
            if (draft == old) return@setOnDismissListener
            val cameraChanged = draft.cameraFrame != old.cameraFrame ||
                draft.cameraResolution != old.cameraResolution ||
                draft.stabilization != old.stabilization ||
                (draft.dual && draft.orderedFormats != old.orderedFormats)
            updateSettings(draft, rebind = cameraChanged)
        }
        sheet.behavior.peekHeight = (resources.displayMetrics.heightPixels * 0.7f).toInt()
        sheet.show()
    }

    // endregion

    // region Export results

    private fun render(state: ExportState) {
        exportPanel.isVisible = state is ExportState.Running
        resultPanel.isVisible = state is ExportState.Done
        recordButton.isEnabled = state is ExportState.Idle

        when (state) {
            is ExportState.Idle -> shownResult = null
            is ExportState.Running -> {
                exportTitle.text = getString(R.string.exporting, state.step, state.total, state.format.label)
                exportProgress.setProgressCompat(state.progress, true)
                exportPercent.text = getString(R.string.percent, state.progress)
            }
            is ExportState.Done -> if (shownResult != state) {
                shownResult = state
                bindResults(state)
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

    private fun bindResults(done: ExportState.Done) {
        resultList.removeAllViews()
        val box = dp(96f)
        done.videos.forEach { video ->
            val row = layoutInflater.inflate(R.layout.item_result, resultList, false)
            val thumb = row.findViewById<ImageView>(R.id.thumb)
            thumb.layoutParams = thumb.layoutParams.apply {
                if (video.format.aspect >= 1f) {
                    width = box.toInt()
                    height = (box / video.format.aspect).toInt()
                } else {
                    height = box.toInt()
                    width = (box * video.format.aspect).toInt()
                }
            }
            loadThumbnail(video.uri, thumb)
            val (w, h) = video.format.sizePx(video.resolution)
            row.findViewById<TextView>(R.id.title).text = getString(R.string.result_title_row, video.format.label, video.resolution.label)
            row.findViewById<TextView>(R.id.subtitle).text = getString(R.string.result_subtitle_row, video.format.use, w, h)
            row.findViewById<View>(R.id.play).setOnClickListener { play(video.uri) }
            thumb.setOnClickListener { play(video.uri) }
            row.findViewById<View>(R.id.share).setOnClickListener { share(video.uri, video.format.label) }
            resultList.addView(row)
        }
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

    private fun share(uri: Uri, label: String) {
        val send = Intent(Intent.ACTION_SEND)
            .setType("video/mp4")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, getString(R.string.share_format, label)))
    }

    // endregion

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            findViewById<View>(R.id.top_bar).updatePadding(top = bars.top)
            findViewById<View>(R.id.bottom_bar).updatePadding(bottom = bars.bottom)
            teleprompter.topInset = bars.top
            listOf(exportPanel, resultPanel, permissionPanel).forEach {
                it.updatePadding(top = bars.top, bottom = bars.bottom)
            }
            insets
        }
    }

    private companion object {
        const val KEY_ASKED = "asked"
    }
}
