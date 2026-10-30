package com.dualframe.camera

import android.app.RecoverableSecurityException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Plays one saved video (or shows one photo) full screen, with share / open-in / delete. */
class PlayerActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private lateinit var uri: Uri
    private var isPhoto = false
    private val mimeType get() = if (isPhoto) "image/jpeg" else "video/mp4"

    private val deleteLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == RESULT_OK) finish()
        }

    @OptIn(UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        uri = intent.data ?: run {
            finish()
            return
        }

        isPhoto = contentResolver.getType(uri)?.startsWith("image/") == true
        findViewById<TextView>(R.id.title).text = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        findViewById<View>(R.id.back).setOnClickListener { finish() }
        findViewById<View>(R.id.share).setOnClickListener { share() }
        findViewById<View>(R.id.open_with).setOnClickListener { openWith() }
        findViewById<View>(R.id.delete).setOnClickListener { confirmDelete() }

        findViewById<PlayerView>(R.id.player_view).apply {
            setShowNextButton(false)
            setShowPreviousButton(false)
            visibility = if (isPhoto) View.GONE else View.VISIBLE
        }
        if (isPhoto) showPhoto()

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            findViewById<View>(R.id.header).updatePadding(top = bars.top)
            findViewById<View>(R.id.player_view).updatePadding(bottom = bars.bottom)
            insets
        }
    }

    private fun showPhoto() {
        val view = findViewById<android.widget.ImageView>(R.id.photo_view)
        view.visibility = View.VISIBLE
        val longSide = maxOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels) * 2
        Thread {
            val bitmap = runCatching {
                android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(contentResolver, uri)) { decoder, info, _ ->
                    val scale = longSide.toFloat() / maxOf(info.size.width, info.size.height)
                    if (scale < 1f) decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
                }
            }.getOrNull()
            runOnUiThread { if (!isDestroyed) view.setImageBitmap(bitmap) }
        }.start()
    }

    override fun onStart() {
        super.onStart()
        if (isPhoto) return
        val exo = ExoPlayer.Builder(this).build().also { player = it }
        findViewById<PlayerView>(R.id.player_view).player = exo
        exo.setMediaItem(MediaItem.fromUri(uri))
        exo.repeatMode = Player.REPEAT_MODE_ONE
        exo.prepare()
        exo.play()
    }

    override fun onStop() {
        super.onStop()
        findViewById<PlayerView>(R.id.player_view).player = null
        player?.release()
        player = null
    }

    private fun share() {
        val send = Intent(Intent.ACTION_SEND)
            .setType(mimeType)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, getString(R.string.share)))
    }

    private fun openWith() {
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mimeType)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { startActivity(Intent.createChooser(view, getString(R.string.open_with))) }
            .onFailure { Toast.makeText(this, R.string.no_player, Toast.LENGTH_SHORT).show() }
    }

    private fun confirmDelete() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_title)
            .setMessage(R.string.delete_message)
            .setPositiveButton(R.string.delete) { _, _ -> delete() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun delete() {
        try {
            if (contentResolver.delete(uri, null, null) > 0) finish()
        } catch (e: SecurityException) {
            // Videos saved before a reinstall belong to the old install: ask the system.
            val sender = when {
                Build.VERSION.SDK_INT >= 30 -> MediaStore.createDeleteRequest(contentResolver, listOf(uri)).intentSender
                e is RecoverableSecurityException -> e.userAction.actionIntent.intentSender
                else -> null
            }
            if (sender != null) {
                deleteLauncher.launch(IntentSenderRequest.Builder(sender).build())
            } else {
                Toast.makeText(this, R.string.delete_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        const val EXTRA_TITLE = "title"
    }
}
