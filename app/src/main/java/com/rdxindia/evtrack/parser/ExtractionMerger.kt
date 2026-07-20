package com.rdxindia.evtrack.parser

/**
 * Merges a first-pass extraction with a second-pass one (an OCR run on a
 * high-resolution or upscaled bitmap). First-pass values win; second-pass
 * values fill the gaps. Raw lines of both passes are kept for the debug view.
 */
object ExtractionMerger {

    const val HIGHRES_PASS = "pass2 (high-res)"
    const val SEGMENT_PASS = "segment-decode"

    fun separatorFor(passName: String): String = "— $passName —"

    fun merge(
        first: ExtractionResult,
        second: ExtractionResult,
        passName: String = HIGHRES_PASS
    ): ExtractionResult {
        val notes = first.confidenceNotes.toMutableList()

        fun pick(field: String, a: Int?, b: Int?): Int? {
            if (a != null) return a
            if (b != null) notes += "$field: recovered by $passName"
            return b
        }

        val odo = pick("odo", first.odo, second.odo)
        val battery = pick("battery", first.battery, second.battery)
        val range = pick("range", first.range, second.range)

        notes += second.confidenceNotes.map { "$passName: $it" }

        val rawLines = if (second.rawLines.isEmpty()) {
            first.rawLines
        } else {
            first.rawLines + OcrLine(separatorFor(passName), 0, 0, 0, 0) + second.rawLines
        }
        return ExtractionResult(odo, battery, range, rawLines, notes)
    }
}
