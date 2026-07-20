package com.rdxindia.evtrack.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GridFiltersTest {

    @Test
    fun `clahe widens a low-contrast image's tonal range`() {
        // Note: with a tight clip limit CLAHE is intentionally gentle on
        // spiky histograms; a generous limit verifies the equalization
        // machinery itself.
        val w = 256
        val h = 256
        // Faint checkerboard squeezed into 100..120.
        val lum = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            if ((x / 8 + y / 8) % 2 == 0) 120 else 100
        }
        val out = GridFilters.clahe(lum, w, h, tiles = 8, clipLimit = 100.0)

        val inRange = 120 - 100
        val outRange = out.max() - out.min()
        assertTrue("expected contrast expansion, got range $outRange", outRange > inRange * 3)
    }

    @Test
    fun `clahe maps uniform background to a low value, not to white`() {
        // Regression: a uniform tile's clipped histogram must keep its mass —
        // the old normalization sent blank background straight to 255.
        val w = 128
        val h = 128
        val lum = IntArray(w * h) { 20 }
        for (y in 40 until 80) for (x in 40 until 80) lum[y * w + x] = 230

        val out = GridFilters.clahe(lum, w, h)
        assertTrue("corner background should stay dark, got ${out[0]}", out[0] < 100)
    }

    @Test
    fun `morphological close bridges small gaps between strokes`() {
        val w = 60
        val h = 20
        val mask = BooleanArray(w * h)
        // Two horizontal bars with a 2px gap at x=28..29, y=8..11.
        for (y in 8..11) {
            for (x in 4 until 28) mask[y * w + x] = true
            for (x in 30 until 56) mask[y * w + x] = true
        }
        val closed = GridFilters.morphClose(mask, w, h, radius = 2)

        assertTrue("gap should be bridged", closed[9 * w + 28] && closed[9 * w + 29])
    }

    @Test
    fun `illumination flatten plus adaptive threshold keeps text on a gradient`() {
        val w = 120
        val h = 80
        // Strong left-to-right illumination gradient (40..200) with a bright
        // "glyph" square whose absolute value is below the far side's
        // background — a global threshold could never separate it.
        val lum = IntArray(w * h) { i ->
            val x = i % w
            40 + (x * 160) / w
        }
        // Glyph smaller than the threshold window (like a real text stroke).
        for (y in 35 until 45) for (x in 14 until 24) lum[y * w + x] += 70

        val flat = GridFilters.illuminationFlatten(lum, w, h)
        val mask = GridFilters.adaptiveThreshold(flat, w, h, blockSize = 15, c = 5)

        assertTrue("glyph center should be foreground", mask[40 * w + 18])
        assertFalse("far background should not be foreground", mask[40 * w + 100])
    }
}
