package com.rdxindia.evtrack.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CtcDecoderTest {

    // blank, "0".."9", space  → mirrors PaddleOCR's layout in miniature.
    private val charset = CtcDecoder.buildCharset((0..9).map { it.toString() })

    private fun logitsOf(vararg steps: Pair<Int, Float>): FloatArray {
        val numClasses = charset.size
        val out = FloatArray(steps.size * numClasses)
        steps.forEachIndexed { t, (index, confidence) ->
            out[t * numClasses + index] = confidence
        }
        return out
    }

    @Test
    fun `charset places blank first and space last`() {
        assertEquals("", charset.first())
        assertEquals(" ", charset.last())
        assertEquals(12, charset.size)          // 1 blank + 10 digits + 1 space
        assertEquals("0", charset[1])
    }

    @Test
    fun `collapses repeats and drops blanks`() {
        // 1 1 blank 1 2 → "112"
        val logits = logitsOf(
            2 to 0.9f, 2 to 0.9f, 0 to 0.9f, 2 to 0.9f, 3 to 0.9f
        )
        val decoded = CtcDecoder.decode(logits, 5, charset.size, charset)

        assertEquals("112", decoded.text)
        assertEquals(0.9f, decoded.confidence, 0.001f)
    }

    @Test
    fun `decodes a realistic odometer reading`() {
        // 1 2 6 7 6 with no repeats needing collapse.
        val logits = logitsOf(
            2 to 0.99f, 3 to 0.98f, 7 to 0.97f, 8 to 0.99f, 7 to 0.96f
        )
        val decoded = CtcDecoder.decode(logits, 5, charset.size, charset)

        assertEquals("12676", decoded.text)
        assertTrue(decoded.confidence > 0.9f)
    }

    @Test
    fun `all-blank output decodes to empty text with zero confidence`() {
        val logits = logitsOf(0 to 0.99f, 0 to 0.99f, 0 to 0.99f)
        val decoded = CtcDecoder.decode(logits, 3, charset.size, charset)

        assertEquals("", decoded.text)
        assertEquals(0f, decoded.confidence, 0.0001f)
    }

    @Test
    fun `undersized logit buffer is handled without crashing`() {
        val decoded = CtcDecoder.decode(FloatArray(4), 10, charset.size, charset)
        assertEquals("", decoded.text)
    }
}
