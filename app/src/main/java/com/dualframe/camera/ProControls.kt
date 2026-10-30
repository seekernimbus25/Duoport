package com.dualframe.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.util.Range
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

enum class WhiteBalance(val label: String, val awbMode: Int) {
    AUTO("Auto", CameraMetadata.CONTROL_AWB_MODE_AUTO),
    TUNGSTEN("3200K", CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT),
    FLUORESCENT("4000K", CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT),
    DAYLIGHT("5500K", CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT),
    CLOUDY("6500K", CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT),
    SHADE("7500K", CameraMetadata.CONTROL_AWB_MODE_SHADE),
}

/** What the user has taken manual control of. `null` means the camera decides (auto). */
data class ManualState(
    val iso: Int? = null,
    val shutterNs: Long? = null,
    val evIndex: Int = 0,
    val whiteBalance: WhiteBalance = WhiteBalance.AUTO,
    val whiteBalanceLocked: Boolean = false,
    /** Manual colour temperature; overrides [whiteBalance] presets when set. */
    val kelvin: Int? = null,
    /** -50 (green) .. +50 (magenta), used with [kelvin]. */
    val tint: Int = 0,
    val focusDiopters: Float? = null,
    /** Exposure held where it is (long-press on the picture), while still on auto. */
    val aeLocked: Boolean = false,
) {
    val manualExposure get() = iso != null || shutterNs != null
    /** Exactly one of shutter / ISO is set: the other one follows the light (priority mode). */
    val shutterPriority get() = shutterNs != null && iso == null
    val isoPriority get() = iso != null && shutterNs == null
    val isAllAuto get() = this == ManualState()
}

/** What the camera is actually doing right now, read back from every frame. */
data class Readout(val iso: Int?, val shutterNs: Long?, val focusDiopters: Float?)

/** What this particular camera lets us control. */
@OptIn(ExperimentalCamera2Interop::class)
class CameraCaps(camera: Camera) {
    private val info = Camera2CameraInfo.from(camera.cameraInfo)

    private val capabilities: IntArray =
        info.getCameraCharacteristic(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)

    val manualSensor = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in capabilities
    val isoRange: Range<Int>? = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
    val shutterRange: Range<Long>? = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)

    /** Closest focus in diopters; 0 means fixed focus. */
    val minFocusDiopters: Float =
        info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
    val manualFocus get() = minFocusDiopters > 0f &&
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in capabilities

    val whiteBalances: List<WhiteBalance> = run {
        val modes = info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES) ?: IntArray(0)
        WhiteBalance.entries.filter { it == WhiteBalance.AUTO || it.awbMode in modes }
    }

    @Suppress("UNCHECKED_CAST")
    val kelvinWb = KelvinWhiteBalance { key -> info.getCameraCharacteristic(key as CameraCharacteristics.Key<Any>) }

    val hasFlash: Boolean = camera.cameraInfo.hasFlashUnit()

    val logSupported: Boolean =
        (info.getCameraCharacteristic(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES) ?: IntArray(0))
            .contains(CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE)

    /** 35mm-equivalent focal length of the 1x lens, for the zoom readout. */
    val equivalentFocalMm: Float? = run {
        val focal = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
        val sensor = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        if (focal == null || sensor == null || sensor.width <= 0f) {
            null
        } else {
            val diagonal = kotlin.math.sqrt(sensor.width * sensor.width + sensor.height * sensor.height)
            focal * 43.27f / diagonal
        }
    }

    /** Standard ISO stops this camera can do. */
    val isoStops: List<Int> = isoRange?.let { r ->
        ISO_STOPS.filter { it in r.lower..r.upper }.ifEmpty { listOf(r.lower, r.upper) }
    } ?: emptyList()

    /** Standard shutter speeds (as 1/x) that fit both the sensor and the frame rate. */
    fun shutterStops(fps: Int): List<Int> {
        val r = shutterRange ?: return emptyList()
        return SHUTTER_DENOMINATORS.filter { d ->
            val ns = 1_000_000_000L / d
            d >= fps && ns in r.lower..r.upper
        }
    }

    companion object {
        val ISO_STOPS = listOf(
            25, 32, 40, 50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800,
            1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400, 8000, 10000, 12800,
        )
        val SHUTTER_DENOMINATORS = listOf(
            24, 25, 30, 48, 50, 60, 100, 120, 125, 180, 250, 500, 1000, 2000, 4000, 8000,
        )
        /** Shutter angles a cinema camera offers. */
        val SHUTTER_ANGLES = listOf(11.25f, 22.5f, 45f, 90f, 144f, 172.8f, 180f, 270f, 360f)
    }

    /** Shutter angles that fit the sensor at [fps], with their exposure time. */
    fun shutterAngles(fps: Int): List<Pair<Float, Long>> {
        val r = shutterRange ?: return emptyList()
        return SHUTTER_ANGLES.map { it to (it / 360.0 * 1_000_000_000.0 / fps).toLong() }
            .filter { it.second in r.lower..r.upper }
    }
}

/** Sends the manual settings and the recording profile's tone curve to the camera. */
@OptIn(ExperimentalCamera2Interop::class)
object ProControls {

    /**
     * [priorityIso] / [priorityShutterNs] are the values the exposure loop picked for whichever
     * side is still on auto in shutter- or ISO-priority mode (Camera2 has no such mode itself).
     */
    fun apply(
        camera: Camera,
        caps: CameraCaps,
        state: ManualState,
        profile: RecordProfile,
        fps: Int,
        lastReadout: Readout?,
        priorityIso: Int? = null,
        priorityShutterNs: Long? = null,
    ) {
        val options = CaptureRequestOptions.Builder()

        if (state.manualExposure && caps.manualSensor) {
            // Until the exposure loop has an opinion, start from what the camera was using,
            // scaled so the picture keeps its brightness.
            val measuredIso = lastReadout?.iso ?: 100
            val measuredShutter = lastReadout?.shutterNs ?: (1_000_000_000L / (fps * 2))
            val maxShutter = 1_000_000_000L / fps
            val shutter = (state.shutterNs ?: priorityShutterNs ?: run {
                val iso = state.iso ?: measuredIso
                (measuredShutter * measuredIso.toDouble() / iso).toLong()
            }).let { ns -> caps.shutterRange?.let { ns.coerceIn(it.lower, minOf(it.upper, maxShutter)) } ?: ns }
            val iso = state.iso ?: priorityIso ?: run {
                val scaled = measuredIso * measuredShutter.toDouble() / shutter
                val range = caps.isoRange
                if (range == null) measuredIso else scaled.roundToInt().coerceIn(range.lower, range.upper)
            }
            options.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            options.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
            options.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, shutter)
            options.setCaptureRequestOption(CaptureRequest.SENSOR_FRAME_DURATION, 1_000_000_000L / fps)
        }

        if (!state.manualExposure && state.aeLocked) {
            options.setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, true)
        }

        val kelvin = state.kelvin?.let { caps.kelvinWb.solve(it, state.tint) }
        if (kelvin != null) {
            options.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
            options.setCaptureRequestOption(
                CaptureRequest.COLOR_CORRECTION_MODE,
                CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX,
            )
            options.setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_GAINS, kelvin.gains)
            options.setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_TRANSFORM, kelvin.transform)
        } else if (state.whiteBalance != WhiteBalance.AUTO) {
            options.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, state.whiteBalance.awbMode)
        }
        if (state.whiteBalanceLocked) {
            options.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, true)
        }

        val focus = state.focusDiopters
        if (focus != null && caps.manualFocus) {
            options.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            options.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, focus.coerceIn(0f, caps.minFocusDiopters))
        }

        if (profile == RecordProfile.LOG && caps.logSupported) {
            options.setCaptureRequestOption(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE)
            options.setCaptureRequestOption(CaptureRequest.TONEMAP_CURVE, Luts.logCurve)
        }

        Camera2CameraControl.from(camera.cameraControl).setCaptureRequestOptions(options.build())
        if (!state.manualExposure && camera.cameraInfo.exposureState.isExposureCompensationSupported) {
            camera.cameraControl.setExposureCompensationIndex(state.evIndex)
        }
    }

    fun readout(result: TotalCaptureResult) = Readout(
        iso = result.get(CaptureResult.SENSOR_SENSITIVITY),
        shutterNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME),
        focusDiopters = result.get(CaptureResult.LENS_FOCUS_DISTANCE),
    )
}

object Labels {
    fun shutter(ns: Long?): String {
        if (ns == null || ns <= 0) return "--"
        val denominator = 1_000_000_000.0 / ns
        return if (denominator >= 1.5) "1/${denominator.roundToLong()}" else "%.1fs".format(ns / 1e9)
    }

    fun iso(iso: Int?) = if (iso == null) "--" else "$iso"

    /** 180° rather than 1/60: how cinema cameras describe motion blur. */
    fun angle(ns: Long?, fps: Int): String {
        if (ns == null || ns <= 0) return "--"
        val deg = ns / 1e9 * fps * 360
        return if (abs(deg - deg.roundToInt()) < 0.05) "${deg.roundToInt()}°" else "%.1f°".format(deg)
    }

    fun kelvin(k: Int) = "${k}K"

    /** Exposure offset like a light meter: "+0.7", "−1.3", "±0". */
    fun stops(v: Float): String = when {
        abs(v) < 0.05f -> "±0"
        v > 0 -> "+%.1f".format(v)
        else -> "−%.1f".format(-v)
    }

    fun focus(diopters: Float?): String = when {
        diopters == null -> "--"
        diopters <= 0.01f -> "∞"
        else -> {
            val meters = 1f / diopters
            if (meters >= 1f) "%.1fm".format(meters) else "${(meters * 100).roundToInt()}cm"
        }
    }

    fun ev(index: Int, step: Float): String {
        val value = index * step
        return when {
            abs(value) < 0.05f -> "±0"
            value > 0 -> "+%.1f".format(value)
            else -> "%.1f".format(value)
        }
    }
}
