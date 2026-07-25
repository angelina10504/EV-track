package com.rdxindia.evtrack.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.rdxindia.evtrack.ocr.OcrService
import com.rdxindia.evtrack.ocr.SegmentOcr
import com.rdxindia.evtrack.parser.DashboardParser
import com.rdxindia.evtrack.parser.EngineExtraction
import com.rdxindia.evtrack.parser.EnsembleMerger
import com.rdxindia.evtrack.parser.EnsembleResult
import com.rdxindia.evtrack.parser.ExtractionMerger
import com.rdxindia.evtrack.parser.ExtractionResult
import com.rdxindia.evtrack.parser.FieldOutcome
import com.rdxindia.evtrack.parser.FieldProvenance
import com.rdxindia.evtrack.parser.OcrLine
import com.rdxindia.evtrack.parser.ProvenanceJson
import com.rdxindia.evtrack.util.ImageUtils
import com.rdxindia.evtrack.util.PrepVariant
import com.rdxindia.evtrack.util.Preprocessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The full extraction pipeline: pass-1 (all engines, ensemble-merged) followed
 * by the retry ladder, instrumented with per-rung telemetry and timings.
 *
 * Lives outside the ViewModel so the review screen and the offline batch
 * harness execute exactly the same code — an evaluation harness that measured
 * a different pipeline would be worse than none.
 */
class ExtractionPipeline(
    private val context: Context,
    private val ocrService: OcrService,
    private val parser: DashboardParser = DashboardParser()
) {

    /** Mutable state threaded through the stages of a single run. */
    private class RunScope {
        var extraction: ExtractionResult = ExtractionResult(null, null, null, emptyList(), emptyList())
        val provenance = mutableMapOf<String, FieldProvenance>()
        val notes = mutableListOf<String>()
        val rungs = mutableListOf<RungEvent>()
        val previews = mutableListOf<VariantPreview>()
    }

    /**
     * Runs the pipeline over [imageUri]. [collectPreviews] should be false for
     * batch runs — retaining preprocessed bitmaps for every image would
     * exhaust memory long before the batch finishes.
     */
    suspend fun run(
        imageUri: Uri,
        lastOdo: Int?,
        collectPreviews: Boolean = true
    ): PipelineRun {
        val startedAt = System.currentTimeMillis()
        val scope = RunScope()

        val bitmap = withContext(Dispatchers.IO) { ImageUtils.loadDownscaledBitmap(context, imageUri) }
            ?: return PipelineRun(
                extraction = scope.extraction,
                provenance = emptyMap(),
                rungEvents = emptyList(),
                ensembleOutcomes = emptyMap(),
                engineLines = emptyList(),
                notes = listOf("image could not be decoded"),
                debugJson = ProvenanceJson.build(emptyMap(), listOf("image could not be decoded")),
                totalMillis = System.currentTimeMillis() - startedAt,
                pass1Millis = 0,
                ladderMillis = 0
            )

        // ---- pass 1: every engine, in parallel, each parsed independently ----
        val pass1StartedAt = System.currentTimeMillis()
        val engineLines = try {
            ocrService.recognizeAll(bitmap)
        } catch (_: Exception) {
            emptyList()
        }
        val perEngine = engineLines.map { EngineExtraction(it.engineName, parser.parse(it.lines)) }
        val lines = engineLines.flatMap { it.lines }
        val ensembleOutcomes = mutableMapOf<String, FieldOutcome>()
        val primaryEngine = ocrService.engineName

        if (perEngine.size >= 2) {
            val ensemble = EnsembleMerger.merge(perEngine[0], perEngine[1], lastOdo = lastOdo)
            scope.extraction = ExtractionResult(
                odo = ensemble.odo.value,
                battery = ensemble.battery.value,
                range = ensemble.range.value,
                rawLines = lines,
                confidenceNotes = perEngine.flatMap { e ->
                    e.result.confidenceNotes.map { "${e.engineName}: $it" }
                }
            )
            scope.notes += ensemble.notes
            for (field in ENSEMBLE_FIELDS) {
                val outcome = ensemble.outcomeFor(field)
                ensembleOutcomes[field] = outcome
                if (outcome.value != null) {
                    scope.provenance[field] = FieldProvenance(
                        field = field,
                        value = outcome.value,
                        engine = outcome.engine ?: primaryEngine,
                        variant = PrepVariant.ORIGINAL.name,
                        stage = STAGE_PASS1_ENSEMBLE,
                        confidence = outcome.confidence
                    )
                }
            }
            scope.rungs += RungEvent(
                rung = STAGE_PASS1_ENSEMBLE,
                engine = EnsembleMerger.ENGINE_BOTH,
                variant = PrepVariant.ORIGINAL.name,
                producedFields = ENSEMBLE_FIELDS.filter { ensembleOutcomes[it]?.value != null }.toSet(),
                acceptedFields = ENSEMBLE_FIELDS.filter { ensembleOutcomes[it]?.value != null }.toSet()
            )
        } else {
            scope.extraction = perEngine.firstOrNull()?.result ?: parser.parse(emptyList())
            recordProvenance(
                scope.provenance, null, scope.extraction, primaryEngine,
                PrepVariant.ORIGINAL.name, STAGE_PASS1
            )
            val filled = fieldsPresent(scope.extraction)
            scope.rungs += RungEvent(STAGE_PASS1, primaryEngine, PrepVariant.ORIGINAL.name, filled, filled)
        }
        val pass1Millis = System.currentTimeMillis() - pass1StartedAt

        // ---- retry ladder ----
        val ladderStartedAt = System.currentTimeMillis()
        var retryBitmap: Bitmap? = null
        var retryLines: List<OcrLine> = emptyList()

        // Rung 1: high-res re-decode of the original file.
        if (isMissing(scope.extraction)) {
            val upscaled = withContext(Dispatchers.IO) {
                ImageUtils.loadDownscaledBitmap(context, imageUri, ImageUtils.OCR_RETRY_DIMENSION)
                    ?.takeIf { retry ->
                        maxOf(retry.width, retry.height) > maxOf(bitmap.width, bitmap.height) * 1.2
                    }
                    ?: ImageUtils.upscaledForOcr(bitmap)
            }
            if (upscaled != null) {
                val firstLines = ladderStage(
                    scope, upscaled, PrepVariant.ORIGINAL.name, STAGE_RETRY_HIGHRES
                )
                if (firstLines.isNotEmpty()) {
                    retryBitmap = upscaled
                    retryLines = firstLines
                }
            }
        }

        // Rungs 2–4: display crop → glare inpaint → preprocessing variants.
        if (isMissing(scope.extraction)) {
            val display = withContext(Dispatchers.Default) {
                ImageUtils.cropBrightDisplay(retryBitmap ?: bitmap)
            }
            if (display != null) {
                val cropBitmap = display.bitmap
                val cropTag = if (display.warpApplied) STAGE_WARP else STAGE_CROP
                if (display.warpApplied) {
                    scope.notes += "display warp: applied, skew ~%.1f°".format(display.skewDegrees ?: 0.0)
                } else if (display.skewDegrees != null) {
                    scope.notes += "display warp: not applied, skew ~%.1f° (below %.0f° threshold)"
                        .format(display.skewDegrees, 5.0)
                }
                if (collectPreviews) {
                    scope.previews += VariantPreview(
                        if (display.warpApplied) "warped crop" else "display crop", thumbnailOf(cropBitmap)
                    )
                }

                var workingCrop: Bitmap = cropBitmap
                withContext(Dispatchers.Default) { ImageUtils.inpaintGlare(cropBitmap) }
                    ?.let { (cleaned, regionCount, coveredFraction) ->
                        workingCrop = cleaned
                        scope.notes += "glare mask: inpainted $regionCount region(s), " +
                            "%.1f%% of crop".format(coveredFraction * 100)
                        if (collectPreviews) {
                            scope.previews += VariantPreview("glare-masked crop", thumbnailOf(cleaned))
                        }
                    }

                for (variant in DISPLAY_CROP_VARIANTS) {
                    if (!isMissing(scope.extraction)) break
                    val prepped = withContext(Dispatchers.Default) {
                        Preprocessor.apply(workingCrop, variant)
                    }
                    if (collectPreviews) {
                        scope.previews += VariantPreview(variant.name, thumbnailOf(prepped))
                    }
                    ladderStage(scope, prepped, variant.name, cropTag)
                }

                // Rungs 5–6 on the rectified crop.
                if (isMissing(scope.extraction)) {
                    val cropLines = ocrStage(cropBitmap)
                    if (cropLines.isNotEmpty()) {
                        segmentRepairStage(
                            scope, cropBitmap, cropLines, cropTag, STAGE_SEGMENT_DECODE,
                            "${ExtractionMerger.SEGMENT_PASS} ($cropTag)"
                        )
                        if (isMissing(scope.extraction)) {
                            anchorRegionStage(scope, cropBitmap, cropLines, cropTag)
                        }
                    }
                }
            }
        }

        // Rung 5 (full frame).
        if (isMissing(scope.extraction)) {
            segmentRepairStage(
                scope, bitmap, lines, PrepVariant.ORIGINAL.name, STAGE_SEGMENT_DECODE,
                ExtractionMerger.SEGMENT_PASS
            )
        }
        if (retryBitmap != null && retryLines.isNotEmpty() && isMissing(scope.extraction)) {
            segmentRepairStage(
                scope, retryBitmap, retryLines, PrepVariant.ORIGINAL.name,
                STAGE_SEGMENT_DECODE_HIGHRES, "${ExtractionMerger.SEGMENT_PASS} (high-res)"
            )
        }

        // Rung 6 (full frame).
        if (isMissing(scope.extraction)) {
            anchorRegionStage(
                scope,
                retryBitmap ?: bitmap,
                if (retryBitmap != null) retryLines else lines,
                PrepVariant.ORIGINAL.name
            )
        }

        val ladderMillis = System.currentTimeMillis() - ladderStartedAt
        val debugJson = ProvenanceJson.build(
            provenance = scope.provenance,
            notes = scope.notes,
            engines = engineLines.map { it.engineName }
        )

        return PipelineRun(
            extraction = scope.extraction.copy(
                confidenceNotes = scope.extraction.confidenceNotes + scope.notes,
                sources = scope.provenance.mapValues { (_, p) -> p.display() }
            ),
            provenance = scope.provenance,
            rungEvents = scope.rungs,
            ensembleOutcomes = ensembleOutcomes,
            engineLines = engineLines,
            notes = scope.notes,
            debugJson = debugJson,
            totalMillis = System.currentTimeMillis() - startedAt,
            pass1Millis = pass1Millis,
            ladderMillis = ladderMillis,
            bitmap = bitmap,
            previews = scope.previews
        )
    }

    /**
     * Runs one rung across the engines in retry order, fill-empty-only.
     * Returns the first engine's lines for later pixel-level stages.
     */
    private suspend fun ladderStage(
        scope: RunScope,
        image: Bitmap,
        variantName: String,
        stageName: String
    ): List<OcrLine> {
        var firstLines: List<OcrLine> = emptyList()
        for (ocrEngine in ocrService.retryEngines) {
            if (!isMissing(scope.extraction)) break
            val engineLines = ocrService.runSafely(ocrEngine, image)
            if (engineLines.isEmpty()) continue
            if (firstLines.isEmpty()) firstLines = engineLines
            val standalone = parser.parse(engineLines)
            val before = scope.extraction
            scope.extraction = ExtractionMerger.merge(
                before, standalone, "$stageName/${ocrEngine.name}"
            )
            recordProvenance(scope.provenance, before, scope.extraction, ocrEngine.name, variantName, stageName)
            scope.rungs += RungEvent(
                rung = stageName,
                engine = ocrEngine.name,
                variant = variantName,
                producedFields = fieldsPresent(standalone),
                acceptedFields = newlyFilled(before, scope.extraction)
            )
        }
        return firstLines
    }

    /** Geometric seven-segment repair of digit-bearing line boxes. */
    private suspend fun segmentRepairStage(
        scope: RunScope,
        sourceBitmap: Bitmap,
        sourceLines: List<OcrLine>,
        variantName: String,
        stageName: String,
        passName: String
    ) {
        val repaired = withContext(Dispatchers.Default) {
            sourceLines.map { line ->
                if (line.text.any { it.isDigit() }) {
                    val seg = SegmentOcr.readLineBox(sourceBitmap, line)
                    if (seg != null && seg.confidence >= SEGMENT_MIN_CONFIDENCE) {
                        line.copy(text = seg.text)
                    } else {
                        line
                    }
                } else {
                    line
                }
            }
        }
        if (repaired.zip(sourceLines).none { (a, b) -> a.text != b.text }) return
        val standalone = parser.parse(repaired)
        val before = scope.extraction
        scope.extraction = ExtractionMerger.merge(before, standalone, passName)
        recordProvenance(scope.provenance, before, scope.extraction, SEGMENT_ENGINE, variantName, stageName)
        scope.rungs += RungEvent(
            rung = stageName,
            engine = SEGMENT_ENGINE,
            variant = variantName,
            producedFields = fieldsPresent(standalone),
            acceptedFields = newlyFilled(before, scope.extraction)
        )
    }

    /**
     * Segment-decodes the region under each missing field's anchor box. This
     * rung only ever attempts fields that are already null, so produced and
     * accepted coincide by construction.
     */
    private suspend fun anchorRegionStage(
        scope: RunScope,
        sourceBitmap: Bitmap,
        sourceLines: List<OcrLine>,
        variantName: String
    ) {
        if (sourceLines.isEmpty()) return
        val current = scope.extraction
        var odo = current.odo
        var battery = current.battery
        var range = current.range
        val notes = current.confidenceNotes.toMutableList()

        withContext(Dispatchers.Default) {
            fun decodeUnder(field: String): String? {
                val anchor = parser.anchorFor(sourceLines, field) ?: return null
                val seg = SegmentOcr.readValueRegionBelow(sourceBitmap, anchor) ?: return null
                if (seg.confidence < SEGMENT_MIN_CONFIDENCE) return null
                notes += "$field: segment-decoded region below anchor \"${anchor.text}\" → \"${seg.text}\""
                return seg.text
            }
            if (odo == null) decodeUnder("odo")?.let { odo = parser.odoValue(it) }
            if (battery == null) decodeUnder("battery")?.let { battery = parser.batteryValue(it) }
            if (range == null) decodeUnder("range")?.let { range = parser.rangeValue(it) }
        }

        if (odo == current.odo && battery == current.battery && range == current.range) return
        scope.extraction = current.copy(
            odo = odo, battery = battery, range = range, confidenceNotes = notes
        )
        recordProvenance(
            scope.provenance, current, scope.extraction, SEGMENT_ENGINE, variantName, STAGE_ANCHOR_REGION
        )
        val accepted = newlyFilled(current, scope.extraction)
        scope.rungs += RungEvent(
            STAGE_ANCHOR_REGION, SEGMENT_ENGINE, variantName, accepted, accepted
        )
    }

    private suspend fun ocrStage(bitmap: Bitmap): List<OcrLine> = try {
        ocrService.recognize(bitmap)
    } catch (_: Exception) {
        emptyList()
    }

    private fun recordProvenance(
        provenance: MutableMap<String, FieldProvenance>,
        before: ExtractionResult?,
        after: ExtractionResult,
        engineName: String,
        variant: String,
        stage: String
    ) {
        if (before?.odo == null && after.odo != null) {
            provenance["odo"] = FieldProvenance("odo", after.odo, engineName, variant, stage)
        }
        if (before?.battery == null && after.battery != null) {
            provenance["battery"] = FieldProvenance("battery", after.battery, engineName, variant, stage)
        }
        if (before?.range == null && after.range != null) {
            provenance["range"] = FieldProvenance("range", after.range, engineName, variant, stage)
        }
    }

    private fun isMissing(extraction: ExtractionResult): Boolean =
        extraction.odo == null || extraction.battery == null || extraction.range == null

    private fun fieldsPresent(result: ExtractionResult): Set<String> = buildSet {
        if (result.odo != null) add("odo")
        if (result.battery != null) add("battery")
        if (result.range != null) add("range")
    }

    private fun newlyFilled(before: ExtractionResult, after: ExtractionResult): Set<String> = buildSet {
        if (before.odo == null && after.odo != null) add("odo")
        if (before.battery == null && after.battery != null) add("battery")
        if (before.range == null && after.range != null) add("range")
    }

    private fun thumbnailOf(bitmap: Bitmap): Bitmap {
        val maxWidth = 480
        if (bitmap.width <= maxWidth) return bitmap
        val scale = maxWidth.toFloat() / bitmap.width
        return Bitmap.createScaledBitmap(
            bitmap, maxWidth, (bitmap.height * scale).toInt().coerceAtLeast(1), true
        )
    }

    companion object {
        const val SEGMENT_ENGINE = "7seg"
        private const val SEGMENT_MIN_CONFIDENCE = 0.75f

        const val STAGE_PASS1 = "pass1"
        const val STAGE_PASS1_ENSEMBLE = "pass1-ensemble"
        const val STAGE_RETRY_HIGHRES = "retry-highres"
        const val STAGE_CROP = "crop"
        const val STAGE_WARP = "WARP"
        const val STAGE_SEGMENT_DECODE = "segment-decode"
        const val STAGE_SEGMENT_DECODE_HIGHRES = "segment-decode-highres"
        const val STAGE_ANCHOR_REGION = "anchor-region"

        /** Every rung name, so the batch report can show dead rungs as zero rows. */
        val ALL_RUNGS = listOf(
            STAGE_PASS1, STAGE_PASS1_ENSEMBLE, STAGE_RETRY_HIGHRES, STAGE_CROP, STAGE_WARP,
            STAGE_SEGMENT_DECODE, STAGE_SEGMENT_DECODE_HIGHRES, STAGE_ANCHOR_REGION
        )

        val ENSEMBLE_FIELDS = listOf(
            EnsembleResult.FIELD_ODO, EnsembleResult.FIELD_BATTERY, EnsembleResult.FIELD_RANGE
        )

        val DISPLAY_CROP_VARIANTS = listOf(
            PrepVariant.CLAHE_STRETCH,
            PrepVariant.MORPH_CLOSE_BIN,
            PrepVariant.ILLUM_FLAT_ADAPTIVE
        )
    }
}
