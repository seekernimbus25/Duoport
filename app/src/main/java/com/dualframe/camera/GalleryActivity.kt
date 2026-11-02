package com.dualframe.camera

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs

/** One saved video (Movies/DualFrame) or photo (Pictures/DualFrame). */
data class GalleryVideo(
    val uri: Uri,
    val name: String,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val dateAddedSec: Long,
    val photo: Boolean = false,
) {
    /** "9:16", "16:9"… worked out from the pixel size; "Original" for the uncut recording. */
    val shapeLabel: String
        get() {
            if (name.contains("_original")) return "Original"
            if (width <= 0 || height <= 0) return ""
            val aspect = width.toFloat() / height
            return OutputFormat.entries.minByOrNull { abs(it.aspect - aspect) }
                ?.takeIf { abs(it.aspect - aspect) < 0.03f }?.label ?: "${width}×$height"
        }
}

object VideoLibrary {
    const val RELATIVE_PATH = "Movies/DualFrame/"
    const val PHOTO_PATH = "Pictures/DualFrame/"

    /**
     * Newest first. Apps can always see the videos they saved themselves, with no permission
     * (Play only allows broad media access to gallery apps). Videos from before a reinstall belong
     * to the old install; they come back through a folder the user picks once ([earlierFolder]).
     */
    fun query(context: Context): List<GalleryVideo> {
        val ours = queryVideos(context) + queryPhotos(context)
        val earlier = runCatching { queryFolder(context, ours.map { it.name }.toSet()) }.getOrDefault(emptyList())
        return (ours + earlier).sortedByDescending { it.dateAddedSec }
    }

    // region Videos from before a reinstall

    private const val PREFS = "library"
    private const val KEY_FOLDER = "earlier_folder"

    /** Where the system folder picker opens: Movies/DualFrame on the phone's own storage. */
    val folderHint: Uri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Movies/DualFrame")

    /** The folder picked with "Find my earlier videos", while the app still has access to it. */
    fun earlierFolder(context: Context): Uri? {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_FOLDER, null) ?: return null
        val uri = Uri.parse(saved)
        return uri.takeIf { context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission } }
    }

    fun setEarlierFolder(context: Context, tree: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(tree, flags)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_FOLDER, tree.toString()).apply()
    }

    /** True for a video found through the picked folder rather than the app's own MediaStore entries. */
    fun isFromFolder(uri: Uri): Boolean = uri.authority != MediaStore.AUTHORITY

    /** Deletes one of ours or one from the picked folder; false if the system has to ask first. */
    fun delete(context: Context, uri: Uri): Boolean =
        if (isFromFolder(uri)) DocumentsContract.deleteDocument(context.contentResolver, uri)
        else context.contentResolver.delete(uri, null, null) > 0

    /** Size, length and turn of a folder video, read once per file version (reading takes ~30 ms). */
    private val folderInfo = java.util.concurrent.ConcurrentHashMap<String, GalleryVideo>()

    private fun queryFolder(context: Context, skip: Set<String>): List<GalleryVideo> {
        val tree = earlierFolder(context) ?: return emptyList()
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val found = mutableListOf<GalleryVideo>()
        context.contentResolver.query(children, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                val mime = c.getString(2) ?: continue
                val photo = mime.startsWith("image/")
                if (name in skip || !(photo || mime.startsWith("video/"))) continue
                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
                val modified = c.getLong(3)
                val key = "$uri@$modified"
                found += folderInfo.getOrPut(key) {
                    if (photo) {
                        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        context.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
                        GalleryVideo(uri, name, 0, bounds.outWidth, bounds.outHeight, modified / 1000, photo = true)
                    } else {
                        val r = android.media.MediaMetadataRetriever()
                        try {
                            r.setDataSource(context, uri)
                            fun meta(k: Int) = r.extractMetadata(k)?.toLongOrNull() ?: 0L
                            val turned = meta(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION) % 180 != 0L
                            val w = meta(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH).toInt()
                            val h = meta(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT).toInt()
                            GalleryVideo(
                                uri, name, meta(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION),
                                if (turned) h else w, if (turned) w else h, modified / 1000,
                            )
                        } finally {
                            r.release()
                        }
                    }
                }
            }
        }
        return found
    }

    /** A grid thumbnail. The folder's provider only makes them for photos, so videos use a frame. */
    fun thumbnail(context: Context, video: GalleryVideo): Bitmap? {
        runCatching { return context.contentResolver.loadThumbnail(video.uri, Size(360, 360), null) }
        if (video.photo || !isFromFolder(video.uri)) return null
        val r = android.media.MediaMetadataRetriever()
        return try {
            r.setDataSource(context, video.uri)
            r.getScaledFrameAtTime(1_000_000L, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 360, 360)
        } catch (e: RuntimeException) {
            null
        } finally {
            r.release()
        }
    }

    // endregion

    private fun queryPhotos(context: Context): List<GalleryVideo> {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.DATE_ADDED,
        )
        val photos = mutableListOf<GalleryVideo>()
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?",
            arrayOf("$PHOTO_PATH%"),
            "${MediaStore.Images.Media.DATE_ADDED} DESC",
        )?.use { c ->
            val id = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val name = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val width = c.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
            val height = c.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
            val date = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            while (c.moveToNext()) {
                photos += GalleryVideo(
                    uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(id)),
                    name = c.getString(name) ?: "",
                    durationMs = 0,
                    width = c.getInt(width),
                    height = c.getInt(height),
                    dateAddedSec = c.getLong(date),
                    photo = true,
                )
            }
        }
        return photos
    }

    private fun queryVideos(context: Context): List<GalleryVideo> {
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.ORIENTATION,
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
            val orientation = c.getColumnIndexOrThrow(MediaStore.Video.Media.ORIENTATION)
            while (c.moveToNext()) {
                // Sideways-stored videos (portrait 4K is saved 3840×2160 turned 90°) show turned.
                val turned = c.getInt(orientation) % 180 != 0
                videos += GalleryVideo(
                    uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, c.getLong(id)),
                    name = c.getString(name) ?: "",
                    durationMs = c.getLong(duration),
                    width = c.getInt(if (turned) height else width),
                    height = c.getInt(if (turned) width else height),
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

    /** Videos picked for trim / join, in the order they were tapped (the join order). */
    private val picked = mutableListOf<GalleryVideo>()
    private lateinit var actionBar: LinearLayout
    private lateinit var actionText: TextView

    /** The system's "allow DualFrame to delete these?" for videos made before a reinstall. */
    private val deleteLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { load() }

    /** "Find my earlier videos": the system folder picker, opened on Movies/DualFrame. */
    private val folderLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
            if (tree != null) {
                runCatching { VideoLibrary.setEarlierFolder(this, tree) }
                    .onFailure { Toast.makeText(this, R.string.library_folder_failed, Toast.LENGTH_SHORT).show() }
                load()
            }
        }
    private lateinit var findEarlier: View

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gallery)

        grid = findViewById(R.id.grid)
        empty = findViewById(R.id.empty)
        countText = findViewById(R.id.count)
        findViewById<View>(R.id.back).setOnClickListener { finish() }

        findEarlier = Glass.pill(this, getString(R.string.library_find_earlier)) { folderLauncher.launch(VideoLibrary.folderHint) }
        findViewById<LinearLayout>(R.id.header).addView(
            findEarlier,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Glass.dp(this@GalleryActivity, 10f).toInt()
            },
        )
        // The grid starts under the header, whatever its height (status bar, the button above).
        findViewById<View>(R.id.header).addOnLayoutChangeListener { header, _, _, _, _, _, _, _, _ ->
            if (grid.paddingTop != header.height) grid.post { grid.updatePadding(top = header.height) }
        }

        grid.layoutManager = GridLayoutManager(this, 3)
        grid.adapter = adapter
        buildActionBar()

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            findViewById<View>(R.id.header).updatePadding(top = bars.top)
            grid.updatePadding(bottom = bars.bottom + Glass.dp(this, 80f).toInt())
            actionBar.updatePadding(bottom = bars.bottom + Glass.dp(this, 12f).toInt())
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
            findEarlier.isVisible = VideoLibrary.earlierFolder(this@GalleryActivity) == null
            empty.isVisible = videos.isEmpty()
            countText.text = resources.getQuantityString(R.plurals.video_count, videos.size, videos.size)
        }
    }

    /** Long-press picks videos; a glass bar at the bottom then offers delete, Trim & join and the editor. */
    private fun buildActionBar() {
        val d = { v: Float -> Glass.dp(this, v).toInt() }
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        actionBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(context, R.drawable.bg_glass_panel)
            setPadding(d(20f), d(12f), d(12f), d(12f))
            visibility = View.GONE
        }
        // Top row: how many, delete, cancel.
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        actionText = Glass.body(this, "")
        top.addView(actionText, LinearLayout.LayoutParams(0, wrap, 1f))
        top.addView(ImageButton(this, null, 0, R.style.DF_IconButton).apply {
            setImageResource(R.drawable.ic_delete)
            imageTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(context, R.color.record_red))
            contentDescription = getString(R.string.delete)
            setOnClickListener { confirmDeletePicked() }
        }, LinearLayout.LayoutParams(d(44f), d(44f)))
        top.addView(Glass.pill(this, getString(android.R.string.cancel)) {
            picked.clear()
            refreshPicked()
        }, LinearLayout.LayoutParams(wrap, wrap).apply { marginStart = d(8f) })
        actionBar.addView(top)
        // Second row: what to make from them.
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END
        }
        actions.addView(Glass.pill(this, getString(R.string.trim_and_join)) {
            if (picked.isNotEmpty()) EditorActivity.open(this, picked.map { it.uri })
            picked.clear()
            refreshPicked()
        })
        // The full editor: timeline, music, captions, grading and design on these clips together.
        actions.addView(Glass.pill(this, getString(R.string.edit_together), selected = true) {
            val videos = picked.filter { !it.photo }.map { it.uri }
            if (videos.isNotEmpty()) DesignActivity.openAll(this, videos)
            picked.clear()
            refreshPicked()
        }, LinearLayout.LayoutParams(wrap, wrap).apply { marginStart = d(8f) })
        actionBar.addView(actions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, wrap).apply {
            topMargin = d(8f)
        })
        findViewById<android.widget.FrameLayout>(R.id.root).addView(
            actionBar,
            android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.BOTTOM,
            ),
        )
    }

    private fun confirmDeletePicked() {
        val chosen = picked.toList()
        if (chosen.isEmpty()) return
        MaterialAlertDialogBuilder(this)
            .setTitle(resources.getQuantityString(R.plurals.delete_picked_title, chosen.size, chosen.size))
            .setMessage(R.string.delete_picked_message)
            .setPositiveButton(R.string.delete) { _, _ -> deletePicked(chosen) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun deletePicked(chosen: List<GalleryVideo>) {
        picked.clear()
        refreshPicked()
        lifecycleScope.launch {
            // Ours are deleted straight away; ones from before a reinstall need the system to ask.
            val notOurs = withContext(Dispatchers.IO) {
                chosen.filter { video ->
                    runCatching { !VideoLibrary.delete(this@GalleryActivity, video.uri) }.getOrDefault(true)
                }
            }
            // The picked folder's videos either went or can't be deleted; only MediaStore can ask.
            val askable = notOurs.filterNot { VideoLibrary.isFromFolder(it.uri) }
            if (askable.size < notOurs.size) Toast.makeText(this@GalleryActivity, R.string.delete_failed, Toast.LENGTH_SHORT).show()
            if (askable.isNotEmpty()) {
                if (Build.VERSION.SDK_INT >= 30) {
                    val sender = MediaStore.createDeleteRequest(contentResolver, askable.map { it.uri }).intentSender
                    deleteLauncher.launch(IntentSenderRequest.Builder(sender).build())
                } else {
                    Toast.makeText(this@GalleryActivity, R.string.delete_failed, Toast.LENGTH_SHORT).show()
                }
            }
            load()
        }
    }

    private fun togglePicked(video: GalleryVideo) {
        if (video.photo) return
        if (!picked.remove(video)) picked += video
        refreshPicked()
    }

    private fun refreshPicked() {
        Glass.show(actionBar, picked.isNotEmpty())
        actionText.text = resources.getQuantityString(R.plurals.picked_count, picked.size, picked.size)
        adapter.notifyDataSetChanged()
    }

    private fun openVideo(video: GalleryVideo) {
        if (picked.isNotEmpty()) {
            togglePicked(video)
            return
        }
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
            val order = picked.indexOf(video)
            holder.shape.text = if (order >= 0) "✓ ${order + 1}" else video.shapeLabel
            holder.thumb.alpha = if (order >= 0) 0.55f else 1f
            holder.duration.text = if (video.photo) getString(R.string.photo) else VideoLibrary.formatDuration(video.durationMs)
            holder.itemView.setOnClickListener { onClick(video) }
            holder.itemView.setOnLongClickListener {
                togglePicked(video)
                true
            }
            holder.job?.cancel()
            val cached = thumbs.get(video.uri)
            holder.thumb.setImageBitmap(cached)
            if (cached == null) {
                holder.job = lifecycleScope.launch {
                    val bitmap = withContext(Dispatchers.IO) {
                        VideoLibrary.thumbnail(this@GalleryActivity, video)
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
