package com.dualframe.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
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
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
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
            frameOverlay.levelDegrees = levelRoll
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

    /** Slow-motion frame rates this camera can record (60 normally, 120 / 240 in high-speed mode). */
    private var slowRates: List<Int> = emptyList()
    private var highSpeedBound = false

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

    private val importLut = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importLutFrom(uri)
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
    }

    override fun onPause() {
        super.onPause()
        sensorManager?.unregisterListener(levelListener)
        statusHandler.removeCallbacks(statusTick)
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
    }

    private fun updateSettings(new: CaptureSettings, rebind: Boolean) {
        val lutChanged = new.activeLutId != settings.activeLutId || new.activeProfile != settings.activeProfile
        settings = new
        SettingsStore.save(this, new)
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
        lifecycleScope.launch {
            val cube = withContext(Dispatchers.Default) { runCatching { Luts.cube(this@MainActivity, id, profile) }.getOrNull() }
            if (id != settings.activeLutId || profile != settings.activeProfile) return@launch
            activeLut = cube
            // Monitoring without a LUT still runs through the shader, with a do-nothing LUT.
            lutProcessor.setLut(cube ?: Luts.identity)
            updateReadouts()
            if (forceRebind || (bound && wantsPreviewEffect() != effectAttached)) bindCamera()
        }
    }

    private fun wantsPreviewEffect() =
        (activeLut != null || settings.activeMonitoring.needsShader) && !livePreviewBroken &&
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
        provider.unbindAll()
        camera = null
        mainCapture = null
        insetCapture = null
        dualKind = DualKind.NONE
        effectAttached = false
        highSpeedBound = false
        lockedLens = null
        lenses = runCatching { Lenses.list(this, provider, settings.lensFacing) }.getOrDefault(emptyList())
        mainLensMm = runCatching { Lenses.mainMm(this, provider, settings.lensFacing) }.getOrDefault(0)
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

        val lens = settings.activeLensKey?.let { key -> lenses.firstOrNull { it.key == key } }
        val selector = lens?.let { Lenses.selector(it) } ?: defaultSelector(provider)
        slowRates = runCatching { slowMotionRates(provider.getCameraInfo(selector)) }.getOrDefault(emptyList())

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
    ): VideoCapture<Recorder> {
        val recorderBuilder = Recorder.Builder()
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
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(Size(320, 240), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                    )
                    .build()
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { analysis ->
                analysis.setAnalyzer(analysisExecutor, ExposureAnalyzer { stats -> runOnUiThread { onExposureStats(stats) } })
            }

    /** One camera. If the chosen combination isn't possible, back off one feature at a time. */
    private fun bindSingle(provider: ProcessCameraProvider, selector: CameraSelector, quiet: Boolean = false): Boolean {
        val info = try {
            provider.getCameraInfo(selector)
        } catch (e: Exception) {
            return false
        }
        val capabilities = Recorder.getVideoCapabilities(info)

        hlgSupported = DynamicRange.HLG_10_BIT in capabilities.supportedDynamicRanges
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
        val bitrate = settings.recordBitrate.bitsPerSecond(settings.cameraResolution)
        val analysisWanted = settings.isPro

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
            Attempt(fpsWanted, enhancedOk, standardOk && !enhancedOk, settings.cameraResolution, hdr, effect != null, analysisWanted),
            Attempt(fpsWanted, false, standardOk, settings.cameraResolution, hdr, effect != null, analysisWanted),
            Attempt(fpsWanted, false, standardOk, settings.cameraResolution, hdr, effect != null, false),
            Attempt(null, false, false, settings.cameraResolution, hdr, effect != null, false),
            Attempt(null, false, false, settings.cameraResolution, false, effect != null, false),
            Attempt(null, false, false, CameraResolution.FHD, false, false, false),
        ).distinct()
        for (attempt in attempts) {
            try {
                val preview = buildPreview(settings.cameraFrame, attempt.fps, previewView, readouts = true, attempt.enhanced)
                val capture = buildCapture(
                    qualitiesFrom(attempt.resolution), settings.cameraFrame, attempt.fps, attempt.standard, attempt.hdr,
                    bitrate = if (attempt.resolution == settings.cameraResolution) bitrate else null,
                )
                val group = UseCaseGroup.Builder().addUseCase(preview).addUseCase(capture)
                if (attempt.analysis) group.addUseCase(buildAnalysis())
                if (attempt.effect && effect != null) group.addEffect(effect)
                camera = provider.bindToLifecycle(this, selector, group.build())
                mainCapture = capture
                effectAttached = attempt.effect && effect != null
                if (attempt.fps != fps && fps != 30) {
                    Toast.makeText(this, getString(R.string.fps_fallback, fps), Toast.LENGTH_LONG).show()
                }
                if (hdr && !attempt.hdr) Toast.makeText(this, R.string.hlg_unsupported, Toast.LENGTH_LONG).show()
                if (analysisWanted && !attempt.analysis) monitorRow.isVisible = false
                return true
            } catch (e: Exception) {
                provider.unbindAll()
            }
        }
        if (!quiet) Toast.makeText(this, getString(R.string.camera_error, "no supported setup"), Toast.LENGTH_LONG).show()
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
        mainCapture?.let { pollResolution(it, attemptsLeft = 20) }
    }

    /** Low Light Boost only works while exposure is automatic. */
    private fun applyNight() {
        val cam = camera ?: return
        val allowed = cam.cameraInfo.isLowLightBoostSupported && !activeManual.manualExposure
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
        val m = manual
        if (!c.manualSensor || !(m.shutterPriority || m.isoPriority)) return
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
            val maxNs = 1_000_000_000L / settings.captureFps
            val current = priorityShutterNs ?: lastReadout?.shutterNs ?: return
            val next = (current * factor).toLong().coerceIn(range.lower, minOf(range.upper, maxNs))
            if (next == current) return
            priorityShutterNs = next
        }
        applyManual()
    }

    override fun onLongPress(x: Float, y: Float) {
        val cam = camera ?: return
        val point = previewView.meteringPointFactory.createPoint(x, y)
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
            parts += "${settings.captureFps} fps"
            lockedLens?.let { parts += it.label(mainLensMm) }
            parts += settings.activeProfile.label
            val bits = settings.recordBitrate.bitsPerSecond(settings.cameraResolution) + 256_000
            val free = runCatching { StatFs(filesDir.path).availableBytes }.getOrDefault(0L)
            val minutes = free * 8 / bits / 60
            parts += if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m left" else "${minutes}m left"
            val battery = getSystemService(BatteryManager::class.java)?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (battery != null && battery in 0..100) parts += "$battery%"
        }
        infoText.text = parts.joinToString("  ·  ")
    }

    // endregion

    private fun applyManual() {
        val cam = camera ?: return
        val c = caps ?: return
        if (!manual.shutterPriority) priorityIso = null
        if (!manual.isoPriority) priorityShutterNs = null
        // The high-speed mode only takes the camera's own automatic settings.
        if (highSpeedBound) {
            updateReadouts()
            openParam?.let { buildControlPanel(it) }
            return
        }
        val fps = settings.captureFps
        ProControls.apply(cam, c, activeManual, settings.activeProfile, fps, lastReadout, priorityIso, priorityShutterNs)
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
        resolutionText = "$w × $h"
        updateInfoLine()
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
                    if (isMain) setRecordingUi(true)
                }
                is VideoRecordEvent.Status -> {
                    if (isMain) {
                        timerText.text = formatDuration(event.recordingStats.recordedDurationNanos)
                        val level = event.recordingStats.audioStats.audioAmplitude
                        audioMeter.update(level)
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
        if (mainCapture == null || countdownRunning) return
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
        recordButton.setImageResource(R.drawable.ic_record)
    }

    private fun beginRecording() {
        val main = mainCapture ?: return
        // Slate-style names: Interview_T007_20261030_1415
        val safeName = settings.clipName.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().replace(' ', '_').ifEmpty { "DualFrame" }
        val take = SettingsStore.nextTake(this)
        baseName = String.format(Locale.US, "%s_T%03d_", safeName, take) +
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
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
                // Log and HDR originals are the "negative": always keep them.
                keepOriginal = settings.keepOriginal || settings.activeProfile != RecordProfile.STANDARD,
                baseName = baseName,
            ),
        )
    }

    private fun setRecordingUi(recording: Boolean) {
        recordButton.setImageResource(if (recording) R.drawable.ic_stop else R.drawable.ic_record)
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
        set(Param.FPS, "${settings.captureFps}", settings.captureFps != 30)
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
                        RecordProfile.LOG -> c.logSupported
                        RecordProfile.HLG -> hlgSupported && !settings.dual
                    }
                    pills.addView(Glass.pill(this, profile.label, profile == settings.profile) {
                        if (profile != settings.profile) {
                            // Log changes what LUTs mean, so start from a sensible look.
                            val lut = if (profile == RecordProfile.LOG && settings.lutId == Luts.NONE) Luts.REC709 else settings.lutId
                            val keptLut = if (profile != RecordProfile.LOG && lut == Luts.REC709) Luts.NONE else lut
                            updateSettings(
                                settings.copy(profile = profile, lutId = keptLut),
                                rebind = profile == RecordProfile.HLG || settings.profile == RecordProfile.HLG,
                            )
                        }
                    }.apply { isEnabled = enabled })
                }
                controlPanel.addView(pills)
                note(settings.profile.description)
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
                if (!c.logSupported) note(getString(R.string.log_unsupported))
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
                listOf(24, 25, 30, 48, 50, 60)
                    .filter { fps -> supported.isEmpty() || supported.any { fps in it.lower..it.upper } }
                    .forEach { fps ->
                        pills.addView(Glass.pill(this, "$fps", fps == settings.fps) {
                            if (fps != settings.fps) updateSettings(settings.copy(fps = fps), rebind = true)
                        })
                    }
                controlPanel.addView(pills)
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
            setLabelFormatter { label(it.roundToInt()) }
            trackActiveTintList = ContextCompat.getColorStateList(context, R.color.accent)!!
            trackInactiveTintList = android.content.res.ColorStateList.valueOf(0x26FFFFFF)
            thumbTintList = ContextCompat.getColorStateList(context, R.color.text_primary)!!
            setTickVisible(false)
            addOnChangeListener { _, v, fromUser ->
                val i = v.roundToInt()
                valueText.text = label(i)
                if (fromUser) onChange(i)
            }
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

    private fun zoomStops(state: ZoomState): List<Float> {
        val stops = mutableListOf<Float>()
        if (state.minZoomRatio < 0.95f) stops += (state.minZoomRatio * 10).roundToInt() / 10f
        listOf(1f, 2f, 3f, 5f, 10f).filterTo(stops) { it <= state.maxZoomRatio + 0.01f }
        return stops
    }

    private fun updateZoom(state: ZoomState) {
        val stops = zoomStops(state)
        if (stops != zoomStopsShown) {
            zoomStopsShown = stops
            zoomCapsule.removeAllViews()
            stops.forEach { ratio ->
                zoomCapsule.addView(Glass.pill(this, zoomText(ratio)) {
                    camera?.cameraControl?.setZoomRatio(ratio.coerceAtLeast(state.minZoomRatio))
                }.apply {
                    tag = ratio
                    background = ContextCompat.getDrawable(context, R.drawable.bg_glass_circle)
                    textSize = 12f
                }, LinearLayout.LayoutParams(dp(42f).toInt(), dp(42f).toInt()).apply { marginStart = dp(2f).toInt(); marginEnd = dp(2f).toInt() })
            }
        }
        // The nearest stop lights up and shows the exact zoom, like a lens ring.
        val nearest = stops.minByOrNull { abs(it - state.zoomRatio) }
        for (i in 0 until zoomCapsule.childCount) {
            val pill = zoomCapsule.getChildAt(i) as TextView
            val ratio = pill.tag as Float
            val active = ratio == nearest
            pill.isSelected = active
            pill.text = if (active) zoomText(state.zoomRatio) else zoomText(ratio)
        }
        val focal = lockedLens?.equivalentMm?.takeIf { it > 0 }?.toFloat() ?: caps?.equivalentFocalMm
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
        SettingsSheet(this, settings, inFrontAndBack = settings.dual, slowRates, phoneSummary()) { draft ->
            val old = settings
            if (draft == old) return@SettingsSheet
            val cameraChanged = draft.cameraFrame != old.cameraFrame ||
                draft.cameraResolution != old.cameraResolution ||
                draft.effectiveStabilization != old.effectiveStabilization ||
                draft.recordBitrate != old.recordBitrate ||
                draft.mirrorFront != old.mirrorFront ||
                draft.activeSlowMotion != old.activeSlowMotion ||
                (draft.dual && draft.orderedFormats != old.orderedFormats)
            updateSettings(draft, rebind = cameraChanged)
        }.show()
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

    // region Export results

    private fun render(state: ExportState) {
        Glass.show(exportPanel, state is ExportState.Running)
        Glass.show(resultPanel, state is ExportState.Done)
        recordButton.isEnabled = state is ExportState.Idle

        when (state) {
            is ExportState.Idle -> shownResult = null
            is ExportState.Running -> {
                exportStep.text = getString(R.string.exporting_step, state.step, state.total)
                exportTitle.text = getString(R.string.exporting, state.format.label)
                exportProgress.setProgressCompat(state.progress, true)
                exportPercent.text = getString(R.string.percent, state.progress)
            }
            is ExportState.Done -> if (shownResult != state) {
                shownResult = state
                bindResults(state)
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
            val (w, h) = video.format.sizePx(video.resolution)
            row.findViewById<TextView>(R.id.title).text = getString(R.string.result_title_row, video.format.label, video.resolution.label)
            row.findViewById<TextView>(R.id.subtitle).text = getString(R.string.result_subtitle_row, video.format.use, w, h)
            row.findViewById<View>(R.id.play).setOnClickListener { play(video) }
            thumb.setOnClickListener { play(video) }
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

    private fun play(video: SavedVideo) {
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .setData(video.uri)
                .putExtra(PlayerActivity.EXTRA_TITLE, "${video.format.label} · ${video.resolution.label}")
        )
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
    }
}
