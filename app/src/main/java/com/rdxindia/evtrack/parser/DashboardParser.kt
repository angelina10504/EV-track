package com.rdxindia.evtrack.parser

import kotlin.math.sqrt

/**
 * Spatial parser for EV dashboard OCR output (reference layout: Ola S1 X+).
 *
 * Values are located relative to their label anchors ("ODO", "BATTERY"/"BATT",
 * "RANGE"), never by "biggest number on screen" — the large center speed number
 * must not be mistaken for the odometer.
 */
class DashboardParser {

    private data class Anchor(val index: Int, val line: OcrLine)

    private companion object {
        // A whole line of three O/D/Q/0 glyphs, optionally followed by "KM":
        // "000 KM", "0D0 KM", "ODD", "QDO" — common misreads of the ODO label.
        val ODO_LABEL_MISREAD = Regex("""^[ODQ0]{3}\s*(KM)?$""")

        // A line that is nothing but a percentage, e.g. "84%" / "0 %".
        val STANDALONE_PERCENT = Regex("""^(\d{1,3})\s*%$""")

        // A line that is only "number + km-like unit", e.g. "0.0 KM", "12,676 KM",
        // tolerating digit/letter misreads: "0.0 LM", "12676 KN". The unit must
        // start with K/L/I so times like "06:17 PM" never match.
        val ODO_VALUE_WITH_UNIT = Regex("""^[0-9O][0-9O.,]{0,7}\s*[KLI][A-Z]{0,2}$""")

        // Labels whose adjacent value must never be mistaken for the odometer.
        val NON_ODO_LABEL = Regex("""TRIP|CURRENT|AVG|SPEED|RANGE|BATTERY|BATT|RIDE|MIN""")
    }

    fun parse(lines: List<OcrLine>): ExtractionResult {
        val notes = mutableListOf<String>()
        val normalized = lines.map { it.copy(text = it.text.uppercase().trim()) }
        val used = mutableSetOf<Int>()

        var odoAnchor = findAnchor(normalized) { it.contains("ODO") }
        if (odoAnchor == null) {
            // The small "ODO" / "ODO km" label is often misread as "000 km",
            // "0D0", "QDO" etc. Accept that as an anchor, but blank the misread
            // text so its digits are never taken as the odometer value.
            val index = normalized.indexOfFirst { ODO_LABEL_MISREAD.matches(it.text) }
            if (index >= 0) {
                val original = normalized[index]
                odoAnchor = Anchor(index, original.copy(text = ""))
                notes += "odo: anchor matched via OCR-tolerant pattern (\"${original.text}\")"
            }
        }
        val batteryAnchor = findAnchor(normalized) { it.contains("BATTERY") || it.contains("BATT") }
        val rangeAnchor = findAnchor(normalized) { it.contains("RANGE") }

        // Anchor lines are reserved: a value for one field is never pulled from
        // another field's label line.
        val anchorIndices = listOfNotNull(odoAnchor, batteryAnchor, rangeAnchor).map { it.index }.toSet()

        // Battery first (its % pattern is the most distinctive), then range, then odo.
        var battery = extractField(
            field = "battery", anchor = batteryAnchor, normalized = normalized,
            used = used, anchorIndices = anchorIndices, notes = notes,
            fromText = ::batteryFrom
        )
        if (battery == null && batteryAnchor == null) {
            // Some dashboards (e.g. MoveOS-style) show only a battery icon with
            // a bare "NN%" and no BATTERY label. A lone percentage line is
            // distinctive enough to use — but only if it is unambiguous.
            val percentLines = normalized.withIndex().filter { (i, line) ->
                i !in used && i !in anchorIndices && STANDALONE_PERCENT.matches(line.text)
            }
            when {
                percentLines.size == 1 -> {
                    val (index, line) = percentLines.first()
                    val value = STANDALONE_PERCENT.find(line.text)
                        ?.groupValues?.get(1)?.toIntOrNull()
                    if (value != null && value in 0..100) {
                        used += index
                        battery = value
                        notes += "battery: no BATTERY label; standalone percentage line \"${line.text}\""
                    }
                }
                percentLines.size > 1 ->
                    notes += "battery: multiple standalone % lines, ambiguous — skipped"
            }
        }
        val range = extractField(
            field = "range", anchor = rangeAnchor, normalized = normalized,
            used = used, anchorIndices = anchorIndices, notes = notes,
            fromText = ::rangeFrom
        )
        var odo = extractField(
            field = "odo", anchor = odoAnchor, normalized = normalized,
            used = used, anchorIndices = anchorIndices, notes = notes,
            fromText = ::odoFrom
        )
        if (odoAnchor == null) {
            notes += "odo: no ODO anchor found — not falling back to largest number (speed trap)"
            // OCR sometimes misses the small ODO label entirely. A line that is
            // ONLY "number + km-ish unit" and does not belong to another label
            // is almost certainly the odometer — accept it if unambiguous.
            // (Bare numbers like the big speed digits still never qualify.)
            val labelLines = normalized.withIndex()
                .filter { (_, line) -> NON_ODO_LABEL.containsMatchIn(line.text) }
            val candidates = normalized.withIndex().filter { (i, line) ->
                i !in used && i !in anchorIndices &&
                    ODO_VALUE_WITH_UNIT.matches(line.text) &&
                    labelLines.none { (li, label) -> li != i && isDirectlyBelow(label, line) }
            }
            when {
                candidates.size == 1 -> {
                    val (index, line) = candidates.first()
                    val value = odoFrom(line.text.replace('O', '0'))
                    if (value != null) {
                        used += index
                        odo = value
                        notes += "odo: no label; lone \"value km\" line \"${line.text}\""
                    }
                }
                candidates.size > 1 ->
                    notes += "odo: multiple unlabeled \"value km\" lines, ambiguous — skipped"
            }
        }

        return ExtractionResult(odo, battery, range, lines, notes)
    }

    private fun findAnchor(lines: List<OcrLine>, match: (String) -> Boolean): Anchor? {
        val index = lines.indexOfFirst { match(it.text) }
        return if (index >= 0) Anchor(index, lines[index]) else null
    }

    private fun extractField(
        field: String,
        anchor: Anchor?,
        normalized: List<OcrLine>,
        used: MutableSet<Int>,
        anchorIndices: Set<Int>,
        notes: MutableList<String>,
        fromText: (String) -> Int?
    ): Int? {
        if (anchor == null) {
            if (field != "odo") notes += "$field: no anchor found"
            return null
        }

        // (a) Label and value merged into one OCR line, e.g. "BATTERY 84%".
        val merged = fromText(anchor.line.text)
        if (merged != null) {
            used += anchor.index
            notes += "$field: taken from merged line \"${anchor.line.text}\""
            return merged
        }

        // (b) Nearest line by center-distance that is below or beside the anchor.
        val candidates = normalized.withIndex().filter { (i, line) ->
            i != anchor.index && i !in used && i !in anchorIndices &&
                fromText(line.text) != null && isBelowOrBeside(anchor.line, line)
        }
        val best = candidates.minByOrNull { (_, line) -> centerDistance(anchor.line, line) }
            ?: run {
                notes += "$field: anchor \"${anchor.line.text}\" found but no nearby numeric line"
                return null
            }

        used += best.index
        val relation = if (best.value.centerY > anchor.line.bottom) "nearest-below" else "nearest-beside"
        notes += "$field: $relation match \"${best.value.text}\""
        return fromText(best.value.text)
    }

    /** True when [line] sits directly under [label] (a label-value stack). */
    private fun isDirectlyBelow(label: OcrLine, line: OcrLine): Boolean =
        line.centerY > label.centerY && (line.top - label.bottom) <= (1.5 * label.height)

    /**
     * Below within 1.5× the anchor height, or beside it: vertical overlap AND
     * a horizontal gap of at most 3× the anchor height. Without the gap limit,
     * an anchor whose value OCR missed can grab a line from the far side of
     * the screen that merely shares its row.
     */
    private fun isBelowOrBeside(anchor: OcrLine, line: OcrLine): Boolean {
        val below = line.centerY > anchor.centerY &&
            (line.top - anchor.bottom) <= (1.5 * anchor.height)
        val verticalOverlap = line.top < anchor.bottom && line.bottom > anchor.top
        val horizontalGap = maxOf(line.left - anchor.right, anchor.left - line.right)
        val beside = verticalOverlap && horizontalGap <= (3 * anchor.height)
        return below || beside
    }

    private fun centerDistance(a: OcrLine, b: OcrLine): Double {
        val dx = (a.centerX - b.centerX).toDouble()
        val dy = (a.centerY - b.centerY).toDouble()
        return sqrt(dx * dx + dy * dy)
    }

    /** Battery: "NN%" preferred, else a bare 1–3 digit number; must be 0–100. */
    private fun batteryFrom(text: String): Int? {
        val pct = Regex("""(\d{1,3})\s*%""").find(text)?.groupValues?.get(1)?.toIntOrNull()
        val value = pct ?: Regex("""\b(\d{1,3})\b""").find(text)?.groupValues?.get(1)?.toIntOrNull()
        return value?.takeIf { it in 0..100 }
    }

    /** Range: 1–3 digit number, "km" stripped. */
    private fun rangeFrom(text: String): Int? {
        val cleaned = text.replace("KM", " ", ignoreCase = true)
        return Regex("""\b(\d{1,3})\b""").find(cleaned)?.groupValues?.get(1)?.toIntOrNull()
    }

    /**
     * Odo: 1–6 digit number, comma separators and "km" stripped. A decimal like
     * "0.0" or "123.4" keeps only the integer part (never "00" → 0 → wrong).
     */
    private fun odoFrom(text: String): Int? {
        val cleaned = text.replace(",", "").replace("KM", " ", ignoreCase = true)
        return Regex("""\b(\d{1,6})(?:\.\d+)?\b""").find(cleaned)
            ?.groupValues?.get(1)?.toIntOrNull()
    }
}
