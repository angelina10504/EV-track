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
import com.rdxindia.evtrack.parser.DashboardParser
import com.rdxindia.evtrack.parser.ExtractionMerger
import com.rdxindia.evtrack.parser.ExtractionResult
import com.rdxindia.evtrack.util.ImageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        val noText: Boolean
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
            val lines = try {
                ocrService.recognize(bitmap)
            } catch (_: Exception) {
                emptyList()
            }
            var extraction = parser.parse(lines)

            // Small glyphs (e.g. a bare "0%" battery, or a dashboard that fills
            // little of the frame) are often below ML Kit's recognition size at
            // the preview resolution. If anything is missing, retry at high
            // resolution: re-decode the original file with real pixels; only if
            // the original has no extra detail, fall back to a 2× upscale.
            if (extraction.odo == null || extraction.battery == null || extraction.range == null) {
                val upscaled = withContext(Dispatchers.IO) {
                    ImageUtils.loadDownscaledBitmap(app, imageUri, ImageUtils.OCR_RETRY_DIMENSION)
                        ?.takeIf { retry ->
                            maxOf(retry.width, retry.height) >
                                maxOf(bitmap.width, bitmap.height) * 1.2
                        }
                        ?: ImageUtils.upscaledForOcr(bitmap)
                }
                if (upscaled != null) {
                    val secondLines = try {
                        ocrService.recognize(upscaled)
                    } catch (_: Exception) {
                        emptyList()
                    }
                    if (secondLines.isNotEmpty()) {
                        extraction = ExtractionMerger.merge(extraction, parser.parse(secondLines))
                    }
                }
            }

            _state.value = ReviewState.Ready(
                bitmap = bitmap,
                extraction = extraction,
                maxOdo = maxOdo,
                noText = extraction.rawLines.isEmpty()
            )
        }
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
        fun factory(app: Application, repository: ReadingRepository) =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ReviewViewModel(app, repository) as T
            }
    }
}
