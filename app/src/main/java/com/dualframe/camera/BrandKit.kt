package com.dualframe.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlaySettings
import java.io.File

/**
 * Your watermark: a logo that goes on every video the app makes, in the corner you pick, at the
 * size and opacity you pick. Set from the design editor's Logo tab.
 */
object BrandKit {
    private const val PREFS = "brand_kit"

    data class Settings(val enabled: Boolean = false, val corner: Corner = Corner.BOTTOM_RIGHT, val sizePct: Int = 18, val opacity: Int = 85)

    private fun file(context: Context) = File(context.filesDir, "brand_logo.png")

    fun settings(context: Context): Settings {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Settings(
            enabled = p.getBoolean("enabled", false) && file(context).exists(),
            corner = Corner.entries.firstOrNull { it.name == p.getString("corner", null) } ?: Corner.BOTTOM_RIGHT,
            sizePct = p.getInt("sizePct", 18),
            opacity = p.getInt("opacity", 85),
        )
    }

    fun save(context: Context, s: Settings) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", s.enabled)
            .putString("corner", s.corner.name)
            .putInt("sizePct", s.sizePct)
            .putInt("opacity", s.opacity)
            .apply()
    }

    fun setLogo(context: Context, uri: Uri, cutout: Boolean) {
        val bitmap = DesignArt.decode(context, uri, 1024) ?: error("Couldn't read that image")
        val logo = if (cutout) DesignArt.cutOutPerson(context, bitmap) ?: bitmap else bitmap
        file(context).outputStream().use { logo.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    fun logo(context: Context): Bitmap? = file(context).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }

    /** Draws the watermark onto a photo (same corner, size and opacity as on videos). */
    fun stamp(context: Context, photo: Bitmap): Bitmap {
        val s = settings(context)
        if (!s.enabled) return photo
        val logo = logo(context) ?: return photo
        val out = if (photo.isMutable) photo else photo.copy(Bitmap.Config.ARGB_8888, true)
        val short = minOf(out.width, out.height).toFloat()
        val w = short * s.sizePct / 100f
        val h = w * logo.height / logo.width
        val m = short * 0.05f
        val left = if (s.corner.isLeft) m else out.width - m - w
        val top = if (s.corner.isTop) m else out.height - m - h
        android.graphics.Canvas(out).drawBitmap(
            logo, null, android.graphics.RectF(left, top, left + w, top + h),
            android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG).apply { alpha = s.opacity * 255 / 100 },
        )
        return out
    }

    /** The watermark as an overlay for an export, or null when it's off. */
    fun overlay(context: Context): BitmapOverlay? {
        val s = settings(context)
        if (!s.enabled) return null
        val logo = logo(context) ?: return null
        return WatermarkOverlay(logo, s)
    }
}

@OptIn(UnstableApi::class)
private class WatermarkOverlay(private val logo: Bitmap, private val s: BrandKit.Settings) : BitmapOverlay() {
    private var settings = OverlaySettings.Builder().build()

    override fun configure(videoSize: Size) {
        super.configure(videoSize)
        val short = minOf(videoSize.width, videoSize.height).toFloat()
        // Logo width = sizePct of the shorter side; pinned to the corner with a small margin.
        val scale = short * s.sizePct / 100f / logo.width
        val marginX = short * 0.05f / videoSize.width * 2f
        val marginY = short * 0.05f / videoSize.height * 2f
        val sx = if (s.corner.isLeft) -1f else 1f
        val sy = if (s.corner.isTop) 1f else -1f
        settings = OverlaySettings.Builder()
            .setScale(scale, scale)
            .setOverlayFrameAnchor(sx, sy)
            .setBackgroundFrameAnchor(sx * (1f - marginX), sy * (1f - marginY))
            .setAlphaScale(s.opacity / 100f)
            .build()
    }

    override fun getBitmap(presentationTimeUs: Long): Bitmap = logo

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings = settings
}
