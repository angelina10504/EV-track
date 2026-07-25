package com.rdxindia.evtrack.batch

import com.rdxindia.evtrack.data.EngineMode
import com.rdxindia.evtrack.parser.FieldConfidence
import com.rdxindia.evtrack.parser.FieldOutcome
import com.rdxindia.evtrack.parser.FieldProvenance
import com.rdxindia.evtrack.pipeline.RungEvent

/** One image run through the pipeline under one engine mode. */
data class BatchRow(
    val imageName: String,
    val mode: EngineMode,
    val odo: Int?,
    val battery: Int?,
    val range: Int?,
    val provenance: Map<String, FieldProvenance>,
    val rungEvents: List<RungEvent>,
    val ensembleOutcomes: Map<String, FieldOutcome>,
    val totalMillis: Long,
    val pass1Millis: Long,
    val ladderMillis: Long,
    /** Truth for this image, resolved once at match time; null when unmatched. */
    val groundTruth: GroundTruthEntry? = null,
    val imageHash: String? = null
) {
    fun valueOf(field: String): Int? = when (field) {
        "odo" -> odo
        "battery" -> battery
        else -> range
    }
}

/** Expected values for one image. Any field may be unknown. */
data class GroundTruthEntry(
    val filename: String,
    val odo: Int? = null,
    val battery: Int? = null,
    val range: Int? = null,
    /**
     * True when the user actively corrected these values. False means the
     * reading was saved as-is — weaker evidence, since it may not have been
     * checked closely.
     */
    val corrected: Boolean = true
) {
    fun valueOf(field: String): Int? = when (field) {
        "odo" -> odo
        "battery" -> battery
        else -> range
    }
}

/**
 * Resolved ground truth, keyed by image content hash first and filename
 * second. Hash matching is what actually works for saved readings; filename
 * is the fallback for CSV truth written by hand.
 */
data class GroundTruthSet(
    val byHash: Map<String, GroundTruthEntry> = emptyMap(),
    val byName: Map<String, GroundTruthEntry> = emptyMap(),
    val sourceLabel: String = "none",
    val correctedCount: Int = 0,
    val confirmedCount: Int = 0
) {
    val isEmpty: Boolean get() = byHash.isEmpty() && byName.isEmpty()
    val size: Int get() = if (byHash.isNotEmpty()) byHash.size else byName.size

    fun lookup(hash: String?, imageName: String): Pair<GroundTruthEntry, Boolean>? {
        if (hash != null) byHash[hash]?.let { return it to true }
        byName[BatchAggregator.normalizeName(imageName)]?.let { return it to false }
        return null
    }
}

/** How ground truth resolved against the picked images — never silent. */
data class MatchSummary(
    val matched: Int,
    val totalImages: Int,
    val source: String,
    val matchedByHash: Int = 0,
    val matchedByName: Int = 0,
    val corrected: Int = 0,
    val confirmed: Int = 0
) {
    val hasTruthSource: Boolean get() = source != "none"

    fun headline(): String = when {
        !hasTruthSource -> "Ground truth: none supplied"
        matched == 0 -> "Ground truth: 0 of $totalImages images matched (source: $source)"
        else -> "Ground truth: $matched of $totalImages images matched (source: $source) — " +
            "$corrected corrected, $confirmed confirmed-as-is"
    }
}

data class Ratio(val hits: Int, val total: Int) {
    val percent: Double get() = if (total == 0) 0.0 else 100.0 * hits / total
    fun format(): String = if (total == 0) "—" else "%d/%d (%.0f%%)".format(hits, total, percent)
}

/** Times a rung ran, produced a value, and had that value accepted. */
data class RungStat(
    val rung: String,
    val ran: Int,
    val producedValues: Int,
    val acceptedValues: Int
) {
    /** A rung that runs but never lands a value is dead weight. */
    val isDead: Boolean get() = ran > 0 && acceptedValues == 0
}

data class LatencyStat(val runs: Int, val totalMs: Long, val pass1Ms: Long, val ladderMs: Long) {
    val avgTotal: Long get() = if (runs == 0) 0 else totalMs / runs
    val avgPass1: Long get() = if (runs == 0) 0 else pass1Ms / runs
    val avgLadder: Long get() = if (runs == 0) 0 else ladderMs / runs
}

data class BatchStats(
    val imageCount: Int,
    /** mode → field → how often the field came out non-null. */
    val fillRates: Map<EngineMode, Map<String, Ratio>>,
    val rungStats: List<RungStat>,
    /** (engine, variant) → number of accepted field values. */
    val engineVariantMatrix: Map<Pair<String, String>, Int>,
    /** BOTH mode only: field → confidence level → count. */
    val mergeOutcomes: Map<String, Map<FieldConfidence, Int>>,
    /** BOTH mode only: tiebreak rule → times it settled a disagreement. */
    val tiebreakCounts: Map<String, Int>,
    val latency: Map<EngineMode, LatencyStat>,
    /** mode → field → correct/compared; null when no ground truth was supplied. */
    val accuracy: Map<EngineMode, Map<String, Ratio>>?
)
