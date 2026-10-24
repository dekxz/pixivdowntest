package com.pixivdownloader.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import java.io.File

/** Port of DownloadWorker.process_image: optional resize + format conversion. */
object ImageProcessor {

    data class Options(
        val format: OutFormat,
        val quality: Int,
        val resize: ResizeMode,
        val width: Int?,
        val height: Int?,
    )

    private fun targetSize(w: Int, h: Int, o: Options): Pair<Int, Int>? = when (o.resize) {
        ResizeMode.NONE -> null
        ResizeMode.AUTO -> {
            val t = o.width ?: return null
            when {
                w > h -> if (w > t) t to (h.toLong() * t / w).toInt() else null
                h > w -> if (h > t) (w.toLong() * t / h).toInt() to t else null
                else -> if (w > t) t to t else null
            }
        }
        ResizeMode.WIDTH -> {
            val nw = o.width ?: return null
            nw to (h.toLong() * nw / w).toInt()
        }
        ResizeMode.HEIGHT -> {
            val nh = o.height ?: return null
            (w.toLong() * nh / h).toInt() to nh
        }
        ResizeMode.BOTH -> {
            val nw = o.width ?: return null
            val nh = o.height ?: return null
            nw to nh
        }
    }

    /** Returns the file to export (the converted one, or [src] unchanged if nothing applies / on error). */
    @Suppress("DEPRECATION")
    fun process(src: File, o: Options, log: (String) -> Unit): File {
        // Like the desktop app, processing only happens when an output format other than "Original" is chosen.
        if (o.format == OutFormat.ORIGINAL) return src
        return try {
            var bmp = BitmapFactory.decodeFile(src.absolutePath) ?: error("Could not decode image.")

            targetSize(bmp.width, bmp.height, o)?.let { (nw, nh) ->
                val w = nw.coerceAtLeast(1)
                val h = nh.coerceAtLeast(1)
                if (w != bmp.width || h != bmp.height) {
                    val scaled = Bitmap.createScaledBitmap(bmp, w, h, true)
                    if (scaled !== bmp) bmp.recycle()
                    bmp = scaled
                }
            }

            val out = File(src.parentFile, "${src.nameWithoutExtension}.${o.format.ext}")
            val (cf, q) = when (o.format) {
                OutFormat.JPEG -> Bitmap.CompressFormat.JPEG to o.quality
                OutFormat.PNG -> Bitmap.CompressFormat.PNG to 100
                else -> (if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY
                         else Bitmap.CompressFormat.WEBP) to o.quality
            }
            out.outputStream().use { bmp.compress(cf, q, it) }
            bmp.recycle()

            if (out.absolutePath != src.absolutePath) src.delete()
            out
        } catch (e: OutOfMemoryError) {
            log("Image too large to process on this device; keeping the original file.")
            src
        } catch (e: Exception) {
            log("Error processing image: ${e.message}")
            src
        }
    }
}
