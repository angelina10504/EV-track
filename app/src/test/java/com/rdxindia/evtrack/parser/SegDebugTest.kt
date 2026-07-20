package com.rdxindia.evtrack.parser

import org.junit.Test

class SegDebugTest {
    private val digitW = 60
    private val digitH = 100
    private val thickness = 12
    private val margin = 20

    private val segments = mapOf(
        '1' to "BC", '8' to "ABCDEFG"
    )

    private fun render(text: String, breakC8: Boolean): Triple<IntArray, Int, Int> {
        var width = margin
        text.forEachIndexed { i, _ -> width += digitW + if (i < text.length - 1) margin else margin }
        val height = digitH + 2 * margin
        val px = IntArray(width * height) { 20 }
        fun fill(x0: Int, y0: Int, w: Int, h: Int) {
            for (y in y0 until y0 + h) for (x in x0 until x0 + w)
                if (x in 0 until width && y in 0 until height) px[y * width + x] = 230
        }
        var x = margin
        for (ch in text) {
            val y = margin
            for (seg in segments.getValue(ch)) {
                when (seg) {
                    'A' -> fill(x + thickness, y, digitW - 2 * thickness, thickness)
                    'B' -> fill(x + digitW - thickness, y, thickness, digitH / 2)
                    'C' -> fill(x + digitW - thickness, y + digitH / 2, thickness, digitH / 2)
                    'D' -> fill(x + thickness, y + digitH - thickness, digitW - 2 * thickness, thickness)
                    'E' -> fill(x, y + digitH / 2, thickness, digitH / 2)
                    'F' -> fill(x, y, thickness, digitH / 2)
                    'G' -> fill(x + thickness, y + digitH / 2 - thickness / 2, digitW - 2 * thickness, thickness)
                }
            }
            x += digitW + margin
        }
        if (breakC8) {
            val x8 = margin + (text.length - 1) * (digitW + margin)
            for (y in (margin + 68) until (margin + 82))
                for (xx in (x8 + digitW - thickness) until (x8 + digitW))
                    px[y * width + xx] = 20
        }
        return Triple(px, width, height)
    }

    @Test
    fun zoneMeansBroken() {
        analyze(render("8", breakC8 = true), "broken8")
    }

    @Test
    fun zoneMeans() {
        analyze(render("8", breakC8 = false), "intact8")
    }

    private fun analyze(rendered: Triple<IntArray, Int, Int>, tag: String) {
        val (px, w, h) = rendered
        val n = w * h
        val otsu = com.rdxindia.evtrack.util.GridFilters.otsu(px, n)
        val ink = px // bright ink already
        val enhanced = com.rdxindia.evtrack.util.GridFilters.illuminationFlatten(
            com.rdxindia.evtrack.util.GridFilters.clahe(ink, w, h), w, h, radius = maxOf(w, h) / 2
        )
        // cell = digit bbox: x margin..margin+59, y margin..margin+99
        val x0 = margin; val x1 = margin + digitW - 1
        val y0 = margin; val y1 = margin + digitH - 1
        val cw = digitW; val chh = digitH
        val zones = listOf(
            "A" to doubleArrayOf(0.20, 0.00, 0.80, 0.20),
            "F" to doubleArrayOf(0.00, 0.05, 0.25, 0.45),
            "B" to doubleArrayOf(0.75, 0.05, 1.00, 0.45),
            "G" to doubleArrayOf(0.20, 0.40, 0.80, 0.60),
            "E" to doubleArrayOf(0.00, 0.55, 0.25, 0.95),
            "C" to doubleArrayOf(0.75, 0.55, 1.00, 0.95),
            "D" to doubleArrayOf(0.20, 0.80, 0.80, 1.00)
        )
        // cell histogram threshold
        val vals = mutableListOf<Int>()
        for (y in y0..y1) for (x in x0..x1) vals += enhanced[y * w + x]
        val split = com.rdxindia.evtrack.util.GridFilters.otsu(vals.toIntArray(), vals.size)
        println("[$tag] otsuOrig=$otsu cellSplit=$split enhancedMin=${enhanced.min()} max=${enhanced.max()}")
        // Replicate read()'s foreground mask and cell derivation.
        val adaptive = com.rdxindia.evtrack.util.GridFilters.adaptiveThreshold(
            enhanced, w, h, blockSize = maxOf(15, h / 2), c = 5
        )
        val floor = com.rdxindia.evtrack.util.GridFilters.otsu(enhanced, n)
        val fg = BooleanArray(n) { adaptive[it] && enhanced[it] > floor }
        val colCounts = IntArray(w)
        for (y in 0 until h) for (x in 0 until w) if (fg[y * w + x]) colCounts[x]++
        val colNoise = maxOf(1, h / 25)
        val filledCols = (0 until w).filter { colCounts[it] > colNoise }
        var fy0 = -1; var fy1 = -1
        for (y in 0 until h) {
            var c2 = 0
            for (x in 0 until w) if (fg[y * w + x]) c2++
            if (c2 > 6) { if (fy0 < 0) fy0 = y; fy1 = y }
        }
        println("[$tag] floor=$floor fgCount=${fg.count { it }} filledCols=${filledCols.firstOrNull()}..${filledCols.lastOrNull()} nCols=${filledCols.size} rowSpan=$fy0..$fy1")
        println("colCounts around digit: " + (15..85 step 5).joinToString { "$it:${colCounts[it]}" })

        val half = maxOf(1, minOf(cw, chh) / 16)
        for ((name, z) in zones) {
            val cx = x0 + ((z[0] + z[2]) / 2 * cw).toInt()
            val cy = y0 + ((z[1] + z[3]) / 2 * chh).toInt()
            var sum = 0; var cnt = 0
            for (y in (cy - half)..(cy + half)) for (x in (cx - half)..(cx + half)) {
                if (x in x0..x1 && y in y0..y1) { sum += enhanced[y * w + x]; cnt++ }
            }
            println("[$tag] zone $name center=($cx,$cy) mean=${sum / cnt} raw=${px[cy * w + cx]}")
        }
    }

    @Test
    fun diagnose() {
        val (p1, w1, h1) = render("8", breakC8 = false)
        println("intact 8 alone -> ${SegmentDigitReader.read(p1, w1, h1)}")
        val (p2, w2, h2) = render("8", breakC8 = true)
        println("broken 8 alone -> ${SegmentDigitReader.read(p2, w2, h2)}")
        val (p3, w3, h3) = render("18", breakC8 = false)
        println("intact 18 -> ${SegmentDigitReader.read(p3, w3, h3)}")
        val (p4, w4, h4) = render("18", breakC8 = true)
        println("broken 18 -> ${SegmentDigitReader.read(p4, w4, h4)}")
    }
}
