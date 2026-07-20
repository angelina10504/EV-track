package com.rdxindia.evtrack.util

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrightRegionDetectorTest {

    private fun grid(width: Int, height: Int, background: Int = 25): IntArray =
        IntArray(width * height) { background }

    private fun IntArray.fillRect(width: Int, x0: Int, y0: Int, w: Int, h: Int, value: Int) {
        for (y in y0 until y0 + h) for (x in x0 until x0 + w) this[y * width + x] = value
    }

    @Test
    fun `finds a bright display rectangle in a dark frame`() {
        val w = 200
        val h = 150
        val lum = grid(w, h)
        lum.fillRect(w, 40, 60, 100, 50, 220)      // the display
        lum.fillRect(w, 5, 5, 4, 4, 240)           // a small glare speck

        val region = BrightRegionDetector.detect(lum, w, h)

        assertNotNull(region)
        assertTrue(region!!.left in 35..45)
        assertTrue(region.top in 55..65)
        assertTrue(region.width in 95..105)
        assertTrue(region.height in 45..55)
    }

    @Test
    fun `ignores a bright but sparse scattered region`() {
        val w = 200
        val h = 150
        val lum = grid(w, h)
        // Scattered bright dots (e.g. background railing highlights): each is a
        // separate tiny component — none is a plausible display.
        for (y in 10 until 60 step 6) for (x in 10 until 180 step 6) {
            lum.fillRect(w, x, y, 2, 2, 230)
        }

        assertNull(BrightRegionDetector.detect(lum, w, h))
    }

    @Test
    fun `rejects a tall narrow bright region`() {
        val w = 200
        val h = 150
        val lum = grid(w, h)
        lum.fillRect(w, 90, 10, 20, 130, 220)      // aspect ~0.15: not a display

        assertNull(BrightRegionDetector.detect(lum, w, h))
    }

    @Test
    fun `uniform image returns null`() {
        assertNull(BrightRegionDetector.detect(grid(100, 100), 100, 100))
    }
}
