package com.rdxindia.evtrack.batch

import com.rdxindia.evtrack.pipeline.PipelineRun

/**
 * Serializes batch results: one row per (image, engine mode) with the
 * extracted values, per-field attribution, timings, rung activity, and — when
 * ground truth is available — per-field correctness.
 */
object BatchCsv {

    fun build(rows: List<BatchRow>): String = buildString {
        append(header()).append('\n')
        for (row in rows) {
            append(rowToCsv(row)).append('\n')
        }
    }

    private fun header(): String {
        val cols = mutableListOf("image", "image_hash", "mode")
        for (field in PipelineRun.FIELDS) {
            cols += listOf(field, "${field}_engine", "${field}_variant", "${field}_stage", "${field}_confidence")
        }
        cols += listOf("total_ms", "pass1_ms", "ladder_ms", "rungs_ran", "rungs_accepted")
        cols += "gt_matched"
        cols += "gt_corrected"
        for (field in PipelineRun.FIELDS) {
            cols += listOf("gt_$field", "${field}_correct")
        }
        return cols.joinToString(",")
    }

    private fun rowToCsv(row: BatchRow): String {
        val cells = mutableListOf(escape(row.imageName), row.imageHash.orEmpty(), row.mode.name)
        for (field in PipelineRun.FIELDS) {
            val p = row.provenance[field]
            cells += row.valueOf(field)?.toString() ?: ""
            cells += p?.engine ?: ""
            cells += p?.variant ?: ""
            cells += p?.stage ?: ""
            cells += p?.confidence?.name ?: ""
        }
        cells += row.totalMillis.toString()
        cells += row.pass1Millis.toString()
        cells += row.ladderMillis.toString()
        cells += escape(row.rungEvents.joinToString(";") { it.rung })
        cells += escape(
            row.rungEvents.filter { it.acceptedFields.isNotEmpty() }
                .joinToString(";") { "${it.rung}:${it.acceptedFields.sorted().joinToString("|")}" }
        )

        val truth = row.groundTruth
        cells += if (truth != null) "1" else "0"
        cells += when {
            truth == null -> ""
            truth.corrected -> "1"
            else -> "0"
        }
        for (field in PipelineRun.FIELDS) {
            val expected = truth?.valueOf(field)
            cells += expected?.toString() ?: ""
            cells += when {
                expected == null -> ""
                row.valueOf(field) == expected -> "1"
                else -> "0"
            }
        }
        return cells.joinToString(",")
    }

    /** Quotes a cell when it contains a comma, quote, or newline. */
    fun escape(raw: String): String =
        if (raw.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + raw.replace("\"", "\"\"") + "\""
        } else {
            raw
        }
}
