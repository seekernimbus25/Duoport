// Duoport ↔ Studio integration contract v1. Mirrors src/services/duoportContract.ts (the zod schema is the reference).
// Pure Kotlin + kotlinx.serialization; no Android imports so it can live in a shared :contracts module used by both apps.
package app.studio.contracts.duoport

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val DUOPORT_CONTRACT_VERSION = 1

/** Intent action / extras the Duoport adapter activity must accept (to be added to Duoport; not present today). */
object DuoportIntent {
    const val ACTION_OPEN = "com.dualframe.camera.action.OPEN_FOR_STUDIO"
    const val ACTION_CAPABILITIES = "com.dualframe.camera.action.CAPABILITIES"
    const val EXTRA_REQUEST_JSON = "com.dualframe.camera.extra.REQUEST_JSON"
    const val EXTRA_RESULT_JSON = "com.dualframe.camera.extra.RESULT_JSON"
}

@Serializable enum class OpenMode { @SerialName("free") FREE, @SerialName("shot") SHOT }

@Serializable data class OutputSpec(val format: String /* "9:16", "16:9", "4:5", "1:1", "3:4", "4:3", "21:9" */, val shortSide: Int /* 720 | 1080 | 2160 */)

@Serializable data class DesiredSettings(
    val outputs: List<OutputSpec>? = null, val fps: Int? = null, val ntsc: Boolean? = null, val slowMotionFps: Int? = null,
    val profile: String? = null, val lutId: String? = null, val bakeLut: Boolean? = null, val codec: String? = null,
    val lensKey: String? = null, val lensLabel: String? = null, val iso: Int? = null, val shutterNs: Long? = null,
    val whiteBalanceK: Int? = null, val stabilization: String? = null, val keepOriginal: Boolean? = null,
)

@Serializable data class ShotContext(
    val projectId: String, val projectName: String, val sceneId: String, val shotId: String, val shotCode: String,
    val shotType: String /* Duoport ShotType.code */, val title: String, val instructions: String, val takeNumber: Int,
    val shootDayId: String? = null, val setupId: String? = null,
)
@Serializable data class Teleprompter(val text: String, val artifactVersionId: String? = null)
@Serializable data class ReferenceFrame(val uri: String, val label: String)
@Serializable data class SetupDiagramRef(val diagramId: String, val version: Int, val json: String)

@Serializable data class OpenCameraRequest(
    val contractVersion: Int = DUOPORT_CONTRACT_VERSION, val requestId: String, val mode: OpenMode,
    val shot: ShotContext? = null, val teleprompter: Teleprompter? = null, val desired: DesiredSettings? = null,
    val referenceFrames: List<ReferenceFrame>? = null, val setupDiagram: SetupDiagramRef? = null,
) { init { require(mode == OpenMode.FREE || shot != null) { "mode SHOT requires shot" } } }

@Serializable enum class RecordingRole { @SerialName("master") MASTER, @SerialName("derivative") DERIVATIVE }

@Serializable data class Recording(
    val mediaId: String, val recordingId: String, val role: RecordingRole, val masterMediaId: String? = null,
    val outputFormat: String? = null, val uri: String, val displayName: String, val mime: String,
    val width: Int? = null, val height: Int? = null, val fps: String? = null, val videoCodec: String? = null,
    val durationMs: Long? = null, val startedAtMs: Long? = null, val timecodeStart: String? = null,
    val sizeBytes: Long? = null, val sha256: String? = null, val photo: Boolean? = null,
)
@Serializable data class ActualSettings(
    val fps: Double? = null, val ntsc: Boolean? = null, val profile: String? = null, val lutId: String? = null, val lutBaked: Boolean? = null,
    val codec: String? = null, val lensKey: String? = null, val lensLabel: String? = null, val equivalentMm: Int? = null,
    val iso: Int? = null, val shutterNs: Long? = null, val whiteBalance: String? = null, val stabilization: String? = null,
)
@Serializable enum class ResultStatus { @SerialName("captured") CAPTURED, @SerialName("cancelled") CANCELLED, @SerialName("failed") FAILED }
@Serializable data class CameraError(val code: String /* permission-denied|camera-unavailable|storage-full|encoder-failed|export-failed|interrupted|unknown */, val message: String, val savedOriginalUri: String? = null)

@Serializable data class CameraResult(
    val contractVersion: Int = DUOPORT_CONTRACT_VERSION, val requestId: String, val status: ResultStatus,
    val error: CameraError? = null, val shotId: String? = null, val takeNumber: Int? = null, val capturedAtMs: Long? = null,
    val actual: ActualSettings? = null, val recordings: List<Recording> = emptyList(), val offline: Boolean? = null,
)

@Serializable data class LensInfo(val key: String, val label: String, val facing: String, val equivalentMm: Int)
@Serializable data class Capabilities(
    val contractVersion: Int = DUOPORT_CONTRACT_VERSION, val device: String, val lenses: List<LensInfo>, val fps: List<Int>,
    val slowMotionFps: List<Int>, val shortSides: List<Int>, val profiles: List<String>, val codecs: List<String>,
    val manualSensor: Boolean, val isoRange: List<Int>?, val shutterRangeNs: List<Long>?, val whiteBalances: List<String>, val luts: List<String>,
)
