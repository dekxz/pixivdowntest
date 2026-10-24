package com.pixivdownloader.app

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Port of the PixivDownloader class: talks to Pixiv's web "ajax" endpoints using the user's PHPSESSID.
 * All methods block; call them from Dispatchers.IO.
 */
class PixivApi(phpsessid: String) {

    /** illustType: 0 = illustration, 1 = manga, 2 = ugoira. */
    data class Illust(val id: String, val title: String, val illustType: Int, val singleUrl: String?)

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val cookie = "PHPSESSID=$phpsessid"

    @Volatile private var current: Call? = null

    /** Aborts the request that is currently running (its blocking read then throws IOException). */
    fun cancel() { current?.cancel() }

    private fun JSONObject.str(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

    private fun getJson(url: String): JSONObject {
        val req = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", "https://www.pixiv.net/")
            .header("Accept", "application/json")
            .header("Cookie", cookie)
            .build()
        val call = client.newCall(req).also { current = it }
        call.execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                error(
                    when (resp.code) {
                        404 -> "Artwork not found (HTTP 404). It may be deleted or private."
                        401, 403 -> "Access denied (HTTP ${resp.code}). Check your PHPSESSID."
                        else -> "HTTP ${resp.code} from Pixiv."
                    }
                )
            }
            val json = try { JSONObject(body) } catch (e: Exception) { error("Unexpected (non-JSON) response from Pixiv.") }
            if (json.optBoolean("error", false)) {
                error(json.optString("message").ifBlank { "Pixiv returned an error." })
            }
            return json
        }
    }

    /** Title + type + (fallback) single image URL. */
    fun fetchIllust(id: String): Illust {
        val body = getJson("https://www.pixiv.net/ajax/illust/$id").optJSONObject("body")
            ?: error("Failed to fetch image metadata.")
        return Illust(
            id = id,
            title = Utils.sanitizeName(body.str("title"), "Pixiv_$id"),
            illustType = body.optInt("illustType", 0),
            singleUrl = body.optJSONObject("urls")?.str("original"),
        )
    }

    /** All pages of the artwork (original quality); falls back to the single URL like the desktop script. */
    fun fetchImageUrls(illust: Illust): List<String> {
        val pages = try {
            getJson("https://www.pixiv.net/ajax/illust/${illust.id}/pages").optJSONArray("body")
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (pages != null) {
            val urls = (0 until pages.length()).mapNotNull {
                pages.optJSONObject(it)?.optJSONObject("urls")?.str("original")
            }
            if (urls.isNotEmpty()) return urls
        }
        return listOfNotNull(illust.singleUrl)
    }

    fun fetchUgoiraZipUrl(id: String): String {
        val body = getJson("https://www.pixiv.net/ajax/illust/$id/ugoira_meta").optJSONObject("body")
        return body?.str("originalSrc") ?: error("Failed to get Ugoira ZIP URL.")
    }

    /**
     * Streams [url] into [target]. The session cookie is deliberately NOT sent to the image CDN;
     * i.pximg.net only checks the Referer.
     */
    fun download(
        url: String,
        referer: String,
        target: File,
        checkCancelled: () -> Unit,
        onProgress: (done: Long, total: Long) -> Unit,
    ) {
        val req = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", referer)
            .build()
        val call = client.newCall(req).also { current = it }
        call.execute().use { resp ->
            if (!resp.isSuccessful) error("Download failed (HTTP ${resp.code}).")
            val body = resp.body ?: error("Empty response from server.")
            val total = body.contentLength().coerceAtLeast(0L)
            var done = 0L
            body.byteStream().use { input ->
                target.outputStream().use { out ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        checkCancelled()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            }
            onProgress(done, if (total > 0) total else done)
        }
    }

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}
