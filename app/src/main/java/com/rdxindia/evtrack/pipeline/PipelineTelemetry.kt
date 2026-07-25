package com.rdxindia.evtrack.pipeline

import android.graphics.Bitmap
import com.rdxindia.evtrack.ocr.EngineLines
import com.rdxindia.evtrack.parser.ExtractionResult
import com.rdxindia.evtrack.parser.FieldOutcome
import com.rdxindia.evtrack.parser.FieldProvenance

/** A preprocessed bitmap that ran through OCR, for the debug panel. */
data class VariantPreview(val label: String, val bitmap: Bitmap)

/**
 * One execution of a retry rung.
 *
 * [producedFields] is what the rung could supply on its own; [acceptedFields]
 * is the subset that actually entered the result. A rung that repeatedly
 * produces values which are never accepted is dead weight — that gap is the
 * whole point of recording both.
 */
data class RungEvent(
    val rung: String,
    val engine: String,
    val variant: String,
    val producedFields: Set<String>,
    val acceptedFields: Set<String>
) {
    val discardedFields: Set<String> get() = producedFields - acceptedFields
}

/** Everything one pipeline execution produced, including timings and telemetry. */
data class PipelineRun(
    val extraction: ExtractionResult,
    val provenance: Map<String, FieldProvenance>,
    val rungEvents: List<RungEvent>,
    /** Per-field ensemble outcomes; empty unless two engines ran. */
    val ensembleOutcomes: Map<String, FieldOutcome>,
    val engineLines: List<EngineLines>,
    val notes: List<String>,
    val debugJson: String,
    val totalMillis: Long,
    val pass1Millis: Long,
    val ladderMillis: Long,
    val bitmap: Bitmap? = null,
    val previews: List<VariantPreview> = emptyList()
) {
    companion object {
        val FIELDS = listOf("odo", "battery", "range")
    }

    fun valueOf(field: String): Int? = when (field) {
        "odo" -> extraction.odo
        "battery" -> extraction.battery
        else -> extraction.range
    }
}
