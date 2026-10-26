package com.pixivdownloader.app

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns

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
 * "Pick existing history file": because Android only lets an app touch files it created, the user can
 * choose any existing .log file with the system file picker. The app keeps the (persistable) permission,
 * and from then on reads and updates that exact file instead of the default one.
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
        private const val PREF_PICKED = "picked_uri"
    }

    private val resolver get() = context.contentResolver
    private val prefs = context.getSharedPreferences("pixiv_history", Context.MODE_PRIVATE)
    private val folderPath = "${Environment.DIRECTORY_DOWNLOADS}/${MediaExporter.FOLDER}"

    init {
        migrateLegacyName()
    }

    private fun uriFor(id: Long): Uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)

    /** The history file the user picked with the file picker, if it is still reachable. */
    private fun picked(): Uri? {
        val uri = prefs.getString(PREF_PICKED, null)?.let { Uri.parse(it) } ?: return null
        return try {
            if (resolver.openInputStream(uri)?.use { true } == true) uri else null
        } catch (_: Exception) {
            null
        }
    }

    /** The picked file if there is one, otherwise the app's own log(s). */
    private fun allLogs(): List<Uri> = picked()?.let { listOf(it) } ?: defaultLogs()

    /** All history files the app created in our folder, oldest first. */
    private fun defaultLogs(): List<Uri> {
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
            val primary = defaultLogs().firstOrNull() ?: return
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

    /** Like [readText] but returns null when the file could not be read (so we never overwrite blindly). */
    private fun readTextOrNull(uri: Uri): String? =
        try {
            resolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: Exception) {
            null
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
        val entry = "$url|$identifier"

        val pickedFile = picked()
        if (pickedFile != null) {
            appendToPicked(pickedFile, entry)
            return
        }

        val logs = defaultLogs()
        val primary = logs.firstOrNull()

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

    // ---------------------------------------------------------------------------------------
    // Pick existing history file
    // ---------------------------------------------------------------------------------------

    data class PickResult(val ok: Boolean, val message: String)

    /** True when a user-picked file is in use. */
    fun isPicked(): Boolean = picked() != null

    private fun displayName(uri: Uri): String? =
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (_: Exception) {
            null
        }

    /** Short description of which log is in use, for the UI. */
    fun label(): String =
        picked()?.let { "History file: ${displayName(it) ?: "selected file"}" }
            ?: "History file: ${NAME} (default, Downloads/${MediaExporter.FOLDER})"

    /** Appends one line to a picked file. Existing content is never rewritten unless appending is unsupported. */
    private fun appendToPicked(pickedFile: Uri, entry: String) {
        val text = readTextOrNull(pickedFile) ?: return // can't read it -> don't risk damaging it
        if (text.lines().any { it.trim() == entry }) return
        val bytes = ((if (text.isNotEmpty() && !text.endsWith("\n")) "\n" else "") + entry + "\n")
            .toByteArray(Charsets.UTF_8)
        try {
            // append mode leaves everything already in the file untouched
            if (resolver.openOutputStream(pickedFile, "wa")?.use { it.write(bytes) } != null) return
        } catch (_: Exception) {}
        // Provider without append support: rewrite the whole file (we already hold its full text).
        val full = text + (if (text.isNotEmpty() && !text.endsWith("\n")) "\n" else "") + entry + "\n"
        write(pickedFile, full)
    }

    /**
     * Starts using [uri] (chosen with ACTION_OPEN_DOCUMENT) as the history file.
     * Keeps the access permission across restarts and copies over any entries of the app's own log
     * that the picked file does not have yet, so nothing is lost.
     */
    @Synchronized
    fun usePickedFile(uri: Uri): PickResult {
        // keep access after the app is closed / the phone is restarted
        try {
            resolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: Exception) {
            return PickResult(false, "Android did not allow read and write access to that file.\nPlease choose a file from a location that allows editing (e.g. Downloads).")
        }

        val text = readTextOrNull(uri)
            ?: return PickResult(false, "That file could not be read.")
        try {
            // open for appending without writing anything: proves we can modify it
            resolver.openOutputStream(uri, "wa")?.use { } ?: return PickResult(false, "That file could not be opened for writing.")
        } catch (e: Exception) {
            return PickResult(false, "That file could not be opened for writing.")
        }

        // bring over entries from the app's own log (if any) that the picked file lacks
        val existing = text.lines().map { it.trim() }.filter { it.isNotEmpty() }.toHashSet()
        val missing = LinkedHashSet<String>()
        for (log in defaultLogs()) {
            readText(log).lines().map { it.trim() }.filter { it.isNotEmpty() && it !in existing }.forEach { missing += it }
        }
        var merged = 0
        if (missing.isNotEmpty()) {
            val bytes = ((if (text.isNotEmpty() && !text.endsWith("\n")) "\n" else "") + missing.joinToString("\n") + "\n")
                .toByteArray(Charsets.UTF_8)
            val ok = try {
                resolver.openOutputStream(uri, "wa")?.use { it.write(bytes) } != null
            } catch (_: Exception) { false }
            if (ok) merged = missing.size
        }

        // release the permission of a previously picked file
        prefs.getString(PREF_PICKED, null)?.let { Uri.parse(it) }?.takeIf { it != uri }?.let { old ->
            try {
                resolver.releasePersistableUriPermission(
                    old, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) {}
        }
        prefs.edit().putString(PREF_PICKED, uri.toString()).apply()

        val name = displayName(uri) ?: "the selected file"
        val entries = existing.size + merged
        return PickResult(
            true,
            "Now using \"$name\" as the history log ($entries entries).\n" +
                if (merged > 0) "$merged entries from the app's previous log were added to it." else "Downloads will be checked against it and added to it."
        )
    }

    /** Goes back to the app's own log in Downloads/PixivDownloader. The picked file is left as it is. */
    @Synchronized
    fun useDefault() {
        prefs.getString(PREF_PICKED, null)?.let { Uri.parse(it) }?.let { old ->
            try {
                resolver.releasePersistableUriPermission(
                    old, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) {}
        }
        prefs.edit().remove(PREF_PICKED).apply()
    }
}
