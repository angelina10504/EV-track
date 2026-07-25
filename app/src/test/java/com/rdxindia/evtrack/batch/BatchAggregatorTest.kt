package com.rdxindia.evtrack.batch

import com.rdxindia.evtrack.data.EngineMode
import com.rdxindia.evtrack.parser.EnsembleMerger
import com.rdxindia.evtrack.parser.FieldConfidence
import com.rdxindia.evtrack.parser.FieldOutcome
import com.rdxindia.evtrack.parser.FieldProvenance
import com.rdxindia.evtrack.pipeline.ExtractionPipeline
import com.rdxindia.evtrack.pipeline.RungEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchAggregatorTest {

    private fun row(
        image: String,
        mode: EngineMode,
        odo: Int? = null,
        battery: Int? = null,
        range: Int? = null,
        rungs: List<RungEvent> = emptyList(),
        outcomes: Map<String, FieldOutcome> = emptyMap(),
        provenance: Map<String, FieldProvenance> = emptyMap(),
        total: Long = 100, pass1: Long = 60, ladder: Long = 40
    ) = BatchRow(image, mode, odo, battery, range, provenance, rungs, outcomes, total, pass1, ladder)

    @Test
    fun `fill rate counts non-null fields per mode`() {
        val stats = BatchAggregator.aggregate(
            listOf(
                row("a.jpg", EngineMode.ML_KIT, odo = 100, battery = 50),
                row("b.jpg", EngineMode.ML_KIT, odo = 200),
                row("a.jpg", EngineMode.PADDLE, odo = 100, battery = 50, range = 40)
            )
        )

        val mlkit = stats.fillRates.getValue(EngineMode.ML_KIT)
        assertEquals(Ratio(2, 2), mlkit.getValue("odo"))
        assertEquals(Ratio(1, 2), mlkit.getValue("battery"))
        assertEquals(Ratio(0, 2), mlkit.getValue("range"))
        assertEquals(Ratio(1, 1), stats.fillRates.getValue(EngineMode.PADDLE).getValue("range"))
        assertEquals(2, stats.imageCount)
    }

    @Test
    fun `rung table separates produced from accepted and flags dead rungs`() {
        val stats = BatchAggregator.aggregate(
            listOf(
                row(
                    "a.jpg", EngineMode.BOTH,
                    rungs = listOf(
                        // produced odo+battery, but only odo was still empty
                        RungEvent("retry-highres", "paddle", "ORIGINAL", setOf("odo", "battery"), setOf("odo")),
                        // ran and produced, nothing accepted → dead
                        RungEvent("crop", "mlkit", "MORPH_CLOSE_BIN", setOf("range"), emptySet())
                    )
                )
            )
        )

        val highres = stats.rungStats.first { it.rung == "retry-highres" }
        assertEquals(1, highres.ran)
        assertEquals(2, highres.producedValues)
        assertEquals(1, highres.acceptedValues)
        assertTrue(!highres.isDead)

        val crop = stats.rungStats.first { it.rung == "crop" }
        assertTrue("rung producing nothing accepted must be flagged dead", crop.isDead)

        // Rungs that never ran still appear, so gaps are visible.
        val anchor = stats.rungStats.first { it.rung == ExtractionPipeline.STAGE_ANCHOR_REGION }
        assertEquals(0, anchor.ran)
    }

    @Test
    fun `engine x variant matrix counts accepted values only`() {
        val stats = BatchAggregator.aggregate(
            listOf(
                row(
                    "a.jpg", EngineMode.BOTH,
                    rungs = listOf(
                        RungEvent("crop", "paddle", "CLAHE_STRETCH", setOf("odo"), setOf("odo")),
                        RungEvent("crop", "mlkit", "CLAHE_STRETCH", setOf("battery"), setOf("battery")),
                        RungEvent("crop", "mlkit", "MORPH_CLOSE_BIN", setOf("range"), emptySet())
                    )
                )
            )
        )

        assertEquals(1, stats.engineVariantMatrix["paddle" to "CLAHE_STRETCH"])
        assertEquals(1, stats.engineVariantMatrix["mlkit" to "CLAHE_STRETCH"])
        // Produced but discarded contributes nothing.
        assertNull(stats.engineVariantMatrix["mlkit" to "MORPH_CLOSE_BIN"])
    }

    @Test
    fun `merge outcomes and tiebreaks are counted for BOTH mode only`() {
        val stats = BatchAggregator.aggregate(
            listOf(
                row(
                    "a.jpg", EngineMode.BOTH,
                    outcomes = mapOf(
                        "odo" to FieldOutcome(1, FieldConfidence.HIGH, "both", null),
                        "battery" to FieldOutcome(
                            84, FieldConfidence.LOW, "paddle", null,
                            tiebreak = EnsembleMerger.TIEBREAK_SANITY
                        )
                    )
                ),
                // ML_KIT rows must not contribute to merge stats.
                row(
                    "b.jpg", EngineMode.ML_KIT,
                    outcomes = mapOf("odo" to FieldOutcome(2, FieldConfidence.HIGH, "both", null))
                )
            )
        )

        assertEquals(1, stats.mergeOutcomes.getValue("odo").getValue(FieldConfidence.HIGH))
        assertEquals(1, stats.mergeOutcomes.getValue("battery").getValue(FieldConfidence.LOW))
        assertEquals(1, stats.tiebreakCounts.getValue(EnsembleMerger.TIEBREAK_SANITY))
        assertEquals(0, stats.tiebreakCounts.getValue(EnsembleMerger.TIEBREAK_CONFIDENCE))
    }

    @Test
    fun `accuracy compares against ground truth and ignores unknown fields`() {
        val truthEntry = GroundTruthEntry("a.jpg", odo = 12676, battery = 99, range = null)
        val stats = BatchAggregator.aggregate(
            listOf(
                row("a.jpg", EngineMode.PADDLE, odo = 12676, battery = 84, range = 91)
                    .copy(groundTruth = truthEntry),
                row("no-truth.jpg", EngineMode.PADDLE, odo = 1)
            )
        )

        val accuracy = stats.accuracy
        assertNotNull(accuracy)
        val paddle = accuracy!!.getValue(EngineMode.PADDLE)
        assertEquals(Ratio(1, 1), paddle.getValue("odo"))      // matched
        assertEquals(Ratio(0, 1), paddle.getValue("battery"))  // 84 != 99
        assertEquals(Ratio(0, 0), paddle.getValue("range"))    // truth unknown → not scored
    }

    @Test
    fun `accuracy is null when no ground truth supplied`() {
        val stats = BatchAggregator.aggregate(listOf(row("a.jpg", EngineMode.ML_KIT, odo = 1)))
        assertNull(stats.accuracy)
    }

    @Test
    fun `latency averages per mode`() {
        val stats = BatchAggregator.aggregate(
            listOf(
                row("a.jpg", EngineMode.BOTH, total = 100, pass1 = 60, ladder = 40),
                row("b.jpg", EngineMode.BOTH, total = 300, pass1 = 100, ladder = 200)
            )
        )
        val latency = stats.latency.getValue(EngineMode.BOTH)
        assertEquals(2, latency.runs)
        assertEquals(200, latency.avgTotal)
        assertEquals(80, latency.avgPass1)
        assertEquals(120, latency.avgLadder)
    }
}
