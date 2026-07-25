package com.rdxindia.evtrack.batch

import com.rdxindia.evtrack.data.EngineMode
import com.rdxindia.evtrack.parser.EnsembleMerger
import com.rdxindia.evtrack.parser.FieldConfidence
import com.rdxindia.evtrack.pipeline.ExtractionPipeline
import com.rdxindia.evtrack.pipeline.PipelineRun

/**
 * Turns raw batch rows into the summary tables. Pure Kotlin so the reporting
 * logic is unit-testable without a device.
 */
object BatchAggregator {

    /**
     * Resolves ground truth onto each row (hash first, filename as fallback)
     * and reports how the match went, so a zero-match run is impossible to
     * miss.
     */
    fun applyGroundTruth(
        rows: List<BatchRow>,
        truth: GroundTruthSet
    ): Pair<List<BatchRow>, MatchSummary> {
        val distinctImages = rows.map { it.imageName }.distinct().size
        if (truth.isEmpty) {
            return rows to MatchSummary(0, distinctImages, "none")
        }
        val resolved = rows.map { row ->
            val hit = truth.lookup(row.imageHash, row.imageName)
            row.copy(groundTruth = hit?.first)
        }
        val matchedImages = resolved
            .filter { it.groundTruth != null }
            .associateBy { it.imageName }
        var byHash = 0
        var byName = 0
        var corrected = 0
        var confirmed = 0
        for ((_, row) in matchedImages) {
            val hit = truth.lookup(row.imageHash, row.imageName) ?: continue
            if (hit.second) byHash++ else byName++
            if (hit.first.corrected) corrected++ else confirmed++
        }
        return resolved to MatchSummary(
            matched = matchedImages.size,
            totalImages = distinctImages,
            source = truth.sourceLabel,
            matchedByHash = byHash,
            matchedByName = byName,
            corrected = corrected,
            confirmed = confirmed
        )
    }

    fun aggregate(rows: List<BatchRow>): BatchStats {
        val fields = PipelineRun.FIELDS
        val modes = rows.map { it.mode }.distinct()

        // (a) fill rate per mode per field
        val fillRates = modes.associateWith { mode ->
            val modeRows = rows.filter { it.mode == mode }
            fields.associateWith { field ->
                Ratio(modeRows.count { it.valueOf(field) != null }, modeRows.size)
            }
        }

        // (b) rung-hit table — every known rung listed, so dead rungs show as zeros
        val seenRungs = rows.flatMap { it.rungEvents }.map { it.rung }
        val rungNames = (ExtractionPipeline.ALL_RUNGS + seenRungs).distinct()
        val rungStats = rungNames.map { rung ->
            val events = rows.flatMap { row -> row.rungEvents.filter { it.rung == rung } }
            RungStat(
                rung = rung,
                ran = events.size,
                producedValues = events.sumOf { it.producedFields.size },
                acceptedValues = events.sumOf { it.acceptedFields.size }
            )
        }

        // (c) engine × variant matrix of accepted values
        val engineVariantMatrix = mutableMapOf<Pair<String, String>, Int>()
        for (row in rows) {
            for (event in row.rungEvents) {
                if (event.acceptedFields.isEmpty()) continue
                val key = event.engine to event.variant
                engineVariantMatrix[key] = (engineVariantMatrix[key] ?: 0) + event.acceptedFields.size
            }
        }

        // (d) merge outcomes and tiebreak usage, BOTH mode only
        val mergeOutcomes = mutableMapOf<String, MutableMap<FieldConfidence, Int>>()
        val tiebreakCounts = EnsembleMerger.TIEBREAK_RULES.associateWith { 0 }.toMutableMap()
        for (row in rows.filter { it.mode == EngineMode.BOTH }) {
            for ((field, outcome) in row.ensembleOutcomes) {
                val level = outcome.confidence ?: continue
                val perField = mergeOutcomes.getOrPut(field) { mutableMapOf() }
                perField[level] = (perField[level] ?: 0) + 1
                outcome.tiebreak?.let { rule ->
                    tiebreakCounts[rule] = (tiebreakCounts[rule] ?: 0) + 1
                }
            }
        }

        // latency per mode
        val latency = modes.associateWith { mode ->
            val modeRows = rows.filter { it.mode == mode }
            LatencyStat(
                runs = modeRows.size,
                totalMs = modeRows.sumOf { it.totalMillis },
                pass1Ms = modeRows.sumOf { it.pass1Millis },
                ladderMs = modeRows.sumOf { it.ladderMillis }
            )
        }

        // (4) accuracy against ground truth resolved onto the rows
        val accuracy = if (rows.none { it.groundTruth != null }) null else {
            modes.associateWith { mode ->
                val modeRows = rows.filter { it.mode == mode }
                fields.associateWith { field ->
                    var correct = 0
                    var compared = 0
                    for (row in modeRows) {
                        val expected = row.groundTruth?.valueOf(field) ?: continue
                        compared++
                        if (row.valueOf(field) == expected) correct++
                    }
                    Ratio(correct, compared)
                }
            }
        }

        return BatchStats(
            imageCount = rows.map { it.imageName }.distinct().size,
            fillRates = fillRates,
            rungStats = rungStats,
            engineVariantMatrix = engineVariantMatrix,
            mergeOutcomes = mergeOutcomes,
            tiebreakCounts = tiebreakCounts,
            latency = latency,
            accuracy = accuracy
        )
    }

    /** Ground-truth lookups are case-insensitive and path-insensitive. */
    fun normalizeName(name: String): String = name.substringAfterLast('/').lowercase().trim()
}
