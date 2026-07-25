package com.rdxindia.evtrack.parser

/** How much the ensemble trusts a merged field value. */
enum class FieldConfidence { HIGH, MEDIUM, LOW }

/** One engine's parse of the same image. */
data class EngineExtraction(val engineName: String, val result: ExtractionResult)

/** The merged outcome for a single field. */
data class FieldOutcome(
    val value: Int?,
    val confidence: FieldConfidence?,
    /** Engine that supplied the value; "both" when the engines agreed. */
    val engine: String?,
    val note: String?,
    /** Which rule settled a disagreement; null unless the engines disagreed. */
    val tiebreak: String? = null
)

data class EnsembleResult(
    val odo: FieldOutcome,
    val battery: FieldOutcome,
    val range: FieldOutcome,
    val notes: List<String>
) {
    fun outcomeFor(field: String): FieldOutcome = when (field) {
        FIELD_ODO -> odo
        FIELD_BATTERY -> battery
        else -> range
    }

    companion object {
        const val FIELD_ODO = "odo"
        const val FIELD_BATTERY = "battery"
        const val FIELD_RANGE = "range"
    }
}

/**
 * Combines two engines' independent parses of the same image, field by field.
 *
 * Agreement is the strongest signal available without ground truth, so equal
 * values are HIGH; a value only one engine found is MEDIUM; a genuine
 * disagreement is LOW and is arbitrated by plausibility first (sanity ranges),
 * then by raw OCR confidence, then by a configured preference.
 *
 * Pure Kotlin — no Android dependencies.
 */
object EnsembleMerger {

    /** Engine preferred for numeric fields when nothing else separates them. */
    const val DEFAULT_NUMERIC_PREFERENCE = "paddle"

    fun merge(
        first: EngineExtraction,
        second: EngineExtraction,
        lastOdo: Int? = null,
        numericPreference: String = DEFAULT_NUMERIC_PREFERENCE
    ): EnsembleResult {
        val notes = mutableListOf<String>()

        val odo = mergeField(
            EnsembleResult.FIELD_ODO, first, second, notes, numericPreference
        ) { value -> odoPasses(value, lastOdo) }

        val battery = mergeField(
            EnsembleResult.FIELD_BATTERY, first, second, notes, numericPreference
        ) { value -> value in 0..100 }

        val range = mergeField(
            EnsembleResult.FIELD_RANGE, first, second, notes, numericPreference
        ) { value -> value in 0..250 }

        return EnsembleResult(odo, battery, range, notes)
    }

    /** Odo must not go backwards, nor jump more than 1000 km, versus [lastOdo]. */
    private fun odoPasses(value: Int, lastOdo: Int?): Boolean {
        if (value < 0) return false
        if (lastOdo == null) return true
        return value >= lastOdo && value <= lastOdo + 1000
    }

    private fun mergeField(
        field: String,
        first: EngineExtraction,
        second: EngineExtraction,
        notes: MutableList<String>,
        numericPreference: String,
        sane: (Int) -> Boolean
    ): FieldOutcome {
        val a = valueOf(field, first.result)
        val b = valueOf(field, second.result)

        // Neither engine found it — the retry ladder will try to fill it.
        if (a == null && b == null) return FieldOutcome(null, null, null, null)

        // Exactly one engine found it.
        if (a == null || b == null) {
            val value = a ?: b!!
            val engine = if (a != null) first.engineName else second.engineName
            val note = "$field: only $engine found $value"
            notes += note
            return FieldOutcome(value, FieldConfidence.MEDIUM, engine, note)
        }

        // Both agree.
        if (a == b) {
            val note = "$field: ${first.engineName} and ${second.engineName} agree on $a"
            notes += note
            return FieldOutcome(a, FieldConfidence.HIGH, ENGINE_BOTH, note)
        }

        // Genuine disagreement: plausibility, then OCR confidence, then preference.
        val aSane = sane(a)
        val bSane = sane(b)
        val confA = lineConfidenceFor(a, first.result.rawLines)
        val confB = lineConfidenceFor(b, second.result.rawLines)

        val (winner, winnerEngine, reason) = when {
            aSane && !bSane -> Triple(a, first.engineName, TIEBREAK_SANITY)
            bSane && !aSane -> Triple(b, second.engineName, TIEBREAK_SANITY)
            confA != null && confB != null && confA != confB ->
                if (confA > confB) Triple(a, first.engineName, TIEBREAK_CONFIDENCE)
                else Triple(b, second.engineName, TIEBREAK_CONFIDENCE)
            first.engineName == numericPreference -> Triple(a, first.engineName, TIEBREAK_PREFERENCE)
            second.engineName == numericPreference -> Triple(b, second.engineName, TIEBREAK_PREFERENCE)
            else -> Triple(a, first.engineName, TIEBREAK_PRIMARY_FALLBACK)
        }

        val note = "$field: disagreement — ${first.engineName}=$a vs ${second.engineName}=$b; " +
            "took $winner from $winnerEngine ($reason)"
        notes += note
        return FieldOutcome(winner, FieldConfidence.LOW, winnerEngine, note, tiebreak = reason)
    }

    private fun valueOf(field: String, result: ExtractionResult): Int? = when (field) {
        EnsembleResult.FIELD_ODO -> result.odo
        EnsembleResult.FIELD_BATTERY -> result.battery
        else -> result.range
    }

    /**
     * Best raw OCR confidence among lines that contain [value]'s digits.
     * Null when no line matches or no engine reported usable confidences —
     * ML Kit's on-device recognizer often omits them.
     */
    internal fun lineConfidenceFor(value: Int, lines: List<OcrLine>): Float? {
        val needle = value.toString()
        var best: Float? = null
        for (line in lines) {
            val digits = line.text.filter { it.isDigit() }
            if (!digits.contains(needle)) continue
            val confidence = line.confidence ?: continue
            if (confidence.isNaN()) continue
            if (best == null || confidence > best) best = confidence
        }
        return best
    }

    const val ENGINE_BOTH = "both"

    const val TIEBREAK_SANITY = "sanity check"
    const val TIEBREAK_CONFIDENCE = "higher OCR confidence"
    const val TIEBREAK_PREFERENCE = "engine preference"
    const val TIEBREAK_PRIMARY_FALLBACK = "primary engine fallback"

    val TIEBREAK_RULES = listOf(
        TIEBREAK_SANITY, TIEBREAK_CONFIDENCE, TIEBREAK_PREFERENCE, TIEBREAK_PRIMARY_FALLBACK
    )
}
