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
    val focusDiopters: Float? = null,
) {
    val manualExposure get() = iso != null || shutterNs != null
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

    val flatLookSupported: Boolean =
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
    }
}

/** Sends the manual settings and the camera-side part of the look to the camera. */
@OptIn(ExperimentalCamera2Interop::class)
object ProControls {

    fun apply(
        camera: Camera,
        caps: CameraCaps,
        state: ManualState,
        look: Look,
        fps: Int,
        lastReadout: Readout?,
    ) {
        val options = CaptureRequestOptions.Builder()

        if (state.manualExposure && caps.manualSensor) {
            // Camera2 has no shutter- or ISO-priority mode, so whichever value is still "auto"
            // is frozen at what the camera was using, keeping brightness where it was.
            val measuredIso = lastReadout?.iso ?: 100
            val measuredShutter = lastReadout?.shutterNs ?: (1_000_000_000L / (fps * 2))
            val shutter = state.shutterNs ?: measuredShutter
            val iso = state.iso ?: run {
                val scaled = measuredIso * measuredShutter.toDouble() / shutter
                val range = caps.isoRange
                if (range == null) measuredIso else scaled.roundToInt().coerceIn(range.lower, range.upper)
            }
            options.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            options.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
            options.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, shutter)
            options.setCaptureRequestOption(CaptureRequest.SENSOR_FRAME_DURATION, 1_000_000_000L / fps)
        }

        if (state.whiteBalance != WhiteBalance.AUTO) {
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

        if (look == Look.FLAT && caps.flatLookSupported) {
            options.setCaptureRequestOption(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE)
            options.setCaptureRequestOption(CaptureRequest.TONEMAP_CURVE, Looks.flatCurve)
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
