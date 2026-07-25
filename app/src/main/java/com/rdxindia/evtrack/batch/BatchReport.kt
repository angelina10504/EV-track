package com.rdxindia.evtrack.batch

import com.rdxindia.evtrack.parser.FieldConfidence
import com.rdxindia.evtrack.pipeline.PipelineRun

/** Renders [BatchStats] as fixed-width text tables for the on-screen report. */
object BatchReport {

    fun render(stats: BatchStats, match: MatchSummary): String = buildString {
        appendLine("IMAGES: ${stats.imageCount}    RUNS: ${stats.latency.values.sumOf { it.runs }}")
        appendLine(match.headline())
        if (match.hasTruthSource && match.matched > 0) {
            appendLine(
                "  matched by content hash: ${match.matchedByHash}, by filename: ${match.matchedByName}"
            )
        }
        if (match.hasTruthSource && match.matched == 0) {
            appendLine()
            appendLine("  !! NO IMAGES MATCHED — accuracy cannot be computed.")
            appendLine("     Saved readings are matched by image content hash. A picked image")
            appendLine("     only matches if that exact file was saved as a reading. Readings")
            appendLine("     saved before hashing existed are backfilled from their stored copy;")
            appendLine("     if that copy was deleted they can never match.")
        }
        appendLine()

        appendLine("(a) FIELD FILL RATE PER MODE")
        appendLine("%-10s %-16s %-16s %-16s".format("mode", "odo", "battery", "range"))
        for ((mode, byField) in stats.fillRates) {
            appendLine(
                "%-10s %-16s %-16s %-16s".format(
                    mode.name,
                    byField["odo"]?.format() ?: "—",
                    byField["battery"]?.format() ?: "—",
                    byField["range"]?.format() ?: "—"
                )
            )
        }
        appendLine()

        appendLine("(b) RUNG HITS  (ran / produced / accepted)")
        appendLine("%-26s %6s %10s %10s  %s".format("rung", "ran", "produced", "accepted", ""))
        for (r in stats.rungStats) {
            val flag = when {
                r.ran == 0 -> "never ran"
                r.isDead -> "DEAD — never accepted"
                else -> ""
            }
            appendLine(
                "%-26s %6d %10d %10d  %s".format(r.rung, r.ran, r.producedValues, r.acceptedValues, flag)
            )
        }
        appendLine()

        appendLine("(c) ENGINE x VARIANT — accepted values")
        if (stats.engineVariantMatrix.isEmpty()) {
            appendLine("  (none)")
        } else {
            val engines = stats.engineVariantMatrix.keys.map { it.first }.distinct().sorted()
            val variants = stats.engineVariantMatrix.keys.map { it.second }.distinct().sorted()
            append("%-22s".format("variant"))
            engines.forEach { append("%10s".format(it)) }
            appendLine()
            for (variant in variants) {
                append("%-22s".format(variant))
                engines.forEach { engine ->
                    append("%10d".format(stats.engineVariantMatrix[engine to variant] ?: 0))
                }
                appendLine()
            }
        }
        appendLine()

        appendLine("(d) MERGE OUTCOMES (BOTH mode)")
        if (stats.mergeOutcomes.isEmpty()) {
            appendLine("  (BOTH mode produced no merged fields)")
        } else {
            appendLine("%-10s %8s %8s %8s".format("field", "HIGH", "MEDIUM", "LOW"))
            for (field in PipelineRun.FIELDS) {
                val counts = stats.mergeOutcomes[field] ?: continue
                appendLine(
                    "%-10s %8d %8d %8d".format(
                        field,
                        counts[FieldConfidence.HIGH] ?: 0,
                        counts[FieldConfidence.MEDIUM] ?: 0,
                        counts[FieldConfidence.LOW] ?: 0
                    )
                )
            }
            appendLine()
            appendLine("  disagreements settled by:")
            for ((rule, count) in stats.tiebreakCounts) {
                appendLine("    %-26s %4d".format(rule, count))
            }
        }
        appendLine()

        appendLine("LATENCY (avg ms per run)")
        appendLine("%-10s %10s %10s %10s".format("mode", "total", "pass1", "ladder"))
        for ((mode, l) in stats.latency) {
            appendLine("%-10s %10d %10d %10d".format(mode.name, l.avgTotal, l.avgPass1, l.avgLadder))
        }

        val accuracy = stats.accuracy
        if (accuracy != null) {
            appendLine()
            appendLine("ACCURACY VS GROUND TRUTH (${match.corrected} corrected, ${match.confirmed} as-is)")
            appendLine("%-10s %-16s %-16s %-16s".format("mode", "odo", "battery", "range"))
            for ((mode, byField) in accuracy) {
                appendLine(
                    "%-10s %-16s %-16s %-16s".format(
                        mode.name,
                        byField["odo"]?.format() ?: "—",
                        byField["battery"]?.format() ?: "—",
                        byField["range"]?.format() ?: "—"
                    )
                )
            }
        }
    }
}
