package com.rdxindia.evtrack.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentDigitReaderTest {

    // --- tiny synthetic seven-segment renderer -------------------------------

    private val digitW = 60
    private val digitH = 100
    private val thickness = 12
    private val margin = 20
    private val bg = 20
    private val fgColor = 230

    private val segments = mapOf(
        '0' to "ABCDEF", '1' to "BC", '2' to "ABGED", '3' to "ABGCD", '4' to "FGBC",
        '5' to "AFGCD", '6' to "AFGECD", '7' to "ABC", '8' to "ABCDEFG", '9' to "ABCDFG"
    )

    private class Canvas(val width: Int, val height: Int, background: Int) {
        val pixels = IntArray(width * height) { background }
        fun fill(x0: Int, y0: Int, w: Int, h: Int, value: Int) {
            for (y in y0 until y0 + h) for (x in x0 until x0 + w) {
                if (x in 0 until width && y in 0 until height) pixels[y * width + x] = value
            }
        }
    }

    /**
     * Renders [text]; [gaps] holds the gap after each character (defaults to
     * 20 px). Returns pixels + dimensions + the x offset of each character.
     */
    private fun render(
        text: String,
        invert: Boolean = false,
        gaps: List<Int>? = null
    ): Rendered {
        fun slotWidth(ch: Char) = if (ch == '.') thickness else digitW
        var width = margin
        text.forEachIndexed { i, ch ->
            width += slotWidth(ch) + if (i < text.length - 1) (gaps?.get(i) ?: margin) else margin
        }
        val height = digitH + 2 * margin
        val canvas = Canvas(width, height, if (invert) fgColor else bg)
        val ink = if (invert) bg else fgColor
        val offsets = mutableListOf<Int>()

        var x = margin
        text.forEachIndexed { i, ch ->
            offsets += x
            if (ch == '.') {
                canvas.fill(x, margin + digitH - thickness, thickness, thickness, ink)
            } else {
                val y = margin
                for (seg in segments.getValue(ch)) {
                    when (seg) {
                        'A' -> canvas.fill(x + thickness, y, digitW - 2 * thickness, thickness, ink)
                        'B' -> canvas.fill(x + digitW - thickness, y, thickness, digitH / 2, ink)
                        'C' -> canvas.fill(x + digitW - thickness, y + digitH / 2, thickness, digitH / 2, ink)
                        'D' -> canvas.fill(x + thickness, y + digitH - thickness, digitW - 2 * thickness, thickness, ink)
                        'E' -> canvas.fill(x, y + digitH / 2, thickness, digitH / 2, ink)
                        'F' -> canvas.fill(x, y, thickness, digitH / 2, ink)
                        'G' -> canvas.fill(x + thickness, y + digitH / 2 - thickness / 2, digitW - 2 * thickness, thickness, ink)
                    }
                }
            }
            x += slotWidth(ch) + if (i < text.length - 1) (gaps?.get(i) ?: margin) else margin
        }
        return Rendered(canvas.pixels, width, height, offsets)
    }

    private class Rendered(
        val pixels: IntArray,
        val width: Int,
        val height: Int,
        val charOffsets: List<Int>
    )

    // --- tests ---------------------------------------------------------------

    @Test
    fun `decodes a multi-digit odometer with uniform spacing`() {
        val r = render("12941")
        val result = SegmentDigitReader.read(r.pixels, r.width, r.height)

        assertNotNull(result)
        assertEquals("12941", result!!.text)
        assertTrue(result.confidence >= 0.9f)
    }

    @Test
    fun `decodes every digit`() {
        // 10 digits exceeds the 8-cell guard, so render in two halves.
        val r1 = render("01234")
        val r2 = render("56789")
        assertEquals("01234", SegmentDigitReader.read(r1.pixels, r1.width, r1.height)?.text)
        assertEquals("56789", SegmentDigitReader.read(r2.pixels, r2.width, r2.height)?.text)
    }

    @Test
    fun `decodes digits with irregular spacing`() {
        val r = render("1705", gaps = listOf(35, 22, 50))
        assertEquals("1705", SegmentDigitReader.read(r.pixels, r.width, r.height)?.text)
    }

    @Test
    fun `digit with one broken segment still decodes with reduced confidence`() {
        // "18" with a hole punched in the middle of the 8's segment C
        // (bottom-right vertical). The gap must not split the cell, and the
        // nearest pattern is still 8 at distance 1.
        val r = render("18")
        val x8 = r.charOffsets[1]
        for (y in (margin + 68) until (margin + 82)) {
            for (x in (x8 + digitW - thickness) until (x8 + digitW)) {
                r.pixels[y * r.width + x] = bg
            }
        }
        val result = SegmentDigitReader.read(r.pixels, r.width, r.height)

        assertNotNull(result)
        assertEquals("18", result!!.text)
        assertTrue(
            "broken segment should reduce confidence, got ${result.confidence}",
            result.confidence in 0.7f..0.95f
        )
    }

    @Test
    fun `decodes a decimal value`() {
        val r = render("0.0")
        assertEquals("0.0", SegmentDigitReader.read(r.pixels, r.width, r.height)?.text)
    }

    @Test
    fun `dot-separated thousands collapse to a plain number`() {
        val r = render("12.676")
        assertEquals("12676", SegmentDigitReader.read(r.pixels, r.width, r.height)?.text)
    }

    @Test
    fun `dark digits on a bright background are decoded via inversion`() {
        val r = render("42", invert = true)
        assertEquals("42", SegmentDigitReader.read(r.pixels, r.width, r.height)?.text)
    }

    @Test
    fun `a blank crop returns null`() {
        val blank = IntArray(100 * 40) { 20 }
        assertNull(SegmentDigitReader.read(blank, 100, 40))
    }
}
