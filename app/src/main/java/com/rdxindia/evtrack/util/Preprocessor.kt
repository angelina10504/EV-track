package com.rdxindia.evtrack.util

import android.graphics.Bitmap

/**
 * Preprocessing variants applied before OCR retry stages. Pass 1 must always
 * use [PrepVariant.ORIGINAL] — never feed binarized images to the first pass;
 * ML Kit's neural recognizer degrades on hard-thresholded input, so the
 * binary variants exist only as later rungs of the retry ladder.
 */
enum class PrepVariant {
    /** Input unchanged. */
    ORIGINAL,

    /** Grayscale → CLAHE (8×8 tiles, clip 2.0) → 2× upscale. */
    CLAHE_STRETCH,

    /**
     * Grayscale → 5×5 Gaussian → Otsu → morphological close (kernel ~12% of
     * median component height, fallback 7 px) → text dark-on-light → 2×.
     */
    MORPH_CLOSE_BIN,

    /**
     * Grayscale → divide by heavily blurred self (removes illumination
     * gradient) → adaptive threshold (block ~1/10 image height) →
     * text dark-on-light → 2×.
     */
    ILLUM_FLAT_ADAPTIVE
}

object Preprocessor {

    fun apply(src: Bitmap, variant: PrepVariant): Bitmap = when (variant) {
        PrepVariant.ORIGINAL -> src

        PrepVariant.CLAHE_STRETCH -> {
            val (lum, w, h) = luminanceOf(src)
            upscale2x(grayBitmap(GridFilters.clahe(lum, w, h, tiles = 8, clipLimit = 2.0), w, h))
        }

        PrepVariant.MORPH_CLOSE_BIN -> {
            val (lum, w, h) = luminanceOf(src)
            val blurred = GridFilters.gaussian5(lum, w, h)
            val threshold = GridFilters.otsu(blurred, w * h)
            var mask = BooleanArray(w * h) { blurred[it] > threshold }
            if (mask.count { it } > w * h / 2) {
                for (i in mask.indices) mask[i] = !mask[i]   // text must be the minority
            }
            val medianHeight = GridFilters.medianComponentHeight(mask, w, h)
            val kernel = if (medianHeight != null) maxOf(3, (medianHeight * 0.12).toInt()) else 7
            val closed = GridFilters.morphClose(mask, w, h, maxOf(1, kernel / 2))
            upscale2x(binaryBitmap(closed, w, h))
        }

        PrepVariant.ILLUM_FLAT_ADAPTIVE -> {
            val (lum, w, h) = luminanceOf(src)
            val flat = GridFilters.illuminationFlatten(lum, w, h)
            val block = ((h / 10) or 1).coerceAtLeast(15)
            var mask = GridFilters.adaptiveThreshold(flat, w, h, block, c = 5)
            if (mask.count { it } > w * h / 2) {
                for (i in mask.indices) mask[i] = !mask[i]
            }
            upscale2x(binaryBitmap(mask, w, h))
        }
    }

    private fun luminanceOf(bitmap: Bitmap): Triple<IntArray, Int, Int> {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val lum = IntArray(w * h) { i ->
            val p = pixels[i]
            (299 * ((p shr 16) and 0xFF) + 587 * ((p shr 8) and 0xFF) + 114 * (p and 0xFF)) / 1000
        }
        return Triple(lum, w, h)
    }

    private fun grayBitmap(lum: IntArray, w: Int, h: Int): Bitmap {
        val pixels = IntArray(w * h) { i ->
            val v = lum[i].coerceIn(0, 255)
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, w, 0, 0, w, h)
        }
    }

    /** Text (foreground) rendered dark on a light background. */
    private fun binaryBitmap(textMask: BooleanArray, w: Int, h: Int): Bitmap {
        val pixels = IntArray(w * h) { i ->
            val v = if (textMask[i]) 0 else 255
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, w, 0, 0, w, h)
        }
    }

    private fun upscale2x(bitmap: Bitmap): Bitmap =
        Bitmap.createScaledBitmap(bitmap, bitmap.width * 2, bitmap.height * 2, true)
}
