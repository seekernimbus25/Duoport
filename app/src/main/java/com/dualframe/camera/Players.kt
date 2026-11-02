package com.dualframe.camera

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer

/**
 * Every player in the app. Media3's default buffer sizes itself for streaming and holds up to
 * ~130 MB of video in the Java heap; a 20 Mbps clip fills it. The player screen and the Grade
 * or Edit screen opened from it each run one, so two full buffers ran the app out of memory.
 * Our clips are local files that read far faster than they play, so a small buffer is plenty.
 */
object Players {
    private const val BUFFER_BYTES = 32 * 1024 * 1024

    @OptIn(UnstableApi::class)
    fun create(context: Context): ExoPlayer {
        val loadControl = DefaultLoadControl.Builder()
            .setTargetBufferBytes(BUFFER_BYTES)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
        return ExoPlayer.Builder(context).setLoadControl(loadControl).build()
    }
}
