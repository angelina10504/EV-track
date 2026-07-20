package com.rdxindia.evtrack.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class QuadFitterTest {

    private fun pt(x: Double, y: Double) = QuadFitter.Pt(x, y)

    @Test
    fun `axis-aligned rectangle has near-zero skew and is not degenerate`() {
        val q = QuadFitter.Quad(pt(0.0, 0.0), pt(100.0, 0.0), pt(100.0, 50.0), pt(0.0, 50.0))

        assertTrue(q.maxEdgeSkewDegrees() < 0.5)
        assertFalse(q.isDegenerate())
        assertEquals(100.0, q.avgWidth(), 0.01)
        assertEquals(50.0, q.avgHeight(), 0.01)
    }

    @Test
    fun `rotated rectangle reports skew near the rotation angle`() {
        val theta = Math.toRadians(15.0)
        fun rot(x: Double, y: Double) = pt(x * cos(theta) - y * sin(theta), x * sin(theta) + y * cos(theta))
        // Corners of a 120x60 rect rotated 15° about the origin.
        val q = QuadFitter.Quad(rot(0.0, 0.0), rot(120.0, 0.0), rot(120.0, 60.0), rot(0.0, 60.0))

        assertEquals(15.0, q.maxEdgeSkewDegrees(), 0.5)
    }

    @Test
    fun `perspective trapezoid is detected as skewed`() {
        // Keystone: narrower at the top than the bottom.
        val q = QuadFitter.Quad(pt(20.0, 0.0), pt(100.0, 0.0), pt(120.0, 60.0), pt(0.0, 60.0))

        assertTrue("trapezoid slanted edges should exceed 5°", q.maxEdgeSkewDegrees() > 5.0)
        assertFalse(q.isDegenerate())
    }

    @Test
    fun `diamond quad covering under 60 percent of its bbox is degenerate`() {
        // Rotated-square diamond: area 5000, bbox 100x100 → fill 0.5.
        val q = QuadFitter.Quad(pt(50.0, 0.0), pt(100.0, 50.0), pt(50.0, 100.0), pt(0.0, 50.0))

        assertTrue(q.isDegenerate())
    }

    @Test
    fun `fit recovers the corners of a filled rectangle mask`() {
        val w = 120
        val h = 80
        val mask = BooleanArray(w * h)
        for (y in 10..60) for (x in 20..100) mask[y * w + x] = true

        val q = QuadFitter.fit(mask, w, h)

        assertNotNull(q)
        assertEquals(20.0, q!!.tl.x, 1.0)
        assertEquals(10.0, q.tl.y, 1.0)
        assertEquals(100.0, q.br.x, 1.0)
        assertEquals(60.0, q.br.y, 1.0)
        assertTrue(q.maxEdgeSkewDegrees() < 1.0)
    }
}
