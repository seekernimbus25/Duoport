package com.dualframe.camera

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import app.studio.contracts.duoport.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * An explicit cross-app capture bridge. It reuses Duoport's EXISTING camera and
 * shot-list exporter; no simulated media or fabricated camera metadata is returned.
 * Wait until exports have finished before backing out of the camera screen.
 *
 * This Activity is not exported. Only this app can invoke the internal camera handoff.
 */
class StudioCaptureActivity : AppCompatActivity() {
    private val json = Json { ignoreUnknownKeys = true }
    private var request: OpenCameraRequest? = null
    private var listId: String? = null
    private var previousId: String? = null
    private var launchTime = 0L
    private val camera = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        finishCapture()
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (intent?.component?.packageName != packageName ||
            intent?.action != DuoportIntent.ACTION_OPEN) {
            setResult(Activity.RESULT_CANCELED); finish(); return
        }
        val parsed = runCatching {
            json.decodeFromString<OpenCameraRequest>(
                requireNotNull(intent.getStringExtra(DuoportIntent.EXTRA_REQUEST_JSON))
            )
        }.getOrNull()
        if (parsed == null || parsed.contractVersion != DUOPORT_CONTRACT_VERSION ||
            parsed.requestId.isBlank() || parsed.mode != OpenMode.SHOT || parsed.shot == null) {
            setResult(Activity.RESULT_CANCELED); finish(); return
        }
        request = parsed
        StudioDiagnostics.breadcrumb("camera", "planned_shot_handoff")
        if (state != null) {
            listId = state.getString("listId")
            previousId = state.getString("previousId")
            launchTime = state.getLong("launchTime")
            return
        }
        val shot = requireNotNull(parsed.shot)
        MaterialAlertDialogBuilder(this)
            .setTitle("Film a Studio shot")
            .setMessage("Open Duoport to film " + shot.shotCode + ": " + shot.title +
                "?\n\nWait for export to finish before returning. " +
                "The saved media will be shared with Filmmaking Studio.")
            .setPositiveButton("Open Duoport") { _, _ -> openCamera(parsed) }
            .setNegativeButton(android.R.string.cancel) { _, _ -> cancelCapture() }
            .setCancelable(false).show()
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putString("listId", listId)
        out.putString("previousId", previousId)
        out.putLong("launchTime", launchTime)
    }

    private fun openCamera(req: OpenCameraRequest) {
        val info = requireNotNull(req.shot)
        previousId = ShotLists.activeId(this)
        launchTime = System.currentTimeMillis()
        val project = ShotLists.create(this, info.projectName.take(80))
        listId = project.id
        project.shots.add(Shot(
            id = project.nextId(),
            number = info.shotCode.substringAfter("-").takeWhile { it.isDigit() }.ifBlank { "1" },
            type = ShotType.entries.firstOrNull { it.code == info.shotType } ?: ShotType.OTHER,
            description = info.instructions.take(2000),
            script = req.teleprompter?.text.orEmpty().take(4000),
            notes = "Studio shot " + info.shotCode + ": " + info.title
        ))
        ShotLists.save(this, project)
        ShotLists.setActive(this, project.id)
        try {
            camera.launch(Intent(this, MainActivity::class.java))
        } catch (error: Exception) {
            ShotLists.setActive(this, previousId)
            fail("camera-unavailable", error.message ?: "Could not launch camera")
        }
    }

    private fun finishCapture() {
        val req = request ?: run { cancelCapture(); return }
        ShotLists.setActive(this, previousId)
        val saved = listId?.let { ShotLists.load(this, it) }?.shots?.firstOrNull()
            ?.takes?.filter { it.createdAt >= launchTime - 2000 && it.videos.isNotEmpty() }
            ?.maxByOrNull { it.createdAt }
        if (saved == null) { cancelCapture(); return }

        val recId = UUID.randomUUID().toString()
        val records = mutableListOf<Recording>()
        var primaryId: String? = null
        saved.videos.forEach { (label, uri) ->
            if (uri.scheme != "content") return@forEach
            val readable = runCatching {
                contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
            }.getOrDefault(false)
            if (!readable) return@forEach
            val mediaId = UUID.randomUUID().toString()
            val isPrimary = primaryId == null
            if (isPrimary) primaryId = mediaId
            val display = runCatching {
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            }.getOrNull() ?: label
            val bytes = runCatching {
                contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getLong(0) else null
                }
            }.getOrNull()
            val mime = contentResolver.getType(uri) ?: "video/mp4"
            records += Recording(
                mediaId = mediaId, recordingId = recId,
                role = if (isPrimary) RecordingRole.MASTER else RecordingRole.DERIVATIVE,
                masterMediaId = if (isPrimary) null else primaryId,
                outputFormat = label, uri = uri.toString(), displayName = display,
                mime = mime, sizeBytes = bytes, startedAtMs = saved.createdAt,
                photo = mime.startsWith("image/")
            )
        }
        if (records.isEmpty()) { fail("export-failed", "No readable saved media found"); return }
        StudioDiagnostics.breadcrumb("camera", "media_export_ready")

        val result = CameraResult(
            requestId = req.requestId, status = ResultStatus.CAPTURED,
            shotId = req.shot?.shotId, takeNumber = saved.number,
            capturedAtMs = saved.createdAt, recordings = records, offline = true
        )
        val output = Intent().putExtra(DuoportIntent.EXTRA_RESULT_JSON, json.encodeToString(result))
        val clips = ClipData.newUri(contentResolver, "Duoport media", Uri.parse(records[0].uri))
        records.drop(1).forEach { clips.addItem(ClipData.Item(Uri.parse(it.uri))) }
        output.clipData = clips
        output.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        setResult(Activity.RESULT_OK, output)
        finish()
    }

    private fun fail(code: String, message: String) {
        val req = request
        if (req == null) {
            setResult(Activity.RESULT_CANCELED)
        } else {
            val result = CameraResult(
                requestId = req.requestId, status = ResultStatus.FAILED,
                shotId = req.shot?.shotId, error = CameraError(code, message)
            )
            setResult(Activity.RESULT_OK, Intent().putExtra(
                DuoportIntent.EXTRA_RESULT_JSON, json.encodeToString(result)
            ))
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }

    private fun cancelCapture() {
        ShotLists.setActive(this, previousId)
        setResult(Activity.RESULT_CANCELED)
        finish()
    }
}
