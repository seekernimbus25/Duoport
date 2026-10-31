package com.dualframe.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaCodecList
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.view.KeyEvent
import android.view.OrientationEventListener
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Range
import android.util.Size
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
import androidx.camera.core.DynamicRange
import androidx.camera.core.ExperimentalSessionConfig
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.MirrorMode
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ZoomState
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.ExperimentalHighSpeedVideo
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.HighSpeedVideoSessionConfig
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
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.slider.LabelFormatter
import com.google.android.material.slider.Slider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.atan2
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(ExperimentalCamera2Interop::class)
@kotlin.OptIn(ExperimentalSessionConfig::class, ExperimentalHighSpeedVideo::class)
class MainActivity : AppCompatActivity(), FrameOverlayView.Listener {

    private enum class Param(val caption: String, val title: String) {
        PROFILE("Profile", "Recording profile"),
        LUT("LUT", "Look (LUT)"),
        FPS("FPS", "Frame rate"),
        LENS("Lens", "Lens"),
        SHUTTER("Shutter", "Shutter speed"),
        ISO("ISO", "ISO"),
        WB("WB", "White balance"),
        FOCUS("Focus", "Focus"),
        EV("Exp", "Exposure"),
        MONITOR("Assist", "Monitoring"),
    }

    /** How the second camera is being captured in Front + Back. */
    private enum class DualKind { NONE, SEPARATE, COMPOSITED }

    private lateinit var previewView: PreviewView
    private lateinit var insetFrame: FrameLayout
    private lateinit var insetPreview: PreviewView
    private lateinit var frameOverlay: FrameOverlayView
    private lateinit var teleprompter: TeleprompterView
    private lateinit var recordButton: ImageButton
    private lateinit var flipButton: ImageButton
    private lateinit var scriptButton: ImageButton

    // Shot list mode: the shot list being shot, and the take just recorded (shot id, take).
    private lateinit var shotBanner: ShotBanner
    private var shotProject: ShotProject? = null
    private var recordingShotTake: Pair<Long, Int>? = null
    private var lastShotTake: Pair<Long, Int>? = null
    private var shownShotId: Long? = null
    private var shownProjectId: String? = null
    private lateinit var dualButton: ImageButton
    private lateinit var settingsButton: ImageButton
    private lateinit var formatScroller: View
    private lateinit var formatChips: LinearLayout
    private lateinit var proStrip: View
    private lateinit var readoutRow: LinearLayout
    private lateinit var meterRow: View
    private lateinit var timerRow: View
    private lateinit var timerText: TextView
    private lateinit var audioMeter: AudioMeterView
    private lateinit var torchButton: ImageButton
    private lateinit var timerButton: ImageButton
    private lateinit var nightButton: ImageButton
    private lateinit var monitorRow: View
    private lateinit var histogramView: HistogramView
    private lateinit var exposureMeter: TextView
    private lateinit var aeLockPill: TextView
    private lateinit var countdownText: TextView
    private lateinit var infoText: TextView
    private lateinit var hintText: TextView
    private lateinit var controlPanel: LinearLayout
    private lateinit var cornerRow: View
    private lateinit var cornerChips: LinearLayout
    private lateinit var zoomCapsule: LinearLayout
    private lateinit var zoomLabel: TextView
    private lateinit var modeAuto: View
    private lateinit var modePro: View
    private lateinit var galleryThumb: ShapeableImageView
    private lateinit var galleryIcon: ImageView
    private lateinit var permissionPanel: View
    private lateinit var exportPanel: View
    private lateinit var exportStep: TextView
    private lateinit var exportTitle: TextView
    private lateinit var exportPercent: TextView
    private lateinit var exportProgress: LinearProgressIndicator
    private lateinit var resultPanel: View
    private lateinit var resultList: LinearLayout

    private var settings = CaptureSettings()
    /** Pro's manual values. Auto ignores them but they are kept for when you come back to Pro. */
    private var manual = ManualState()
    private val activeManual get() = (if (settings.isPro) manual else ManualState()).copy(aeLocked = aeAfLocked)
    /** Long-press lock: exposure and focus held at a point, in Auto and Pro alike. */
    private var aeAfLocked = false
    private var torchOn = false
    /** What the exposure loop chose for the "auto" side in shutter- / ISO-priority. */
    private var priorityIso: Int? = null
    private var priorityShutterNs: Long? = null
    private var lastStats: ExposureStats? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var countdownRunning = false
    private val statusHandler = Handler(Looper.getMainLooper())
    /**
     * Preview watchdog. If the picture stops moving, find out which part stalled and turn that
     * part off, instead of leaving a frozen viewfinder:
     *  - the live LUT / monitoring pass stopped drawing → show the plain camera picture
     *  - the camera itself stopped sending frames with DF-Log's tone curve → switch DF-Log off
     */
    private var boundAt = 0L
    private var stallRecoveries = 0
    private var logCurveBroken = false
    private val stallWatch = object : Runnable {
        override fun run() {
            checkForStall()
            statusHandler.postDelayed(this, 1000)
        }
    }

    private val statusTick = object : Runnable {
        override fun run() {
            updateInfoLine()
            statusHandler.postDelayed(this, 10_000)
        }
    }
    private var resolutionText = ""
    private var levelRoll = 0f
    private val sensorManager by lazy { getSystemService(SensorManager::class.java) }
    private val levelListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            // Roll of an upright phone, smoothed; clockwise tilt is positive.
            val roll = Math.toDegrees(atan2(-event.values[0].toDouble(), event.values[1].toDouble())).toFloat()
            levelRoll += (roll - levelRoll) * 0.2f
            // When recording sideways the level is drawn in the turned frame.
            var relative = levelRoll + frameOverlay.rotation
            if (relative > 180f) relative -= 360f
            if (relative < -180f) relative += 360f
            frameOverlay.levelDegrees = relative
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }
    private var caps: CameraCaps? = null
    private var lastReadout: Readout? = null
    @Volatile private var lastReadoutAt = 0L
    private var openParam: Param? = null
    private val readoutValues = mutableMapOf<Param, TextView>()
    private var autoCell: TextView? = null

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var observedCamera: Camera? = null
    private var mainCapture: VideoCapture<Recorder>? = null
    private var insetCapture: VideoCapture<Recorder>? = null
    private var dualKind = DualKind.NONE
    private var bound = false
    private var askedForPermission = false
    private var shownResult: ExportState? = null

    private val lutProcessor by lazy { LutPreviewProcessor() }
    /** The LUT for the current profile + choice, or null for none. */
    private var activeLut: LutCube? = null
    private var effectAttached = false
    private var livePreviewBroken = false
    private var hlgSupported = false

    /** Lenses on the current side, the phone's 1× focal length, and the lens locked now (if any). */
    private var lenses: List<Lens> = emptyList()
    private var mainLensMm = 0
    private var lockedLens: Lens? = null
    /** Zoom kept inside this range while a lens in a multi-lens camera is locked. */
    private var lockedZoom: ClosedFloatingPointRange<Float>? = null

    /** Slow-motion frame rates this camera can record (60 normally, 120 / 240 in high-speed mode). */
    private var slowRates: List<Int> = emptyList()
    private var highSpeedBound = false

    /** Photo mode's still capture (instead of [mainCapture]). */
    private var imageCapture: ImageCapture? = null
    private val photoExecutor = Executors.newSingleThreadExecutor()

    /** HLG is only offered through the codec list on some phones (not their camcorder profiles). */
    private var hlgViaCodecs = false

    /** Which way up the next recording / photo is: follows the phone when turned sideways. */
    private var recordRotation = Surface.ROTATION_0
    private val orientationListener by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) = onPhoneTurned(orientation)
        }
    }

    // Auto-reframe: face tracking on the analysis frames, and the positions during a take.
    private var subjectTracker: SubjectTracker? = null
    private val exposureAnalyzer = ExposureAnalyzer { stats -> runOnUiThread { onExposureStats(stats) } }
    private var analysisUseCase: ImageAnalysis? = null
    private var previewUseCase: Preview? = null
    private val trackSamples = mutableListOf<SubjectSample>()

    /** Follow me without an analysis stream (paused for the resolution): faces come from the preview. */
    private var previewTracking = false
    private var previewTrackBusy = false
    private val previewTrackTick = object : Runnable {
        override fun run() {
            trackFromPreview()
            if (previewTracking) statusHandler.postDelayed(this, PREVIEW_TRACK_MS)
        }
    }
    @Volatile private var recordStartNanos = 0L

    /** Frames in a row where the camera reported it wasn't using the DF-Log curve. */
    @Volatile private var tonemapIgnored = 0
    private var recordingStartMs = 0L

    /** The highest bitrate any H.264 / HEVC encoder on this phone accepts, in Mbps. */
    private val encoderMaxMbps: Int by lazy {
        runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { it.isEncoder }
                .flatMap { info ->
                    info.supportedTypes.filter { it == "video/avc" || it == "video/hevc" }
                        .mapNotNull { info.getCapabilitiesForType(it).videoCapabilities?.bitrateRange?.upper }
                }
                .maxOrNull()?.div(1_000_000) ?: 0
        }.getOrDefault(0)
    }

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

    private var captionStyleSheet: CaptionStyleSheet? = null
    private val importFont = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                    CaptionFonts.import(this@MainActivity, uri, name)
                }
            }
            result.onSuccess { id -> captionStyleSheet?.onFontImported(id) }
                .onFailure { Toast.makeText(this@MainActivity, it.message ?: "Couldn't import that font", Toast.LENGTH_LONG).show() }
        }
    }

    private fun openCaptionStyle() {
        captionStyleSheet = CaptionStyleSheet(this, onImportFont = {
            importFont.launch(arrayOf("font/*", "application/x-font-ttf", "application/x-font-otf", "application/octet-stream"))
        }).also { it.show() }
    }

    private val importLut = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importLutFrom(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (intent.hasExtra(TestHooks.EXTRA_IMPORT_MEDIA)) {
            lifecycleScope.launch(Dispatchers.IO) {
                val added = runCatching { TestHooks.importMedia(this@MainActivity) }.getOrDefault(-1)
                android.util.Log.i("DualFrameTest", "imported $added")
            }
        }

        settings = SettingsStore.load(this)
        askedForPermission = savedInstanceState?.getBoolean(KEY_ASKED, false) ?: false

        previewView = findViewById(R.id.preview)
        insetFrame = findViewById(R.id.inset_frame)
        insetPreview = findViewById(R.id.inset_preview)
        frameOverlay = findViewById(R.id.overlay)
        teleprompter = findViewById(R.id.teleprompter)
        recordButton = findViewById(R.id.record_button)
        flipButton = findViewById(R.id.flip_button)
        scriptButton = findViewById(R.id.script_button)
        dualButton = findViewById(R.id.dual_button)
        settingsButton = findViewById(R.id.settings_button)
        formatScroller = findViewById(R.id.format_scroller)
        formatChips = findViewById(R.id.format_chips)
        proStrip = findViewById(R.id.pro_strip)
        readoutRow = findViewById(R.id.readouts)
        meterRow = findViewById(R.id.meter_row)
        timerRow = findViewById(R.id.timer_row)
        timerText = findViewById(R.id.timer)
        audioMeter = findViewById(R.id.audio_meter)
        torchButton = findViewById(R.id.torch_button)
        timerButton = findViewById(R.id.timer_button)
        nightButton = findViewById(R.id.night_button)
        monitorRow = findViewById(R.id.monitor_row)
        histogramView = findViewById(R.id.histogram)
        exposureMeter = findViewById(R.id.exposure_meter)
        aeLockPill = findViewById(R.id.ae_lock_pill)
        countdownText = findViewById(R.id.countdown)
        infoText = findViewById(R.id.info)
        hintText = findViewById(R.id.hint)
        controlPanel = findViewById(R.id.control_panel)
        // Back closes an open Pro panel first instead of leaving the camera.
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (openParam != null) {
                    closeControlPanel()
                    return
                }
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        })
        cornerRow = findViewById(R.id.corner_row)
        cornerChips = findViewById(R.id.corner_chips)
        zoomCapsule = findViewById(R.id.zoom_capsule)
        zoomLabel = findViewById(R.id.zoom_label)
        modeAuto = findViewById(R.id.mode_auto)
        modePro = findViewById(R.id.mode_pro)
        galleryThumb = findViewById(R.id.gallery_thumb)
        galleryIcon = findViewById(R.id.gallery_icon)
        permissionPanel = findViewById(R.id.permission_panel)
        exportPanel = findViewById(R.id.export_panel)
        exportStep = findViewById(R.id.export_step)
        exportTitle = findViewById(R.id.export_title)
        exportPercent = findViewById(R.id.export_percent)
        exportProgress = findViewById(R.id.export_progress)
        resultPanel = findViewById(R.id.result_panel)
        resultList = findViewById(R.id.result_list)

        frameOverlay.listener = this
        buildReadouts()
        buildCornerChips()
        applySettingsToUi()

        recordButton.setOnClickListener {
            when {
                countdownRunning -> cancelCountdown()
                isRecording -> stopRecording()
                else -> startRecording()
            }
        }
        flipButton.setOnClickListener {
            val facing = if (settings.lensFacing == CameraSelector.LENS_FACING_BACK) {
                CameraSelector.LENS_FACING_FRONT
            } else {
                CameraSelector.LENS_FACING_BACK
            }
            flipButton.animate().rotationBy(180f).setDuration(260).start()
            updateSettings(settings.copy(lensFacing = facing, lensKey = null), rebind = true)
        }
        dualButton.setOnClickListener {
            val turningOn = !settings.dual
            if (turningOn && !supportsFrontAndBack()) {
                Toast.makeText(this, R.string.dual_unsupported, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (turningOn) Toast.makeText(this, R.string.dual_help, Toast.LENGTH_LONG).show()
            updateSettings(settings.copy(dual = turningOn), rebind = true)
        }
        scriptButton.setOnClickListener {
            val show = !teleprompter.isVisible
            Glass.show(teleprompter, show)
            if (!show) teleprompter.pause()
            scriptButton.isSelected = show
        }
        settingsButton.setOnClickListener { showSettings() }
        torchButton.setOnClickListener {
            torchOn = !torchOn
            camera?.cameraControl?.enableTorch(torchOn)
            updateTorchIcon()
        }
        timerButton.setOnClickListener {
            val next = when (settings.timerSeconds) { 0 -> 3; 3 -> 10; else -> 0 }
            updateSettings(settings.copy(timerSeconds = next), rebind = false)
            Toast.makeText(
                this,
                if (next == 0) getString(R.string.timer_off) else getString(R.string.timer_on, next),
                Toast.LENGTH_SHORT,
            ).show()
        }
        aeLockPill.setOnClickListener { unlockAeAf() }
        nightButton.setOnClickListener {
            val on = !settings.night
            if (on && settings.isPro && manual.manualExposure) {
                Toast.makeText(this, R.string.night_needs_auto, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            updateSettings(settings.copy(night = on), rebind = false)
            applyNight()
            Toast.makeText(this, if (on) R.string.night_on else R.string.night_off, Toast.LENGTH_SHORT).show()
        }
        findViewById<View>(R.id.capture_video).setOnClickListener { setPhotoMode(false) }
        findViewById<View>(R.id.capture_photo).setOnClickListener { setPhotoMode(true) }
        setupShots()
        modeAuto.setOnClickListener { setMode(CaptureMode.AUTO) }
        modePro.setOnClickListener { setMode(CaptureMode.PRO) }
        findViewById<View>(R.id.gallery_button).setOnClickListener { openLibrary() }
        findViewById<View>(R.id.grant_button).setOnClickListener { requestOrOpenSettings() }
        findViewById<View>(R.id.record_again).setOnClickListener { DualExporter.acknowledge() }
        findViewById<View>(R.id.open_library).setOnClickListener {
            DualExporter.acknowledge()
            openLibrary()
        }

        applyInsets()
        refreshLut(forceRebind = false)

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
        refreshGalleryThumb()
        updateLevelSensor()
        statusHandler.post(statusTick)
        stallRecoveries = 0
        statusHandler.postDelayed(stallWatch, 1000)
        if (orientationListener.canDetectOrientation()) orientationListener.enable()
        if (previewTracking) statusHandler.post(previewTrackTick)
        refreshShots()
    }

    override fun onPause() {
        super.onPause()
        sensorManager?.unregisterListener(levelListener)
        statusHandler.removeCallbacks(statusTick)
        statusHandler.removeCallbacks(stallWatch)
        statusHandler.removeCallbacks(previewTrackTick)
        orientationListener.disable()
    }

    /** Volume buttons start and stop recording, like a camera's record button. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val volume = keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        if (volume && settings.volumeKeysRecord && bound && DualExporter.state.value is ExportState.Idle) {
            if (event.repeatCount == 0) recordButton.performClick()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val volume = keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        if (volume && settings.volumeKeysRecord && bound) return true
        return super.onKeyUp(keyCode, event)
    }

    override fun onStop() {
        super.onStop()
        // CameraX closes the camera now; it is opened again (with the manual settings) on resume.
        bound = false
    }

    override fun onDestroy() {
        super.onDestroy()
        lutProcessor.release()
        analysisExecutor.shutdown()
        photoExecutor.shutdown()
        subjectTracker?.close()
    }

    private fun updateSettings(new: CaptureSettings, rebind: Boolean) {
        stallRecoveries = 0
        val lutChanged = new.activeLutId != settings.activeLutId || new.activeProfile != settings.activeProfile ||
            new.activeGrade != settings.activeGrade
        val outputsChanged = new.outputs != settings.outputs
        settings = new
        SettingsStore.save(this, new)
        // Shapes changed while shooting a shot list: they become that list's shapes.
        shotProject?.takeIf { outputsChanged && new.outputs.isNotEmpty() }?.let { p ->
            ShotLists.load(this, p.id)?.let { fresh ->
                fresh.outputs.clear()
                fresh.outputs.putAll(new.outputs)
                ShotLists.save(this, fresh)
            }
        }
        applySettingsToUi()
        // A new LUT can mean adding or removing the live-preview effect, which needs a rebind;
        // refreshLut decides once the LUT is baked.
        if (lutChanged) {
            refreshLut(forceRebind = rebind)
        } else if (rebind || (bound && wantsPreviewEffect() != effectAttached)) {
            // Turning zebras / peaking / false colour on or off adds or removes the GPU pass.
            bindCamera()
        }
        if (!rebind) applyManual()
    }

    private fun setPhotoMode(photo: Boolean) {
        if (photo == settings.photoMode || isRecording || countdownRunning) return
        closeControlPanel()
        if (photo && settings.dual) Toast.makeText(this, R.string.photo_no_dual, Toast.LENGTH_LONG).show()
        updateSettings(settings.copy(photoMode = photo), rebind = true)
    }

    private fun setMode(mode: CaptureMode) {
        if (mode == settings.mode || isRecording) return
        closeControlPanel()
        // The profile (HLG needs a different camera stream) and LUT can differ between modes.
        updateSettings(settings.copy(mode = mode), rebind = true)
    }

    /** Everything on screen that depends on [settings] but not on the camera being open. */
    private fun applySettingsToUi() {
        dualButton.isSelected = settings.dual
        Glass.show(cornerRow, settings.dual)
        for (i in 0 until cornerChips.childCount) {
            cornerChips.getChildAt(i).isSelected = i == settings.insetCorner.ordinal
        }
        frameOverlay.grid = settings.grid
        val monitoring = settings.activeMonitoring
        frameOverlay.centerMark = monitoring.centerMark
        frameOverlay.safeArea = monitoring.safeArea
        lutProcessor.setMonitoring(monitoring)
        exposureAnalyzer.scope = if (settings.isPro) monitoring.scope else 0
        // The waveform needs a wider box than the histogram; the vectorscope a square-ish one.
        histogramView.layoutParams = histogramView.layoutParams.apply {
            width = dp(if (monitoring.scope == 1) 150f else 112f).toInt()
            height = dp(if (monitoring.scope == 2) 76f else 52f).toInt()
        }
        Glass.show(monitorRow, monitoring.histogram && settings.isPro)
        updateLevelSensor()
        timerButton.setImageResource(
            when (settings.timerSeconds) {
                3 -> R.drawable.ic_timer_3_alt_1
                10 -> R.drawable.ic_timer_10_alt_1
                else -> R.drawable.ic_timer_off
            }
        )
        timerButton.isSelected = settings.timerSeconds > 0
        frameOverlay.formats = settings.orderedFormats
        buildFormatChips()

        val pro = settings.isPro
        Glass.show(proStrip, pro)
        val accent = ContextCompat.getColor(this, R.color.accent)
        val dim = ContextCompat.getColor(this, R.color.text_tertiary)
        val photo = settings.photoMode
        findViewById<TextView>(R.id.capture_video).setTextColor(if (photo) dim else accent)
        findViewById<TextView>(R.id.capture_photo).setTextColor(if (photo) accent else dim)
        if (!isRecording && !countdownRunning) recordButton.setImageResource(shutterIcon())
        recordButton.contentDescription = getString(if (photo) R.string.take_photo else R.string.start_recording)
        dualButton.isEnabled = !photo
        dualButton.alpha = if (photo) 0.35f else 1f
        frameOverlay.desqueeze = settings.activeAnamorphic
        // Anamorphic preview: narrow the picture across the screen so it looks desqueezed.
        previewView.scaleX = 1f / settings.activeAnamorphic
        lutProcessor.setCinematic(settings.cinematic)
        findViewById<TextView>(R.id.mode_auto_text).setTextColor(if (pro) dim else accent)
        findViewById<TextView>(R.id.mode_pro_text).setTextColor(if (pro) accent else dim)
        findViewById<View>(R.id.mode_auto_dot).visibility = if (pro) View.INVISIBLE else View.VISIBLE
        findViewById<View>(R.id.mode_pro_dot).visibility = if (pro) View.VISIBLE else View.INVISIBLE
        updateReadouts()
    }

    // region LUT

    /** Bakes the LUT for the current choice off the main thread, then updates the live preview. */
    private fun refreshLut(forceRebind: Boolean) {
        val id = settings.activeLutId
        val profile = settings.activeProfile
        val grade = settings.activeGrade
        lutProcessor.setFinish(grade)
        lifecycleScope.launch {
            val cube = withContext(Dispatchers.Default) {
                runCatching { Luts.cube(this@MainActivity, id, profile, grade) }.getOrNull()
            }
            // A newer change (e.g. the next slider step) has already started its own bake.
            if (id != settings.activeLutId || profile != settings.activeProfile || grade != settings.activeGrade) return@launch
            activeLut = cube
            // Monitoring without a LUT still runs through the shader, with a do-nothing LUT.
            lutProcessor.setLut(cube ?: Luts.identity)
            updateReadouts()
            if (forceRebind || (bound && wantsPreviewEffect() != effectAttached)) bindCamera()
        }
    }

    private fun wantsPreviewEffect() =
        (activeLut != null || settings.activeMonitoring.needsShader || settings.cinematic > 0 || settings.activeGrade.hasFinish) &&
            !livePreviewBroken &&
            settings.activeProfile != RecordProfile.HLG && !settings.dual

    private fun updateLevelSensor() {
        val sm = sensorManager ?: return
        sm.unregisterListener(levelListener)
        if (settings.activeMonitoring.level) {
            val sensor = sm.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            if (sensor != null) sm.registerListener(levelListener, sensor, SensorManager.SENSOR_DELAY_UI)
        } else {
            frameOverlay.levelDegrees = null
        }
    }

    private fun updateTorchIcon() {
        torchButton.isSelected = torchOn
        torchButton.setImageResource(if (torchOn) R.drawable.ic_flashlight_on else R.drawable.ic_flashlight_off)
    }

    private fun importLutFrom(uri: Uri) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                    Luts.import(this@MainActivity, uri, name)
                }
            }
            result.onSuccess { id ->
                updateSettings(settings.copy(lutId = id), rebind = false)
                Toast.makeText(this@MainActivity, R.string.lut_imported, Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(this@MainActivity, getString(R.string.lut_import_failed, it.message), Toast.LENGTH_LONG).show()
            }
        }
    }

    // endregion

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
        if (isRecording) return
        this.provider = provider
        stopPreviewTracking()
        provider.unbindAll()
        camera = null
        mainCapture = null
        insetCapture = null
        dualKind = DualKind.NONE
        effectAttached = false
        highSpeedBound = false
        imageCapture = null
        analysisUseCase = null
        lockedLens = null
        lenses = runCatching { Lenses.list(this, provider, settings.lensFacing) }.getOrDefault(emptyList())
        mainLensMm = runCatching { Lenses.mainMm(this, provider, settings.lensFacing) }.getOrDefault(0)
        flipButton.isVisible =
            provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) && provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)

        if (settings.dual && !settings.photoMode) {
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

        val lens = settings.activeLensKey?.let { key -> lenses.firstOrNull { it.key == key } }
        val selector = lens?.let { Lenses.selector(it) } ?: defaultSelector(provider)
        slowRates = runCatching { slowMotionRates(provider.getCameraInfo(selector)) }.getOrDefault(emptyList())

        if (settings.photoMode) {
            when {
                bindPhoto(provider, selector) -> {
                    lockedLens = lens
                    onCameraBound()
                }
                lens != null && bindPhoto(provider, defaultSelector(provider)) -> {
                    Toast.makeText(this, R.string.lens_unavailable, Toast.LENGTH_LONG).show()
                    settings = settings.copy(lensKey = null)
                    SettingsStore.save(this, settings)
                    onCameraBound()
                }
                else -> bound = false
            }
            return
        }

        if (settings.activeSlowMotion > 0 && settings.activeSlowMotion !in slowRates) {
            // This camera can't do the chosen speed: take the nearest slower one it can.
            val fallback = slowRates.filter { it < settings.activeSlowMotion }.maxOrNull() ?: 0
            Toast.makeText(this, getString(R.string.slowmo_unsupported, settings.activeSlowMotion), Toast.LENGTH_LONG).show()
            settings = settings.copy(slowMotion = fallback)
            SettingsStore.save(this, settings)
            applySettingsToUi()
        }

        if (settings.isHighSpeed && bindHighSpeed(provider, selector, settings.activeSlowMotion)) {
            lockedLens = lens
            onCameraBound()
            return
        }
        if (settings.isHighSpeed) {
            Toast.makeText(this, getString(R.string.slowmo_unsupported, settings.activeSlowMotion), Toast.LENGTH_LONG).show()
            settings = settings.copy(slowMotion = if (60 in slowRates) 60 else 0)
            SettingsStore.save(this, settings)
            applySettingsToUi()
        }

        if (bindSingle(provider, selector, quiet = lens != null)) {
            lockedLens = lens
            onCameraBound()
        } else if (lens != null && bindSingle(provider, defaultSelector(provider))) {
            // Some phones list a lens but won't stream it on its own.
            Toast.makeText(this, R.string.lens_unavailable, Toast.LENGTH_LONG).show()
            settings = settings.copy(lensKey = null)
            SettingsStore.save(this, settings)
            onCameraBound()
        } else {
            bound = false
        }
    }

    /** Photo mode: preview + full-resolution stills (+ the live look and meters when possible). */
    private fun bindPhoto(provider: ProcessCameraProvider, selector: CameraSelector): Boolean {
        val effect = if (wantsPreviewEffect()) {
            LutPreviewEffect(lutProcessor) {
                runOnUiThread {
                    livePreviewBroken = true
                    Toast.makeText(this, R.string.lut_preview_unavailable, Toast.LENGTH_LONG).show()
                    bindCamera()
                }
            }
        } else {
            null
        }
        for ((withAnalysis, withEffect) in listOf(true to true, false to true, false to false)) {
            try {
                val preview = buildPreview(settings.cameraFrame, null, previewView, readouts = true)
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setAspectRatioStrategy(
                                AspectRatioStrategy(settings.cameraFrame.cameraRatio, AspectRatioStrategy.FALLBACK_RULE_AUTO)
                            )
                            .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                            .build()
                    )
                    .setTargetRotation(recordRotation)
                    .build()
                val group = UseCaseGroup.Builder().addUseCase(preview).addUseCase(capture)
                if (withAnalysis && (settings.isPro || settings.activeAutoReframe)) group.addUseCase(buildAnalysis())
                if (withEffect && effect != null) group.addEffect(effect)
                camera = provider.bindToLifecycle(this, selector, group.build())
                imageCapture = capture
                effectAttached = withEffect && effect != null
                if (settings.isPro && !withAnalysis) monitorRow.isVisible = false
                return true
            } catch (e: Exception) {
                provider.unbindAll()
            }
        }
        return false
    }

    private fun defaultSelector(provider: ProcessCameraProvider): CameraSelector {
        val selector = CameraSelector.Builder().requireLensFacing(settings.lensFacing).build()
        return if (provider.hasCamera(selector)) selector else CameraSelector.DEFAULT_BACK_CAMERA
    }

    /**
     * Slow-motion speeds this camera offers: 60 fps through a normal recording, and 120 / 240
     * through the camera's high-speed mode, when the phone opens it to apps.
     */
    private fun slowMotionRates(info: CameraInfo): List<Int> {
        val rates = mutableListOf<Int>()
        if (info.supportedFrameRateRanges.any { 60 in it.lower..it.upper }) rates += 60
        runCatching {
            val highSpeed = Recorder.getHighSpeedVideoCapabilities(info) ?: return@runCatching
            val qualities = highSpeed.getSupportedQualities(DynamicRange.SDR)
            if (qualities.isEmpty()) return@runCatching
            val capture = VideoCapture.withOutput(
                Recorder.Builder().setQualitySelector(QualitySelector.fromOrderedList(qualities)).build()
            )
            val config = HighSpeedVideoSessionConfig.Builder(capture).setPreview(Preview.Builder().build()).build()
            info.getSupportedFrameRateRanges(config).map { it.upper }.filter { it >= 120 }.forEach { rates += it }
        }
        return rates.distinct().sorted()
    }

    /**
     * 120 / 240 fps: the camera's high-speed mode. It only allows a preview and the recording
     * (no live LUT, analysis or manual controls), and records without sound. The recording is
     * real-time high frame rate; the finished videos are slowed to play back at 30 fps.
     */
    private fun bindHighSpeed(provider: ProcessCameraProvider, selector: CameraSelector, fps: Int): Boolean =
        try {
            val info = provider.getCameraInfo(selector)
            val qualities = Recorder.getHighSpeedVideoCapabilities(info)?.getSupportedQualities(DynamicRange.SDR).orEmpty()
            if (qualities.isEmpty()) {
                false
            } else {
                val capture = VideoCapture.withOutput(
                    Recorder.Builder().setQualitySelector(QualitySelector.fromOrderedList(qualities)).build()
                )
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val config = HighSpeedVideoSessionConfig.Builder(capture)
                    .setPreview(preview)
                    .setFrameRateRange(Range(fps, fps))
                    .setSlowMotionEnabled(false)
                    .build()
                camera = provider.bindToLifecycle(this, selector, config)
                mainCapture = capture
                highSpeedBound = true
                true
            }
        } catch (e: Exception) {
            provider.unbindAll()
            false
        }

    private fun qualitiesFrom(top: CameraResolution): List<Quality> =
        CameraResolution.entries.drop(top.ordinal).map { it.quality }

    private fun buildPreview(
        frame: CameraFrame,
        fps: Int?,
        target: PreviewView,
        readouts: Boolean,
        enhancedStabilization: Boolean = false,
    ): Preview {
        val builder = Preview.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy(frame.cameraRatio, AspectRatioStrategy.FALLBACK_RULE_AUTO))
                    .build()
            )
        if (fps != null) builder.setTargetFrameRate(Range(fps, fps))
        // Preview stabilization steadies preview and recording together: the strongest mode.
        if (enhancedStabilization) builder.setPreviewStabilizationEnabled(true)
        if (readouts) Camera2Interop.Extender(builder).setSessionCaptureCallback(readoutCallback)
        return builder.build().also { it.setSurfaceProvider(target.surfaceProvider) }
    }

    private fun buildCapture(
        qualities: List<Quality>,
        frame: CameraFrame,
        fps: Int?,
        stabilization: Boolean,
        hdr: Boolean = false,
        bitrate: Int? = null,
        codecSource: Boolean = false,
    ): VideoCapture<Recorder> {
        val recorderBuilder = Recorder.Builder()
        // Some phones list 10-bit HLG only in their codecs, not their camcorder profiles.
        if (codecSource) recorderBuilder.setVideoCapabilitiesSource(Recorder.VIDEO_CAPABILITIES_SOURCE_CODEC_CAPABILITIES)
        recorderBuilder
            .setAspectRatio(frame.cameraRatio)
            .setQualitySelector(
                QualitySelector.fromOrderedList(qualities, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))
            )
        if (bitrate != null) recorderBuilder.setTargetVideoEncodingBitRate(bitrate)
        val mirror = if (settings.mirrorFront) MirrorMode.MIRROR_MODE_ON_FRONT_ONLY else MirrorMode.MIRROR_MODE_OFF
        val builder = VideoCapture.Builder(recorderBuilder.build()).setMirrorMode(mirror)
        if (fps != null) builder.setTargetFrameRate(Range(fps, fps))
        if (stabilization) builder.setVideoStabilizationEnabled(true)
        if (hdr) builder.setDynamicRange(DynamicRange.HLG_10_BIT)
        return builder.build()
    }

    /** Small frames for the histogram, exposure meter and shutter / ISO priority. */
    private fun buildAnalysis(): ImageAnalysis =
        ImageAnalysis.Builder()
            .setTargetRotation(recordRotation)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(
                        AspectRatioStrategy(settings.cameraFrame.cameraRatio, AspectRatioStrategy.FALLBACK_RULE_AUTO)
                    )
                    .setResolutionStrategy(
                        ResolutionStrategy(Size(320, 240), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                    )
                    .build()
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { analysis ->
                analysis.setAnalyzer(analysisExecutor) { proxy -> analyzeFrame(proxy) }
                analysisUseCase = analysis
            }

    /** One camera. If the chosen combination isn't possible, back off one feature at a time. */
    private fun bindSingle(provider: ProcessCameraProvider, selector: CameraSelector, quiet: Boolean = false): Boolean {
        val info = try {
            provider.getCameraInfo(selector)
        } catch (e: Exception) {
            return false
        }
        val capabilities = Recorder.getVideoCapabilities(info)
        val codecCapabilities = runCatching {
            Recorder.getVideoCapabilities(info, Recorder.VIDEO_CAPABILITIES_SOURCE_CODEC_CAPABILITIES)
        }.getOrNull()
        val hlgFromProfiles = DynamicRange.HLG_10_BIT in capabilities.supportedDynamicRanges
        val hlgFromCodecs = codecCapabilities?.supportedDynamicRanges?.contains(DynamicRange.HLG_10_BIT) == true
        hlgSupported = hlgFromProfiles || hlgFromCodecs
        hlgViaCodecs = !hlgFromProfiles && hlgFromCodecs
        val wantHdr = settings.activeProfile == RecordProfile.HLG
        if (wantHdr && !hlgSupported) Toast.makeText(this, R.string.hlg_unsupported, Toast.LENGTH_LONG).show()
        val hdr = wantHdr && hlgSupported

        val fps = settings.captureFps
        val fpsOk = info.supportedFrameRateRanges.any { fps in it.lower..it.upper }

        val wantStab = settings.effectiveStabilization
        val enhancedOk = wantStab == Stabilization.ENHANCED && Preview.getPreviewCapabilities(info).isStabilizationSupported
        val standardOk = wantStab != Stabilization.OFF && capabilities.isStabilizationSupported
        if (wantStab != Stabilization.OFF && !enhancedOk && !standardOk) {
            Toast.makeText(this, R.string.stabilization_unsupported, Toast.LENGTH_SHORT).show()
        }
        val bitrate = settings.recordMbps.takeIf { it > 0 }?.let { it * 1_000_000 }
        val analysisWanted = settings.isPro || settings.activeAutoReframe
        // At 4K some phones (Pixel 9a) quietly share one square stream between the preview and
        // the analysis, and the preview comes out squashed. Skip the analysis there: Follow me
        // reads faces from the preview instead, and Pro's histogram pauses.
        val analysisFits = settings.cameraResolution != CameraResolution.UHD
        val analysisTried = analysisWanted && analysisFits

        val effect = if (wantsPreviewEffect() && !hdr) {
            LutPreviewEffect(lutProcessor) {
                // The live LUT preview failed on this phone; carry on without it (export still applies it).
                runOnUiThread {
                    livePreviewBroken = true
                    Toast.makeText(this, R.string.lut_preview_unavailable, Toast.LENGTH_LONG).show()
                    bindCamera()
                }
            }
        } else {
            null
        }

        data class Attempt(
            val fps: Int?,
            val enhanced: Boolean,
            val standard: Boolean,
            val resolution: CameraResolution,
            val hdr: Boolean,
            val effect: Boolean,
            val analysis: Boolean,
        )
        val fpsWanted = if (fpsOk) fps else null
        val attempts = listOf(
            Attempt(fpsWanted, enhancedOk, standardOk && !enhancedOk, settings.cameraResolution, hdr, effect != null, analysisTried),
            Attempt(fpsWanted, false, standardOk, settings.cameraResolution, hdr, effect != null, analysisTried),
            Attempt(fpsWanted, false, standardOk, settings.cameraResolution, hdr, effect != null, false),
            Attempt(null, false, false, settings.cameraResolution, hdr, effect != null, false),
            Attempt(null, false, false, settings.cameraResolution, false, effect != null, false),
            Attempt(null, false, false, CameraResolution.FHD, false, false, false),
        ).distinct()
        fun bind(attempt: Attempt): VideoCapture<Recorder>? = try {
            analysisUseCase = null
            val preview = buildPreview(settings.cameraFrame, attempt.fps, previewView, readouts = true, attempt.enhanced)
            previewUseCase = preview
            val capture = buildCapture(
                qualitiesFrom(attempt.resolution), settings.cameraFrame, attempt.fps, attempt.standard, attempt.hdr,
                bitrate = if (attempt.resolution == settings.cameraResolution) bitrate else null,
                codecSource = attempt.hdr && hlgViaCodecs,
            )
            val group = UseCaseGroup.Builder().addUseCase(preview).addUseCase(capture)
            if (attempt.analysis) group.addUseCase(buildAnalysis())
            if (attempt.effect && effect != null) group.addEffect(effect)
            camera = provider.bindToLifecycle(this, selector, group.build())
            capture
        } catch (e: Exception) {
            provider.unbindAll()
            null
        }

        for (attempt in attempts) {
            var used = attempt
            var capture = bind(attempt) ?: continue
            var pausedForResolution = false
            // Some phones (Pixel 9a) can't stream preview + a big recording + analysis side by side, so
            // CameraX shares one preview-sized stream for preview and video: 4K comes out 1600×1200 or
            // 720×1280. The chosen resolution wins: drop the analysis if that actually records bigger.
            val monitoredArea = recordArea(capture)
            if (attempt.analysis && monitoredArea in 1 until targetArea(attempt.resolution) / 2) {
                provider.unbindAll()
                val plain = attempt.copy(analysis = false)
                val plainCapture = bind(plain)
                if (plainCapture != null && recordArea(plainCapture) > monitoredArea) {
                    used = plain
                    capture = plainCapture
                    pausedForResolution = true
                } else {
                    provider.unbindAll()
                    capture = bind(attempt) ?: continue
                }
            }
            mainCapture = capture
            effectAttached = used.effect && effect != null
            if (used.fps != fps && fps != 30) {
                Toast.makeText(this, getString(R.string.fps_fallback, fps), Toast.LENGTH_LONG).show()
            }
            if (hdr && !used.hdr) Toast.makeText(this, R.string.hlg_unsupported, Toast.LENGTH_LONG).show()
            if (analysisWanted && !used.analysis) {
                monitorRow.isVisible = false
                // Follow me doesn't need the analysis stream: it can find faces in the preview.
                if (settings.activeAutoReframe) startPreviewTracking()
                if (settings.isPro && (pausedForResolution || !analysisFits)) {
                    Toast.makeText(this, R.string.histogram_paused_resolution, Toast.LENGTH_LONG).show()
                }
            }
            return true
        }
        if (!quiet) Toast.makeText(this, getString(R.string.camera_error, "no supported setup"), Toast.LENGTH_LONG).show()
        return false
    }

    /** Pixels in the recording CameraX settled on, 0 if it hasn't said yet. */
    private fun recordArea(capture: VideoCapture<Recorder>): Int =
        capture.resolutionInfo?.resolution?.let { it.width * it.height } ?: 0

    private fun targetArea(resolution: CameraResolution): Int = when (resolution) {
        CameraResolution.UHD -> 3840 * 2160
        CameraResolution.FHD -> 1920 * 1080
        CameraResolution.HD -> 1280 * 720
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
     * Front + Back as two separate recordings. The inset is laid into each video afterwards,
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
        boundAt = SystemClock.elapsedRealtime()
        val c = CameraCaps(cam)
        caps = c
        lastReadout = null

        val frame = if (dualKind == DualKind.NONE) settings.cameraFrame else CameraFrame.FULL_4_3
        frameOverlay.sourceAspect = frame.portraitAspect
        frameOverlay.desqueeze = settings.activeAnamorphic
        if (!settings.activeFollowRotation || dualKind != DualKind.NONE) recordRotation = Surface.ROTATION_0
        applyRecordRotation()
        frameOverlay.movable = dualKind != DualKind.COMPOSITED
        frameOverlay.insetCorner = if (dualKind == DualKind.SEPARATE) settings.insetCorner else null
        insetFrame.isVisible = dualKind == DualKind.SEPARATE
        hintText.setText(if (dualKind == DualKind.COMPOSITED) R.string.hint_dual_fixed else R.string.hint_frames)
        positionInsetPreview()

        observedCamera?.cameraInfo?.zoomState?.removeObservers(this)
        observedCamera = cam
        zoomStopsShown = emptyList()
        val zoomNow = cam.cameraInfo.zoomState.value
        lockedZoom = lockedLens?.let {
            Lenses.zoomRange(it, lenses, mainLensMm, zoomNow?.minZoomRatio ?: 0f, zoomNow?.maxZoomRatio ?: Float.MAX_VALUE)
        }
        lockedZoom?.let { cam.cameraControl.setZoomRatio(it.start) }
        cam.cameraInfo.zoomState.observe(this) { updateZoom(it) }

        // Manual values this camera can't do are dropped rather than silently ignored.
        manual = manual.copy(
            iso = manual.iso?.takeIf { c.manualSensor },
            shutterNs = manual.shutterNs?.takeIf { c.manualSensor },
            focusDiopters = manual.focusDiopters?.takeIf { c.manualFocus },
            whiteBalance = manual.whiteBalance.takeIf { it in c.whiteBalances } ?: WhiteBalance.AUTO,
            kelvin = manual.kelvin?.takeIf { c.kelvinWb.isSupported },
        )
        aeAfLocked = false
        aeLockPill.isVisible = false
        priorityIso = null
        priorityShutterNs = null
        lastStats = null

        torchButton.isVisible = c.hasFlash
        if (!c.hasFlash) torchOn = false
        if (torchOn) cam.cameraControl.enableTorch(true)
        updateTorchIcon()
        nightButton.isVisible = cam.cameraInfo.isLowLightBoostSupported && !highSpeedBound
        if (!highSpeedBound) applyNight()
        if (highSpeedBound) monitorRow.isVisible = false

        applyManual()
        resolutionText = ""
        updateInfoLine()
        refreshSourceAspect()
    }

    /** Low Light Boost only works while exposure is automatic. */
    private fun applyNight() {
        val cam = camera ?: return
        val allowed = cam.cameraInfo.isLowLightBoostSupported && !effectiveManual(caps).manualExposure
        nightButton.isSelected = settings.night && allowed
        if (cam.cameraInfo.isLowLightBoostSupported) {
            runCatching { cam.cameraControl.enableLowLightBoostAsync(settings.night && allowed) }
        }
    }

    // region Exposure assist: histogram, meter, shutter / ISO priority, AE/AF lock

    /** Well-exposed average brightness for the current profile (Log sits brighter by design). */
    private fun exposureTarget() = when (settings.activeProfile) {
        RecordProfile.LOG -> 0.50f
        RecordProfile.HLG -> 0.38f
        RecordProfile.STANDARD -> 0.44f
    }

    private fun onExposureStats(stats: ExposureStats) {
        lastStats = stats
        if (!settings.isPro) return
        histogramView.update(stats)
        val offset = stats.offsetStops(exposureTarget())
        exposureMeter.text = getString(R.string.exposure_meter, Labels.stops(offset))
        exposureMeter.setTextColor(
            ContextCompat.getColor(this, if (kotlin.math.abs(offset) < 0.35f) R.color.accent else R.color.text_primary)
        )
        runPriorityLoop(offset)
    }

    /**
     * Shutter priority (shutter fixed, ISO follows the light) and ISO priority (the reverse).
     * Camera2 has neither, so this nudges the free value a fraction of a stop at a time toward
     * a balanced exposure, with a dead zone so the picture doesn't pump.
     */
    private fun runPriorityLoop(offsetStops: Float) {
        val c = caps ?: return
        val m = effectiveManual(c)
        if (!c.manualSensor || !(m.shutterPriority || m.isoPriority)) return
        if (highSpeedBound) return
        if (kotlin.math.abs(offsetStops) < 0.15f) return
        val step = (-offsetStops * 0.6f).coerceIn(-0.5f, 0.5f)
        val factor = 2.0.pow(step.toDouble())
        if (m.shutterPriority) {
            val range = c.isoRange ?: return
            val current = priorityIso ?: lastReadout?.iso ?: return
            val next = (current * factor).roundToInt().coerceIn(range.lower, range.upper)
            if (next == current) return
            priorityIso = next
        } else {
            val range = c.shutterRange ?: return
            val maxNs = settings.frameDurationNs
            val current = priorityShutterNs ?: lastReadout?.shutterNs ?: return
            val next = (current * factor).toLong().coerceIn(range.lower, minOf(range.upper, maxNs))
            if (next == current) return
            priorityShutterNs = next
        }
        applyManual()
    }

    override fun onLongPress(x: Float, y: Float) {
        val cam = camera ?: return
        val point = previewView.meteringPointFactory.createPoint(previewX(x), y)
        cam.cameraControl.startFocusAndMetering(
            FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                .disableAutoCancel()
                .build()
        ).addListener({
            // Lock once focus and exposure have settled on the point.
            aeAfLocked = true
            Glass.show(aeLockPill, true)
            applyManual()
        }, ContextCompat.getMainExecutor(this))
        previewView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
    }

    private fun unlockAeAf() {
        if (!aeAfLocked) return
        aeAfLocked = false
        Glass.show(aeLockPill, false)
        camera?.cameraControl?.cancelFocusAndMetering()
        applyManual()
    }

    /** Which microphone Android will record from: a plugged-in USB or wired mic wins over the built-in one. */
    private fun micName(): String {
        val inputs = getSystemService(AudioManager::class.java)?.getDevices(AudioManager.GET_DEVICES_INPUTS).orEmpty()
        return when {
            inputs.any { it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET } -> "USB mic"
            inputs.any { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET } -> "Wired mic"
            else -> "Phone mic"
        }
    }

    /** Pro status: recording size, fps, profile, time left on the phone, battery. */
    private fun updateInfoLine() {
        val parts = mutableListOf<String>()
        if (resolutionText.isNotEmpty()) parts += resolutionText
        if (dualKind != DualKind.NONE) parts += getString(R.string.front_and_back)
        if (settings.activeSlowMotion > 0) {
            parts += getString(R.string.slowmo_info, settings.activeSlowMotion / 30, settings.activeSlowMotion)
        } else if (settings.timelapse > 1) {
            parts += if (settings.isHyperlapse) "Hyperlapse ${settings.timelapse}×" else "Time-lapse ${settings.timelapse}×"
        }
        if (settings.isPro) {
            parts += "${settings.fpsLabel} fps"
            if (settings.recordMbps > 0 && !settings.photoMode) parts += RecordBitrate.label(settings.recordMbps)
            if (!settings.photoMode) parts += micName()
            lockedLens?.let { parts += it.label(mainLensMm) }
            parts += settings.activeProfile.label
            val bits = (settings.recordMbps.takeIf { it > 0 }?.times(1_000_000)
                ?: RecordBitrate.defaultBitsPerSecond(settings.cameraResolution)) + 256_000
            val free = runCatching { StatFs(filesDir.path).availableBytes }.getOrDefault(0L)
            val minutes = free * 8 / bits / 60
            parts += if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m left" else "${minutes}m left"
            val battery = getSystemService(BatteryManager::class.java)?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (battery != null && battery in 0..100) parts += "$battery%"
        }
        infoText.text = parts.joinToString("  ·  ")
    }

    // endregion

    /**
     * What is actually sent to the camera. Film rates (23.976…) need the app to time frames
     * itself, so with exposure on Auto the shutter is held at 180° and ISO follows the light.
     */
    private fun effectiveManual(c: CameraCaps?): ManualState {
        val m = activeManual
        if (settings.activeNtsc && c?.manualSensor == true && !m.manualExposure) {
            return m.copy(shutterNs = settings.frameDurationNs / 2)
        }
        return m
    }

    private fun checkForStall() {
        val cam = camera ?: return
        if (!bound || isRecording || highSpeedBound || countdownRunning) return
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        val now = SystemClock.elapsedRealtime()
        if (now - boundAt < 3000) return
        // Two rescues per camera start at most, so a phone that's simply slow isn't toggled forever.
        if (stallRecoveries >= 2) return

        val previewStalled = effectAttached && now - lutProcessor.lastDrawAt > 2500
        val cameraStalled = now - lastReadoutAt > 2500
        if (!previewStalled && !cameraStalled) return
        stallRecoveries++

        when {
            effectAttached -> {
                // The GPU pass goes first: if it hangs it can also hold up the camera.
                livePreviewBroken = true
                Toast.makeText(this, R.string.lut_preview_unavailable, Toast.LENGTH_LONG).show()
                bindCamera()
            }
            settings.activeProfile == RecordProfile.LOG && caps?.logSupported == true && !logCurveBroken -> {
                logCurveBroken = true
                Toast.makeText(this, R.string.log_stalled, Toast.LENGTH_LONG).show()
                val lut = if (settings.lutId == Luts.REC709) Luts.NONE else settings.lutId
                updateSettings(settings.copy(profile = RecordProfile.STANDARD, lutId = lut), rebind = true)
            }
            else -> {
                // Unknown hiccup: reopening the camera usually clears it.
                cam.cameraControl.cancelFocusAndMetering()
                bindCamera()
            }
        }
    }

    private fun applyManual() {
        val cam = camera ?: return
        val c = caps ?: return
        if (!manual.shutterPriority) priorityIso = null
        if (!manual.isoPriority) priorityShutterNs = null
        // The high-speed mode only takes the camera's own automatic settings.
        if (highSpeedBound) {
            updateReadouts()
            refreshControlPanel()
            return
        }
        val fps = settings.captureFps
        if (logCurveBroken && settings.activeProfile == RecordProfile.LOG) return
        ProControls.apply(
            cam, c, effectiveManual(c), settings.activeProfile, fps, lastReadout, priorityIso, priorityShutterNs,
            frameDurationNs = settings.frameDurationNs,
        )
        updateReadouts()
        refreshControlPanel()
    }

    /** True while a finger is on a panel slider: the panel mustn't be rebuilt under it. */
    private var sliderDragging = false
    private var panelStale = false

    /**
     * Rebuilds the open panel, or waits for the slider being dragged to be let go. Removing a
     * Slider mid-drag, with its value label up, leaves a null in the overlay and the next draw
     * crashes (NullPointerException in ViewGroup.hasChildWithZ).
     */
    private fun refreshControlPanel() {
        val param = openParam ?: return
        if (sliderDragging) panelStale = true else buildControlPanel(param)
    }

    /** Live ISO / shutter / focus from the camera, a few times a second. */
    private val readoutCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            checkLogApplied(result)
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
    /** The recorded picture's real shape, once the camera has settled on a size and rotation. */
    private fun refreshSourceAspect() {
        mainCapture?.let { capture -> pollResolution(capture, { capture.resolutionInfo }, attemptsLeft = 20) }
        imageCapture?.let { capture -> pollResolution(capture, { capture.resolutionInfo }, attemptsLeft = 20) }
    }

    /** A screen x position in the preview's own (un-narrowed) coordinates, for focus and metering. */
    private fun previewX(x: Float): Float {
        val squeeze = settings.activeAnamorphic
        if (squeeze == 1f) return x
        val cx = previewView.width / 2f
        return cx + (x - cx) * squeeze
    }

    /** Every analysis frame: exposure stats (Pro) and, with Follow me on, face tracking. */
    private fun analyzeFrame(proxy: ImageProxy) {
        if (settings.isPro) exposureAnalyzer.measure(proxy)
        if (!settings.activeAutoReframe) {
            proxy.close()
            return
        }
        val rotated = proxy.imageInfo.rotationDegrees % 180 != 0
        val analysisAspect = if (rotated) proxy.height.toFloat() / proxy.width else proxy.width.toFloat() / proxy.height
        val tracker = subjectTracker ?: SubjectTracker().also { subjectTracker = it }
        tracker.track(proxy) { x, y, found ->
            proxy.close()
            if (found) onSubject(x, y, analysisAspect)
        }
    }

    /**
     * A face at ([x], [y]) in the analysis frame. The analysis and the recording may be cut
     * differently from the sensor (4:3 vs 16:9), so map it into the recorded picture first.
     */
    private fun onSubject(x: Float, y: Float, analysisAspect: Float) {
        val recordAspect = frameOverlay.sourceAspect
        val (rx, ry) = if (recordAspect > analysisAspect) {
            val share = analysisAspect / recordAspect
            x to (y - (1f - share) / 2f) / share
        } else {
            val share = recordAspect / analysisAspect
            (x - (1f - share) / 2f) / share to y
        }
        val px = rx.coerceIn(0f, 1f)
        val py = ry.coerceIn(0f, 1f)
        val start = recordStartNanos
        if (isRecording && start > 0L) {
            val timeUs = (SystemClock.elapsedRealtimeNanos() - start) / 1000
            synchronized(trackSamples) { trackSamples += SubjectSample(timeUs, px, py) }
        }
        runOnUiThread { frameOverlay.follow(px, py) }
    }

    private fun startPreviewTracking() {
        previewTracking = true
        statusHandler.removeCallbacks(previewTrackTick)
        statusHandler.postDelayed(previewTrackTick, PREVIEW_TRACK_MS)
    }

    private fun stopPreviewTracking() {
        previewTracking = false
        statusHandler.removeCallbacks(previewTrackTick)
    }

    /** Grabs the preview, turns it the way the recording is, and looks for a face in it. */
    private fun trackFromPreview() {
        if (previewTrackBusy || !settings.activeAutoReframe) return
        val shot = previewView.bitmap ?: return
        val frame = runCatching { uprightPreviewFrame(shot) }.getOrNull()
        shot.recycle()
        if (frame == null) return
        previewTrackBusy = true
        val tracker = subjectTracker ?: SubjectTracker().also { subjectTracker = it }
        tracker.track(frame) { x, y, found ->
            previewTrackBusy = false
            val aspect = frame.width.toFloat() / frame.height
            frame.recycle()
            if (found && previewTracking) onSubject(x, y, aspect)
        }
    }

    /**
     * The picture part of a preview grab (the preview is fitted, so it may have bars), small,
     * turned upright for a sideways recording and un-mirrored like the recording.
     */
    private fun uprightPreviewFrame(shot: Bitmap): Bitmap {
        val info = previewUseCase?.resolutionInfo
        val shown = info?.let {
            val r = it.resolution
            if (it.rotationDegrees % 180 == 0) r.width.toFloat() / r.height else r.height.toFloat() / r.width
        } ?: (shot.width.toFloat() / shot.height)
        val vw = shot.width.toFloat()
        val vh = shot.height.toFloat()
        val (cw, ch) = if (vw / vh > shown) vh * shown to vh else vw to vw / shown
        val left = ((vw - cw) / 2f).toInt()
        val top = ((vh - ch) / 2f).toInt()
        val width = cw.toInt().coerceIn(1, shot.width - left)
        val height = ch.toInt().coerceIn(1, shot.height - top)

        // The screen stays upright; a sideways recording is the preview turned a quarter.
        var degrees = when (recordRotation) {
            Surface.ROTATION_90 -> -90f
            Surface.ROTATION_270 -> 90f
            Surface.ROTATION_180 -> 180f
            else -> 0f
        }
        // The front preview is a mirror; the recording only is when "mirror front" is on.
        val unmirror = settings.lensFacing == CameraSelector.LENS_FACING_FRONT && !settings.mirrorFront
        if (unmirror) degrees = -degrees
        val scale = PREVIEW_TRACK_SIZE / maxOf(width, height)
        val matrix = Matrix().apply {
            postScale(if (unmirror) -scale else scale, scale)
            postRotate(degrees)
        }
        return Bitmap.createBitmap(shot, left, top, width, height, matrix, true)
    }

    /**
     * Some phones accept the DF-Log tone curve but quietly keep their own processing. The camera
     * reports which tone mode it really used; if it isn't the curve for ~2 s, say so plainly.
     */
    private fun checkLogApplied(result: TotalCaptureResult) {
        if (settings.activeProfile != RecordProfile.LOG || logCurveBroken || highSpeedBound) {
            tonemapIgnored = 0
            return
        }
        val mode = result.get(CaptureResult.TONEMAP_MODE) ?: return
        if (mode == CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE) {
            tonemapIgnored = 0
            return
        }
        if (++tonemapIgnored == 60) {
            runOnUiThread {
                if (logCurveBroken || settings.activeProfile != RecordProfile.LOG) return@runOnUiThread
                logCurveBroken = true
                Toast.makeText(this, R.string.log_not_applied, Toast.LENGTH_LONG).show()
                val lut = if (settings.lutId == Luts.REC709) Luts.NONE else settings.lutId
                updateSettings(settings.copy(profile = RecordProfile.STANDARD, lutId = lut), rebind = true)
            }
        }
    }

    private fun pollResolution(owner: Any, info: () -> androidx.camera.core.ResolutionInfo?, attemptsLeft: Int) {
        if ((owner !== mainCapture && owner !== imageCapture) || attemptsLeft <= 0) return
        val res = info()
        if (res == null) {
            previewView.postDelayed({ pollResolution(owner, info, attemptsLeft - 1) }, 250)
            return
        }
        val crop = res.cropRect
        var w = if (crop.isEmpty) res.resolution.width else crop.width()
        var h = if (crop.isEmpty) res.resolution.height else crop.height()
        if (res.rotationDegrees % 180 != 0) w = h.also { h = w }
        frameOverlay.sourceAspect = w.toFloat() / h
        resolutionText = "$w × $h"
        updateInfoLine()
    }

    /**
     * Turning the phone sideways makes the next recording (or photo) landscape. The screen stays
     * upright like the phone's own camera app; only the frames overlay turns with the phone,
     * so its shapes line up with the world.
     */
    private fun onPhoneTurned(degrees: Int) {
        if (degrees == OrientationEventListener.ORIENTATION_UNKNOWN) return
        val rotation = when (degrees) {
            in 60..120 -> Surface.ROTATION_270
            in 240..300 -> Surface.ROTATION_90
            in 0..30, in 330..359 -> Surface.ROTATION_0
            else -> return // in between, or upside down: keep what we have
        }
        val target = if (settings.activeFollowRotation && dualKind == DualKind.NONE) rotation else Surface.ROTATION_0
        if (target == recordRotation || isRecording || countdownRunning || !bound) return
        recordRotation = target
        applyRecordRotation()
        refreshSourceAspect()
    }

    private fun applyRecordRotation() {
        mainCapture?.targetRotation = recordRotation
        imageCapture?.targetRotation = recordRotation
        analysisUseCase?.targetRotation = recordRotation
        val turn = when (recordRotation) {
            Surface.ROTATION_90 -> 90f
            Surface.ROTATION_270 -> -90f
            else -> 0f
        }
        if (frameOverlay.rotation == turn) return
        val parent = frameOverlay.parent as View
        frameOverlay.layoutParams = if (turn == 0f) {
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        } else {
            FrameLayout.LayoutParams(parent.height, parent.width, Gravity.CENTER)
        }
        frameOverlay.rotation = turn
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
        // High-speed recordings have no sound (the slowed videos drop it anyway).
        if (isMain && !highSpeedBound && has(Manifest.permission.RECORD_AUDIO)) pending = pending.withAudioEnabled()
        return pending.start(ContextCompat.getMainExecutor(this)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    if (isMain) {
                        recordStartNanos = SystemClock.elapsedRealtimeNanos()
                        setRecordingUi(true)
                    }
                }
                is VideoRecordEvent.Status -> {
                    if (isMain) {
                        timerText.text = formatDuration(event.recordingStats.recordedDurationNanos)
                        val level = event.recordingStats.audioStats.audioAmplitude
                        audioMeter.update(level)
                        teleprompter.onVoiceLevel(level)
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
        if ((mainCapture == null && imageCapture == null) || countdownRunning) return
        if (DualExporter.state.value !is ExportState.Idle) return
        closeControlPanel()
        if (settings.timerSeconds > 0) countdown(settings.timerSeconds) else beginRecording()
    }

    /** Self-timer: big numbers, a tick each second, then record. Tap record again to cancel. */
    private fun countdown(seconds: Int) {
        countdownRunning = true
        recordButton.setImageResource(R.drawable.ic_stop)
        var left = seconds
        val tick = object : Runnable {
            override fun run() {
                if (!countdownRunning) return
                if (left == 0) {
                    countdownRunning = false
                    countdownText.isVisible = false
                    beginRecording()
                    return
                }
                countdownText.text = "$left"
                countdownText.isVisible = true
                countdownText.scaleX = 1.3f
                countdownText.scaleY = 1.3f
                countdownText.alpha = 0f
                countdownText.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(300).start()
                countdownText.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                left--
                statusHandler.postDelayed(this, 1000)
            }
        }
        statusHandler.post(tick)
    }

    private fun cancelCountdown() {
        countdownRunning = false
        countdownText.isVisible = false
        recordButton.setImageResource(shutterIcon())
    }

    private fun shutterIcon() = if (settings.photoMode) R.drawable.ic_shutter else R.drawable.ic_record

    /** Slate-style names: Interview_T007_20261030_141502, or Kitchen_S03_T02_MS_… for a shot list. */
    private fun slateName(): String {
        val project = shotProject
        recordingShotTake = null
        if (project != null && !settings.photoMode) {
            val shot = project.currentShot
            val begun = ShotLists.beginTake(this, project)
            if (shot != null && begun != null) {
                recordingShotTake = shot.id to begun.second
                return begun.first
            }
        }
        val safeName = settings.clipName.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().replace(' ', '_').ifEmpty { "DualFrame" }
        val take = SettingsStore.nextTake(this)
        return String.format(Locale.US, "%s_T%03d_", safeName, take) +
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    }

    /** One still, then cut into every chosen shape (full sensor resolution, no video encoder limits). */
    private fun takePhoto() {
        val capture = imageCapture ?: return
        recordButton.setImageResource(R.drawable.ic_shutter)
        recordButton.isEnabled = false
        val name = slateName()
        val mirror = settings.mirrorFront && settings.lensFacing == CameraSelector.LENS_FACING_FRONT
        val job = { bitmap: android.graphics.Bitmap, rotation: Int ->
            PhotoJob(
                image = bitmap,
                rotationDegrees = rotation,
                mirror = mirror,
                outputs = settings.orderedFormats,
                placement = frameOverlay.placement,
                lut = if (settings.bakeLut) activeLut else null,
                anamorphic = settings.activeAnamorphic,
                cinematic = settings.cinematic,
                keepOriginal = settings.keepOriginal,
                baseName = name,
            )
        }
        // A quick dip of the preview, like a shutter.
        previewView.animate().alpha(0.15f).setDuration(70).withEndAction {
            previewView.animate().alpha(1f).setDuration(180).start()
        }.start()
        capture.takePicture(photoExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val rotation = image.imageInfo.rotationDegrees
                val bitmap = runCatching { image.toBitmap() }.getOrNull()
                image.close()
                runOnUiThread {
                    recordButton.isEnabled = true
                    if (bitmap == null) {
                        Toast.makeText(this@MainActivity, getString(R.string.photo_failed, "no image"), Toast.LENGTH_LONG).show()
                    } else {
                        DualExporter.startPhoto(this@MainActivity, job(bitmap, rotation))
                    }
                }
            }

            override fun onError(exception: ImageCaptureException) {
                runOnUiThread {
                    recordButton.isEnabled = true
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.photo_failed, exception.message ?: "camera error"),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        })
    }

    private fun beginRecording() {
        if (settings.photoMode) {
            takePhoto()
            return
        }
        val main = mainCapture ?: return
        baseName = slateName()
        recordingStartMs = System.currentTimeMillis()
        recordStartNanos = 0L
        synchronized(trackSamples) { trackSamples.clear() }
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
        lastShotTake = null
        refreshShots()
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
        lastShotTake = if (mainTake != null) recordingShotTake else null
        refreshShots()

        val main = mainTake
        if (main == null) {
            insetTake?.file?.delete()
            Toast.makeText(this, getString(R.string.record_failed, lastError), Toast.LENGTH_LONG).show()
            return
        }
        val hdr = settings.activeProfile == RecordProfile.HLG && dualKind == DualKind.NONE && hlgSupported && !highSpeedBound
        DualExporter.start(
            this,
            ExportJob(
                main = main,
                inset = insetTake,
                insetCorner = settings.insetCorner,
                outputs = settings.orderedFormats.map { it to settings.outputs.getValue(it) },
                placement = frameOverlay.placement,
                lut = if (settings.bakeLut) activeLut else null,
                speed = settings.playbackSpeed,
                hdrSource = hdr,
                keepHdr = settings.hdrCuts,
                codec = settings.codec,
                quality = settings.exportQuality,
                // Only what you asked for: one format in, one video out (plus the original if kept).
                keepOriginal = settings.keepOriginal,
                baseName = baseName,
                audioGainDb = settings.audioGainDb,
                cinematic = settings.cinematic,
                anamorphic = settings.activeAnamorphic,
                grade = if (settings.bakeLut) settings.activeGrade else ColorGrade(),
                timecodeStartMs = recordingStartMs,
                timecodeRate = settings.fpsLabel,
                captions = settings.captions,
                captionStyle = CaptionStyle.load(this),
                captionReview = settings.captionReview,
                track = if (settings.activeAutoReframe && dualKind == DualKind.NONE) {
                    synchronized(trackSamples) { trackSamples.toList() }
                } else {
                    null
                },
            ),
        )
    }

    private fun setRecordingUi(recording: Boolean) {
        recordButton.setImageResource(if (recording) R.drawable.ic_stop else shutterIcon())
        recordButton.contentDescription = getString(if (recording) R.string.stop_recording else R.string.start_recording)
        Glass.show(timerRow, recording)
        Glass.show(meterRow, recording && settings.isPro && !highSpeedBound)
        formatScroller.isVisible = !recording
        if (recording) timerText.text = formatDuration(0)
        listOf<View>(flipButton, dualButton, settingsButton, modeAuto, modePro).forEach {
            it.isEnabled = !recording
            it.animate().alpha(if (recording) 0.35f else 1f).setDuration(160).start()
        }
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
        if (aeAfLocked) {
            unlockAeAf()
            return
        }
        if (activeManual.focusDiopters != null) return // manual focus wins
        val point = previewView.meteringPointFactory.createPoint(previewX(x), y)
        cam.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
    }

    override fun onPinch(scaleFactor: Float) {
        val cam = camera ?: return
        val zoom = cam.cameraInfo.zoomState.value ?: return
        cam.cameraControl.setZoomRatio(clampZoom(zoom.zoomRatio * scaleFactor, zoom))
    }

    override fun onFramesChanged() {
        positionInsetPreview()
        for (i in 0 until formatChips.childCount) {
            val pill = formatChips.getChildAt(i)
            if (pill.tag is OutputFormat) pill.isSelected = pill.tag == frameOverlay.selected
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

    // region Pro strip (readouts) and control panel

    private fun dp(value: Float) = Glass.dp(this, value)

    private fun buildReadouts() {
        // First cell: one tap back to everything automatic.
        autoCell = Glass.pill(this, "A") {
            manual = ManualState()
            closeControlPanel()
            applyManual()
            Toast.makeText(this, R.string.all_auto, Toast.LENGTH_SHORT).show()
        }.apply {
            contentDescription = getString(R.string.all_auto)
            minHeight = dp(52f).toInt()
            minWidth = dp(52f).toInt()
            minimumHeight = dp(52f).toInt()
        }
        readoutRow.addView(
            autoCell,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { marginEnd = dp(6f).toInt() },
        )

        Param.entries.forEach { param ->
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                minimumWidth = dp(64f).toInt()
                minimumHeight = dp(52f).toInt()
                setPadding(dp(10f).toInt(), dp(4f).toInt(), dp(10f).toInt(), dp(4f).toInt())
                isClickable = true
                isFocusable = true
                background = ContextCompat.getDrawable(context, R.drawable.bg_glass_tile)
                contentDescription = param.title
                setOnClickListener { toggleControlPanel(param) }
            }
            cell.addView(Glass.label(this, param.caption))
            val value = TextView(this).apply {
                setTextAppearance(R.style.DF_Text_Value)
                maxLines = 1
            }
            cell.addView(value)
            readoutValues[param] = value
            readoutRow.addView(
                cell,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { marginEnd = dp(6f).toInt() },
            )
        }
    }

    private fun updateReadouts() {
        if (readoutValues.isEmpty()) return
        val r = lastReadout
        val m = activeManual
        val accent = ContextCompat.getColor(this, R.color.accent)
        val normal = ContextCompat.getColor(this, R.color.text_primary)
        fun set(param: Param, text: String, isManual: Boolean) {
            readoutValues[param]?.apply {
                this.text = text
                setTextColor(if (isManual) accent else normal)
            }
        }
        set(
            Param.PROFILE,
            when (settings.profile) {
                RecordProfile.STANDARD -> "STD"
                RecordProfile.LOG -> "LOG"
                RecordProfile.HLG -> "HLG"
            },
            settings.profile != RecordProfile.STANDARD,
        )
        val lutName = Luts.displayName(this, settings.lutId, settings.profile)
        set(Param.LUT, if (lutName.length > 10) lutName.take(9) + "…" else lutName, settings.lutId != Luts.NONE)
        set(Param.FPS, settings.fpsLabel, settings.captureFps != 30 || settings.activeNtsc)
        set(Param.LENS, lockedLens?.let { if (it.equivalentMm > 0) "${it.equivalentMm}mm" else "Lock" } ?: "Auto", lockedLens != null)
        val shutterNs = m.shutterNs ?: priorityShutterNs ?: r?.shutterNs
        set(
            Param.SHUTTER,
            if (settings.shutterAngle) Labels.angle(shutterNs, settings.captureFps) else Labels.shutter(shutterNs),
            m.shutterNs != null,
        )
        set(Param.ISO, Labels.iso(m.iso ?: priorityIso ?: r?.iso), m.iso != null)
        val wb = when {
            m.kelvin != null -> Labels.kelvin(m.kelvin) + if (m.tint != 0) " ${if (m.tint > 0) "M" else "G"}${kotlin.math.abs(m.tint)}" else ""
            m.whiteBalance == WhiteBalance.AUTO -> "AWB"
            else -> m.whiteBalance.label
        }
        set(
            Param.WB,
            if (m.whiteBalanceLocked) "$wb ·L" else wb,
            m.kelvin != null || m.whiteBalance != WhiteBalance.AUTO || m.whiteBalanceLocked,
        )
        set(Param.FOCUS, if (m.focusDiopters != null) Labels.focus(m.focusDiopters) else "AF", m.focusDiopters != null)
        val step = camera?.cameraInfo?.exposureState?.exposureCompensationStep?.toFloat() ?: 0f
        set(Param.EV, if (m.manualExposure) "M" else Labels.ev(m.evIndex, step), m.evIndex != 0)
        val mon = settings.monitoring
        val active = listOfNotNull(
            "Z${mon.zebra}".takeIf { mon.zebra > 0 },
            "Peak".takeIf { mon.peaking > 0 },
            "FC".takeIf { mon.falseColor },
        )
        set(Param.MONITOR, active.joinToString(" ").ifEmpty { "Off" }, active.isNotEmpty())

        autoCell?.isSelected = manual.isAllAuto
    }

    private fun toggleControlPanel(param: Param) {
        if (openParam == param) closeControlPanel() else buildControlPanel(param)
    }

    private fun closeControlPanel() {
        openParam = null
        sliderDragging = false
        panelStale = false
        Glass.show(controlPanel, false)
    }

    private fun panelPills(): ChipGroup = ChipGroup(this).apply {
        chipSpacingHorizontal = dp(8f).toInt()
        chipSpacingVertical = dp(8f).toInt()
        setPadding(0, dp(10f).toInt(), 0, 0)
    }

    private fun buildControlPanel(param: Param) {
        val c = caps ?: return
        val firstOpen = openParam == null
        openParam = param
        controlPanel.removeAllViews()
        if (firstOpen) Glass.show(controlPanel, true) else controlPanel.isVisible = true

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            Glass.section(this, param.title).apply { setPadding(0, 0, 0, 0) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val autoable = param in setOf(Param.SHUTTER, Param.ISO, Param.WB, Param.FOCUS, Param.EV)
        if (autoable) {
            val isAuto = when (param) {
                Param.SHUTTER -> manual.shutterNs == null
                Param.ISO -> manual.iso == null
                Param.WB -> manual.whiteBalance == WhiteBalance.AUTO && manual.kelvin == null && !manual.whiteBalanceLocked
                Param.FOCUS -> manual.focusDiopters == null
                else -> manual.evIndex == 0
            }
            header.addView(Glass.pill(this, "Auto", isAuto) {
                manual = when (param) {
                    Param.SHUTTER -> manual.copy(shutterNs = null)
                    Param.ISO -> manual.copy(iso = null)
                    Param.WB -> manual.copy(whiteBalance = WhiteBalance.AUTO, kelvin = null, tint = 0, whiteBalanceLocked = false)
                    Param.FOCUS -> manual.copy(focusDiopters = null)
                    else -> manual.copy(evIndex = 0)
                }
                applyManual()
            })
        }
        header.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_close)
            imageTintList = ContextCompat.getColorStateList(context, R.color.text_secondary)
            background = null
            contentDescription = getString(R.string.close)
            minimumWidth = dp(44f).toInt()
            minimumHeight = dp(44f).toInt()
            setOnClickListener { closeControlPanel() }
        })
        controlPanel.addView(header)

        if (highSpeedBound && autoable) {
            note(getString(R.string.highspeed_auto_only))
            return
        }

        when (param) {
            Param.PROFILE -> {
                val pills = panelPills()
                RecordProfile.entries.forEach { profile ->
                    val enabled = when (profile) {
                        RecordProfile.STANDARD -> true
                        RecordProfile.LOG -> c.logSupported && !logCurveBroken
                        RecordProfile.HLG -> hlgSupported && !settings.dual && !settings.photoMode
                    }
                    pills.addView(Glass.pill(this, profile.label, profile == settings.profile) {
                        if (profile != settings.profile) {
                            // DF-Log starts flat (that's the point). A LUT chosen with Log is for
                            // viewing only unless you turn baking on, so the files stay flat.
                            val keptLut = if (profile != RecordProfile.LOG && settings.lutId == Luts.REC709) Luts.NONE else settings.lutId
                            updateSettings(
                                settings.copy(
                                    profile = profile,
                                    lutId = keptLut,
                                    bakeLut = if (profile == RecordProfile.LOG) false else settings.bakeLut,
                                ),
                                rebind = profile == RecordProfile.HLG || settings.profile == RecordProfile.HLG,
                            )
                        }
                    }.apply { isEnabled = enabled })
                }
                controlPanel.addView(pills)
                note(settings.profile.description)
                if (settings.profile == RecordProfile.LOG) note(getString(R.string.log_flat_note))
                if (settings.profile == RecordProfile.HLG) {
                    panelSwitch(getString(R.string.hdr_cuts), settings.hdrCuts) {
                        updateSettings(settings.copy(hdrCuts = it), rebind = false)
                    }
                    note(getString(if (settings.hdrCuts) R.string.hdr_cuts_on else R.string.hdr_cuts_off))
                }
                if (settings.profile == RecordProfile.LOG) {
                    controlPanel.addView(Glass.pill(this, getString(R.string.save_log_lut)) { saveLogLut() }.apply {
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply { topMargin = dp(10f).toInt() }
                    })
                }
                if (!c.logSupported || logCurveBroken) note(getString(R.string.log_unsupported))
                if (!hlgSupported) note(getString(R.string.hlg_unsupported))
            }
            Param.LUT -> {
                val pills = panelPills()
                Luts.available(this, settings.profile).forEach { lut ->
                    pills.addView(Glass.pill(this, lut.name, lut.id == settings.lutId) {
                        if (lut.id != settings.lutId) updateSettings(settings.copy(lutId = lut.id), rebind = false)
                    }.apply {
                        if (lut.id.startsWith("custom:")) {
                            setOnLongClickListener {
                                confirmDeleteLut(lut)
                                true
                            }
                        }
                    })
                }
                pills.addView(Glass.pill(this, getString(R.string.import_lut)) {
                    importLut.launch(arrayOf("*/*"))
                })
                controlPanel.addView(pills)
                val info = Luts.available(this, settings.profile).firstOrNull { it.id == settings.lutId }
                note(info?.description ?: "")
                panelSwitch(getString(R.string.lut_bake), settings.bakeLut) {
                    updateSettings(settings.copy(bakeLut = it), rebind = false)
                }
                if (settings.profile == RecordProfile.LOG) note(getString(R.string.lut_view_only_note))
                if (settings.profile == RecordProfile.HLG) note(getString(R.string.lut_hlg_note))
                else if (!effectAttached && activeLut != null) note(getString(R.string.lut_export_only))
            }
            Param.MONITOR -> {
                val mon = settings.monitoring
                fun setMon(new: Monitoring) = updateSettings(settings.copy(monitoring = new), rebind = false)
                controlPanel.addView(Glass.label(this, "Zebras").apply { setPadding(0, dp(10f).toInt(), 0, 0) })
                val zebras = panelPills()
                listOf(0, 70, 80, 90, 95, 100).forEach { z ->
                    zebras.addView(Glass.pill(this, if (z == 0) "Off" else "$z%", z == mon.zebra) { setMon(mon.copy(zebra = z)) })
                }
                controlPanel.addView(zebras)
                controlPanel.addView(Glass.label(this, "Focus peaking").apply { setPadding(0, dp(12f).toInt(), 0, 0) })
                val peaking = panelPills()
                listOf("Off", "Low", "High").forEachIndexed { i, label ->
                    peaking.addView(Glass.pill(this, label, i == mon.peaking) { setMon(mon.copy(peaking = i)) })
                }
                PeakColor.entries.forEach { color ->
                    peaking.addView(Glass.pill(this, "●", color == mon.peakColor) { setMon(mon.copy(peakColor = color)) }.apply {
                        setTextColor(android.graphics.Color.rgb((color.r * 255).toInt(), (color.g * 255).toInt(), (color.b * 255).toInt()))
                        contentDescription = color.label
                    })
                }
                controlPanel.addView(peaking)
                panelSwitch(getString(R.string.false_color), mon.falseColor) { setMon(mon.copy(falseColor = it)) }
                panelSwitch(getString(R.string.histogram), mon.histogram) { setMon(mon.copy(histogram = it)) }
                val scopes = panelPills()
                listOf("Histogram", "Waveform", "Vectorscope").forEachIndexed { i, label ->
                    scopes.addView(Glass.pill(this, label, i == mon.scope) { setMon(mon.copy(scope = i, histogram = true)) })
                }
                controlPanel.addView(scopes)
                panelSwitch(getString(R.string.level), mon.level) { setMon(mon.copy(level = it)) }
                panelSwitch(getString(R.string.center_mark), mon.centerMark) { setMon(mon.copy(centerMark = it)) }
                panelSwitch(getString(R.string.safe_area), mon.safeArea) { setMon(mon.copy(safeArea = it)) }
                panelSwitch(getString(R.string.grid), settings.grid) { updateSettings(settings.copy(grid = it), rebind = false) }
                note(getString(R.string.monitor_note))
                if (dualKind != DualKind.NONE || settings.activeProfile == RecordProfile.HLG) {
                    note(getString(R.string.monitor_unavailable))
                }
            }
            Param.FPS -> {
                val supported = camera?.cameraInfo?.supportedFrameRateRanges.orEmpty()
                val pills = panelPills()
                val rates = listOf(24, 25, 30, 48, 50, 60)
                    .filter { fps -> supported.isEmpty() || supported.any { fps in it.lower..it.upper } }
                rates.forEach { fps ->
                    pills.addView(Glass.pill(this, "$fps", fps == settings.fps && !settings.ntsc) {
                        updateSettings(settings.copy(fps = fps, ntsc = false), rebind = fps != settings.fps)
                    })
                }
                // 23.976 / 29.97 / 59.94: the app times frames itself, which needs manual sensor control.
                if (c.manualSensor) {
                    rates.filter { it in NTSC_BASES }.forEach { fps ->
                        pills.addView(Glass.pill(this, ntscLabel(fps), fps == settings.fps && settings.ntsc) {
                            updateSettings(settings.copy(fps = fps, ntsc = true), rebind = fps != settings.fps)
                        })
                    }
                }
                controlPanel.addView(pills)
                if (c.manualSensor) note(getString(R.string.ntsc_note)) else note(getString(R.string.ntsc_unsupported))
                if (settings.activeSlowMotion > 0) note(getString(R.string.fps_slowmo_note, settings.activeSlowMotion))
                note("24 for a film feel, 30 for everyday, 60 for smooth motion. For slow motion, use Settings → Speed.")
                if (dualKind != DualKind.NONE) note("Front + Back records at the camera's standard rate.")
            }
            Param.SHUTTER -> {
                val fps = settings.captureFps
                val current = manual.shutterNs ?: lastReadout?.shutterNs ?: (1_000_000_000L / (fps * 2))
                if (!c.manualSensor) {
                    note("This camera doesn't allow manual shutter speed.")
                } else if (settings.shutterAngle) {
                    val angles = c.shutterAngles(fps)
                    if (angles.size >= 2) {
                        val index = angles.indices.minByOrNull { abs(angles[it].second - current) } ?: 0
                        slider(angles.size, index, { Labels.angle(angles[it].second, fps) }) { i ->
                            manual = manual.copy(shutterNs = angles[i].second)
                            applyManual()
                        }
                    }
                    note("180° is natural motion blur; smaller angles look crisper and more staccato.")
                } else {
                    val stops = c.shutterStops(fps)
                    if (stops.size >= 2) {
                        val index = stops.indices.minByOrNull { abs(1_000_000_000L / stops[it] - current) } ?: 0
                        slider(stops.size, index, { "1/${stops[it]}" }) { i ->
                            manual = manual.copy(shutterNs = 1_000_000_000L / stops[i])
                            applyManual()
                        }
                    }
                    note("1/${fps * 2} gives the most natural motion at $fps fps.")
                }
                if (c.manualSensor) {
                    note("Shutter priority: with ISO on Auto, ISO keeps following the light.")
                    panelSwitch(getString(R.string.shutter_angle), settings.shutterAngle) {
                        updateSettings(settings.copy(shutterAngle = it), rebind = false)
                    }
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
                    note("Lower is cleaner, higher is brighter but grainier. With shutter on Auto, shutter follows the light (ISO priority).")
                }
            }
            Param.WB -> {
                val pills = panelPills()
                c.whiteBalances.forEach { wb ->
                    val label = if (wb == WhiteBalance.AUTO) "Auto" else "${wb.label} · ${wbName(wb)}"
                    pills.addView(Glass.pill(this, label, wb == manual.whiteBalance && manual.kelvin == null) {
                        manual = manual.copy(whiteBalance = wb, kelvin = null, tint = 0)
                        applyManual()
                    })
                }
                controlPanel.addView(pills)
                if (c.kelvinWb.isSupported) {
                    val kelvins = (20..100).map { it * 100 }
                    val currentK = manual.kelvin ?: 5600
                    controlPanel.addView(Glass.label(this, "Temperature").apply { setPadding(0, dp(12f).toInt(), 0, 0) })
                    slider(kelvins.size, kelvins.indexOf(currentK.coerceIn(2000, 10000) / 100 * 100), { "${kelvins[it]}K" }) { i ->
                        manual = manual.copy(kelvin = kelvins[i])
                        applyManual()
                    }
                    controlPanel.addView(Glass.label(this, "Tint").apply { setPadding(0, dp(8f).toInt(), 0, 0) })
                    slider(101, manual.tint + 50, { v -> (v - 50).let { if (it == 0) "0" else if (it > 0) "M$it" else "G${-it}" } }) { i ->
                        manual = manual.copy(tint = i - 50, kelvin = manual.kelvin ?: 5600)
                        applyManual()
                    }
                }
                controlPanel.addView(MaterialSwitch(this).apply {
                    text = getString(R.string.wb_lock)
                    setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                    isChecked = manual.whiteBalanceLocked
                    minHeight = dp(48f).toInt()
                    setOnCheckedChangeListener { _, checked ->
                        manual = manual.copy(whiteBalanceLocked = checked)
                        applyManual()
                    }
                })
            }
            Param.LENS -> {
                val pills = panelPills()
                pills.addView(Glass.pill(this, getString(R.string.lens_auto), settings.lensKey == null) {
                    if (settings.lensKey != null) updateSettings(settings.copy(lensKey = null), rebind = true)
                })
                lenses.forEach { lens ->
                    pills.addView(Glass.pill(this, lens.label(mainLensMm), lens.key == settings.lensKey) {
                        if (lens.key != settings.lensKey) updateSettings(settings.copy(lensKey = lens.key), rebind = true)
                    })
                }
                controlPanel.addView(pills)
                note(getString(if (lenses.size > 1) R.string.lens_note else R.string.lens_single))
                if (dualKind != DualKind.NONE) note(getString(R.string.lens_dual))
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
                    note("Left is far (∞), right is close.")
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

    /** Writes the DF-Log → Rec.709 LUT to Downloads for Resolve, Premiere, Final Cut, LumaFusion… */
    private fun saveLogLut() {
        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, "DF-Log_to_Rec709.cube")
                        put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }
                    val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: error("Downloads unavailable")
                    contentResolver.openOutputStream(uri)!!.use { it.write(Luts.logToRec709CubeText().toByteArray()) }
                }.isSuccess
            }
            Toast.makeText(
                this@MainActivity,
                if (saved) R.string.log_lut_saved else R.string.log_lut_failed,
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    /** What this phone's cameras can do, shown in Settings so it's clear what's available and why. */
    private fun phoneSummary(): List<Pair<String, String>> {
        val p = provider
        val c = caps
        val yes = getString(R.string.yes)
        val no = getString(R.string.no)
        val out = mutableListOf<Pair<String, String>>()
        val maker = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        out += getString(R.string.phone_model) to "$maker ${Build.MODEL} · Android ${Build.VERSION.RELEASE}"
        if (p != null) {
            listOf(
                CameraSelector.LENS_FACING_BACK to R.string.phone_back_lenses,
                CameraSelector.LENS_FACING_FRONT to R.string.phone_front_lenses,
            ).forEach { (facing, label) ->
                val list = runCatching { Lenses.list(this, p, facing) }.getOrDefault(emptyList())
                val mm = runCatching { Lenses.mainMm(this, p, facing) }.getOrDefault(0)
                if (list.isNotEmpty()) out += getString(label) to list.joinToString(",  ") { it.label(mm) }
            }
        }
        if (c != null) {
            out += getString(R.string.phone_manual) to if (c.manualSensor) yes else no
            out += getString(R.string.phone_kelvin) to if (c.kelvinWb.isSupported) yes else no
            out += getString(R.string.phone_log) to if (c.logSupported) yes else no
        }
        out += getString(R.string.phone_hlg) to if (hlgSupported) yes else no
        val fpsList = camera?.cameraInfo?.supportedFrameRateRanges.orEmpty().let { ranges ->
            listOf(24, 25, 30, 48, 50, 60).filter { f -> ranges.any { f in it.lower..it.upper } }
        }
        if (fpsList.isNotEmpty()) out += getString(R.string.phone_fps) to fpsList.joinToString(" · ")
        out += getString(R.string.phone_slowmo) to slowRates.joinToString(" · ") { "$it fps" }.ifEmpty { no }
        out += getString(R.string.phone_dual) to if (supportsFrontAndBack()) yes else no
        return out
    }

    private fun confirmDeleteLut(lut: LutInfo) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.delete_lut_title, lut.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                Luts.delete(this, lut.id)
                val next = if (settings.lutId == lut.id) Luts.NONE else settings.lutId
                updateSettings(settings.copy(lutId = next), rebind = false)
                openParam?.let { buildControlPanel(it) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun panelSwitch(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
        controlPanel.addView(MaterialSwitch(this).apply {
            text = label
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            isChecked = value
            minHeight = dp(48f).toInt()
            setOnCheckedChangeListener { _, checked -> onChange(checked) }
        })
    }

    private fun wbName(wb: WhiteBalance) = when (wb) {
        WhiteBalance.AUTO -> ""
        WhiteBalance.TUNGSTEN -> "Indoor"
        WhiteBalance.FLUORESCENT -> "Tube light"
        WhiteBalance.DAYLIGHT -> "Sun"
        WhiteBalance.CLOUDY -> "Cloudy"
        WhiteBalance.SHADE -> "Shade"
    }

    private fun note(text: String) {
        if (text.isEmpty()) return
        controlPanel.addView(Glass.caption(this, text).apply {
            textSize = 12f
            setPadding(0, dp(8f).toInt(), 0, 0)
        })
    }

    /** A stepped slider over [count] positions with a large live value. */
    private fun slider(count: Int, index: Int, label: (Int) -> String, onChange: (Int) -> Unit) {
        val start = index.coerceIn(0, count - 1)
        val valueText = TextView(this).apply {
            setTextAppearance(R.style.DF_Text_Title)
            gravity = Gravity.CENTER
            text = label(start)
            setPadding(0, dp(4f).toInt(), 0, 0)
        }
        val slider = Slider(this).apply {
            valueFrom = 0f
            valueTo = (count - 1).toFloat()
            stepSize = 1f
            value = start.toFloat()
            // The value is shown above the slider. The floating label lives in the window overlay and
            // crashes if the panel is rebuilt while it's fading out (BaseSlider onAnimationEnd NPE).
            labelBehavior = LabelFormatter.LABEL_GONE
            trackActiveTintList = ContextCompat.getColorStateList(context, R.color.accent)!!
            trackInactiveTintList = android.content.res.ColorStateList.valueOf(0x26FFFFFF)
            thumbTintList = ContextCompat.getColorStateList(context, R.color.text_primary)!!
            setTickVisible(false)
            addOnChangeListener { _, v, fromUser ->
                val i = v.roundToInt()
                valueText.text = label(i)
                if (fromUser) onChange(i)
            }
            addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) {
                    sliderDragging = true
                }

                override fun onStopTrackingTouch(slider: Slider) {
                    sliderDragging = false
                    // After the slider has finished with the touch and hidden its label.
                    if (panelStale) slider.post {
                        panelStale = false
                        refreshControlPanel()
                    }
                }
            })
        }
        controlPanel.addView(valueText)
        controlPanel.addView(slider)
    }

    // endregion

    // region Format pills, corner pills, zoom capsule

    private fun buildFormatChips() {
        formatChips.removeAllViews()
        settings.orderedFormats.forEach { format ->
            val pill = Glass.pill(this, "●  ${format.label}  ${settings.outputs[format]?.label}") {
                frameOverlay.selected = format
            }
            pill.tag = format
            pill.isSelected = format == frameOverlay.selected
            // The dot carries the frame's colour so pills and frames match at a glance.
            pill.text = android.text.SpannableString(pill.text).apply {
                setSpan(android.text.style.ForegroundColorSpan(format.color), 0, 1, 0)
            }
            formatChips.addView(pill, spacedParams())
        }
        if (!settings.dual) {
            formatChips.addView(Glass.pill(this, "◎  Follow me", settings.autoReframe) {
                if (!isRecording) {
                    val on = !settings.autoReframe
                    updateSettings(settings.copy(autoReframe = on), rebind = true)
                    Toast.makeText(this, if (on) R.string.follow_on else R.string.follow_off, Toast.LENGTH_SHORT).show()
                }
            }.apply { contentDescription = getString(R.string.follow_me) }, spacedParams())
        }
        formatChips.addView(Glass.pill(this, "+") { showSettings() }.apply {
            contentDescription = getString(R.string.edit_formats)
        }, spacedParams())
    }

    private fun spacedParams() =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { marginEnd = dp(8f).toInt() }

    private fun buildCornerChips() {
        Corner.entries.forEach { corner ->
            cornerChips.addView(Glass.pill(this, corner.label) {
                if (corner != settings.insetCorner && !isRecording) {
                    // The fallback mode bakes the inset in, so it needs the camera reopened.
                    updateSettings(settings.copy(insetCorner = corner), rebind = dualKind == DualKind.COMPOSITED)
                    if (dualKind == DualKind.SEPARATE) frameOverlay.insetCorner = corner
                }
            }.apply {
                textSize = 16f
                background = null
                minWidth = dp(44f).toInt()
                contentDescription = corner.name.lowercase().replace('_', ' ')
                setBackgroundResource(R.drawable.bg_glass_circle)
            }, LinearLayout.LayoutParams(dp(44f).toInt(), dp(44f).toInt()).apply { marginStart = dp(4f).toInt() })
        }
    }

    private var zoomStopsShown: List<Float> = emptyList()

    /** Within the camera's zoom range and, while a lens is locked, within that lens. */
    private fun clampZoom(ratio: Float, state: ZoomState): Float {
        val clamped = ratio.coerceIn(state.minZoomRatio, state.maxZoomRatio)
        return lockedZoom?.let { clamped.coerceIn(it.start, it.endInclusive) } ?: clamped
    }

    /**
     * Just two buttons: the ultra-wide (if there is one) and 1×, which shows the live zoom.
     * With a lens locked, only that lens's button.
     */
    private fun zoomStops(state: ZoomState): List<Float> {
        lockedZoom?.let { return listOf((it.start * 10).roundToInt() / 10f) }
        val stops = mutableListOf<Float>()
        if (state.minZoomRatio < 0.95f) stops += (state.minZoomRatio * 10).roundToInt() / 10f
        stops += 1f
        return stops
    }

    private fun updateZoom(state: ZoomState) {
        // CameraX can reset zoom to 1× as the camera opens, after the lock set it. Pinch and the zoom
        // button stay in range, so out of range means a reset: go back to the lens's own zoom.
        lockedZoom?.let { range ->
            if (state.zoomRatio !in range) camera?.cameraControl?.setZoomRatio(range.start)
        }
        val stops = zoomStops(state)
        if (stops != zoomStopsShown) {
            zoomStopsShown = stops
            zoomCapsule.removeAllViews()
            stops.forEach { ratio ->
                zoomCapsule.addView(Glass.pill(this, zoomText(ratio)) {
                    camera?.cameraControl?.setZoomRatio(clampZoom(ratio, state))
                }.apply {
                    tag = ratio
                    background = ContextCompat.getDrawable(context, R.drawable.bg_glass_circle)
                    textSize = 12f
                }, LinearLayout.LayoutParams(dp(42f).toInt(), dp(42f).toInt()).apply { marginStart = dp(2f).toInt(); marginEnd = dp(2f).toInt() })
            }
        }
        // Below 1× the wide button lights up; from 1× up, the 1× button shows the exact zoom.
        val nearest = when {
            stops.size == 1 -> stops.first()
            state.zoomRatio < 0.95f -> stops.first()
            else -> 1f
        }
        for (i in 0 until zoomCapsule.childCount) {
            val pill = zoomCapsule.getChildAt(i) as TextView
            val ratio = pill.tag as Float
            val active = ratio == nearest
            pill.isSelected = active
            pill.text = if (active) zoomText(state.zoomRatio) else zoomText(ratio)
        }
        // A lens locked by zoom shares the main lens's scale; a stand-alone one has its own.
        val ownFocal = lockedLens?.takeIf { lockedZoom == null }?.equivalentMm?.takeIf { it > 0 }?.toFloat()
        val focal = ownFocal ?: caps?.equivalentFocalMm
        zoomLabel.text = focal?.let { "${(it * state.zoomRatio).roundToInt()} mm" } ?: ""
    }

    private fun zoomText(ratio: Float): String {
        val rounded = (ratio * 10).roundToInt() / 10f
        return if (rounded % 1f == 0f) "${rounded.toInt()}×" else "$rounded×"
    }

    // endregion

    // region Settings, library

    private fun showSettings() {
        if (isRecording) return
        SettingsSheet(
            this, settings, inFrontAndBack = settings.dual, slowRates, phoneSummary(), encoderMaxMbps, micName(),
            onCaptionStyle = { openCaptionStyle() },
        ) { draft ->
            val old = settings
            if (draft == old) return@SettingsSheet
            val cameraChanged = draft.cameraFrame != old.cameraFrame ||
                draft.cameraResolution != old.cameraResolution ||
                draft.effectiveStabilization != old.effectiveStabilization ||
                draft.recordMbps != old.recordMbps ||
                draft.activeFollowRotation != old.activeFollowRotation ||
                draft.mirrorFront != old.mirrorFront ||
                draft.activeSlowMotion != old.activeSlowMotion ||
                draft.activeAutoReframe != old.activeAutoReframe ||
                (draft.dual && draft.orderedFormats != old.orderedFormats)
            updateSettings(draft, rebind = cameraChanged)
            if (draft.captions != old.captions) prepareCaptions(draft.captions)
        }.show()
    }

    /** Captions use Android's free on-device speech pack: make sure it's downloaded. */
    private fun prepareCaptions(language: CaptionLanguage) {
        if (language == CaptionLanguage.OFF) return
        if (!Transcriber.isAvailable(this)) {
            Toast.makeText(this, R.string.captions_unavailable, Toast.LENGTH_LONG).show()
            return
        }
        Transcriber.prepare(this, language) { status ->
            val message = when (status) {
                Transcriber.PackStatus.READY -> getString(R.string.captions_ready, language.label)
                Transcriber.PackStatus.DOWNLOADING -> getString(R.string.captions_downloading, language.label)
                Transcriber.PackStatus.UNSUPPORTED -> getString(R.string.captions_unsupported, language.label)
                Transcriber.PackStatus.UNKNOWN -> null
            }
            message?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
        }
    }

    private fun openLibrary() {
        startActivity(Intent(this, GalleryActivity::class.java))
    }

    private fun refreshGalleryThumb() {
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    VideoLibrary.query(this@MainActivity).firstOrNull()?.let {
                        contentResolver.loadThumbnail(it.uri, Size(160, 160), null)
                    }
                }.getOrNull()
            }
            galleryThumb.setImageBitmap(bitmap)
            galleryThumb.isVisible = bitmap != null
            galleryIcon.isVisible = bitmap == null
        }
    }

    // endregion

    // region Shot list

    private fun setupShots() {
        shotBanner = ShotBanner(this).apply { visibility = View.GONE }
        findViewById<LinearLayout>(R.id.top_bar).addView(
            shotBanner,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(12f).toInt(), dp(8f).toInt(), dp(12f).toInt(), 0)
            },
        )
        findViewById<View>(R.id.capture_shots).setOnClickListener {
            if (isRecording || countdownRunning) return@setOnClickListener
            ShotListActivity.open(this, ShotLists.activeId(this))
        }
        shotBanner.onPrev = { moveShot(-1) }
        shotBanner.onNext = { moveShot(1) }
        shotBanner.onList = { shotProject?.let { ShotListActivity.open(this, it.id) } }
        shotBanner.onExit = {
            ShotLists.setActive(this, null)
            lastShotTake = null
            refreshShots()
        }
        shotBanner.onGood = { rateLastTake(good = true, next = true) }
        shotBanner.onGoodAgain = { rateLastTake(good = true, next = false) }
        shotBanner.onRetake = { rateLastTake(good = false, next = false) }
    }

    private fun moveShot(by: Int) {
        val p = shotProject ?: return
        if (isRecording) return
        val target = p.current + by
        if (target !in p.shots.indices) {
            if (by > 0) Toast.makeText(this, R.string.shots_last_done, Toast.LENGTH_LONG).show()
            return
        }
        p.current = target
        ShotLists.save(this, p)
        lastShotTake = null
        refreshShots()
    }

    private fun rateLastTake(good: Boolean, next: Boolean) {
        val p = shotProject ?: return
        val (shotId, take) = lastShotTake ?: return
        ShotLists.rate(this, p.id, shotId, take, good)
        lastShotTake = null
        val message = when {
            !good -> getString(R.string.shots_rated_retake, take)
            next -> getString(R.string.shots_rated_good, take)
            else -> getString(R.string.shots_rated_good_again, take)
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        shotProject = ShotLists.active(this)
        if (next) moveShot(1) else refreshShots()
    }

    /** Shows or hides the shot card and puts the current shot's lines on the teleprompter. */
    private fun refreshShots() {
        if (!::shotBanner.isInitialized) return
        val p = ShotLists.active(this)
        shotProject = p
        if (p != null && settings.photoMode && !isRecording) setPhotoMode(false)
        findViewById<TextView>(R.id.capture_shots).setTextColor(
            ContextCompat.getColor(this, if (p != null) R.color.accent else R.color.text_secondary)
        )
        // Starting on a shot list: shoot in its shapes.
        if (p != null && p.id != shownProjectId && !isRecording) {
            shownProjectId = p.id
            if (p.outputs.isNotEmpty() && p.outputs != settings.outputs) {
                updateSettings(settings.copy(outputs = p.outputs.toMap()), rebind = true)
                Toast.makeText(
                    this,
                    getString(R.string.shots_shapes_applied, p.outputs.keys.joinToString(" + ") { it.label }),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
        if (p == null) {
            shownProjectId = null
            Glass.show(shotBanner, false)
            if (shownShotId != null) {
                shownShotId = null
                teleprompter.useShotScript(null, null)
            }
            return
        }
        shotBanner.bind(p, isRecording, lastShotTake?.takeIf { it.first == p.currentShot?.id }?.second)
        Glass.show(shotBanner, true)
        val shot = p.currentShot
        if (shot?.id != shownShotId && !isRecording) {
            shownShotId = shot?.id
            teleprompter.useShotScript(shot?.script) { edited ->
                ShotLists.load(this, p.id)?.let { fresh ->
                    fresh.shots.firstOrNull { it.id == shot?.id }?.script = edited
                    ShotLists.save(this, fresh)
                }
            }
            if (shot?.script?.isNotBlank() == true && !teleprompter.isVisible) {
                Glass.show(teleprompter, true)
                scriptButton.isSelected = true
            }
        }
    }

    // endregion

    // region Export results

    private fun render(state: ExportState) {
        Glass.show(exportPanel, state is ExportState.Running)
        Glass.show(resultPanel, state is ExportState.Done)
        recordButton.isEnabled = state is ExportState.Idle

        when (state) {
            is ExportState.Idle -> shownResult = null
            is ExportState.ReviewCaptions -> if (shownResult != state) {
                shownResult = state
                CaptionEditorActivity.review(this)
            }
            is ExportState.Running -> {
                exportStep.text = getString(R.string.exporting_step, state.step, state.total)
                exportTitle.text = state.label ?: getString(R.string.exporting, state.format.label)
                if (state.label != null) exportStep.text = getString(R.string.captions_step)
                exportProgress.setProgressCompat(state.progress, true)
                exportPercent.text = getString(R.string.percent, state.progress)
            }
            is ExportState.Done -> if (shownResult != state) {
                shownResult = state
                bindResults(state)
                refreshShots()
                state.note?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
                refreshGalleryThumb()
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
                refreshGalleryThumb()
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
            val (w, h) = if (video.photo) video.width to video.height else video.format.sizePx(video.resolution)
            row.findViewById<TextView>(R.id.title).text = if (video.photo) {
                "${video.format.label} · ${getString(R.string.photo)}"
            } else {
                getString(R.string.result_title_row, video.format.label, video.resolution.label)
            }
            row.findViewById<TextView>(R.id.subtitle).text = getString(R.string.result_subtitle_row, video.format.use, w, h)
            row.findViewById<View>(R.id.play).setOnClickListener { play(video) }
            thumb.setOnClickListener { play(video) }
            row.findViewById<View>(R.id.share).setOnClickListener { share(video.uri, video.format.label, video.photo) }
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

    private fun play(video: SavedVideo) {
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .setData(video.uri)
                .putExtra(
                    PlayerActivity.EXTRA_TITLE,
                    "${video.format.label} · ${if (video.photo) getString(R.string.photo) else video.resolution.label}",
                )
        )
    }

    private fun share(uri: Uri, label: String, photo: Boolean = false) {
        val send = Intent(Intent.ACTION_SEND)
            .setType(if (photo) "image/jpeg" else "video/mp4")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, getString(R.string.share_format, label)))
    }

    // endregion

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            findViewById<View>(R.id.top_bar).updatePadding(top = bars.top)
            findViewById<View>(R.id.bottom_bar).updatePadding(bottom = bars.bottom + dp(8f).toInt())
            teleprompter.topInset = bars.top
            listOf(exportPanel, resultPanel, permissionPanel).forEach {
                it.updatePadding(top = bars.top, bottom = bars.bottom)
            }
            insets
        }
    }

    private companion object {
        const val KEY_ASKED = "asked"

        /** How often Follow me looks for a face in the preview when it has no analysis stream. */
        const val PREVIEW_TRACK_MS = 200L

        /** Long side of the preview copy the face detector gets. */
        const val PREVIEW_TRACK_SIZE = 480f
    }
}
