package app.filmmaking.studio.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.media.MediaRecorder
import org.json.JSONArray
import org.json.JSONObject

/**
 * Reads what THIS phone's cameras report through Camera2. Only these values may be shown as
 * "verified"; anything the user types into a shot plan stays a plan.
 */
object CameraProfileProbe {
    fun probe(context: Context): JSONObject {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val lenses = JSONArray()
        val fps = sortedSetOf<Int>()
        val sizes = sortedSetOf<Int>()
        var manual = false
        for (id in manager.cameraIdList) {
            val c = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: continue
            val facing = when (c.get(CameraCharacteristics.LENS_FACING)) {
                CameraMetadata.LENS_FACING_FRONT -> "front"; CameraMetadata.LENS_FACING_BACK -> "back"; else -> "external"
            }
            val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList().orEmpty()
            val sensor = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val equivalent = if (sensor != null && sensor.width > 0) focal.map { f ->
                (f * 43.27f / kotlin.math.hypot(sensor.width.toDouble(), sensor.height.toDouble()).toFloat()).toInt()
            } else emptyList()
            lenses.put(JSONObject().put("id", id).put("facing", facing)
                .put("focalLengthsMm", JSONArray(focal.map { it.toDouble() })).put("equivalentMm", JSONArray(equivalent)))
            c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.forEach { fps += it.upper }
            c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(MediaRecorder::class.java)
                ?.forEach { sizes += minOf(it.width, it.height) }
            val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toList().orEmpty()
            if (CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in caps) manual = true
        }
        return JSONObject().put("device", android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL)
            .put("lenses", lenses).put("fps", JSONArray(fps.toList()))
            .put("videoShortSides", JSONArray(sizes.filter { it >= 480 }.toList()))
            .put("manualSensor", manual)
    }
}
