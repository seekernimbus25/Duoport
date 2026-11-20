package app.filmmaking.studio.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/** A file the user picked from an SD card, USB reader or Files, with its real SHA-256. */
data class PickedFile(
    val uri: String,
    val displayName: String,
    val sizeBytes: Long,
    val sha256: String,
    val mime: String
)

sealed class MatchResult {
    data class Link(val file: PickedFile, val clip: StudioRecord) : MatchResult()
    data class Relink(val file: PickedFile, val clip: StudioRecord, val mediaId: String) : MatchResult()
    data class AlreadyLinked(val file: PickedFile, val clip: StudioRecord) : MatchResult()
    data class ChecksumMismatch(val file: PickedFile, val clip: StudioRecord) : MatchResult()
    data class DuplicateContent(val file: PickedFile, val existingMediaId: String) : MatchResult()
    data class Unmatched(val file: PickedFile) : MatchResult()
}

data class ImportSummary(val linked: Int, val relinked: Int, val skipped: List<String>) {
    val message: String get() = buildString {
        append("$linked clip(s) imported and SHA-256 verified")
        if (relinked > 0) append(", $relinked relinked")
        if (skipped.isNotEmpty()) append(". Not imported: " + skipped.joinToString("; ").take(400))
    }
}

/** Pure matching rules, unit-tested without Android. */
object ExternalMediaMatcher {
    fun match(files: List<PickedFile>, clips: List<StudioRecord>, media: List<StudioRecord>): List<MatchResult> {
        val live = clips.filter { it.deletedAt == null && it.collection == ExternalCameraNaming.COLLECTION }
        val liveMedia = media.filter { it.deletedAt == null }
        val seenHashes = mutableSetOf<String>()
        return files.map { f ->
            val clip = live.firstOrNull { JSONObject(it.payload).optString("filename").equals(f.displayName, ignoreCase = true) }
            val sameContent = liveMedia.firstOrNull {
                JSONObject(it.payload).optJSONObject("checksum")?.optString("value") == f.sha256
            }
            when {
                clip == null -> MatchResult.Unmatched(f)
                !seenHashes.add(f.sha256) -> MatchResult.DuplicateContent(f, "picked twice")
                else -> {
                    val c = JSONObject(clip.payload)
                    val linkedMediaId = c.optString("mediaId").takeIf { it.isNotBlank() }
                    val linkedSha = c.optString("sha256").takeIf { it.isNotBlank() }
                    when {
                        linkedMediaId == null && sameContent != null -> MatchResult.DuplicateContent(f, sameContent.id)
                        linkedMediaId == null -> MatchResult.Link(f, clip)
                        linkedSha != null && linkedSha != f.sha256 -> MatchResult.ChecksumMismatch(f, clip)
                        liveMedia.any { it.id == linkedMediaId && JSONObject(it.payload).optString("uri") == f.uri } ->
                            MatchResult.AlreadyLinked(f, clip)
                        else -> MatchResult.Relink(f, clip, linkedMediaId)
                    }
                }
            }
        }
    }
}

/**
 * Turns external-camera filename references into ACTUAL media + take records — but only
 * for files that were really read on this device and hashed. A filename alone never counts.
 */
class ExternalMediaImport(context: Context, private val db: StudioDatabase) {
    private val app = context.applicationContext
    private val dao = db.dao()

    suspend fun hash(uri: Uri): PickedFile = withContext(Dispatchers.IO) {
        val resolver = app.contentResolver
        var name = uri.lastPathSegment.orEmpty().substringAfterLast('/')
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        val digest = MessageDigest.getInstance("SHA-256")
        var length = 0L
        (resolver.openInputStream(uri) ?: error("Cannot read $name")).use { stream ->
            val block = ByteArray(1 shl 20)
            while (true) {
                val n = stream.read(block)
                if (n < 0) break
                digest.update(block, 0, n); length += n
            }
        }
        require(length > 0) { "$name is empty" }
        if (size >= 0) require(size == length) { "$name changed while it was being read" }
        PickedFile(uri.toString(), name, length,
            digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) },
            resolver.getType(uri) ?: "application/octet-stream")
    }

    suspend fun import(projectId: String, uris: List<Uri>): ImportSummary {
        require(uris.isNotEmpty()) { "No files selected" }
        val picked = uris.map { uri ->
            // Keep read access across restarts so the clip can be re-verified and relinked.
            runCatching { app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            hash(uri)
        }
        return db.withTransaction {
            val clips = dao.listByProject(ExternalCameraNaming.COLLECTION, projectId)
            val media = dao.listByProject("media", projectId)
            var linked = 0; var relinked = 0
            val skipped = mutableListOf<String>()
            for (result in ExternalMediaMatcher.match(picked, clips, media)) {
                when (result) {
                    is MatchResult.Link -> { link(projectId, result.file, result.clip); linked++ }
                    is MatchResult.Relink -> { relink(result.file, result.mediaId); relinked++ }
                    is MatchResult.AlreadyLinked -> skipped += result.file.displayName + " already linked"
                    is MatchResult.ChecksumMismatch -> skipped += result.file.displayName +
                        " differs from the file linked before (checksum mismatch)"
                    is MatchResult.DuplicateContent -> skipped += result.file.displayName + " is a duplicate of footage already imported"
                    is MatchResult.Unmatched -> skipped += result.file.displayName + " matches no logged filename"
                }
            }
            ImportSummary(linked, relinked, skipped)
        }
    }

    private suspend fun link(projectId: String, f: PickedFile, clip: StudioRecord) {
        val c = JSONObject(clip.payload)
        val shotId = c.getString("shotId")
        val shot = requireNotNull(dao.find("shots", shotId)) { "Shot for ${f.displayName} no longer exists" }
        val takes = dao.listByProject("takes", projectId)
        val number = 1 + takes.count { JSONObject(it.payload).optString("shotId") == shotId }
        val stamp = Instant.now().toString()
        val takeId = UUID.randomUUID().toString()
        val mediaId = UUID.randomUUID().toString()
        val media = JSONObject().put("id", mediaId).put("projectId", projectId)
            .put("sceneId", c.optString("sceneId")).put("shotId", shotId).put("takeId", takeId)
            .put("filename", f.displayName).put("originalFilename", c.optString("filename"))
            .put("uri", f.uri).put("mime", f.mime).put("role", "master").put("placeholder", false)
            .put("source", "external-camera").put("externalClipId", clip.id)
            .put("sizeBytes", f.sizeBytes)
            .put("checksum", JSONObject().put("algo", "sha256").put("value", f.sha256))
            .put("verification", "verified").put("verifiedAt", stamp).put("createdAt", stamp)
        dao.upsert(StudioRecord("media", mediaId, f.displayName, media.toString()))
        val take = JSONObject().put("id", takeId).put("projectId", projectId).put("shotId", shotId)
            .put("number", number).put("mediaAssetIds", JSONArray().put(mediaId))
            .put("capturedAt", c.optString("loggedAt", stamp)).put("settings", JSONObject())
            .put("preferred", false).put("favourite", false).put("quality", "review")
            .put("directorNotes", "").put("continuityNotes", "").put("kind", "picture")
            .put("source", "external-camera").put("externalClipId", clip.id)
        dao.upsert(StudioRecord("takes", takeId, "Take $number", take.toString()))
        c.put("status", "linked").put("footageImported", true).put("mediaId", mediaId).put("takeId", takeId)
            .put("sha256", f.sha256).put("updatedAt", stamp)
        dao.upsert(clip.copy(payload = c.toString(), updatedAt = System.currentTimeMillis(), dirty = true))
        val s = JSONObject(shot.payload)
        if (s.optString("status") in setOf("planned", "ready", "in progress"))
            dao.upsert(shot.copy(payload = s.put("status", "complete").put("updatedAt", stamp).toString(),
                updatedAt = System.currentTimeMillis(), dirty = true))
    }

    /** Same bytes at a new location (card re-inserted, file moved): only the URI changes. */
    private suspend fun relink(f: PickedFile, mediaId: String) {
        val row = requireNotNull(dao.find("media", mediaId)) { "Linked media record missing" }
        val m = JSONObject(row.payload).put("uri", f.uri).put("verification", "verified")
            .put("verifiedAt", Instant.now().toString())
            .put("checksum", JSONObject().put("algo", "sha256").put("value", f.sha256))
        m.remove("verificationError")
        dao.upsert(row.copy(payload = m.toString(), updatedAt = System.currentTimeMillis(), dirty = true))
    }
}
