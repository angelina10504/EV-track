package com.rdxindia.evtrack.batch

import com.rdxindia.evtrack.data.Reading

/**
 * Ground truth from either a `ground_truth.csv` shipped alongside the images,
 * or exported from saved readings.
 *
 * Readings are matched by image content hash, not filename: images are renamed
 * to `reading_<millis>.jpg` when copied into internal storage, so the original
 * gallery filename is gone and name matching can never succeed. Filename
 * remains a fallback for CSV truth, where the user writes real names.
 */
object GroundTruth {

    const val FILE_NAME = "ground_truth.csv"

    /**
     * Parses `filename,odo,battery,range`. A header row is optional, blank and
     * `#` comment lines are skipped, and empty cells mean "unknown" rather than
     * zero — so a partially-filled truth file only scores the fields it knows.
     */
    fun parseCsv(text: String): GroundTruthSet {
        val byName = LinkedHashMap<String, GroundTruthEntry>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val cells = splitCsvLine(line)
            if (cells.isEmpty()) continue
            val name = cells[0].trim()
            if (name.isEmpty()) continue
            if (name.equals("filename", ignoreCase = true) || name.equals("image", ignoreCase = true)) continue
            byName[BatchAggregator.normalizeName(name)] = GroundTruthEntry(
                filename = name,
                odo = cells.getOrNull(1)?.trim()?.toIntOrNull(),
                battery = cells.getOrNull(2)?.trim()?.toIntOrNull(),
                range = cells.getOrNull(3)?.trim()?.toIntOrNull(),
                corrected = true
            )
        }
        return GroundTruthSet(
            byHash = emptyMap(),
            byName = byName,
            sourceLabel = "$FILE_NAME (${byName.size} entries)"
        )
    }

    /**
     * Export mode: every saved reading is usable truth, keyed by image hash.
     *
     * User-corrected rows are the strongest evidence. Rows saved as-is are
     * weaker — the user may have accepted them without checking closely — so
     * they are kept but counted separately and surfaced in the report.
     */
    fun fromReadings(readings: List<Reading>): GroundTruthSet {
        val byHash = LinkedHashMap<String, GroundTruthEntry>()
        val byName = LinkedHashMap<String, GroundTruthEntry>()
        var corrected = 0
        var confirmed = 0

        for (reading in readings) {
            val name = reading.imagePath?.substringAfterLast('/').orEmpty()
            val entry = GroundTruthEntry(
                filename = name.ifEmpty { "reading_${reading.id}" },
                odo = reading.odoKm,
                battery = reading.batteryPct,
                range = reading.rangeKm,
                corrected = reading.userEdited
            )
            val hash = reading.imageHash
            // Newest-first input, so an earlier entry is the more recent one.
            if (hash != null && !byHash.containsKey(hash)) {
                byHash[hash] = entry
                if (reading.userEdited) corrected++ else confirmed++
            }
            if (name.isNotEmpty()) byName.putIfAbsent(BatchAggregator.normalizeName(name), entry)
        }

        val label = "saved readings — $corrected corrected, $confirmed confirmed-as-is"
        return GroundTruthSet(byHash, byName, label, corrected, confirmed)
    }

    /** Renders truth back to CSV, for seeding a truth file from corrections. */
    fun toCsv(entries: Collection<GroundTruthEntry>): String = buildString {
        append("filename,odo,battery,range\n")
        for (e in entries) {
            append(BatchCsv.escape(e.filename)).append(',')
            append(e.odo?.toString() ?: "").append(',')
            append(e.battery?.toString() ?: "").append(',')
            append(e.range?.toString() ?: "").append('\n')
        }
    }

    /** Minimal CSV splitter handling quoted cells with embedded commas. */
    internal fun splitCsvLine(line: String): List<String> {
        val cells = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                inQuotes && ch == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
                    sb.append('"'); i++
                }
                ch == '"' -> inQuotes = !inQuotes
                ch == ',' && !inQuotes -> { cells += sb.toString(); sb.setLength(0) }
                else -> sb.append(ch)
            }
            i++
        }
        cells += sb.toString()
        return cells
    }
}
