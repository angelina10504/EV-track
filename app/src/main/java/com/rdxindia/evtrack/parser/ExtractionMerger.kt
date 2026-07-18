package com.rdxindia.evtrack.parser

/**
 * Merges a first-pass extraction with a second-pass one (an OCR run on a
 * high-resolution or upscaled bitmap). First-pass values win; second-pass
 * values fill the gaps. Raw lines of both passes are kept for the debug view.
 */
object ExtractionMerger {

    const val PASS_SEPARATOR = "— second pass (high-res) —"

    fun merge(first: ExtractionResult, second: ExtractionResult): ExtractionResult {
        val notes = first.confidenceNotes.toMutableList()

        fun pick(field: String, a: Int?, b: Int?): Int? {
            if (a != null) return a
            if (b != null) notes += "$field: recovered by second-pass OCR (high-res)"
            return b
        }

        val odo = pick("odo", first.odo, second.odo)
        val battery = pick("battery", first.battery, second.battery)
        val range = pick("range", first.range, second.range)

        notes += second.confidenceNotes.map { "pass2: $it" }

        val rawLines = if (second.rawLines.isEmpty()) {
            first.rawLines
        } else {
            first.rawLines + OcrLine(PASS_SEPARATOR, 0, 0, 0, 0) + second.rawLines
        }
        return ExtractionResult(odo, battery, range, rawLines, notes)
    }
}
