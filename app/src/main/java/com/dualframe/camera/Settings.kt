package com.dualframe.camera

import android.content.Context
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.video.Quality

/** A finished video shape. Every format the user ticks is cut from the same recording. */
enum class OutputFormat(val label: String, val ratioW: Int, val ratioH: Int, val use: String, val color: Int) {
    PORTRAIT_9_16("9:16", 9, 16, "Reels · Shorts · Stories", 0xFFD8B98A.toInt()),
    LANDSCAPE_16_9("16:9", 16, 9, "YouTube · TV", 0xFFF4F1EA.toInt()),
    PORTRAIT_4_5("4:5", 4, 5, "Instagram feed", 0xFF8EC5E8.toInt()),
    SQUARE("1:1", 1, 1, "Square posts", 0xFFA8C9A0.toInt()),
    PORTRAIT_3_4("3:4", 3, 4, "Tall photo-style", 0xFFE3A6B4.toInt()),
    LANDSCAPE_4_3("4:3", 4, 3, "Classic TV", 0xFFB9A7E0.toInt()),
    CINEMA_21_9("21:9", 21, 9, "Cinematic widescreen", 0xFFE8A87C.toInt());

    /** width / height */
    val aspect: Float get() = ratioW.toFloat() / ratioH

    /**
     * Output size in pixels for [resolution]: the short side matches the chosen resolution,
     * capped so the long side stays within what phone encoders handle (3840). Even numbers only.
     */
    fun sizePx(resolution: OutputResolution): Pair<Int, Int> {
        val shortRatio = minOf(ratioW, ratioH).toFloat()
        val longRatio = maxOf(ratioW, ratioH).toFloat()
        var short = resolution.shortSide.toFloat()
        var long = short * longRatio / shortRatio
        if (long > MAX_LONG_SIDE) {
            long = MAX_LONG_SIDE.toFloat()
            short = long * shortRatio / longRatio
        }
        val s = short.toInt() and 1.inv()
        val l = long.toInt() and 1.inv()
        return if (ratioW >= ratioH) l to s else s to l
    }

    private companion object {
        const val MAX_LONG_SIDE = 3840
    }
}

enum class OutputResolution(val label: String, val shortSide: Int) {
    HD("720p", 720),
    FHD("1080p", 1080),
    QHD("1440p", 1440),
    UHD("4K", 2160),
}

/** How much data the finished videos get. Scaled by resolution, see [bitrateFor]. */
enum class ExportQuality(val label: String, val mbpsAt1080p: Int) {
    STANDARD("Standard", 12),
    HIGH("High", 20),
    MAX("Max", 35),
}

fun bitrateFor(quality: ExportQuality, width: Int, height: Int): Int {
    val pixels = width.toLong() * height
    return (quality.mbpsAt1080p * 1_000_000L * pixels / (1920L * 1080L)).toInt().coerceIn(2_000_000, 120_000_000)
}

/** The shape the camera itself records before anything is cut out of it. */
enum class CameraFrame(
    val label: String,
    val description: String,
    @AspectRatio.Ratio val cameraRatio: Int,
    /** width / height while holding the phone upright. */
    val portraitAspect: Float,
) {
    FULL_4_3(
        "Full sensor 4:3",
        "Uses the whole camera sensor, so tall and wide videos both have room. Best when making several formats.",
        AspectRatio.RATIO_4_3,
        3f / 4f,
    ),
    WIDE_16_9(
        "Widescreen 16:9",
        "Records a 16:9 picture. Sharpest for a single 9:16 or 16:9 video, but other shapes are cut tighter.",
        AspectRatio.RATIO_16_9,
        9f / 16f,
    ),
}

enum class CameraResolution(val label: String, val quality: Quality) {
    UHD("4K", Quality.UHD),
    FHD("1080p", Quality.FHD),
    HD("720p", Quality.HD),
}

/** Which corner the second camera sits in. The same corner is used in every video. */
enum class Corner(val label: String, val isLeft: Boolean, val isTop: Boolean) {
    TOP_LEFT("↖", isLeft = true, isTop = true),
    TOP_RIGHT("↗", isLeft = false, isTop = true),
    BOTTOM_LEFT("↙", isLeft = true, isTop = false),
    BOTTOM_RIGHT("↘", isLeft = false, isTop = false),
}

/** Auto: the phone decides everything, like its own camera app. Pro: you do. */
enum class CaptureMode { AUTO, PRO }

/** How the camera itself encodes the picture (Pro only). */
enum class RecordProfile(val label: String, val description: String) {
    STANDARD("Standard", "Your phone's own colour processing. Ready to post."),
    LOG("DF-Log", "Flat log curve that keeps shadow and highlight detail for grading. Pair it with a LUT."),
    HLG("HLG HDR", "10-bit HDR (HEVC). The original is kept as HDR; your cut videos are converted to standard range."),
}

enum class Stabilization(val label: String, val description: String) {
    OFF("Off", "Widest picture, no digital cropping"),
    STANDARD("Standard", "Smooths handheld shake, slightly tighter picture"),
    ENHANCED("Enhanced", "Strongest smoothing for walking shots (like Action mode), tighter picture"),
}

/** Bitrate of the camera recording itself (the master the videos are cut from). */
enum class RecordBitrate(val label: String, val factor: Float) {
    STANDARD("Standard", 1.0f),
    HIGH("High", 1.6f),
    MAX("Max", 2.4f);

    fun bitsPerSecond(resolution: CameraResolution): Int {
        val base = when (resolution) {
            CameraResolution.UHD -> 45_000_000
            CameraResolution.FHD -> 17_000_000
            CameraResolution.HD -> 10_000_000
        }
        return (base * factor).toInt()
    }
}

enum class PeakColor(val label: String, val r: Float, val g: Float, val b: Float) {
    RED("Red", 1f, 0.2f, 0.2f),
    GREEN("Green", 0.25f, 1f, 0.35f),
    BLUE("Blue", 0.3f, 0.6f, 1f),
    YELLOW("Yellow", 1f, 0.9f, 0.2f),
    WHITE("White", 1f, 1f, 1f),
}

/** Exposure and focus aids drawn over the preview only (never recorded). */
data class Monitoring(
    /** Zebra stripes above this brightness in percent; 0 = off. */
    val zebra: Int = 0,
    /** Focus peaking: 0 off, 1 low, 2 high sensitivity. */
    val peaking: Int = 0,
    val peakColor: PeakColor = PeakColor.RED,
    val falseColor: Boolean = false,
    val histogram: Boolean = true,
    val level: Boolean = false,
    val centerMark: Boolean = false,
    val safeArea: Boolean = false,
) {
    /** Anything that needs the GPU preview pass. */
    val needsShader get() = zebra > 0 || peaking > 0 || falseColor
}

enum class VideoCodec(val label: String, val mimeType: String) {
    H264("H.264", "video/avc"),
    HEVC("HEVC", "video/hevc"),
}

data class CaptureSettings(
    /** The videos to make, each with its own resolution. */
    val outputs: Map<OutputFormat, OutputResolution> = mapOf(
        OutputFormat.PORTRAIT_9_16 to OutputResolution.FHD,
        OutputFormat.LANDSCAPE_16_9 to OutputResolution.FHD,
    ),
    val exportQuality: ExportQuality = ExportQuality.HIGH,
    val cameraFrame: CameraFrame = CameraFrame.FULL_4_3,
    val cameraResolution: CameraResolution = CameraResolution.UHD,
    val fps: Int = 30,
    val mode: CaptureMode = CaptureMode.AUTO,
    val profile: RecordProfile = RecordProfile.STANDARD,
    /** See [Luts]: "none", a built-in id, or "custom:<file name>". */
    val lutId: String = Luts.NONE,
    val codec: VideoCodec = VideoCodec.H264,
    val monitoring: Monitoring = Monitoring(),
    /** Show and set shutter as an angle (180°) instead of a time (1/60). */
    val shutterAngle: Boolean = false,
    /** False: the LUT is only for viewing; the videos stay as recorded. */
    val bakeLut: Boolean = true,
    val recordBitrate: RecordBitrate = RecordBitrate.HIGH,
    val volumeKeysRecord: Boolean = true,
    val mirrorFront: Boolean = true,
    /** File names start with this, then the take number. */
    val clipName: String = "DualFrame",
    /** Self-timer before recording starts, in seconds (0 = off). */
    val timerSeconds: Int = 0,
    val stabilization: Stabilization = Stabilization.OFF,
    /** Low Light Boost: the camera brightens dark scenes (may lower the frame rate). */
    val night: Boolean = false,
    /** 1 = normal; otherwise the finished videos play this many times faster (time-lapse). */
    val timelapse: Int = 1,
    /** Time-lapse while walking/driving: forces the strongest stabilization while recording. */
    val hyperlapse: Boolean = false,
    val grid: Boolean = false,
    val keepOriginal: Boolean = false,
    val dual: Boolean = false,
    val insetCorner: Corner = Corner.TOP_RIGHT,
    val lensFacing: Int = CameraSelector.LENS_FACING_BACK,
) {
    val isPro get() = mode == CaptureMode.PRO

    val isHyperlapse get() = hyperlapse && timelapse > 1

    /** Hyperlapse needs the steadiest picture the phone can give. */
    val effectiveStabilization get() = if (isHyperlapse) Stabilization.ENHANCED else stabilization

    /** Auto mode always records the phone's own look, whatever Pro is set to. */
    val activeProfile get() = if (isPro) profile else RecordProfile.STANDARD
    val activeLutId get() = if (isPro) lutId else Luts.NONE
    val activeMonitoring get() = if (isPro) monitoring else Monitoring(histogram = false)

    /** Formats in a fixed, predictable order. */
    val orderedFormats: List<OutputFormat> get() = OutputFormat.entries.filter { it in outputs }
}

object SettingsStore {
    private const val PREFS = "capture_settings_v3"

    fun load(context: Context): CaptureSettings {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val d = CaptureSettings()
        val outputs = p.getString("outputs", null)
            ?.split(',')
            ?.mapNotNull { entry ->
                val (f, r) = entry.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
                val format = OutputFormat.entries.firstOrNull { it.name == f } ?: return@mapNotNull null
                format to enumOr(r, OutputResolution.FHD)
            }
            ?.toMap()
            ?.takeIf { it.isNotEmpty() }
            ?: d.outputs
        return CaptureSettings(
            outputs = outputs,
            exportQuality = enumOr(p.getString("exportQuality", null), d.exportQuality),
            cameraFrame = enumOr(p.getString("cameraFrame", null), d.cameraFrame),
            cameraResolution = enumOr(p.getString("cameraResolution", null), d.cameraResolution),
            fps = p.getInt("fps", d.fps),
            mode = enumOr(p.getString("mode", null), d.mode),
            profile = enumOr(p.getString("profile", null), d.profile),
            lutId = p.getString("lutId", null) ?: d.lutId,
            codec = enumOr(p.getString("codec", null), d.codec),
            monitoring = Monitoring(
                zebra = p.getInt("zebra", d.monitoring.zebra),
                peaking = p.getInt("peaking", d.monitoring.peaking),
                peakColor = enumOr(p.getString("peakColor", null), d.monitoring.peakColor),
                falseColor = p.getBoolean("falseColor", d.monitoring.falseColor),
                histogram = p.getBoolean("histogram", d.monitoring.histogram),
                level = p.getBoolean("level", d.monitoring.level),
                centerMark = p.getBoolean("centerMark", d.monitoring.centerMark),
                safeArea = p.getBoolean("safeArea", d.monitoring.safeArea),
            ),
            shutterAngle = p.getBoolean("shutterAngle", d.shutterAngle),
            bakeLut = p.getBoolean("bakeLut", d.bakeLut),
            recordBitrate = enumOr(p.getString("recordBitrate", null), d.recordBitrate),
            volumeKeysRecord = p.getBoolean("volumeKeysRecord", d.volumeKeysRecord),
            mirrorFront = p.getBoolean("mirrorFront", d.mirrorFront),
            clipName = p.getString("clipName", null) ?: d.clipName,
            timerSeconds = p.getInt("timerSeconds", d.timerSeconds),
            stabilization = enumOr(p.getString("stabilizationMode", null), d.stabilization),
            night = p.getBoolean("night", d.night),
            timelapse = p.getInt("timelapse", d.timelapse),
            hyperlapse = p.getBoolean("hyperlapse", d.hyperlapse),
            grid = p.getBoolean("grid", d.grid),
            keepOriginal = p.getBoolean("keepOriginal", d.keepOriginal),
            dual = p.getBoolean("dual", d.dual),
            insetCorner = enumOr(p.getString("insetCorner", null), d.insetCorner),
            lensFacing = p.getInt("lensFacing", d.lensFacing),
        )
    }

    fun save(context: Context, s: CaptureSettings) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("outputs", s.outputs.entries.joinToString(",") { "${it.key.name}:${it.value.name}" })
            .putString("exportQuality", s.exportQuality.name)
            .putString("cameraFrame", s.cameraFrame.name)
            .putString("cameraResolution", s.cameraResolution.name)
            .putInt("fps", s.fps)
            .putString("mode", s.mode.name)
            .putString("profile", s.profile.name)
            .putString("lutId", s.lutId)
            .putString("codec", s.codec.name)
            .putInt("zebra", s.monitoring.zebra)
            .putInt("peaking", s.monitoring.peaking)
            .putString("peakColor", s.monitoring.peakColor.name)
            .putBoolean("falseColor", s.monitoring.falseColor)
            .putBoolean("histogram", s.monitoring.histogram)
            .putBoolean("level", s.monitoring.level)
            .putBoolean("centerMark", s.monitoring.centerMark)
            .putBoolean("safeArea", s.monitoring.safeArea)
            .putBoolean("shutterAngle", s.shutterAngle)
            .putBoolean("bakeLut", s.bakeLut)
            .putString("recordBitrate", s.recordBitrate.name)
            .putBoolean("volumeKeysRecord", s.volumeKeysRecord)
            .putBoolean("mirrorFront", s.mirrorFront)
            .putString("clipName", s.clipName)
            .putInt("timerSeconds", s.timerSeconds)
            .putString("stabilizationMode", s.stabilization.name)
            .putBoolean("night", s.night)
            .putInt("timelapse", s.timelapse)
            .putBoolean("hyperlapse", s.hyperlapse)
            .putBoolean("grid", s.grid)
            .putBoolean("keepOriginal", s.keepOriginal)
            .putBoolean("dual", s.dual)
            .putString("insetCorner", s.insetCorner.name)
            .putInt("lensFacing", s.lensFacing)
            .apply()
    }

    /** Take numbers keep counting across launches, like a slate. */
    fun nextTake(context: Context): Int {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val take = p.getInt("take", 0) + 1
        p.edit().putInt("take", take).apply()
        return take
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default
}
