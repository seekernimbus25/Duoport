package com.dualframe.camera

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs

/** One saved video in Movies/DualFrame. */
data class GalleryVideo(
    val uri: Uri,
    val name: String,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val dateAddedSec: Long,
) {
    /** "9:16", "16:9"… worked out from the pixel size. */
    val shapeLabel: String
        get() {
            if (width <= 0 || height <= 0) return ""
            val aspect = width.toFloat() / height
            return OutputFormat.entries.minByOrNull { abs(it.aspect - aspect) }
                ?.takeIf { abs(it.aspect - aspect) < 0.03f }?.label ?: "${width}×$height"
        }
}

object VideoLibrary {
    const val RELATIVE_PATH = "Movies/DualFrame/"

    /**
     * Newest first. Apps can always see the videos they saved themselves, with no permission,
     * which is all the library needs (Play only allows broad media access to gallery apps).
     */
    fun query(context: Context): List<GalleryVideo> {
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.DATE_ADDED,
        )
        val videos = mutableListOf<GalleryVideo>()
        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?",
            arrayOf("$RELATIVE_PATH%"),
            "${MediaStore.Video.Media.DATE_ADDED} DESC",
        )?.use { c ->
            val id = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val name = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val duration = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val width = c.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
            val height = c.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
            val date = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            while (c.moveToNext()) {
                videos += GalleryVideo(
                    uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, c.getLong(id)),
                    name = c.getString(name) ?: "",
                    durationMs = c.getLong(duration),
                    width = c.getInt(width),
                    height = c.getInt(height),
                    dateAddedSec = c.getLong(date),
                )
            }
        }
        return videos
    }

    fun formatDuration(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) {
            String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
        } else {
            String.format(Locale.US, "%d:%02d", s / 60, s % 60)
        }
    }
}

class GalleryActivity : AppCompatActivity() {

    private lateinit var grid: RecyclerView
    private lateinit var empty: View
    private lateinit var countText: TextView
    private val adapter = VideoAdapter { openVideo(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gallery)

        grid = findViewById(R.id.grid)
        empty = findViewById(R.id.empty)
        countText = findViewById(R.id.count)
        findViewById<View>(R.id.back).setOnClickListener { finish() }

        grid.layoutManager = GridLayoutManager(this, 3)
        grid.adapter = adapter

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            findViewById<View>(R.id.header).updatePadding(top = bars.top)
            grid.updatePadding(bottom = bars.bottom)
            insets
        }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        lifecycleScope.launch {
            val videos = withContext(Dispatchers.IO) { runCatching { VideoLibrary.query(this@GalleryActivity) }.getOrDefault(emptyList()) }
            adapter.submit(videos)
            empty.isVisible = videos.isEmpty()
            countText.text = resources.getQuantityString(R.plurals.video_count, videos.size, videos.size)
        }
    }

    private fun openVideo(video: GalleryVideo) {
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .setData(video.uri)
                .putExtra(PlayerActivity.EXTRA_TITLE, video.shapeLabel)
        )
    }

    private inner class VideoAdapter(private val onClick: (GalleryVideo) -> Unit) :
        RecyclerView.Adapter<VideoAdapter.Holder>() {

        private var items: List<GalleryVideo> = emptyList()
        private val thumbs = LruCache<Uri, Bitmap>(120)

        @android.annotation.SuppressLint("NotifyDataSetChanged")
        fun submit(list: List<GalleryVideo>) {
            items = list
            notifyDataSetChanged()
        }

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val thumb: ImageView = view.findViewById(R.id.thumb)
            val shape: TextView = view.findViewById(R.id.shape)
            val duration: TextView = view.findViewById(R.id.duration)
            var job: Job? = null
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_gallery, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val video = items[position]
            holder.shape.text = video.shapeLabel
            holder.duration.text = VideoLibrary.formatDuration(video.durationMs)
            holder.itemView.setOnClickListener { onClick(video) }
            holder.job?.cancel()
            val cached = thumbs.get(video.uri)
            holder.thumb.setImageBitmap(cached)
            if (cached == null) {
                holder.job = lifecycleScope.launch {
                    val bitmap = withContext(Dispatchers.IO) {
                        runCatching { contentResolver.loadThumbnail(video.uri, Size(360, 360), null) }.getOrNull()
                    }
                    if (bitmap != null) {
                        thumbs.put(video.uri, bitmap)
                        holder.thumb.setImageBitmap(bitmap)
                    }
                }
            }
        }
    }
}
