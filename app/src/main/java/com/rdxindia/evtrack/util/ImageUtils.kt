package com.rdxindia.evtrack.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File

object ImageUtils {

    private const val MAX_DIMENSION = 1920

    /** Long-edge cap for the second, high-resolution OCR retry pass. */
    const val OCR_RETRY_DIMENSION = 3400

    /**
     * Loads a bitmap from [uri], downscaled to at most [maxDimension] px on the
     * long edge and rotated upright according to its EXIF orientation.
     */
    fun loadDownscaledBitmap(
        context: Context,
        uri: Uri,
        maxDimension: Int = MAX_DIMENSION
    ): Bitmap? {
        val resolver = context.contentResolver

        // Bounds-only decode: decodeStream returns null here by design and only
        // fills outWidth/outHeight, so success is judged from those.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsStream = resolver.openInputStream(uri) ?: return null
        boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sampleSize * 2) >= maxDimension) {
            sampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val sampled = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, decodeOptions)
        } ?: return null

        val longEdge = maxOf(sampled.width, sampled.height)
        val scaled = if (longEdge > maxDimension) {
            val scale = maxDimension.toFloat() / longEdge
            Bitmap.createScaledBitmap(
                sampled,
                (sampled.width * scale).toInt().coerceAtLeast(1),
                (sampled.height * scale).toInt().coerceAtLeast(1),
                true
            )
        } else {
            sampled
        }

        return applyExifRotation(context, uri, scaled)
    }

    private fun applyExifRotation(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
        val orientation = try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f); matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f); matrix.postScale(-1f, 1f)
            }
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /**
     * Returns a 2× upscaled copy of [bitmap] for a second OCR pass (small
     * glyphs like "0%" are often below ML Kit's recognition size at 1×), or
     * null when the bitmap is already large enough that upscaling won't help.
     * The long edge is capped to keep memory in check.
     */
    fun upscaledForOcr(bitmap: Bitmap, maxLongEdge: Int = 3400): Bitmap? {
        val longEdge = maxOf(bitmap.width, bitmap.height)
        if (longEdge <= 0) return null
        val scale = minOf(2f, maxLongEdge.toFloat() / longEdge)
        if (scale <= 1.1f) return null
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt(),
            (bitmap.height * scale).toInt(),
            true
        )
    }

    /**
     * Detects the backlit display in [bitmap] and returns a cropped copy of it
     * (with margin), or null when no plausible display region is found or the
     * crop wouldn't be meaningfully smaller than the full frame.
     */
    fun cropBrightDisplay(bitmap: Bitmap): Bitmap? {
        val analysisMax = 200
        val longEdge = maxOf(bitmap.width, bitmap.height)
        if (longEdge <= 0) return null
        val scale = analysisMax.toFloat() / longEdge
        val analysis = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(16),
                (bitmap.height * scale).toInt().coerceAtLeast(16),
                true
            )
        } else {
            bitmap
        }

        val aw = analysis.width
        val ah = analysis.height
        val pixels = IntArray(aw * ah)
        analysis.getPixels(pixels, 0, aw, 0, 0, aw, ah)
        val luminance = IntArray(aw * ah) { i ->
            val p = pixels[i]
            (299 * ((p shr 16) and 0xFF) + 587 * ((p shr 8) and 0xFF) + 114 * (p and 0xFF)) / 1000
        }

        val region = BrightRegionDetector.detect(luminance, aw, ah) ?: return null

        val fx = bitmap.width.toFloat() / aw
        val fy = bitmap.height.toFloat() / ah
        val marginX = (region.width * fx * 0.10f).toInt()
        val marginY = (region.height * fy * 0.10f).toInt()
        val left = ((region.left * fx).toInt() - marginX).coerceAtLeast(0)
        val top = ((region.top * fy).toInt() - marginY).coerceAtLeast(0)
        val right = ((region.right * fx).toInt() + marginX).coerceAtMost(bitmap.width - 1)
        val bottom = ((region.bottom * fy).toInt() + marginY).coerceAtMost(bitmap.height - 1)
        val cropW = right - left + 1
        val cropH = bottom - top + 1
        if (cropW < 64 || cropH < 64) return null
        if (cropW.toLong() * cropH > bitmap.width.toLong() * bitmap.height * 85 / 100) return null

        return Bitmap.createBitmap(bitmap, left, top, cropW, cropH)
    }

    /**
     * Inpaints large blown-out glare regions (see [GlareInpainter]) and returns
     * the cleaned grayscale bitmap with region count and covered fraction, or
     * null when no qualifying glare region exists.
     */
    fun inpaintGlare(bitmap: Bitmap): Triple<Bitmap, Int, Double>? {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return null
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val luminance = IntArray(w * h) { i ->
            val p = pixels[i]
            (299 * ((p shr 16) and 0xFF) + 587 * ((p shr 8) and 0xFF) + 114 * (p and 0xFF)) / 1000
        }
        val result = GlareInpainter.inpaint(luminance, w, h) ?: return null
        val out = IntArray(w * h) { i ->
            val v = result.luminance[i].coerceIn(0, 255)
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        val cleaned = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setPixels(out, 0, w, 0, 0, w, h)
        }
        return Triple(cleaned, result.regionCount, result.coveredFraction)
    }

    /**
     * Copies the image at [uri] into app-internal storage (filesDir/readings/)
     * so gallery deletions don't break history. Returns the absolute path.
     */
    fun copyToInternalStorage(context: Context, uri: Uri, timestamp: Long): String? {
        return try {
            val dir = File(context.filesDir, "readings").apply { mkdirs() }
            val dest = File(dir, "reading_$timestamp.jpg")
            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            dest.absolutePath
        } catch (_: Exception) {
            null
        }
    }
}
