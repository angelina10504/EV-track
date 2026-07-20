package com.rdxindia.evtrack.util

import org.junit.Assert.assertTrue
import org.junit.Test

class SharpnessMeterTest {

    @Test
    fun `sharp checkerboard scores far above the blur threshold`() {
        val w = 100
        val h = 100
        val lum = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            if ((x / 4 + y / 4) % 2 == 0) 230 else 20
        }
        assertTrue(SharpnessMeter.score(lum, w, h) > SharpnessMeter.BLURRY_BELOW * 10)
    }

    @Test
    fun `smooth gradient scores below the blur threshold`() {
        val w = 100
        val h = 100
        val lum = IntArray(w * h) { i -> ((i % w) * 255) / w }
        assertTrue(SharpnessMeter.score(lum, w, h) < SharpnessMeter.BLURRY_BELOW)
    }

    @Test
    fun `uniform image scores zero`() {
        val lum = IntArray(50 * 50) { 128 }
        assertTrue(SharpnessMeter.score(lum, 50, 50) == 0.0)
    }
}
