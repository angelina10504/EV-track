package com.rdxindia.evtrack.parser

import com.rdxindia.evtrack.util.GridFilters

/**
 * Geometric decoder for seven-segment digits. ML Kit's Latin model was never
 * trained on segment fonts and misreads them ("18" for "00"); but a
 * seven-segment digit is fully described by which of its 7 segments are lit,
 * so a crop can be decoded directly.
 *
 * Pipeline: normalize polarity (ink bright) → CLAHE → illumination
 * flattening → adaptive threshold → digit-cell splitting with variable-gap
 * tolerance → per-cell zone sampling against the cell's own histogram.
 *
 * Pure Kotlin over a luminance grid — no Android dependencies, JVM-testable.
 */
object SegmentDigitReader {

    data class Result(val text: String, val confidence: Float)

    private const val A = 1
    private const val B = 2
    private const val C = 4
    private const val D = 8
    private const val E = 16
    private const val F = 32
    private const val G = 64

    private val DIGIT_PATTERNS = mapOf(
        (A or B or C or D or E or F) to '0',
        (B or C) to '1',
        (A or B or G or E or D) to '2',
        (A or B or G or C or D) to '3',
        (F or G or B or C) to '4',
        (A or F or G or C or D) to '5',
        (A or F or G or E or C or D) to '6',
        (A or B or C) to '7',
        (A or B or C or D or E or F or G) to '8',
        (A or B or C or D or F or G) to '9'
    )

    // Sampling zone per segment, as fractions of the digit cell (x0, y0, x1, y1).
    private val ZONES = listOf(
        A to Zone(0.20, 0.00, 0.80, 0.20),
        F to Zone(0.00, 0.05, 0.25, 0.45),
        B to Zone(0.75, 0.05, 1.00, 0.45),
        G to Zone(0.20, 0.40, 0.80, 0.60),
        E to Zone(0.00, 0.55, 0.25, 0.95),
        C to Zone(0.75, 0.55, 1.00, 0.95),
        D to Zone(0.20, 0.80, 0.80, 1.00)
    )

    private data class Zone(val x0: Double, val y0: Double, val x1: Double, val y1: Double)

    private class Cell(val x0: Int, val x1: Int, val y0: Int, val y1: Int) {
        val w get() = x1 - x0 + 1
        val h get() = y1 - y0 + 1
    }

    /**
     * Decodes [luminance] (row-major, 0–255, [width]×[height]) as a run of
     * seven-segment digits. Returns null when the crop doesn't decode cleanly —
     * the caller should then keep whatever OCR produced.
     */
    fun read(luminance: IntArray, width: Int, height: Int): Result? {
        if (width < 8 || height < 8 || luminance.size < width * height) return null
        val n = width * height

        // Polarity: ink must be bright before enhancement — adaptive
        // thresholding only finds "brighter than surroundings".
        val otsu = GridFilters.otsu(luminance, n)
        var bright = 0
        for (i in 0 until n) if (luminance[i] > otsu) bright++
        if (bright == 0 || bright == n) return null
        val ink = if (bright > n / 2) IntArray(n) { 255 - luminance[it] } else luminance

        // CLAHE, then illumination flattening, then adaptive threshold. The
        // flatten radius must exceed a glyph's size, or the background
        // estimate tracks the strokes and erases their own contrast.
        val enhanced = GridFilters.illuminationFlatten(
            GridFilters.clahe(ink, width, height), width, height,
            radius = maxOf(width, height) / 2
        )
        // Adaptive threshold handles residual unevenness; the global Otsu
        // floor (legitimate post-flattening) stops border-gradient artifacts
        // from becoming phantom foreground.
        val adaptive = GridFilters.adaptiveThreshold(
            enhanced, width, height, blockSize = maxOf(15, height / 2), c = 5
        )
        val floor = GridFilters.otsu(enhanced, n)
        val foreground = BooleanArray(n) { adaptive[it] && enhanced[it] > floor }
        if (foreground.none { it }) return null

        val cells = splitIntoCells(foreground, width, height) ?: return null

        val maxH = cells.maxOf { it.h }
        val globalMidY = (cells.minOf { it.y0 } + cells.maxOf { it.y1 }) / 2

        val out = StringBuilder()
        var digitCount = 0
        var confidence = 1f

        for (cell in cells) {
            // Small low blob between digits: decimal point / thousands separator.
            if (cell.h < 0.4 * maxH) {
                if (cell.y0 >= globalMidY && out.isNotEmpty()) {
                    out.append('.')
                    continue
                }
                if (digitCount >= 2) { confidence = minOf(confidence, 0.85f); break }
                return null
            }
            // Narrow full-height cell: the digit "1" (only its right segments).
            if (cell.w < 0.4 * cell.h) {
                if (cellFillFraction(foreground, width, cell) < 0.3) {
                    if (digitCount >= 2) { confidence = minOf(confidence, 0.85f); break }
                    return null
                }
                out.append('1')
                digitCount++
                continue
            }

            val threshold = cellThresholdOf(enhanced, width, cell)
            val mask = segmentMask(enhanced, width, cell, threshold)
            var bestChar = ' '
            var bestDist = Int.MAX_VALUE
            for ((pattern, char) in DIGIT_PATTERNS) {
                // Asymmetric cost: a lit segment sampling dark (broken or dim)
                // is far more likely than a dark region sampling lit, so a
                // missing segment costs 1 and a phantom extra costs 2. This
                // resolves ties like 8-minus-C, which is one-missing from 8
                // but one-extra from 2.
                val missing = Integer.bitCount(pattern and mask.inv())
                val extra = Integer.bitCount(mask and pattern.inv())
                val dist = missing + 2 * extra
                if (dist < bestDist) { bestDist = dist; bestChar = char }
            }
            when {
                bestDist == 0 -> { out.append(bestChar); digitCount++ }
                bestDist == 1 -> {
                    out.append(bestChar); digitCount++
                    confidence = minOf(confidence, 0.8f)
                }
                // Undecodable cell after ≥2 digits: trailing unit/percent glyphs.
                digitCount >= 2 -> { confidence = minOf(confidence, 0.85f); break }
                else -> return null
            }
        }

        if (digitCount == 0) return null
        return Result(normalizeSeparators(out.toString()), confidence)
    }

    /**
     * Split on empty column runs — but only runs wider than 30% of the median
     * glyph width count as separators. Narrower gaps (a digit split in two by
     * a broken segment) are merged back into one cell.
     */
    private fun splitIntoCells(fg: BooleanArray, width: Int, height: Int): List<Cell>? {
        val colCounts = IntArray(width)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) if (fg[row + x]) colCounts[x]++
        }
        val colNoise = maxOf(1, height / 25)

        val runs = mutableListOf<IntRange>()
        var start = -1
        for (x in 0 until width) {
            val filled = colCounts[x] > colNoise
            if (filled && start < 0) start = x
            if (!filled && start >= 0) {
                if (x - start >= 2) runs += start until x
                start = -1
            }
        }
        if (start >= 0 && width - start >= 2) runs += start until width
        if (runs.isEmpty() || runs.size > 16) return null

        val medianWidth = runs.map { it.count() }.sorted()[runs.size / 2]
        val gapThreshold = maxOf(1, (medianWidth * 0.30).toInt())

        val merged = mutableListOf<IntRange>()
        for (run in runs) {
            val last = merged.lastOrNull()
            if (last != null && (run.first - last.last - 1) <= gapThreshold) {
                merged[merged.size - 1] = last.first..run.last
            } else {
                merged += run
            }
        }
        if (merged.size > 8) return null

        return merged.map { xs ->
            val rowNoise = maxOf(1, xs.count() / 10)
            var y0 = -1
            var y1 = -1
            for (y in 0 until height) {
                var count = 0
                val row = y * width
                for (x in xs) if (fg[row + x]) count++
                if (count > rowNoise) {
                    if (y0 < 0) y0 = y
                    y1 = y
                }
            }
            if (y0 < 0) return null
            Cell(xs.first, xs.last, y0, y1)
        }
    }

    /**
     * Threshold for one cell: the midpoint between the cell's dark and bright
     * histogram modes — not a global constant.
     */
    private fun cellThresholdOf(gray: IntArray, width: Int, cell: Cell): Int {
        val hist = IntArray(256)
        var count = 0
        for (y in cell.y0..cell.y1) {
            val row = y * width
            for (x in cell.x0..cell.x1) {
                hist[gray[row + x].coerceIn(0, 255)]++
                count++
            }
        }
        if (count == 0) return 128
        val values = IntArray(count)
        var vi = 0
        for (v in 0..255) repeat(hist[v]) { values[vi++] = v }
        val split = GridFilters.otsu(values, count)

        var darkMode = 0
        var darkBest = -1
        for (v in 0..split) if (hist[v] > darkBest) { darkBest = hist[v]; darkMode = v }
        var brightMode = 255
        var brightBest = -1
        for (v in (split + 1)..255) if (hist[v] > brightBest) { brightBest = hist[v]; brightMode = v }
        if (brightBest <= 0) return split
        return (darkMode + brightMode) / 2
    }

    /**
     * Mean gray over a small window (~4×4, scaled to cell size) at each zone's
     * center, compared against the cell's own [threshold].
     */
    private fun segmentMask(gray: IntArray, width: Int, cell: Cell, threshold: Int): Int {
        val half = maxOf(1, minOf(cell.w, cell.h) / 16)
        var mask = 0
        for ((bit, zone) in ZONES) {
            val cx = cell.x0 + ((zone.x0 + zone.x1) / 2 * cell.w).toInt()
            val cy = cell.y0 + ((zone.y0 + zone.y1) / 2 * cell.h).toInt()
            var sum = 0
            var count = 0
            for (y in (cy - half)..(cy + half)) {
                if (y < cell.y0 || y > cell.y1) continue
                val row = y * width
                for (x in (cx - half)..(cx + half)) {
                    if (x < cell.x0 || x > cell.x1) continue
                    sum += gray[row + x]
                    count++
                }
            }
            if (count > 0 && sum / count > threshold) mask = mask or bit
        }
        return mask
    }

    private fun cellFillFraction(fg: BooleanArray, width: Int, cell: Cell): Double {
        var on = 0
        for (y in cell.y0..cell.y1) {
            val row = y * width
            for (x in cell.x0..cell.x1) if (fg[row + x]) on++
        }
        return on.toDouble() / (cell.w * cell.h)
    }

    /**
     * "12.676" (3 digits after the dot) is a thousands separator → 12676;
     * "0.0" / "123.4" are decimals and stay as-is for the field parsers.
     */
    private fun normalizeSeparators(raw: String): String {
        if (!raw.contains('.')) return raw
        val lastDot = raw.lastIndexOf('.')
        val digitsAfter = raw.length - lastDot - 1
        return if (digitsAfter == 3) {
            raw.replace(".", "")
        } else {
            raw.substring(0, lastDot).replace(".", "") + raw.substring(lastDot)
        }
    }
}
