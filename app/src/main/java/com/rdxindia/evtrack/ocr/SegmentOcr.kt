package com.rdxindia.evtrack.ocr

import android.graphics.Bitmap
import com.rdxindia.evtrack.parser.OcrLine
import com.rdxindia.evtrack.parser.SegmentDigitReader

/**
 * Runs the seven-segment decoder on the bitmap region of an OCR line.
 * ML Kit locates the digit lines reliably but misreads segment glyphs;
 * its bounding boxes serve as region proposals for the geometric decoder.
 */
object SegmentOcr {

    fun readLineBox(bitmap: Bitmap, line: OcrLine): SegmentDigitReader.Result? {
        val padX = ((line.right - line.left) * 0.06).toInt()
        val padY = ((line.bottom - line.top) * 0.12).toInt()
        return readRegion(
            bitmap,
            line.left - padX, line.top - padY, line.right + padX, line.bottom + padY
        )
    }

    /**
     * Decodes the region where a label's value should sit — directly below the
     * anchor box (slightly widened; values are not always left-aligned with
     * their label). Used when OCR produced no line at all for the value.
     */
    fun readValueRegionBelow(bitmap: Bitmap, anchor: OcrLine): SegmentDigitReader.Result? {
        val anchorW = anchor.right - anchor.left
        val anchorH = anchor.bottom - anchor.top
        if (anchorW <= 0 || anchorH <= 0) return null
        return readRegion(
            bitmap,
            anchor.left - (anchorW * 0.3).toInt(),
            anchor.bottom,
            anchor.right + (anchorW * 0.6).toInt(),
            anchor.bottom + (anchorH * 2.4).toInt()
        )
    }

    private fun readRegion(bitmap: Bitmap, l: Int, t: Int, r: Int, b: Int): SegmentDigitReader.Result? {
        val left = l.coerceAtLeast(0)
        val top = t.coerceAtLeast(0)
        val right = r.coerceAtMost(bitmap.width)
        val bottom = b.coerceAtMost(bitmap.height)
        val width = right - left
        val height = bottom - top
        if (width < 8 || height < 8) return null

        val pixels = IntArray(width * height)
        try {
            bitmap.getPixels(pixels, 0, width, left, top, width, height)
        } catch (_: Exception) {
            return null
        }
        val luminance = IntArray(width * height) { i ->
            val p = pixels[i]
            (299 * ((p shr 16) and 0xFF) + 587 * ((p shr 8) and 0xFF) + 114 * (p and 0xFF)) / 1000
        }
        return SegmentDigitReader.read(luminance, width, height)
    }
}
