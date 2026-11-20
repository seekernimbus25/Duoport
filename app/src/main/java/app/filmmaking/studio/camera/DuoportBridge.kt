package app.filmmaking.studio.camera

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.dualframe.camera.StudioCaptureActivity
import androidx.activity.result.contract.ActivityResultContract
import app.studio.contracts.duoport.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Native boundary only. Duoport's StudioCaptureActivity is not available yet.
 * Until its adapter exists, the request will be unavailable, never simulated as captured.
 */
class DuoportCaptureContract : ActivityResultContract<OpenCameraRequest, CameraResult?>() {
    private val json = Json { ignoreUnknownKeys = true }
    override fun createIntent(context: Context, input: OpenCameraRequest): Intent =
        Intent(context, StudioCaptureActivity::class.java).apply {
            action = DuoportIntent.ACTION_OPEN
            putExtra(DuoportIntent.EXTRA_REQUEST_JSON, json.encodeToString(input))
        }

    override fun parseResult(resultCode: Int, intent: Intent?): CameraResult? {
        if (resultCode != Activity.RESULT_OK) return null
        val raw = intent?.getStringExtra(DuoportIntent.EXTRA_RESULT_JSON) ?: return null
        val result = runCatching { json.decodeFromString<CameraResult>(raw) }.getOrNull() ?: return null
        return result.takeIf { it.contractVersion == DUOPORT_CONTRACT_VERSION }
    }
}
