package com.pixivdownloader.app

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore

/**
 * pixiv_downloader_history.log: ONE file in Downloads/PixivDownloader, created the first time and then
 * updated in place. One "<canonical artwork url>|<format identifier>" line per completed download.
 *
 * To make sure the existing file is always found (and a second copy is never created):
 *  - the MediaStore id of the log is remembered in SharedPreferences and checked first;
 *  - otherwise the file is looked up by name prefix and matched to our folder in code
 *    (Android may have renamed it to ".log.txt" or "pixiv_downloader_history (1).log");
 *  - a log from older builds ("download_history.log") is picked up too, merged and renamed to the new name;
 *  - any extra copies are merged into the first one and deleted.
 *
 * Note: Android only lets an app read files it created itself. After an uninstall/reinstall
 * (or installing a build signed with a different key) the app can't see the old log and starts a new one.
 */
class DownloadHistory(private val context: Context) {

    companion object {
        const val NAME = "pixiv_downloader_history.log"
        private const val PREFIX = "pixiv_downloader_history"
        /** Name used by earlier builds; kept so an existing history is not lost. */
        private const val LEGACY_PREFIX = "download_history"
        private const val PREF_ID = "log_id"
    }

    private val resolver get() = context.contentResolver
    private val prefs = context.getSharedPreferences("pixiv_history", Context.MODE_PRIVATE)
    private val folderPath = "${Environment.DIRECTORY_DOWNLOADS}/${MediaExporter.FOLDER}"

    init {
        migrateLegacyName()
    }

    private fun uriFor(id: Long): Uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)

    /** All history files in our folder, oldest first. */
    private fun allLogs(): List<Uri> {
        val ids = sortedSetOf<Long>()

        // 1) the id we stored when we created the file
        val saved = prefs.getLong(PREF_ID, -1L)
        if (saved >= 0) {
            try {
                resolver.query(uriFor(saved), arrayOf(MediaStore.Downloads._ID), null, null, null)?.use { c ->
                    if (c.moveToFirst()) ids += saved
                }
            } catch (_: Exception) {}
        }

        // 2) lookup by name prefix, folder checked in code
        try {
            resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.RELATIVE_PATH),
                "${MediaStore.Downloads.DISPLAY_NAME} LIKE ? OR ${MediaStore.Downloads.DISPLAY_NAME} LIKE ?",
                arrayOf("$PREFIX%", "$LEGACY_PREFIX%"),
                "${MediaStore.Downloads._ID} ASC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val rel = c.getString(1)?.trim('/') ?: continue
                    if (rel.equals(folderPath, ignoreCase = true)) ids += c.getLong(0)
                }
            }
        } catch (_: Exception) {}

        return ids.map { uriFor(it) }
    }

    fun uri(): Uri? = allLogs().firstOrNull()

    /** If the primary log still has the old file name, rename it to NAME (content is kept). */
    @Synchronized
    private fun migrateLegacyName() {
        try {
            val primary = allLogs().firstOrNull() ?: return
            val current = resolver.query(primary, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: return
            if (current.startsWith(LEGACY_PREFIX, ignoreCase = true)) {
                val values = ContentValues().apply { put(MediaStore.Downloads.DISPLAY_NAME, NAME) }
                resolver.update(primary, values, null, null)
            }
        } catch (_: Exception) {}
    }

    private fun readText(uri: Uri): String =
        try {
            resolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
        } catch (e: Exception) {
            ""
        }

    private fun write(uri: Uri, text: String): Boolean {
        val bytes = text.toByteArray(Charsets.UTF_8)
        // "wt" = truncate and rewrite the whole (small) file; some providers only accept other modes
        for (mode in listOf("wt", "rwt", "w")) {
            try {
                val ok = resolver.openOutputStream(uri, mode)?.use { it.write(bytes) } != null
                if (ok) return true
            } catch (_: Exception) {}
        }
        return false
    }

    @Synchronized
    fun contains(url: String, identifier: String): Boolean {
        val entry = "$url|$identifier"
        return allLogs().any { uri -> readText(uri).lines().any { it.trim() == entry } }
    }

    @Synchronized
    fun append(url: String, identifier: String) {
        val logs = allLogs()
        val primary = logs.firstOrNull()
        val entry = "$url|$identifier"

        if (primary == null) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, NAME)
                // generic type so Android keeps the ".log" extension
                put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                put(MediaStore.Downloads.RELATIVE_PATH, folderPath)
            }
            val created = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
            prefs.edit().putLong(PREF_ID, ContentUris.parseId(created)).apply()
            write(created, "$entry\n")
            return
        }

        prefs.edit().putLong(PREF_ID, ContentUris.parseId(primary)).apply()

        // Existing log: keep every old line, merge any stray copies, add the new entry.
        val lines = LinkedHashSet<String>()
        for (log in logs) {
            readText(log).lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { lines += it }
        }
        lines += entry

        // Never create a new file here: if rewriting fails the entry is skipped rather than duplicated.
        if (write(primary, lines.joinToString("\n") + "\n")) {
            for (extra in logs.drop(1)) {
                try { resolver.delete(extra, null, null) } catch (_: Exception) {}
            }
        }
    }
}
