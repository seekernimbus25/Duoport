package app.filmmaking.studio.data

import androidx.room.withTransaction
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

/**
 * A reference to a clip on a separate camera/card. This is deliberately NOT a Take
 * or MediaAsset: entering a name does not prove that footage exists on the phone.
 */
data class ExternalFilenamePattern(
    val prefix: String,
    val digits: String,
    val extension: String
) {
    val width: Int get() = digits.length
    val number: Long get() = digits.toLong()

    fun filenameFor(suffix: String): String {
        require(suffix.matches(Regex("[0-9]{1,12}"))) { "Enter only the numeric suffix (e.g. 0046)" }
        val n = suffix.toLongOrNull() ?: error("File number is too large")
        return prefix + n.toString().padStart(width, '0') + extension
    }

    val nextSuffix: String? get() = (number + 1L).takeIf { it <= 999_999_999_999L }
        ?.toString()?.padStart(width, '0')

    val nextFilename: String? get() = nextSuffix?.let(::filenameFor)
}

/** Supports DJI_0045.MP4, DJI_20261120_0045.MP4, C0001.MOV and similar camera names. */
object ExternalCameraNaming {
    private val trailing = Regex("^(.*?)([0-9]{1,12})(\\.[A-Za-z0-9]{1,10})?$")
    private val forbidden = Regex("[/\\\\\\u0000-\\u001f<>:\"|?*]")

    fun checkedFilename(value: String): String {
        val name = value.trim()
        require(name.isNotEmpty() && name.length <= 160 &&
            !forbidden.containsMatchIn(name) && name != "." && name != "..") {
            "Enter a single camera filename, not a folder or file path"
        }
        return name
    }

    fun detect(filename: String): ExternalFilenamePattern? {
        val name = checkedFilename(filename)
        val match = trailing.matchEntire(name) ?: return null
        val digits = match.groupValues[2]
        val n = digits.toLongOrNull() ?: return null
        if (n >= 999_999_999_999L) return null
        return ExternalFilenamePattern(
            prefix = match.groupValues[1],
            digits = digits,
            extension = match.groupValues[3]
        )
    }

    /** The most recently logged numbered file defines the default for the next shot. */
    fun nextFrom(projectRecords: List<StudioRecord>): ExternalFilenamePattern? =
        projectRecords.asSequence()
            .filter { it.collection == COLLECTION && it.deletedAt == null }
            .sortedByDescending { it.updatedAt }
            .mapNotNull { detect(JSONObject(it.payload).optString("filename")) }
            .firstOrNull()

    const val COLLECTION = "externalClips"
}

class ExternalCameraLog(private val database: StudioDatabase) {
    private val dao = database.dao()

    /**
     * Checks duplicates ACROSS the whole project, not just one shot.
     * The current camera sequence survives switching scenes and app restarts.
     */
    suspend fun add(shotId: String, requestedFilename: String): StudioRecord = database.withTransaction {
        val shot = requireNotNull(dao.find("shots", shotId)) { "Choose a planned shot first" }
        val shotInfo = JSONObject(shot.payload)
        val projectId = shotInfo.getString("projectId")
        val sceneId = shotInfo.getString("sceneId")
        val filename = ExternalCameraNaming.checkedFilename(requestedFilename)
        val existing = dao.listByProject(ExternalCameraNaming.COLLECTION, projectId)
        require(existing.none {
            JSONObject(it.payload).optString("filename").equals(filename, ignoreCase = true)
        }) { "That filename is already logged for this film" }
        val number = existing.count { JSONObject(it.payload).optString("shotId") == shotId } + 1
        val pattern = ExternalCameraNaming.detect(filename)
        val stamp = Instant.now().toString()
        val id = UUID.randomUUID().toString()
        val info = JSONObject().put("id", id).put("projectId", projectId)
            .put("sceneId", sceneId).put("shotId", shotId)
            .put("filename", filename).put("externalTakeNumber", number)
            .put("source", "external-camera")
            .put("status", "awaiting-footage-import")
            .put("footageImported", false)
            .put("loggedAt", stamp).put("updatedAt", stamp)
        if (pattern != null) {
            info.put("numberPrefix", pattern.prefix)
                .put("numericSuffix", pattern.digits)
                .put("numberWidth", pattern.width)
                .put("extension", pattern.extension)
        }
        val row = StudioRecord(ExternalCameraNaming.COLLECTION, id, filename, info.toString())
        dao.upsert(row)
        row
    }

    /** Removes a mistaken reference only; never deletes any actual camera file. */
    suspend fun remove(referenceId: String) {
        val old = requireNotNull(dao.find(ExternalCameraNaming.COLLECTION, referenceId))
        require(old.deletedAt == null) { "Reference already removed" }
        dao.tombstone(ExternalCameraNaming.COLLECTION, referenceId)
    }
}
