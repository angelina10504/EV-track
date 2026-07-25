package com.rdxindia.evtrack.ocr

/**
 * Greedy CTC decoding for the PP-OCR recognition head.
 *
 * The charset layout is PaddleOCR's: index 0 is the CTC blank, indices
 * 1..N map to the dictionary lines, and the final index is the space
 * character (PaddleOCR appends it when `use_space_char` is enabled). The
 * English PP-OCRv5 mobile head emits 438 classes = 1 blank + 436 dict + 1
 * space, which [buildCharset] reproduces.
 *
 * Pure Kotlin — JVM-testable.
 */
object CtcDecoder {

    data class Decoded(val text: String, val confidence: Float)

    fun buildCharset(dictionaryLines: List<String>): List<String> =
        buildList {
            add("")            // 0: CTC blank
            addAll(dictionaryLines)
            add(" ")           // last: space
        }

    /**
     * @param logits row-major [timeSteps]×[numClasses] probabilities
     * @return collapsed text plus the mean probability of the kept steps
     */
    fun decode(
        logits: FloatArray,
        timeSteps: Int,
        numClasses: Int,
        charset: List<String>
    ): Decoded {
        if (timeSteps <= 0 || numClasses <= 0 || logits.size < timeSteps * numClasses) {
            return Decoded("", 0f)
        }
        val sb = StringBuilder()
        var confSum = 0.0
        var kept = 0
        var previous = -1

        for (t in 0 until timeSteps) {
            val offset = t * numClasses
            var bestIndex = 0
            var bestValue = Float.NEGATIVE_INFINITY
            for (c in 0 until numClasses) {
                val v = logits[offset + c]
                if (v > bestValue) { bestValue = v; bestIndex = c }
            }
            // Collapse repeats, drop blanks (index 0).
            if (bestIndex != previous && bestIndex != 0) {
                sb.append(charset.getOrElse(bestIndex) { "" })
                confSum += bestValue
                kept++
            }
            previous = bestIndex
        }
        val confidence = if (kept == 0) 0f else (confSum / kept).toFloat()
        return Decoded(sb.toString(), confidence)
    }
}
