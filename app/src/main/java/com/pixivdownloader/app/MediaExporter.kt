package com.pixivdownloader.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * Copies finished files into the public Downloads/PixivDownloader folder (no storage permission needed).
 * Mirrors the desktop layout:  Images/<title> (<id>)/...   and   MP4_OUTPUT/<title> (<id>)/...
 */
object MediaExporter {

    const val FOLDER = "PixivDownloader"
    const val IMAGES_DIR = "Images"
    const val VIDEO_DIR = "MP4_OUTPUT"

    /** RELATIVE_PATH of the top-level folder as MediaStore stores it (with trailing slash). */
    val RELATIVE_PATH = relativePath()

    /** "Download/PixivDownloader/<sub>/" (sub may be empty). */
    fun relativePath(sub: String = ""): String {
        val s = sub.trim('/')
        return "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER/" + if (s.isEmpty()) "" else "$s/"
    }

    /** Finds a file by exact display name (only files this app created are visible). */
    fun find(context: Context, displayName: String, sub: String = ""): Uri? {
        context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Downloads._ID),
            "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH}=?",
            arrayOf(displayName, relativePath(sub)),
            null
        )?.use { c ->
            if (c.moveToFirst()) {
                return Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0).toString())
            }
        }
        return null
    }

    /** Returns "Name.ext" if free, otherwise "Name_1.ext", "Name_2.ext", ... */
    fun uniqueName(context: Context, baseName: String, ext: String, sub: String = ""): String {
        val suffix = if (ext.isEmpty()) "" else ".$ext"
        var candidate = "$baseName$suffix"
        var counter = 1
        while (find(context, candidate, sub) != null) {
            candidate = "${baseName}_$counter$suffix"
            counter++
        }
        return candidate
    }

    fun mimeFor(ext: String): String = when (ext.lowercase()) {
        "mp4" -> "video/mp4"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "txt" -> "text/plain"
        else -> "application/octet-stream"
    }

    fun export(context: Context, file: File, displayName: String = file.name, sub: String = ""): Boolean {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, mimeFor(file.extension))
            put(MediaStore.Downloads.RELATIVE_PATH, relativePath(sub).trimEnd('/'))
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
        return try {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                ?: return false
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            false
        }
    }
}
