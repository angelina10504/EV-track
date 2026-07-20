package com.rdxindia.evtrack.ui.review

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.rdxindia.evtrack.data.Reading
import com.rdxindia.evtrack.data.ReadingRepository
import com.rdxindia.evtrack.ocr.OcrService
import com.rdxindia.evtrack.ocr.SegmentOcr
import com.rdxindia.evtrack.parser.DashboardParser
import com.rdxindia.evtrack.parser.ExtractionMerger
import com.rdxindia.evtrack.parser.ExtractionResult
import com.rdxindia.evtrack.parser.OcrLine
import com.rdxindia.evtrack.util.ImageUtils
import com.rdxindia.evtrack.util.PrepVariant
import com.rdxindia.evtrack.util.Preprocessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A preprocessed bitmap that ran through OCR, for the debug panel. */
data class VariantPreview(val label: String, val bitmap: Bitmap)

sealed class ReviewState {
    data object Loading : ReviewState()

    /**
     * OCR finished (or failed softly). [bitmap] may be null if the image could
     * not be decoded; [extraction] holds whatever was found (possibly nothing).
     */
    data class Ready(
        val bitmap: Bitmap?,
        val extraction: ExtractionResult,
        val maxOdo: Int?,
        val noText: Boolean,
        val variantPreviews: List<VariantPreview> = emptyList()
    ) : ReviewState()

    data object Saved : ReviewState()
}

class ReviewViewModel(
    private val app: Application,
    private val repository: ReadingRepository
) : AndroidViewModel(app) {

    private val ocrService = OcrService()
    private val parser = DashboardParser()

    private val _state = MutableStateFlow<ReviewState>(ReviewState.Loading)
    val state: StateFlow<ReviewState> = _state

    private var processed = false

    fun process(imageUri: Uri) {
        if (processed) return
        processed = true
        viewModelScope.launch {
            val maxOdo = repository.getMaxOdo()
            val bitmap = withContext(Dispatchers.IO) {
                ImageUtils.loadDownscaledBitmap(app, imageUri)
            }
            if (bitmap == null) {
                _state.value = ReviewState.Ready(
                    bitmap = null,
                    extraction = ExtractionResult(null, null, null, emptyList(), emptyList()),
                    maxOdo = maxOdo,
                    noText = true
                )
                return@launch
            }
            val engine = ocrService.engineName
            val sources = mutableMapOf<String, String>()

            // Stage 1: always ORIGINAL — never feed preprocessed/binarized
            // images to the first pass.
            val lines = ocrStage(bitmap, PrepVariant.ORIGINAL)
            var extraction = parser.parse(lines)
            recordSources(sources, null, extraction, "$engine / ORIGINAL / pass1")
            var retryBitmap: Bitmap? = null
            var retryLines: List<OcrLine> = emptyList()

            // Stage 2: high-res retry — re-decode the original file with real
            // pixels; only if the original has no extra detail, 2× upscale.
            if (isMissing(extraction)) {
                val upscaled = withContext(Dispatchers.IO) {
                    ImageUtils.loadDownscaledBitmap(app, imageUri, ImageUtils.OCR_RETRY_DIMENSION)
                        ?.takeIf { retry ->
                            maxOf(retry.width, retry.height) >
                                maxOf(bitmap.width, bitmap.height) * 1.2
                        }
                        ?: ImageUtils.upscaledForOcr(bitmap)
                }
                if (upscaled != null) {
                    val secondLines = ocrStage(upscaled, PrepVariant.ORIGINAL)
                    if (secondLines.isNotEmpty()) {
                        val before = extraction
                        extraction = ExtractionMerger.merge(extraction, parser.parse(secondLines))
                        recordSources(sources, before, extraction, "$engine / ORIGINAL / retry-highres")
                        retryBitmap = upscaled
                        retryLines = secondLines
                    }
                }
            }

            // Rungs 2–4: crop to the backlit display, inpaint large glare
            // regions, then walk the preprocessing variant ladder over the
            // cleaned crop until fields fill or rungs run out.
            val pipelineNotes = mutableListOf<String>()
            val previews = mutableListOf<VariantPreview>()
            if (isMissing(extraction)) {
                val crop = withContext(Dispatchers.Default) {
                    ImageUtils.cropBrightDisplay(retryBitmap ?: bitmap)
                }
                if (crop != null) {
                    var workingCrop: Bitmap = crop
                    withContext(Dispatchers.Default) { ImageUtils.inpaintGlare(crop) }
                        ?.let { (cleaned, regionCount, coveredFraction) ->
                            workingCrop = cleaned
                            val percent = "%.1f".format(coveredFraction * 100)
                            pipelineNotes += "glare mask: inpainted $regionCount region(s), $percent% of crop"
                            previews += VariantPreview("glare-masked crop", thumbnailOf(cleaned))
                        }
                    for (variant in DISPLAY_CROP_VARIANTS) {
                        if (!isMissing(extraction)) break
                        val prepped = withContext(Dispatchers.Default) {
                            Preprocessor.apply(workingCrop, variant)
                        }
                        previews += VariantPreview(variant.name, thumbnailOf(prepped))
                        val cropLines = ocrStage(prepped, PrepVariant.ORIGINAL)
                        if (cropLines.isEmpty()) continue
                        val before = extraction
                        extraction = ExtractionMerger.merge(
                            extraction, parser.parse(cropLines), "display-crop/${variant.name}"
                        )
                        recordSources(sources, before, extraction, "$engine / ${variant.name} / display-crop")
                    }
                }
            }

            // Rung 5: geometric seven-segment repair of digit-bearing line
            // boxes, per source bitmap (boxes live in that bitmap's space).
            if (isMissing(extraction)) {
                val before = extraction
                extraction = segmentRepair(extraction, bitmap, lines, ExtractionMerger.SEGMENT_PASS)
                recordSources(sources, before, extraction, "7seg / ORIGINAL / segment-decode")
            }
            if (retryBitmap != null && retryLines.isNotEmpty() && isMissing(extraction)) {
                val before = extraction
                extraction = segmentRepair(
                    extraction, retryBitmap, retryLines, "${ExtractionMerger.SEGMENT_PASS} (high-res)"
                )
                recordSources(sources, before, extraction, "7seg / ORIGINAL / segment-decode-highres")
            }

            // Rung 6: when an anchor exists but OCR produced no line at all
            // for its value, decode the region below the anchor box.
            if (isMissing(extraction)) {
                val before = extraction
                extraction = anchorRegionDecode(
                    extraction,
                    retryBitmap ?: bitmap,
                    if (retryBitmap != null) retryLines else lines
                )
                recordSources(sources, before, extraction, "7seg / ORIGINAL / anchor-region")
            }

            _state.value = ReviewState.Ready(
                bitmap = bitmap,
                extraction = extraction.copy(
                    confidenceNotes = extraction.confidenceNotes + pipelineNotes,
                    sources = sources
                ),
                maxOdo = maxOdo,
                noText = extraction.rawLines.isEmpty(),
                variantPreviews = previews
            )
        }
    }

    private fun isMissing(extraction: ExtractionResult): Boolean =
        extraction.odo == null || extraction.battery == null || extraction.range == null

    /** Downscaled copy for debug-panel display; keeps state memory bounded. */
    private fun thumbnailOf(bitmap: Bitmap): Bitmap {
        val maxWidth = 480
        if (bitmap.width <= maxWidth) return bitmap
        val scale = maxWidth.toFloat() / bitmap.width
        return Bitmap.createScaledBitmap(
            bitmap, maxWidth, (bitmap.height * scale).toInt().coerceAtLeast(1), true
        )
    }

    /** One OCR stage: preprocess with [variant], then recognize. */
    private suspend fun ocrStage(bitmap: Bitmap, variant: PrepVariant): List<OcrLine> {
        val prepped = if (variant == PrepVariant.ORIGINAL) bitmap else {
            withContext(Dispatchers.Default) { Preprocessor.apply(bitmap, variant) }
        }
        return try {
            ocrService.recognize(prepped)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Records "value ← engine / variant / stage" for fields this stage filled. */
    private fun recordSources(
        sources: MutableMap<String, String>,
        before: ExtractionResult?,
        after: ExtractionResult,
        source: String
    ) {
        if (before?.odo == null && after.odo != null) sources["odo"] = "${after.odo} ← $source"
        if (before?.battery == null && after.battery != null) {
            sources["battery"] = "${after.battery} ← $source"
        }
        if (before?.range == null && after.range != null) sources["range"] = "${after.range} ← $source"
    }

    /** Segment-decodes the region under each missing field's anchor box. */
    private suspend fun anchorRegionDecode(
        current: ExtractionResult,
        sourceBitmap: Bitmap,
        sourceLines: List<OcrLine>
    ): ExtractionResult {
        if (sourceLines.isEmpty()) return current
        var odo = current.odo
        var battery = current.battery
        var range = current.range
        val notes = current.confidenceNotes.toMutableList()

        withContext(Dispatchers.Default) {
            fun decodeUnder(field: String): String? {
                val anchor = parser.anchorFor(sourceLines, field) ?: return null
                val seg = SegmentOcr.readValueRegionBelow(sourceBitmap, anchor) ?: return null
                if (seg.confidence < 0.75f) return null
                notes += "$field: segment-decoded region below anchor \"${anchor.text}\" → \"${seg.text}\""
                return seg.text
            }
            if (odo == null) decodeUnder("odo")?.let { odo = parser.odoValue(it) }
            if (battery == null) decodeUnder("battery")?.let { battery = parser.batteryValue(it) }
            if (range == null) decodeUnder("range")?.let { range = parser.rangeValue(it) }
        }

        return if (odo != current.odo || battery != current.battery || range != current.range) {
            current.copy(odo = odo, battery = battery, range = range, confidenceNotes = notes)
        } else {
            current
        }
    }

    /** Decodes digit-bearing line boxes geometrically and re-parses; merged so only missing fields fill. */
    private suspend fun segmentRepair(
        current: ExtractionResult,
        sourceBitmap: Bitmap,
        sourceLines: List<OcrLine>,
        passName: String
    ): ExtractionResult {
        val repaired = withContext(Dispatchers.Default) {
            sourceLines.map { line ->
                if (line.text.any { it.isDigit() }) {
                    val seg = SegmentOcr.readLineBox(sourceBitmap, line)
                    if (seg != null && seg.confidence >= 0.75f) line.copy(text = seg.text) else line
                } else {
                    line
                }
            }
        }
        if (repaired.zip(sourceLines).none { (a, b) -> a.text != b.text }) return current
        return ExtractionMerger.merge(current, parser.parse(repaired), passName)
    }

    fun save(
        imageUri: Uri,
        odo: Int?,
        battery: Int?,
        range: Int?,
        eventType: String
    ) {
        val current = _state.value as? ReviewState.Ready ?: return
        viewModelScope.launch {
            val timestamp = System.currentTimeMillis()
            val imagePath = withContext(Dispatchers.IO) {
                ImageUtils.copyToInternalStorage(app, imageUri, timestamp)
            }
            val extraction = current.extraction
            val userEdited = odo != extraction.odo ||
                battery != extraction.battery ||
                range != extraction.range
            repository.insert(
                Reading(
                    timestamp = timestamp,
                    odoKm = odo,
                    batteryPct = battery,
                    rangeKm = range,
                    eventType = eventType,
                    userEdited = userEdited,
                    ocrOdo = extraction.odo,
                    ocrBattery = extraction.battery,
                    ocrRange = extraction.range,
                    imagePath = imagePath
                )
            )
            _state.value = ReviewState.Saved
        }
    }

    companion object {
        /** Retry ladder over the display crop, gentlest first. */
        private val DISPLAY_CROP_VARIANTS = listOf(
            PrepVariant.CLAHE_STRETCH,
            PrepVariant.MORPH_CLOSE_BIN,
            PrepVariant.ILLUM_FLAT_ADAPTIVE
        )

        fun factory(app: Application, repository: ReadingRepository) =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ReviewViewModel(app, repository) as T
            }
    }
}
