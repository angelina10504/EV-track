package com.rdxindia.evtrack.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DbPostProcessorTest {

    private fun blankMap(w: Int, h: Int) = FloatArray(w * h)

    private fun FloatArray.fillRect(w: Int, x0: Int, y0: Int, x1: Int, y1: Int, v: Float) {
        for (y in y0..y1) for (x in x0..x1) this[y * w + x] = v
    }

    @Test
    fun `finds one box per text blob and unclips it outward`() {
        val w = 200
        val h = 100
        val prob = blankMap(w, h)
        prob.fillRect(w, 40, 40, 79, 59, 0.95f)   // a 40x20 "text" blob

        val boxes = DbPostProcessor.extract(prob, w, h)

        assertEquals(1, boxes.size)
        val b = boxes[0].bounds()
        // Unclip expands by area*ratio/perimeter = (40*20*1.5)/120 = 10 px.
        assertTrue("expected left edge expanded past 40, was ${b[0]}", b[0] < 40)
        assertTrue("expected right edge expanded past 79, was ${b[2]}", b[2] > 79)
        assertTrue(boxes[0].score > 0.9f)
    }

    @Test
    fun `low-confidence blob is rejected by the box threshold`() {
        val w = 120
        val h = 80
        val prob = blankMap(w, h)
        // Above the 0.3 binarization threshold but below the 0.6 box threshold.
        prob.fillRect(w, 30, 30, 69, 49, 0.45f)

        assertTrue(DbPostProcessor.extract(prob, w, h).isEmpty())
    }

    @Test
    fun `separate blobs yield separate boxes and scale to source coordinates`() {
        val w = 200
        val h = 100
        val prob = blankMap(w, h)
        prob.fillRect(w, 20, 20, 59, 39, 0.9f)
        prob.fillRect(w, 120, 60, 159, 79, 0.9f)

        val boxes = DbPostProcessor.extract(prob, w, h, scaleX = 2.0, scaleY = 3.0)

        assertEquals(2, boxes.size)
        // With scaleX=2 every x coordinate must land beyond the map width.
        val maxX = boxes.flatMap { it.points }.maxOf { it.x }
        assertTrue("coordinates should be scaled to source space", maxX > 200)
    }

    @Test
    fun `empty probability map yields no boxes`() {
        assertTrue(DbPostProcessor.extract(blankMap(50, 50), 50, 50).isEmpty())
    }

    @Test
    fun `convex hull of a filled square is its four corners`() {
        val pts = buildList {
            for (y in 0..10) for (x in 0..10) add(DbPostProcessor.P(x.toDouble(), y.toDouble()))
        }
        val hull = DbPostProcessor.convexHull(pts)

        assertEquals(4, hull.size)
        assertEquals(100.0, DbPostProcessor.polygonArea(hull), 0.001)
    }

    @Test
    fun `orderCorners returns top-left first and bottom-left last`() {
        val scrambled = listOf(
            DbPostProcessor.P(10.0, 50.0),   // bottom-left
            DbPostProcessor.P(60.0, 12.0),   // top-right
            DbPostProcessor.P(8.0, 10.0),    // top-left
            DbPostProcessor.P(62.0, 52.0)    // bottom-right
        )
        val ordered = DbPostProcessor.orderCorners(scrambled)

        assertEquals(8.0, ordered[0].x, 0.001)   // tl
        assertEquals(60.0, ordered[1].x, 0.001)  // tr
        assertEquals(62.0, ordered[2].x, 0.001)  // br
        assertEquals(10.0, ordered[3].x, 0.001)  // bl
    }
}
