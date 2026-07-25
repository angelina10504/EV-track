package com.rdxindia.evtrack.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnsembleMergerTest {

    private fun line(text: String, confidence: Float? = null) =
        OcrLine(text, 0, 0, 100, 20, confidence)

    private fun extraction(
        odo: Int? = null,
        battery: Int? = null,
        range: Int? = null,
        lines: List<OcrLine> = emptyList()
    ) = ExtractionResult(odo, battery, range, lines, emptyList())

    private fun mlkit(
        odo: Int? = null, battery: Int? = null, range: Int? = null,
        lines: List<OcrLine> = emptyList()
    ) = EngineExtraction("mlkit", extraction(odo, battery, range, lines))

    private fun paddle(
        odo: Int? = null, battery: Int? = null, range: Int? = null,
        lines: List<OcrLine> = emptyList()
    ) = EngineExtraction("paddle", extraction(odo, battery, range, lines))

    // --- case 1: both engines agree -----------------------------------------

    @Test
    fun `agreement on every field is HIGH confidence`() {
        val result = EnsembleMerger.merge(
            mlkit(odo = 12676, battery = 99, range = 91),
            paddle(odo = 12676, battery = 99, range = 91)
        )

        assertEquals(12676, result.odo.value)
        assertEquals(FieldConfidence.HIGH, result.odo.confidence)
        assertEquals(EnsembleMerger.ENGINE_BOTH, result.odo.engine)
        assertEquals(FieldConfidence.HIGH, result.battery.confidence)
        assertEquals(FieldConfidence.HIGH, result.range.confidence)
        assertTrue(result.notes.any { it.contains("agree on 12676") })
    }

    // --- case 2: exactly one engine found the field --------------------------

    @Test
    fun `value from a single engine is MEDIUM and attributed to that engine`() {
        val result = EnsembleMerger.merge(
            mlkit(odo = 12676, battery = null),
            paddle(odo = null, battery = 84)
        )

        assertEquals(12676, result.odo.value)
        assertEquals(FieldConfidence.MEDIUM, result.odo.confidence)
        assertEquals("mlkit", result.odo.engine)

        assertEquals(84, result.battery.value)
        assertEquals(FieldConfidence.MEDIUM, result.battery.confidence)
        assertEquals("paddle", result.battery.engine)
    }

    @Test
    fun `field neither engine found stays null for the retry ladder`() {
        val result = EnsembleMerger.merge(mlkit(odo = 100), paddle(odo = 100))

        assertNull(result.battery.value)
        assertNull(result.battery.confidence)
        assertNull(result.range.value)
    }

    // --- case 3: disagreement -----------------------------------------------

    @Test
    fun `disagreement prefers the value passing the sanity check`() {
        // 470% is impossible; 84% is plausible, so battery must take 84 even
        // though the implausible value came from the first engine.
        val result = EnsembleMerger.merge(
            mlkit(battery = 470),
            paddle(battery = 84)
        )

        assertEquals(84, result.battery.value)
        assertEquals(FieldConfidence.LOW, result.battery.confidence)
        assertEquals("paddle", result.battery.engine)
        assertTrue(result.notes.any { it.contains("sanity check") })
        assertTrue(result.notes.any { it.contains("mlkit=470") && it.contains("paddle=84") })
    }

    @Test
    fun `odo sanity uses the last saved reading as the plausible window`() {
        // 12728 continues from 12676; 1272 would be a huge rollback.
        val result = EnsembleMerger.merge(
            mlkit(odo = 1272),
            paddle(odo = 12728),
            lastOdo = 12676
        )

        assertEquals(12728, result.odo.value)
        assertEquals(FieldConfidence.LOW, result.odo.confidence)
        assertEquals("paddle", result.odo.engine)
    }

    @Test
    fun `range above 250 loses to a plausible range`() {
        val result = EnsembleMerger.merge(
            mlkit(range = 911),
            paddle(range = 91)
        )

        assertEquals(91, result.range.value)
        assertEquals(FieldConfidence.LOW, result.range.confidence)
    }

    @Test
    fun `when both values are sane the higher OCR confidence wins`() {
        val result = EnsembleMerger.merge(
            mlkit(battery = 84, lines = listOf(line("BATTERY 84%", 0.95f))),
            paddle(battery = 64, lines = listOf(line("BATTERY 64%", 0.55f)))
        )

        assertEquals(84, result.battery.value)
        assertEquals("mlkit", result.battery.engine)
        assertTrue(result.notes.any { it.contains("higher OCR confidence") })
    }

    @Test
    fun `without usable confidences a numeric disagreement falls back to paddle`() {
        // Both 84 and 64 are sane batteries and no line carries confidence.
        val result = EnsembleMerger.merge(
            mlkit(battery = 84, lines = listOf(line("BATTERY 84%"))),
            paddle(battery = 64, lines = listOf(line("BATTERY 64%")))
        )

        assertEquals(64, result.battery.value)
        assertEquals("paddle", result.battery.engine)
        assertEquals(FieldConfidence.LOW, result.battery.confidence)
        assertTrue(result.notes.any { it.contains("engine preference") })
    }

    @Test
    fun `both values failing sanity still resolves to the preferred engine`() {
        val result = EnsembleMerger.merge(
            mlkit(battery = 470),
            paddle(battery = 300)
        )

        assertEquals(300, result.battery.value)
        assertEquals("paddle", result.battery.engine)
        assertEquals(FieldConfidence.LOW, result.battery.confidence)
    }

    // --- confidence lookup helper -------------------------------------------

    @Test
    fun `line confidence lookup matches the line containing the value`() {
        val lines = listOf(
            line("ODO km", 0.99f),
            line("12676", 0.80f),
            line("TRIP 65", 0.90f)
        )
        assertEquals(0.80f, EnsembleMerger.lineConfidenceFor(12676, lines)!!, 0.001f)
        assertNull(EnsembleMerger.lineConfidenceFor(999, lines))
    }

    @Test
    fun `NaN confidences are treated as unavailable`() {
        val lines = listOf(line("84", Float.NaN))
        assertNull(EnsembleMerger.lineConfidenceFor(84, lines))
    }
}
