package app.filmmaking.studio.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.MessageDigest

data class VerificationSummary(
    val verified: Int,
    val missing: Int,
    val skipped: Int
)

/**
 * Actual SHA-256 verification of media returned by Duoport.
 * Never promotes simulated or inaccessible files to "verified".
 *
 * This is a manual foreground-triggered IO operation, not the eventual WorkManager job.
 * Videos can be large; do not read them into memory.
 */
class MediaVerification(
    context: Context, private val database: StudioDatabase
) {
    private val app = context.applicationContext

    suspend fun verify(projectId: String): VerificationSummary = withContext(Dispatchers.IO) {
        val dao = database.dao()
        val rows = dao.listByProject("media", projectId)
        var verified = 0
        var missing = 0
        var skipped = 0
        for (row in rows) {
            val item = JSONObject(row.payload)
            if (item.optBoolean("placeholder") || item.optString("verification") == "simulated") {
                skipped++
                continue
            }
            val uri = Uri.parse(item.optString("uri"))
            if (uri.scheme != "content") {
                item.put("verification", "invalid")
                dao.upsert(row.copy(payload = item.toString(), dirty = true,
                    updatedAt = System.currentTimeMillis()))
                missing++
                continue
            }
            val result = runCatching {
                val digest = MessageDigest.getInstance("SHA-256")
                var length = 0L
                val content = app.contentResolver.openInputStream(uri)
                    ?: error("The file is no longer readable")
                content.use { stream ->
                    val block = ByteArray(256 * 1024)
                    while (true) {
                        val count = stream.read(block)
                        if (count < 0) break
                        if (count == 0) continue
                        digest.update(block, 0, count)
                        length += count
                    }
                }
                require(length > 0L) { "Empty recording" }
                val sha = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
                sha to length
            }
            result.onSuccess { (sha, size) ->
                // Once confirmed, a file change in the same URI is caught by the next pass.
                item.put("verification", "verified")
                    .put("checksum", JSONObject().put("algo", "sha256").put("value", sha))
                    .put("sizeBytes", size)
                dao.upsert(row.copy(payload = item.toString(), dirty = true,
                    updatedAt = System.currentTimeMillis()))
                verified++
            }.onFailure {
                item.put("verification", "missing")
                item.put("verificationError", "Unable to read or hash the recording")
                dao.upsert(row.copy(payload = item.toString(), dirty = true,
                    updatedAt = System.currentTimeMillis()))
                missing++
            }
        }
        VerificationSummary(verified, missing, skipped)
    }
}
