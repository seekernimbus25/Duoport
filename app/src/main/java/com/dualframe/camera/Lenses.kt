package com.dualframe.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalLensFacing
import androidx.camera.lifecycle.ProcessCameraProvider
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * One lens the user can lock to. Phones publish lenses two ways: as their own camera
 * ([physicalId] null), or as one lens inside a multi-lens camera that normally switches
 * lenses by itself as you zoom ([physicalId] set; streamed on its own with setPhysicalCameraId).
 */
data class Lens(
    val facing: Int,
    val cameraId: String,
    val physicalId: String?,
    /** 35mm-equivalent focal length, 0 if the phone doesn't say. */
    val equivalentMm: Int,
    /** A USB camera (or HDMI capture card) the phone exposes as a camera. */
    val external: Boolean = false,
) {
    val key get() = if (physicalId == null) cameraId else "$cameraId/$physicalId"

    /** "0.5× · 13mm", relative to the phone's main lens. */
    fun label(mainMm: Int): String = when {
        external -> "USB camera"
        equivalentMm <= 0 -> "Lens $key"
        mainMm <= 0 -> "${equivalentMm}mm"
        else -> "${zoomText(equivalentMm.toFloat() / mainMm)} · ${equivalentMm}mm"
    }

    private fun zoomText(z: Float): String {
        val rounded = (z * 10).roundToInt() / 10f
        return if (rounded % 1f == 0f) "${rounded.toInt()}×" else "$rounded×"
    }
}

@OptIn(ExperimentalCamera2Interop::class, ExperimentalLensFacing::class)
object Lenses {

    /** Every lens on [facing] this phone lets apps open, widest first, without duplicates. */
    fun list(context: Context, provider: ProcessCameraProvider, facing: Int): List<Lens> {
        val manager = context.getSystemService(CameraManager::class.java) ?: return emptyList()
        val found = mutableListOf<Lens>()
        for (info in provider.availableCameraInfos) {
            val external = info.lensFacing == CameraSelector.LENS_FACING_EXTERNAL
            if (info.lensFacing != facing && !(external && facing == CameraSelector.LENS_FACING_BACK)) continue
            val id = cameraId(info) ?: continue
            val chars = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: continue
            val physical = chars.physicalCameraIds
            if (physical.size > 1 && !external) {
                // A multi-lens camera: each lens inside it can be locked.
                for (pid in physical) {
                    val pc = runCatching { manager.getCameraCharacteristics(pid) }.getOrNull() ?: continue
                    found += Lens(facing, id, pid, equivalentMm(pc))
                }
            } else {
                found += Lens(facing, id, null, equivalentMm(chars), external)
            }
        }
        // Some phones publish the same lens both ways; keep the stand-alone camera.
        val unique = mutableListOf<Lens>()
        for (lens in found.sortedBy { it.physicalId != null }) {
            val twin = unique.any {
                !it.external && !lens.external && lens.equivalentMm > 0 && abs(it.equivalentMm - lens.equivalentMm) <= 1
            }
            if (!twin) unique += lens
        }
        return unique.sortedWith(compareBy({ it.external }, { it.equivalentMm }))
    }

    /** Focal length of the lens the phone uses by default on [facing] (its "1×"). */
    fun mainMm(context: Context, provider: ProcessCameraProvider, facing: Int): Int {
        val selector = CameraSelector.Builder().requireLensFacing(facing).build()
        if (!provider.hasCamera(selector)) return 0
        val id = cameraId(provider.getCameraInfo(selector)) ?: return 0
        val manager = context.getSystemService(CameraManager::class.java) ?: return 0
        return runCatching { equivalentMm(manager.getCameraCharacteristics(id)) }.getOrDefault(0)
    }

    fun selector(lens: Lens): CameraSelector {
        val builder = CameraSelector.Builder()
            .addCameraFilter { infos -> infos.filter { cameraId(it) == lens.cameraId } }
        if (lens.physicalId != null) builder.setPhysicalCameraId(lens.physicalId)
        return builder.build()
    }

    private fun cameraId(info: CameraInfo): String? = runCatching { Camera2CameraInfo.from(info).cameraId }.getOrNull()

    private fun equivalentMm(chars: CameraCharacteristics): Int {
        val focal = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return 0
        val sensor = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return 0
        val diagonal = sqrt(sensor.width * sensor.width + sensor.height * sensor.height)
        if (diagonal <= 0f) return 0
        return (focal * 43.27f / diagonal).roundToInt()
    }
}
