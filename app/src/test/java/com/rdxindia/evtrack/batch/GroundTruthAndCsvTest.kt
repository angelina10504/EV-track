package com.rdxindia.evtrack.batch

import com.rdxindia.evtrack.data.EngineMode
import com.rdxindia.evtrack.data.Reading
import com.rdxindia.evtrack.parser.FieldConfidence
import com.rdxindia.evtrack.parser.FieldProvenance
import com.rdxindia.evtrack.pipeline.RungEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroundTruthAndCsvTest {

    private fun reading(
        id: Long,
        odo: Int?, battery: Int?, range: Int?,
        userEdited: Boolean,
        imagePath: String?,
        imageHash: String?
    ) = Reading(
        id = id, timestamp = id, odoKm = odo, batteryPct = battery, rangeKm = range,
        eventType = "MANUAL", userEdited = userEdited,
        ocrOdo = null, ocrBattery = null, ocrRange = null,
        imagePath = imagePath, imageHash = imageHash
    )

    @Test
    fun `parses csv with header comments and blank cells`() {
        val truth = GroundTruth.parseCsv(
            """
            filename,odo,battery,range
            # a comment
            IMG_001.jpg,12676,99,91

            IMG_002.jpg,12728,,96
            """.trimIndent()
        )

        assertEquals(2, truth.byName.size)
        assertEquals(12676, truth.byName.getValue("img_001.jpg").odo)
        // Missing cell means "unknown", not zero.
        assertNull(truth.byName.getValue("img_002.jpg").battery)
    }

    @Test
    fun `quoted cells with commas are split correctly`() {
        val cells = GroundTruth.splitCsvLine("\"my, photo.jpg\",100,50,40")
        assertEquals("my, photo.jpg", cells[0])
        assertEquals("100", cells[1])
    }

    // --- the bug this change fixes ------------------------------------------

    @Test
    fun `readings are keyed by content hash, not by the renamed stored file`() {
        // The saved copy is renamed to reading_<millis>.jpg, so its name can
        // never equal the picked gallery image's name — only the hash can match.
        val truth = GroundTruth.fromReadings(
            listOf(
                reading(1, 12676, 99, 91, true, "/files/readings/reading_1753467890123.jpg", "abc123")
            )
        )

        assertEquals(12676, truth.byHash.getValue("abc123").odo)
        val (entry, viaHash) = truth.lookup("abc123", "IMG_20260726_110723.jpg")!!
        assertTrue("hash must win over filename", viaHash)
        assertEquals(12676, entry.odo)
        // Filename lookup alone finds nothing for a real gallery name.
        assertNull(truth.lookup(null, "IMG_20260726_110723.jpg"))
    }

    @Test
    fun `all readings are truth, with corrected and confirmed counted separately`() {
        val truth = GroundTruth.fromReadings(
            listOf(
                reading(1, 100, 90, 80, true, "/f/reading_1.jpg", "h1"),
                reading(2, 200, 80, 70, false, "/f/reading_2.jpg", "h2"),
                reading(3, 300, 70, 60, false, "/f/reading_3.jpg", "h3")
            )
        )

        assertEquals(3, truth.byHash.size)
        assertEquals(1, truth.correctedCount)
        assertEquals(2, truth.confirmedCount)
        assertTrue(truth.byHash.getValue("h1").corrected)
        assertFalse(truth.byHash.getValue("h2").corrected)
        assertTrue(truth.sourceLabel.contains("1 corrected"))
        assertTrue(truth.sourceLabel.contains("2 confirmed-as-is"))
    }

    @Test
    fun `readings without a hash cannot be matched by hash`() {
        val truth = GroundTruth.fromReadings(
            listOf(reading(1, 100, 90, 80, true, "/f/reading_1.jpg", null))
        )
        assertTrue(truth.byHash.isEmpty())
        assertNull(truth.lookup("anything", "reading_x.jpg"))
    }

    @Test
    fun `filename remains a fallback when no hash is available`() {
        val truth = GroundTruth.parseCsv("IMG_001.jpg,12676,99,91")
        val (entry, viaHash) = truth.lookup(null, "IMG_001.jpg")!!
        assertFalse("CSV truth matches by name", viaHash)
        assertEquals(12676, entry.odo)
        // An unknown hash falls through to the filename.
        assertNotNull(truth.lookup("unknown-hash", "IMG_001.jpg"))
    }

    // --- match summary ------------------------------------------------------

    @Test
    fun `match summary reports counts and flags a zero-match run`() {
        val rows = listOf(
            row("a.jpg", hash = "h1"),
            row("b.jpg", hash = "h2")
        )
        val truth = GroundTruth.fromReadings(
            listOf(reading(1, 100, 90, 80, true, "/f/reading_1.jpg", "h1"))
        )

        val (resolved, match) = BatchAggregator.applyGroundTruth(rows, truth)

        assertEquals(1, match.matched)
        assertEquals(2, match.totalImages)
        assertEquals(1, match.matchedByHash)
        assertEquals(1, match.corrected)
        assertTrue(match.headline().contains("1 of 2 images matched"))
        assertNotNull(resolved.first { it.imageName == "a.jpg" }.groundTruth)
        assertNull(resolved.first { it.imageName == "b.jpg" }.groundTruth)
    }

    @Test
    fun `zero match is announced instead of silently showing no accuracy`() {
        val truth = GroundTruth.fromReadings(
            listOf(reading(1, 100, 90, 80, true, "/f/reading_1.jpg", "other-hash"))
        )
        val (resolved, match) = BatchAggregator.applyGroundTruth(listOf(row("a.jpg", "h1")), truth)

        assertEquals(0, match.matched)
        assertTrue(match.headline().contains("0 of 1 images matched"))
        val report = BatchReport.render(BatchAggregator.aggregate(resolved), match)
        assertTrue("report must warn loudly", report.contains("NO IMAGES MATCHED"))
    }

    @Test
    fun `no truth source reports none rather than zero matches`() {
        val (_, match) = BatchAggregator.applyGroundTruth(listOf(row("a.jpg", "h1")), GroundTruthSet())
        assertFalse(match.hasTruthSource)
        assertTrue(match.headline().contains("none supplied"))
    }

    // --- csv ----------------------------------------------------------------

    @Test
    fun `csv export includes hash, match flags and correctness`() {
        val rows = listOf(
            row("IMG_001.jpg", hash = "abc123").copy(
                odo = 12676, battery = 99,
                provenance = mapOf(
                    "odo" to FieldProvenance(
                        "odo", 12676, "both", "ORIGINAL", "pass1-ensemble", FieldConfidence.HIGH
                    )
                ),
                rungEvents = listOf(
                    RungEvent("retry-highres", "paddle", "ORIGINAL", setOf("battery"), setOf("battery"))
                ),
                groundTruth = GroundTruthEntry("reading_1.jpg", odo = 12676, battery = 84, corrected = true)
            )
        )

        val lines = BatchCsv.build(rows).trim().lines()

        assertEquals(2, lines.size)
        assertTrue(lines[0].startsWith("image,image_hash,mode,odo,odo_engine"))
        val data = lines[1]
        assertTrue(data.contains("IMG_001.jpg,abc123,BOTH"))
        assertTrue(data.contains("both,ORIGINAL,pass1-ensemble,HIGH"))
        assertTrue("rung activity recorded", data.contains("retry-highres:battery"))
        // gt_matched=1, gt_corrected=1, odo correct, battery wrong.
        assertTrue(data.contains(",1,1,12676,1,84,0,"))
    }

    @Test
    fun `csv escapes commas and quotes in names`() {
        assertEquals("\"a,b.jpg\"", BatchCsv.escape("a,b.jpg"))
        assertEquals("\"say \"\"hi\"\"\"", BatchCsv.escape("say \"hi\""))
        assertEquals("plain.jpg", BatchCsv.escape("plain.jpg"))
    }

    private fun row(name: String, hash: String? = null) = BatchRow(
        imageName = name,
        mode = EngineMode.BOTH,
        odo = null, battery = null, range = null,
        provenance = emptyMap(),
        rungEvents = emptyList(),
        ensembleOutcomes = emptyMap(),
        totalMillis = 10, pass1Millis = 5, ladderMillis = 5,
        imageHash = hash
    )
}
