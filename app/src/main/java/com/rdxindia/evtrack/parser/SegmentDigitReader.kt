package com.rdxindia.evtrack.parser

/**
 * Geometric decoder for seven-segment digits. ML Kit's Latin model was never
 * trained on segment fonts and misreads them ("18" for "00"); but a
 * seven-segment digit is fully described by which of its 7 segments are lit,
 * so a crop can be decoded directly: binarize, split into digit cells, sample
 * the 7 segment zones per cell, look the on/off pattern up.
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

        val threshold = otsuThreshold(luminance, width * height)
        val foreground = BooleanArray(width * height) { luminance[it] > threshold }
        var fgCount = foreground.count { it }
        if (fgCount == 0) return null
        if (fgCount > width * height / 2) {
            // Dark glyphs on a bright background: invert.
            for (i in foreground.indices) foreground[i] = !foreground[i]
            fgCount = width * height - fgCount
            if (fgCount == 0) return null
        }

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

            val mask = segmentMask(foreground, width, cell)
            var bestChar = ' '
            var bestDist = Int.MAX_VALUE
            for ((pattern, char) in DIGIT_PATTERNS) {
                val dist = Integer.bitCount(mask xor pattern)
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

    /** Split on empty column runs; each run of filled columns is one glyph cell. */
    private fun splitIntoCells(fg: BooleanArray, width: Int, height: Int): List<Cell>? {
        val colCounts = IntArray(width)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) if (fg[row + x]) colCounts[x]++
        }
        val colNoise = maxOf(1, height / 25)

        val ranges = mutableListOf<IntRange>()
        var start = -1
        for (x in 0 until width) {
            val filled = colCounts[x] > colNoise
            if (filled && start < 0) start = x
            if (!filled && start >= 0) {
                if (x - start >= 2) ranges += start until x
                start = -1
            }
        }
        if (start >= 0 && width - start >= 2) ranges += start until width
        if (ranges.isEmpty() || ranges.size > 8) return null

        return ranges.map { xs ->
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

    private fun segmentMask(fg: BooleanArray, width: Int, cell: Cell): Int {
        var mask = 0
        for ((bit, zone) in ZONES) {
            if (zoneFraction(fg, width, cell, zone) >= 0.30) mask = mask or bit
        }
        return mask
    }

    private fun zoneFraction(fg: BooleanArray, width: Int, cell: Cell, zone: Zone): Double {
        val ax0 = cell.x0 + (zone.x0 * cell.w).toInt()
        val ax1 = (cell.x0 + (zone.x1 * cell.w).toInt() - 1).coerceAtMost(cell.x1)
        val ay0 = cell.y0 + (zone.y0 * cell.h).toInt()
        val ay1 = (cell.y0 + (zone.y1 * cell.h).toInt() - 1).coerceAtMost(cell.y1)
        if (ax1 < ax0 || ay1 < ay0) return 0.0
        var on = 0
        var total = 0
        for (y in ay0..ay1) {
            val row = y * width
            for (x in ax0..ax1) {
                total++
                if (fg[row + x]) on++
            }
        }
        return if (total == 0) 0.0 else on.toDouble() / total
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

    private fun otsuThreshold(lum: IntArray, n: Int): Int {
        val hist = IntArray(256)
        for (i in 0 until n) hist[lum[i].coerceIn(0, 255)]++
        var sum = 0L
        for (t in 0..255) sum += t.toLong() * hist[t]
        var sumB = 0L
        var wB = 0L
        var best = 0
        var bestVar = -1.0
        for (t in 0..255) {
            wB += hist[t]
            if (wB == 0L) continue
            val wF = n - wB
            if (wF <= 0L) break
            sumB += t.toLong() * hist[t]
            val mB = sumB.toDouble() / wB
            val mF = (sum - sumB).toDouble() / wF
            val variance = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (variance > bestVar) { bestVar = variance; best = t }
        }
        return best
    }
}
