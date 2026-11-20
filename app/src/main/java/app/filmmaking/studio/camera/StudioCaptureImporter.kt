package app.filmmaking.studio.camera

import android.content.Context
import androidx.room.withTransaction
import app.filmmaking.studio.data.StudioDatabase
import app.filmmaking.studio.data.StudioRecord
import app.studio.contracts.duoport.CameraResult
import app.studio.contracts.duoport.RecordingRole
import app.studio.contracts.duoport.ResultStatus
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

/**
 * Validates an actual Duoport result and imports all take/media rows atomically.
 * "reported" is deliberately not "verified" until a checksum pass has run.
 */
class StudioCaptureImporter(private val context: Context, private val db: StudioDatabase) {
    suspend fun import(expectedShotId: String, requestId: String, result: CameraResult): String {
        require(result.requestId == requestId) { "Camera request ID mismatch" }
        require(result.shotId == expectedShotId) { "Camera shot ID mismatch" }
        require(result.status == ResultStatus.CAPTURED) {
            result.error?.message ?: "Recording cancelled or failed; no take created"
        }
        require(result.recordings.isNotEmpty()) { "Camera returned no recorded media" }
        require(result.recordings.any { it.role == RecordingRole.MASTER }) { "Camera returned no primary file" }

        // Check that every URI is actually readable from this Android process.
        // No checksum is invented; a later worker can promote reported -> verified.
        for (file in result.recordings) {
            require(file.uri.startsWith("content://")) { "Only content:// media URIs are supported" }
            val uri = android.net.Uri.parse(file.uri)
            val accessible = runCatching {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
            }.getOrDefault(false)
            require(accessible) { "Cannot read recording " + file.displayName }
        }
        val dao = db.dao()
        return db.withTransaction {
            val shot = requireNotNull(dao.find("shots", expectedShotId)) { "Unknown planned shot" }
            val shotPayload = JSONObject(shot.payload)
            val projectId = shotPayload.getString("projectId")
            val existingTakes = dao.listByProject("takes", projectId)
            val duplicate = existingTakes.any {
                JSONObject(it.payload).optString("duoportRequestId") == requestId
            }
            if (duplicate) return@withTransaction "Recording already imported"
            val number = 1 + existingTakes.count { JSONObject(it.payload).optString("shotId") == expectedShotId }
            val takeId = UUID.randomUUID().toString()
            val date = Instant.ofEpochMilli(result.capturedAtMs ?: System.currentTimeMillis()).toString()
            val ids = JSONArray()
            result.recordings.forEach { file ->
                require(file.mediaId.isNotBlank() && file.recordingId.isNotBlank())
                require(dao.find("media", file.mediaId) == null) { "Duplicate media ID" }
                val media = JSONObject()
                    .put("id", file.mediaId).put("projectId", projectId)
                    .put("sceneId", shotPayload.getString("sceneId"))
                    .put("shotId", expectedShotId).put("takeId", takeId)
                    .put("recordingId", file.recordingId)
                    .put("filename", file.displayName).put("originalFilename", file.displayName)
                    .put("uri", file.uri).put("mime", file.mime)
                    .put("role", if (file.role == RecordingRole.MASTER) "master" else "derivative")
                    .put("placeholder", false).put("verification", "reported")
                    .put("createdAt", date)
                if (file.masterMediaId != null) media.put("masterId", file.masterMediaId)
                if (file.outputFormat != null) media.put("deliveryFormat", file.outputFormat)
                if (file.sizeBytes != null) media.put("sizeBytes", file.sizeBytes)
                if (file.width != null) media.put("width", file.width)
                if (file.height != null) media.put("height", file.height)
                if (file.fps != null) media.put("frameRate", file.fps)
                file.durationMs?.let { media.put("durationSec", it / 1000.0) }
                if (file.sha256 != null) media.put("checksum", JSONObject().put("algo","sha256").put("value",file.sha256))
                dao.upsert(StudioRecord("media", file.mediaId, file.displayName, media.toString()))
                ids.put(file.mediaId)
            }
            val duration = result.recordings.firstNotNullOfOrNull { it.durationMs }?.div(1000.0) ?: 0.0
            val take = JSONObject().put("id", takeId).put("projectId", projectId)
                .put("shotId", expectedShotId).put("number", number).put("mediaAssetIds", ids)
                .put("durationSec", duration).put("capturedAt", date)
                .put("settings", JSONObject()).put("preferred", false).put("favourite", false)
                .put("quality", "review").put("directorNotes", "")
                .put("continuityNotes", "").put("kind", "picture")
                .put("recordingId", result.recordings.first().recordingId)
                .put("duoportRequestId", requestId)
            dao.upsert(StudioRecord("takes", takeId, "Take " + number, take.toString()))
            val updated = JSONObject(shot.payload)
                .put("status", "complete").put("updatedAt", Instant.now().toString())
            dao.upsert(shot.copy(payload = updated.toString(),
                updatedAt = System.currentTimeMillis(), dirty = true))
            "Take " + number + " imported (" + result.recordings.size + " actual file(s), not yet checksum verified)"
        }
    }
}
