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
    private val gap = 20
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

    private fun render(text: String, invert: Boolean = false): Triple<IntArray, Int, Int> {
        val width = text.sumOf { if (it == '.') thickness + gap else digitW + gap } + gap
        val height = digitH + 2 * gap
        val canvas = Canvas(width, height, if (invert) fgColor else bg)
        val ink = if (invert) bg else fgColor
        var x = gap
        for (ch in text) {
            if (ch == '.') {
                canvas.fill(x, gap + digitH - thickness, thickness, thickness, ink)
                x += thickness + gap
                continue
            }
            val y = gap
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
            x += digitW + gap
        }
        return Triple(canvas.pixels, width, height)
    }

    // --- tests ---------------------------------------------------------------

    @Test
    fun `decodes a multi-digit odometer`() {
        val (pixels, w, h) = render("12941")
        val result = SegmentDigitReader.read(pixels, w, h)

        assertNotNull(result)
        assertEquals("12941", result!!.text)
        assertTrue(result.confidence >= 0.9f)
    }

    @Test
    fun `decodes every digit`() {
        // 10 digits exceeds the 8-cell guard, so render in two halves.
        val (p1, w1, h1) = render("01234")
        val (p2, w2, h2) = render("56789")
        assertEquals("01234", SegmentDigitReader.read(p1, w1, h1)?.text)
        assertEquals("56789", SegmentDigitReader.read(p2, w2, h2)?.text)
    }

    @Test
    fun `decodes a decimal value`() {
        val (pixels, w, h) = render("0.0")
        assertEquals("0.0", SegmentDigitReader.read(pixels, w, h)?.text)
    }

    @Test
    fun `dot-separated thousands collapse to a plain number`() {
        val (pixels, w, h) = render("12.676")
        assertEquals("12676", SegmentDigitReader.read(pixels, w, h)?.text)
    }

    @Test
    fun `dark digits on a bright background are decoded via inversion`() {
        val (pixels, w, h) = render("42", invert = true)
        assertEquals("42", SegmentDigitReader.read(pixels, w, h)?.text)
    }

    @Test
    fun `a blank crop returns null`() {
        val blank = IntArray(100 * 40) { 20 }
        assertNull(SegmentDigitReader.read(blank, 100, 40))
    }
}
