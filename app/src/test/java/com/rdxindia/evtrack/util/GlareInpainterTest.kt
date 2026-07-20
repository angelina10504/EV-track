package com.rdxindia.evtrack.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlareInpainterTest {

    @Test
    fun `large glare region is filled with the surround median`() {
        val w = 100
        val h = 100
        val lum = IntArray(w * h) { 40 }
        for (y in 40 until 60) for (x in 40 until 60) lum[y * w + x] = 255

        val result = GlareInpainter.inpaint(lum, w, h)

        assertNotNull(result)
        assertEquals(1, result!!.regionCount)
        assertEquals(40, result.luminance[50 * w + 50])       // glare center now background
        assertTrue(result.coveredFraction in 0.03..0.05)      // 400 of 10000 px
    }

    @Test
    fun `small specular dots are left untouched`() {
        val w = 100
        val h = 100
        val lum = IntArray(w * h) { 40 }
        for (y in 10 until 12) for (x in 10 until 12) lum[y * w + x] = 255   // 4 px < 0.5%

        assertNull(GlareInpainter.inpaint(lum, w, h))
    }

    @Test
    fun `image without glare returns null`() {
        assertNull(GlareInpainter.inpaint(IntArray(50 * 50) { 100 }, 50, 50))
    }
}
