package com.dualframe.camera

import android.content.ContentValues
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import java.io.File

/**
 * Hooks for the automated emulator tests. They only ever do anything in a debuggable build
 * (the emulator test build); release and Play builds ignore them completely.
 *
 * - Test media: files the test pushes to the app's own folder
 *   (Android/data/com.dualframe.camera/files/test_media) are copied into the library, so the
 *   library, editors and designer have known clips to work on.
 * - Captions: the emulator has no speech recognizer, so a transcript the test pushes
 *   (files/test_transcript.json: [{"start":0,"end":400,"text":"Hello"}, …]) stands in for it.
 */
object TestHooks {
    const val EXTRA_IMPORT_MEDIA = "dualframe.test.import_media"

    fun enabled(context: Context) = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    private fun transcriptFile(context: Context) = context.getExternalFilesDir(null)?.let { File(it, "test_transcript.json") }

    fun hasTranscript(context: Context) = enabled(context) && transcriptFile(context)?.exists() == true

    fun transcript(context: Context): List<CaptionWord>? {
        if (!hasTranscript(context)) return null
        return runCatching {
            val arr = JSONArray(transcriptFile(context)!!.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                CaptionWord(o.getLong("start"), o.getLong("end"), o.getString("text"))
            }
        }.getOrNull()
    }

    /** Copies pushed test clips and photos into the library. Returns how many were added. */
    fun importMedia(context: Context): Int {
        if (!enabled(context)) return 0
        val dir = context.getExternalFilesDir("test_media") ?: return 0
        var added = 0
        dir.listFiles().orEmpty().sortedBy { it.name }.forEach { file ->
            val video = file.extension.lowercase() in setOf("mp4", "mov", "3gp", "mkv")
            val image = file.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp")
            if (!video && !image) return@forEach
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(MediaStore.MediaColumns.MIME_TYPE, if (video) "video/mp4" else if (file.extension.lowercase() == "png") "image/png" else "image/jpeg")
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    if (video) "${Environment.DIRECTORY_MOVIES}/DualFrame" else "${Environment.DIRECTORY_PICTURES}/DualFrame",
                )
            }
            val collection = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val uri = context.contentResolver.insert(collection, values) ?: return@forEach
            context.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
            file.delete()
            added++
        }
        return added
    }
}
