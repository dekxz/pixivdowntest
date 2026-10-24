package com.pixivdownloader.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.util.zip.ZipFile
import kotlin.math.ceil

/** Port of UgoiraProcessor: unpack the frames ZIP. */
object UgoiraProcessor {

    /** Extracts all files flat into [dir] (entry paths are ignored, which also rules out zip-slip). */
    fun extractZip(
        zip: File,
        dir: File,
        checkCancelled: () -> Unit,
        onProgress: (done: Int, total: Int) -> Unit,
    ): List<File> {
        dir.mkdirs()
        val result = mutableListOf<File>()
        ZipFile(zip).use { zf ->
            val entries = zf.entries().toList().filter { !it.isDirectory }
            entries.forEachIndexed { i, entry ->
                checkCancelled()
                val name = File(entry.name).name
                if (name.isNotEmpty()) {
                    val target = File(dir, name)
                    zf.getInputStream(entry).use { input -> target.outputStream().use { input.copyTo(it) } }
                    result += target
                }
                onProgress(i + 1, entries.size)
            }
        }
        return result
    }
}

/**
 * Port of VideoCreator, using the platform H.264 encoder instead of ffmpeg.
 *  - frames are limited to 1280x720 and padded to even dimensions (same rule as the desktop script)
 *  - every source frame is shown for (60 / contentFps) output frames, so the video is a constant 60 FPS
 *  - target bitrate 15 Mbit/s, yuv420p, MP4 container
 */
object VideoCreator {

    private const val MIME = "video/avc"
    private const val OUTPUT_FPS = 60
    private const val BITRATE = 15_000_000
    private const val MAX_W = 1280
    private const val MAX_H = 720

    private val FRAME_NAME = Regex("""^\d+\.(jpe?g|png)$""", RegexOption.IGNORE_CASE)

    fun create(
        extracted: List<File>,
        contentFps: Int,
        output: File,
        checkCancelled: () -> Unit,
        onProgress: (done: Int, total: Int, message: String) -> Unit,
    ) {
        val frames = extracted
            .filter { FRAME_NAME.matches(it.name) }
            .sortedBy { it.nameWithoutExtension.toLongOrNull() ?: 0L }
        if (frames.isEmpty()) error("No images found in the Ugoira ZIP.")

        // --- target size (same rule as VideoCreator.prepare_image) ---
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(frames[0].absolutePath, bounds)
        val srcW = bounds.outWidth
        val srcH = bounds.outHeight
        if (srcW <= 0 || srcH <= 0) error("Could not read the first frame.")
        var sw = srcW
        var sh = srcH
        if (sw > MAX_W || sh > MAX_H) {
            if (sw > sh) {
                sw = MAX_W
                sh = (srcH.toLong() * MAX_W / srcW).toInt()
            } else {
                sh = MAX_H
                sw = (srcW.toLong() * MAX_H / srcH).toInt()
            }
        }
        sw = sw.coerceAtLeast(2)
        sh = sh.coerceAtLeast(2)

        val codec = MediaCodec.createEncoderByType(MIME)
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var ok = false
        try {
            val caps = codec.codecInfo.getCapabilitiesForType(MIME).videoCapabilities
            val w = alignUp(sw, maxOf(2, caps.widthAlignment))
            val h = alignUp(sh, maxOf(2, caps.heightAlignment))
            if (!caps.isSizeSupported(w, h)) error("This device's H.264 encoder does not support ${w}x$h.")
            val bitrate = BITRATE.coerceIn(caps.bitrateRange.lower, caps.bitrateRange.upper)

            val format = MediaFormat.createVideoFormat(MIME, w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, OUTPUT_FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val mux = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mux

            // --- frame -> I420 conversion buffers ---
            val canvasBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(canvasBmp)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            val dst = Rect(0, 0, sw, sh)
            val pixels = IntArray(w * h)
            val yuv = ByteArray(w * h * 3 / 2)

            fun loadFrame(index: Int) {
                val bmp = BitmapFactory.decodeFile(frames[index].absolutePath)
                    ?: error("Could not decode ${frames[index].name}")
                canvas.drawColor(Color.BLACK)
                canvas.drawBitmap(bmp, null, dst, paint)
                bmp.recycle()
                canvasBmp.getPixels(pixels, 0, w, 0, 0, w, h)
                toI420(pixels, w, h, yuv)
            }

            val totalOut = ceil(frames.size.toDouble() * OUTPUT_FPS / contentFps).toInt()
            val info = MediaCodec.BufferInfo()
            var outIndex = 0
            var loaded = -1
            var inputDone = false
            var track = -1

            onProgress(0, frames.size, "Preparing images for video")
            loop@ while (true) {
                checkCancelled()

                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val pts = outIndex * 1_000_000L / OUTPUT_FPS
                        if (outIndex >= totalOut) {
                            codec.queueInputBuffer(inIdx, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val src = minOf(frames.size - 1, (outIndex.toLong() * contentFps / OUTPUT_FPS).toInt())
                            if (src != loaded) {
                                loadFrame(src)
                                loaded = src
                                onProgress(src + 1, frames.size, "Creating video")
                            }
                            val image = codec.getInputImage(inIdx) ?: error("Encoder returned no input image.")
                            fillImage(image, yuv, w, h)
                            codec.queueInputBuffer(inIdx, 0, yuv.size, pts, 0)
                            outIndex++
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = mux.addTrack(codec.outputFormat)
                        mux.start()
                        muxerStarted = true
                    }
                    outIdx >= 0 -> {
                        val buf = codec.getOutputBuffer(outIdx)
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (buf != null && info.size > 0 && muxerStarted) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            mux.writeSampleData(track, buf, info)
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break@loop
                    }
                }
            }
            canvasBmp.recycle()
            ok = true
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            codec.release()
            try { if (muxerStarted) muxer?.stop() } catch (_: Exception) { ok = false }
            try { muxer?.release() } catch (_: Exception) {}
            if (!ok) output.delete()
        }
    }

    private fun alignUp(v: Int, a: Int) = ((v + a - 1) / a) * a

    /** ARGB pixels -> planar I420 (BT.601, limited range), chroma averaged over 2x2 blocks. */
    private fun toI420(px: IntArray, w: Int, h: Int, out: ByteArray) {
        var yi = 0
        for (j in 0 until h) {
            val row = j * w
            for (i in 0 until w) {
                val c = px[row + i]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                out[yi++] = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).toByte()
            }
        }
        val cw = w / 2
        val ch = h / 2
        var ui = w * h
        var vi = w * h + cw * ch
        for (j in 0 until ch) {
            for (i in 0 until cw) {
                var r = 0
                var g = 0
                var b = 0
                for (dy in 0..1) {
                    for (dx in 0..1) {
                        val c = px[(2 * j + dy) * w + 2 * i + dx]
                        r += (c shr 16) and 0xFF
                        g += (c shr 8) and 0xFF
                        b += c and 0xFF
                    }
                }
                r = r shr 2
                g = g shr 2
                b = b shr 2
                out[ui++] = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).coerceIn(0, 255).toByte()
                out[vi++] = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).coerceIn(0, 255).toByte()
            }
        }
    }

    /** Copies the I420 buffer into the encoder's input image, honouring row/pixel strides. */
    private fun fillImage(image: Image, yuv: ByteArray, w: Int, h: Int) {
        val ySize = w * h
        val cSize = (w / 2) * (h / 2)
        copyPlane(image.planes[0], yuv, 0, w, h)
        copyPlane(image.planes[1], yuv, ySize, w / 2, h / 2)
        copyPlane(image.planes[2], yuv, ySize + cSize, w / 2, h / 2)
    }

    private fun copyPlane(plane: Image.Plane, src: ByteArray, srcOffset: Int, pw: Int, ph: Int) {
        val buf = plane.buffer
        val rowStride = plane.rowStride
        val pixStride = plane.pixelStride
        if (pixStride == 1 && rowStride == pw) {
            buf.position(0)
            buf.put(src, srcOffset, pw * ph)
            return
        }
        for (r in 0 until ph) {
            val base = r * rowStride
            val s = srcOffset + r * pw
            if (pixStride == 1) {
                buf.position(base)
                buf.put(src, s, pw)
            } else {
                for (c in 0 until pw) buf.put(base + c * pixStride, src[s + c])
            }
        }
    }
}
