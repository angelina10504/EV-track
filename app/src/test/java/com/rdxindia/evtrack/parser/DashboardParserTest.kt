package com.rdxindia.evtrack.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardParserTest {

    private val parser = DashboardParser()

    private fun line(text: String, left: Int, top: Int, right: Int, bottom: Int) =
        OcrLine(text, left, top, right, bottom)

    @Test
    fun `merged label and value lines are parsed`() {
        // OCR merged each label with its value into a single line.
        val lines = listOf(
            line("ODO 12,676 km", 40, 60, 320, 110),
            line("62", 500, 300, 700, 480),          // big center speed number
            line("BATTERY 84%", 812, 96, 1040, 150),
            line("RANGE 91 km", 820, 200, 1030, 250)
        )
        val result = parser.parse(lines)

        assertEquals(12676, result.odo)
        assertEquals(84, result.battery)
        assertEquals(91, result.range)
        assertTrue(result.confidenceNotes.any { it.contains("merged") })
    }

    @Test
    fun `separate label and value lines are matched spatially`() {
        // Each label is its own line with the value directly below it.
        val lines = listOf(
            line("ODO", 40, 60, 140, 100),
            line("12,676 km", 40, 105, 260, 150),
            line("62", 500, 300, 700, 480),          // speed, far from all anchors
            line("BATTERY", 812, 60, 1000, 100),
            line("84%", 830, 105, 930, 150),
            line("RANGE", 812, 200, 980, 240),
            line("91 km", 830, 245, 940, 290)
        )
        val result = parser.parse(lines)

        assertEquals(12676, result.odo)
        assertEquals(84, result.battery)
        assertEquals(91, result.range)
    }

    @Test
    fun `missing field returns null with a note`() {
        val lines = listOf(
            line("ODO 12,676 km", 40, 60, 320, 110),
            line("BATTERY 84%", 812, 96, 1040, 150)
            // no RANGE anywhere
        )
        val result = parser.parse(lines)

        assertEquals(12676, result.odo)
        assertEquals(84, result.battery)
        assertNull(result.range)
        assertTrue(result.confidenceNotes.any { it.startsWith("range:") })
    }

    @Test
    fun `speed number is not used as odo when no odo anchor exists`() {
        // No ODO label detected: parser must NOT fall back to the big center number.
        val lines = listOf(
            line("62", 500, 300, 700, 480),
            line("BATTERY 84%", 812, 96, 1040, 150),
            line("RANGE 91 km", 820, 200, 1030, 250)
        )
        val result = parser.parse(lines)

        assertNull(result.odo)
        assertEquals(84, result.battery)
        assertEquals(91, result.range)
        assertTrue(result.confidenceNotes.any { it.contains("no ODO anchor") })
    }

    @Test
    fun `odo anchor picks nearby value not distant speed number`() {
        // ODO label with its value beside it; the speed number vertically
        // overlaps the anchor too, but is much farther away.
        val lines = listOf(
            line("ODO", 40, 60, 140, 100),
            line("12676", 160, 62, 300, 102),        // beside the anchor
            line("88", 600, 40, 800, 220),           // speed, overlaps vertically
            line("BATTERY 84%", 812, 96, 1040, 150),
            line("RANGE 91 km", 820, 200, 1030, 250)
        )
        val result = parser.parse(lines)

        assertEquals(12676, result.odo)
    }

    @Test
    fun `real Ola dashboard with misread ODO label is parsed`() {
        // Approximate line layout of an actual Ola S1 X+ photo where OCR
        // misreads the segment-font "ODO km" label as "000 km".
        val lines = listOf(
            line("08:11", 1300, 370, 1470, 400),      // clock
            line("BATTERY", 1240, 470, 1470, 515),
            line("99%", 1350, 540, 1490, 600),
            line("000 km", 215, 505, 390, 545),       // misread "ODO km" label
            line("12676", 215, 560, 430, 610),
            line("00", 680, 500, 980, 780),           // big speed digits
            line("km/h", 790, 790, 880, 820),
            line("TRIP km", 215, 680, 390, 720),
            line("0", 215, 745, 250, 790),            // trip value
            line("RANGE km", 1240, 660, 1480, 700),
            line("99", 1390, 720, 1470, 770),
            line("PARKED", 700, 880, 960, 930)
        )
        val result = parser.parse(lines)

        assertEquals(12676, result.odo)
        assertEquals(99, result.battery)
        assertEquals(99, result.range)
        assertTrue(result.confidenceNotes.any { it.contains("OCR-tolerant") })
    }

    @Test
    fun `MoveOS-style dashboard without battery label is parsed`() {
        // Layout with no "BATTERY" label (battery icon + bare "0%"), a decimal
        // odometer ("0.0 km"), and an "ESTIMATED RANGE" big digit.
        val lines = listOf(
            line("06:17 PM", 500, 378, 585, 398),
            line("ODO", 155, 505, 195, 522),
            line("0.0 KM", 155, 535, 260, 562),
            line("CURRENT RIDE", 152, 595, 295, 615),
            line("0.0 KM 0 MIN", 152, 630, 320, 655),
            line("AVG. SPEED", 152, 690, 275, 710),
            line("0 KM/H", 152, 722, 230, 745),
            line("0", 505, 555, 590, 660),            // big speed digit
            line("KM/H", 520, 695, 575, 715),
            line("ESTIMATED RANGE", 712, 540, 890, 562),
            line("0%", 712, 648, 745, 665),           // bare % under battery icon
            line("0 KM", 815, 580, 925, 665),         // big range digit
            line("PARK ASSIST", 150, 835, 295, 858)
        )
        val result = parser.parse(lines)

        assertEquals(0, result.odo)
        assertEquals(0, result.battery)
        assertEquals(0, result.range)
        assertTrue(result.confidenceNotes.any { it.contains("standalone percentage") })
    }

    @Test
    fun `real MoveOS OCR output where ODO label was not recognized`() {
        // Verbatim ML Kit output from a real photo: the "ODO" label and the
        // battery "0%" were not recognized at all, "km" was misread as "lm".
        val lines = listOf(
            line("0.0 lm", 151, 536, 237, 557),
            line("CURRENT RIDE", 161, 589, 292, 614),
            line("O.0 ken O min", 152, 628, 318, 652),
            line("AVe. SPEED", 154, 689, 275, 705),
            line("PARK ASSIsT", 150, 828, 295, 859),
            line("0617 PM", 499, 377, 583, 398),
            line("km/h", 518, 693, 574, 712),
            line("eSTIMATED RANGE", 713, 540, 888, 559),
            line("RIDE", 763, 705, 838, 732),
            line("0.", 854, 578, 901, 662),
            line("km", 883, 635, 923, 655)
        )
        val result = parser.parse(lines)

        assertEquals(0, result.odo)          // recovered from lone "0.0 lm" line
        assertNull(result.battery)           // "0%" never appeared in OCR output
        assertEquals(0, result.range)
        assertTrue(result.confidenceNotes.any { it.contains("lone") })
    }

    @Test
    fun `anchor never grabs a value from the far side of its row`() {
        // Verbatim second-pass (2× upscale) OCR of a real photo: the range
        // digit was lost, and the only numeric line ("O.0 lem", the odometer)
        // sits 955 px to the left of the RANGE anchor on the same row. RANGE
        // must refuse it; the odo fallback may then claim it.
        val lines = listOf(
            line("O.0 lem", 301, 1071, 472, 1112),
            line("cURRENT RIDE >", 304, 1187, 632, 1221),
            line("0.0 len 0 min", 305, 1261, 638, 1303),
            line("AVe. SPEED", 309, 1379, 551, 1411),
            line("PARK ASSIST", 299, 1668, 595, 1711),
            line("o617 PM", 999, 755, 1165, 793),
            line("km/h", 1035, 1390, 1152, 1427),
            line("eSTIMATED RANGE", 1427, 1086, 1779, 1120),
            line("km", 1766, 1270, 1847, 1313),
            line("RIDE S", 1526, 1400, 1797, 1473)
        )
        val result = parser.parse(lines)

        assertNull(result.range)             // far-left line must not become range
        assertEquals(0, result.odo)          // odo fallback claims "O.0 lem"
        assertNull(result.battery)
    }

    @Test
    fun `decimal odometer keeps integer part only`() {
        val lines = listOf(
            line("ODO", 155, 505, 195, 522),
            line("123.4 KM", 155, 535, 280, 562)
        )
        val result = parser.parse(lines)

        assertEquals(123, result.odo)
    }

    @Test
    fun `battery outside 0-100 is rejected`() {
        val lines = listOf(
            line("ODO 12,676 km", 40, 60, 320, 110),
            line("BATTERY 470%", 812, 96, 1040, 150),
            line("RANGE 91 km", 820, 200, 1030, 250)
        )
        val result = parser.parse(lines)

        assertNull(result.battery)
        assertEquals(12676, result.odo)
        assertEquals(91, result.range)
    }

    @Test
    fun `second-pass merge fills only the missing fields`() {
        val first = ExtractionResult(
            odo = 12676, battery = null, range = 99,
            rawLines = listOf(line("ODO 12676", 0, 0, 100, 20)),
            confidenceNotes = listOf("odo: taken from merged line \"ODO 12676\"")
        )
        val second = ExtractionResult(
            odo = null, battery = 84, range = 91,
            rawLines = listOf(line("84%", 0, 0, 50, 20)),
            confidenceNotes = listOf("battery: standalone percentage line \"84%\"")
        )
        val merged = ExtractionMerger.merge(first, second)

        assertEquals(12676, merged.odo)
        assertEquals(84, merged.battery)      // filled from second pass
        assertEquals(99, merged.range)        // first pass wins over second
        assertTrue(merged.confidenceNotes.any { it.contains("recovered by second-pass") })
        assertTrue(merged.rawLines.any { it.text == ExtractionMerger.PASS_SEPARATOR })
    }

    @Test
    fun `a line is never assigned to two fields`() {
        // "91" is the only numeric line, near both a BATTERY and a RANGE
        // anchor; it must be assigned to exactly one field, never both.
        val lines = listOf(
            line("BATTERY", 812, 60, 1000, 100),
            line("RANGE", 812, 200, 980, 240),
            line("91", 830, 245, 940, 290)
        )
        val result = parser.parse(lines)

        // Whichever field got 91, the other must be null.
        val assignments = listOfNotNull(result.battery, result.range)
        assertEquals(listOf(91), assignments)
    }
}
