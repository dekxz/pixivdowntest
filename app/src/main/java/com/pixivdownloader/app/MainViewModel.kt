package com.pixivdownloader.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class MainViewModel(app: Application) : AndroidViewModel(app) {

    data class Msg(val title: String, val text: String)
    data class Progress(val current: Long = 0, val total: Long = 0, val text: String = "")

    private val ctx = app
    private val tokenStore = TokenStore(app)
    val history = DownloadHistory(app)

    // ---- UI state ----
    val token = MutableStateFlow(tokenStore.get())
    val tokenStatus = MutableStateFlow(statusFor(tokenStore.get()))
    val url = MutableStateFlow("")
    val contentType = MutableStateFlow(ContentType.AUTO)
    val outFormat = MutableStateFlow(OutFormat.ORIGINAL)
    val quality = MutableStateFlow(Presets.DEFAULT_QUALITY)
    val resizeMode = MutableStateFlow(ResizeMode.NONE)
    val width = MutableStateFlow("")
    val height = MutableStateFlow("")
    val speedIndex = MutableStateFlow(Presets.DEFAULT_SPEED_INDEX)
    val progress = MutableStateFlow(Progress())
    val logText = MutableStateFlow("")
    val busy = MutableStateFlow(false)
    val message = MutableStateFlow<Msg?>(null)

    private var job: Job? = null
    @Volatile private var cancelled = false
    @Volatile private var api: PixivApi? = null

    // ---- helpers ----
    private fun statusFor(saved: String) = if (saved.isEmpty()) "No PHPSESSID saved" else "PHPSESSID saved"

    private fun log(s: String) {
        logText.update { old ->
            val n = old + s + "\n"
            if (n.length > 80_000) n.takeLast(60_000) else n
        }
    }

    private fun setProgress(current: Long, total: Long, text: String) {
        progress.value = Progress(current, total, text)
    }

    private fun show(title: String, text: String) { message.value = Msg(title, text) }
    fun dismissMessage() { message.value = null }

    fun setToken(s: String) { token.value = s }
    fun setUrl(s: String) { url.value = s }
    fun setContentType(t: ContentType) { contentType.value = t }
    fun setOutFormat(f: OutFormat) { outFormat.value = f }
    fun setQuality(q: Int) { quality.value = q }
    fun setResizeMode(m: ResizeMode) { resizeMode.value = m }
    fun setWidth(s: String) { width.value = s }
    fun setHeight(s: String) { height.value = s }
    fun setSpeedIndex(i: Int) { speedIndex.value = i }

    fun saveToken() {
        if (token.value.isBlank()) { show("Input Required", "Please enter a PHPSESSID."); return }
        val clean = tokenStore.save(token.value)
        token.value = clean
        tokenStatus.value = statusFor(clean)
        show("Success", "PHPSESSID saved successfully!")
    }

    // ---- download / cancel ----
    fun onDownloadClicked() {
        if (busy.value) cancel() else startDownload()
    }

    private fun cancel() {
        cancelled = true
        log("\nCancellation requested...")
        api?.cancel()
        job?.cancel()
    }

    private fun startDownload() {
        val u = url.value.trim()
        if (u.isEmpty()) { show("Input Required", "Please enter a Pixiv URL."); return }
        val saved = tokenStore.get()
        if (saved.isEmpty()) { show("Configuration Required", "Please enter and save your PHPSESSID first."); return }

        val fmt = outFormat.value
        val rm = if (fmt != OutFormat.ORIGINAL) resizeMode.value else ResizeMode.NONE
        val w = width.value.toIntOrNull()?.takeIf { it > 0 }
        val h = height.value.toIntOrNull()?.takeIf { it > 0 }
        if (rm != ResizeMode.NONE) {
            val needW = rm == ResizeMode.AUTO || rm == ResizeMode.WIDTH || rm == ResizeMode.BOTH
            val needH = rm == ResizeMode.HEIGHT || rm == ResizeMode.BOTH
            if ((needW && w == null) || (needH && h == null)) {
                show("Input Error", "Resize dimensions must be valid numbers.")
                return
            }
        }
        val opts = ImageProcessor.Options(fmt, quality.value, rm, w, h)
        val fps = Presets.SPEEDS[speedIndex.value.coerceIn(0, Presets.SPEEDS.lastIndex)].fps
        val type = contentType.value

        cancelled = false
        logText.value = ""
        progress.value = Progress()
        busy.value = true
        job = viewModelScope.launch {
            try {
                runDownload(u, type, opts, fps, saved)
            } finally {
                busy.value = false
            }
        }
    }

    private fun identifier(isUgoira: Boolean, o: ImageProcessor.Options, fps: Int): String =
        if (isUgoira) "Ugoira|${fps}fps" else buildString {
            append("Image|").append(o.format.label)
            if (o.format != OutFormat.ORIGINAL) {
                if (o.format == OutFormat.JPEG || o.format == OutFormat.WEBP) append("|q").append(o.quality)
                if (o.resize != ResizeMode.NONE) {
                    append("|").append(o.resize.name).append(":")
                    append(o.width ?: "").append("x").append(o.height ?: "")
                }
            }
        }

    private suspend fun runDownload(rawUrl: String, type: ContentType, opts: ImageProcessor.Options, fps: Int, token: String) {
        val work = File(ctx.cacheDir, "work_${UUID.randomUUID()}").apply { mkdirs() }
        val client = PixivApi(token)
        api = client
        try {
            val id = Utils.extractArtworkId(rawUrl)
            if (id == null) {
                log("Invalid Pixiv URL. It should be either an artwork page or direct image URL.")
                setProgress(0, 0, "Download failed")
                show("Invalid URL", "Please enter a Pixiv artwork page or i.pximg.net image URL.")
                return
            }

            log("Fetching artwork info...")
            val illust = withContext(Dispatchers.IO) { client.fetchIllust(id) }
            val isUgoira = when (type) {
                ContentType.UGOIRA -> true
                ContentType.IMAGE -> false
                ContentType.AUTO -> illust.illustType == 2
            }
            if (type == ContentType.AUTO) log("Detected: ${if (isUgoira) "Ugoira animation" else "image(s)"}")
            else if (type == ContentType.IMAGE && illust.illustType == 2) {
                log("Note: this artwork is an Ugoira. Choose 'Auto-detect' or 'Ugoira Animation' to get the animation.")
            }

            val key = "https://www.pixiv.net/artworks/$id"
            val ident = identifier(isUgoira, opts, fps)
            if (history.contains(key, ident)) {
                setProgress(0, 0, "Already downloaded")
                show("Already Downloaded", "This artwork has already been downloaded with the selected format ($ident).")
                return
            }

            val folder = "${illust.title} ($id)"
            val count = if (isUgoira) downloadUgoira(client, illust, fps, folder, work)
            else downloadImages(client, illust, opts, folder, work)

            history.append(key, ident)
            setProgress(1, 1, "Download completed!")
            show("Success", "Download completed!\n$count file(s) saved to Downloads/${MediaExporter.FOLDER}.")
        } catch (e: CancellationException) {
            onCancelled()
            throw e
        } catch (e: OutOfMemoryError) {
            log("\nError: out of memory while processing this artwork.")
            setProgress(0, 0, "Download failed")
            show("Download Failed", "The device ran out of memory. Please check the log for details.")
        } catch (e: Exception) {
            if (cancelled) onCancelled()
            else {
                log("\nError: ${e.message}")
                setProgress(0, 0, "Download failed")
                show("Download Failed", "The process failed. Please check the log for details.")
            }
        } finally {
            work.deleteRecursively()
            api = null
        }
    }

    private fun onCancelled() {
        log("\nProcess cancelled by user.")
        setProgress(0, 0, "Operation Cancelled")
        show("Cancelled", "The operation was cancelled.")
    }

    private suspend fun downloadImages(
        client: PixivApi,
        illust: PixivApi.Illust,
        opts: ImageProcessor.Options,
        folder: String,
        work: File,
    ): Int = withContext(Dispatchers.IO) {
        log("\n=== Processing Image(s) ===")
        val urls = client.fetchImageUrls(illust)
        if (urls.isEmpty()) error("No images found to download.")
        log("Found ${urls.size} images to download...")

        val sub = "${MediaExporter.IMAGES_DIR}/$folder"
        val referer = "https://www.pixiv.net/artworks/${illust.id}"
        var saved = 0
        urls.forEach { imgUrl ->
            ensureActive()
            val name = imgUrl.substringBefore('?').substringAfterLast('/')
            val file = File(work, name)
            client.download(imgUrl, referer, file, { ensureActive() }) { done, total -> setProgress(done, total, name) }

            val processed = ImageProcessor.process(file, opts) { log(it) }
            val finalName = MediaExporter.uniqueName(ctx, processed.nameWithoutExtension, processed.extension, sub)
            if (MediaExporter.export(ctx, processed, finalName, sub)) {
                log("Saved: $folder/$finalName")
                saved++
            } else {
                log("ERROR: could not save $finalName")
            }
            processed.delete()
        }
        if (saved == 0) error("None of the images could be saved.")
        saved
    }

    private suspend fun downloadUgoira(
        client: PixivApi,
        illust: PixivApi.Illust,
        fps: Int,
        folder: String,
        work: File,
    ): Int = withContext(Dispatchers.IO) {
        log("\n=== Processing Ugoira Animation ===")
        val zipUrl = client.fetchUgoiraZipUrl(illust.id)

        val zip = File(work, "ugoira.zip")
        client.download(zipUrl, "https://www.pixiv.net/", zip, { ensureActive() }) { done, total ->
            setProgress(done, total, "Downloading ${illust.title}.zip")
        }

        log("\n=== Extracting ZIP ===")
        val frames = UgoiraProcessor.extractZip(zip, File(work, "frames"), { ensureActive() }) { done, total ->
            setProgress(done.toLong(), total.toLong(), "Extracting ${illust.title}")
        }
        zip.delete()
        log("Extracted ${frames.size} frames.")

        log("\n=== Creating Video ===")
        val video = File(work, "out.mp4")
        VideoCreator.create(frames, fps, video, { ensureActive() }) { done, total, msg ->
            setProgress(done.toLong(), total.toLong(), msg)
        }

        val sub = "${MediaExporter.VIDEO_DIR}/$folder"
        val finalName = MediaExporter.uniqueName(ctx, folder, "mp4", sub)
        if (!MediaExporter.export(ctx, video, finalName, sub)) error("Could not save the video.")
        log("\nProcess completed successfully!\nVideo saved to: Downloads/${MediaExporter.FOLDER}/$sub/$finalName")
        1
    }
}
