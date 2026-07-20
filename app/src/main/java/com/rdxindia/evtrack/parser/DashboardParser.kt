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
        // Longest plausible dashboard label line ("ESTIMATED RANGE", merged
        // "BATTERY 84%"). Anything longer is prose/URL junk, never an anchor.
        const val MAX_ANCHOR_LENGTH = 24

        // A line that is nothing but a percentage, e.g. "84%" / "0 %".
        val STANDALONE_PERCENT = Regex("""^(\d{1,3})\s*%$""")

        // A line that is only "number + km-like unit", e.g. "0.0 KM", "12,676 KM",
        // tolerating digit/letter misreads: "0.0 LM", "12676 KN". The unit must
        // start with K/L/I so times like "06:17 PM" never match.
        val ODO_VALUE_WITH_UNIT = Regex("""^[0-9O][0-9O.,]{0,7}\s*[KLI][A-Z]{0,2}$""")

        // Labels whose adjacent value must never be mistaken for the odometer.
        val NON_ODO_LABELS = arrayOf(
            "TRIP", "CURRENT", "AVG", "SPEED", "RANGE", "BATTERY", "BATT", "RIDE", "MIN"
        )

        // Seven-segment/OCR letter→digit confusions, applied only inside
        // words that already contain a digit.
        val LETTER_TO_DIGIT = mapOf(
            'O' to '0', 'Q' to '0', 'D' to '0',
            'I' to '1', 'L' to '1',
            'Z' to '2', 'S' to '5', 'G' to '6', 'B' to '8'
        )
    }

    fun parse(lines: List<OcrLine>): ExtractionResult {
        val notes = mutableListOf<String>()
        val normalized = lines.map { it.copy(text = it.text.uppercase().trim()) }
        val used = mutableSetOf<Int>()

        val odoAnchor = findOdoAnchor(normalized, notes)
        val batteryAnchor = findAnchor(normalized) { FuzzyLabel.lineMatches(it, "BATTERY", "BATT") }
        val rangeAnchor = findAnchor(normalized) { FuzzyLabel.lineMatches(it, "RANGE") }

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
                .filter { (_, line) -> FuzzyLabel.lineMatches(line.text, *NON_ODO_LABELS) }
            val candidates = normalized.withIndex().filter { (i, line) ->
                i !in used && i !in anchorIndices &&
                    ODO_VALUE_WITH_UNIT.matches(line.text) &&
                    labelLines.none { (li, label) -> li != i && isDirectlyBelow(label, line) }
            }
            when {
                candidates.size == 1 -> {
                    val (index, line) = candidates.first()
                    val value = odoFrom(line.text)
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

    /**
     * Anchor lines must be label-like: short. A long line (a URL in a screen
     * photo, a merged text wall) that merely contains "ODO" or "BATTERY" as a
     * substring must never anchor a field.
     */
    /**
     * Public lookup of a field's anchor line (original bounding box, normalized
     * text) — used by the anchor-region segment-decode pass when OCR produced
     * no line at all for the value under a label.
     */
    fun anchorFor(lines: List<OcrLine>, field: String): OcrLine? {
        val normalized = lines.map { it.copy(text = it.text.uppercase().trim()) }
        return when (field) {
            "odo" -> findOdoAnchor(normalized, null)?.line
            "battery" -> findAnchor(normalized) { FuzzyLabel.lineMatches(it, "BATTERY", "BATT") }?.line
            "range" -> findAnchor(normalized) { FuzzyLabel.lineMatches(it, "RANGE") }?.line
            else -> null
        }
    }

    /** Public value parsers for text produced outside the normal line flow. */
    fun odoValue(text: String): Int? = odoFrom(text.uppercase().trim())
    fun batteryValue(text: String): Int? = batteryFrom(text.uppercase().trim())
    fun rangeValue(text: String): Int? = rangeFrom(text.uppercase().trim())

    private fun findAnchor(lines: List<OcrLine>, match: (String) -> Boolean): Anchor? {
        val index = lines.indexOfFirst { it.text.length <= MAX_ANCHOR_LENGTH && match(it.text) }
        return if (index >= 0) Anchor(index, lines[index]) else null
    }

    /** The odometer label is "ODO" on most clusters, "TOTAL km" on some Ola variants. */
    private fun findOdoAnchor(normalized: List<OcrLine>, notes: MutableList<String>?): Anchor? {
        findAnchor(normalized) { it.contains("ODO") || it.contains("TOTAL") }?.let { return it }
        // The small label is often misread ("000 km", "0D0", "QDO", "T0TAL").
        // Accept a fuzzy token match as an anchor, but remove the misread
        // words so their digits are never taken as the odometer value.
        val index = normalized.indexOfFirst { line ->
            line.text.length <= MAX_ANCHOR_LENGTH && FuzzyLabel.tokenize(line.text).any {
                FuzzyLabel.matches(it, "ODO") || FuzzyLabel.matches(it, "TOTAL")
            }
        }
        if (index < 0) return null
        val original = normalized[index]
        val cleanedText = original.text.split(Regex("""\s+"""))
            .filterNot { word ->
                FuzzyLabel.tokenize(word).any {
                    FuzzyLabel.matches(it, "ODO") || FuzzyLabel.matches(it, "TOTAL")
                }
            }
            .joinToString(" ")
        notes?.add("odo: anchor matched via OCR-tolerant pattern (\"${original.text}\")")
        return Anchor(index, original.copy(text = cleanedText))
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
     * Below within 1.5× the anchor height and roughly horizontally aligned,
     * or beside it: vertical overlap AND a horizontal gap of at most 3× the
     * anchor height. A value also renders at most ~5× its label's height —
     * the huge center speed digits exceed that and are never a label's value.
     */
    private fun isBelowOrBeside(anchor: OcrLine, line: OcrLine): Boolean {
        val anchorH = maxOf(anchor.height, 1)
        if (line.height > 5 * anchorH) return false
        val horizontalOverlap = line.left < anchor.right && line.right > anchor.left
        val horizontalGap = maxOf(line.left - anchor.right, anchor.left - line.right)
        val below = line.centerY > anchor.centerY &&
            (line.top - anchor.bottom) <= (1.5 * anchorH) &&
            (horizontalOverlap || horizontalGap <= 2 * anchorH)
        val verticalOverlap = line.top < anchor.bottom && line.bottom > anchor.top
        val beside = verticalOverlap && horizontalGap <= (3 * anchorH)
        return below || beside
    }

    private fun centerDistance(a: OcrLine, b: OcrLine): Double {
        val dx = (a.centerX - b.centerX).toDouble()
        val dy = (a.centerY - b.centerY).toDouble()
        return sqrt(dx * dx + dy * dy)
    }

    /**
     * Seven-segment digits often OCR as letters ("l2941" for 12941, "1O%" for
     * 10%). Words that already contain at least one digit get their lookalike
     * letters mapped back to digits; pure-letter words (labels, "RIDE", "S")
     * are left untouched so they can never fabricate a number.
     */
    private fun digitNormalize(text: String): String =
        text.split(" ").joinToString(" ") { word ->
            if (word.any { it.isDigit() }) {
                word.map { LETTER_TO_DIGIT[it] ?: it }.joinToString("")
            } else {
                word
            }
        }

    /** Battery: "NN%" preferred, else a bare 1–3 digit number; must be 0–100. */
    private fun batteryFrom(text: String): Int? {
        val cleaned = digitNormalize(text)
        val pct = Regex("""(\d{1,3})\s*%""").find(cleaned)?.groupValues?.get(1)?.toIntOrNull()
        val value = pct ?: Regex("""\b(\d{1,3})\b""").find(cleaned)?.groupValues?.get(1)?.toIntOrNull()
        return value?.takeIf { it in 0..100 }
    }

    /** Range: 1–3 digit number, "km" stripped. */
    private fun rangeFrom(text: String): Int? {
        val cleaned = digitNormalize(text).replace("KM", " ", ignoreCase = true)
        return Regex("""\b(\d{1,3})\b""").find(cleaned)?.groupValues?.get(1)?.toIntOrNull()
    }

    /**
     * Odo: 1–6 digit number, comma separators and "km" stripped. A decimal like
     * "0.0" or "123.4" keeps only the integer part (never "00" → 0 → wrong).
     */
    private fun odoFrom(text: String): Int? {
        var cleaned = digitNormalize(text).replace(",", "").replace("KM", " ", ignoreCase = true).trim()
        // OCR often splits a large seven-segment number into groups ("76 12"
        // for 7612); when the line is nothing but digit groups, join them.
        if (cleaned.isNotEmpty() && cleaned.all { it.isDigit() || it == ' ' || it == '.' }) {
            cleaned = cleaned.replace(" ", "")
        }
        return Regex("""\b(\d{1,6})(?:\.\d+)?\b""").find(cleaned)
            ?.groupValues?.get(1)?.toIntOrNull()
    }
}
