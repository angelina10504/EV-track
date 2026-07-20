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
    fun `detectDisplay fits a near-axis-aligned quad to an upright display`() {
        val w = 200
        val h = 150
        val lum = grid(w, h)
        lum.fillRect(w, 40, 60, 100, 50, 220)

        val display = BrightRegionDetector.detectDisplay(lum, w, h)

        assertNotNull(display)
        assertTrue("upright display should read as low-skew", display!!.quad.maxEdgeSkewDegrees() < 3.0)
    }

    @Test
    fun `detectDisplay reports skew for a sheared display`() {
        // Parallelogram: top edge y=30 spans x=20..170; each row shifts right,
        // reaching x=50..200 at y=129. Left/right edges slant ~16.7°.
        val w = 300
        val h = 200
        val lum = grid(w, h)
        val shear = 30.0
        for (row in 0..99) {
            val y = 30 + row
            val offset = (shear * row / 99.0).toInt()
            for (x in (20 + offset)..(170 + offset)) lum[y * w + x] = 220
        }

        val display = BrightRegionDetector.detectDisplay(lum, w, h)

        assertNotNull(display)
        assertTrue(
            "sheared display should exceed the 5° warp threshold, was ${display!!.quad.maxEdgeSkewDegrees()}",
            display.quad.maxEdgeSkewDegrees() > 5.0
        )
        assertTrue(display.quad.maxEdgeSkewDegrees() < 25.0)
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
