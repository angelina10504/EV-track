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
import com.rdxindia.evtrack.ocr.EngineLines
import com.rdxindia.evtrack.ocr.OcrService
import com.rdxindia.evtrack.parser.ExtractionResult
import com.rdxindia.evtrack.pipeline.ExtractionPipeline
import com.rdxindia.evtrack.pipeline.VariantPreview
import com.rdxindia.evtrack.util.ImageHasher
import com.rdxindia.evtrack.util.ImageUtils
import java.io.File
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
        val noText: Boolean,
        val variantPreviews: List<VariantPreview> = emptyList(),
        /** Pass-1 lines per engine, for side-by-side debug comparison. */
        val engineLines: List<EngineLines> = emptyList()
    ) : ReviewState()

    data object Saved : ReviewState()
}

class ReviewViewModel(
    private val app: Application,
    private val repository: ReadingRepository
) : AndroidViewModel(app) {

    private val pipeline = ExtractionPipeline(app, OcrService.from(app))

    private val _state = MutableStateFlow<ReviewState>(ReviewState.Loading)
    val state: StateFlow<ReviewState> = _state

    private var processed = false

    /** Per-field provenance JSON for the reading saved from this screen. */
    private var debugJson: String? = null

    fun process(imageUri: Uri) {
        if (processed) return
        processed = true
        viewModelScope.launch {
            val maxOdo = repository.getMaxOdo()
            val run = pipeline.run(imageUri, lastOdo = maxOdo, collectPreviews = true)
            debugJson = run.debugJson
            _state.value = ReviewState.Ready(
                bitmap = run.bitmap,
                extraction = run.extraction,
                maxOdo = maxOdo,
                noText = run.extraction.rawLines.isEmpty(),
                variantPreviews = run.previews,
                engineLines = run.engineLines
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
            // Hash the source bytes so this reading can be matched back to the
            // same image later; the stored copy is renamed, so names can't.
            val imageHash = withContext(Dispatchers.IO) {
                imagePath?.let { ImageHasher.hashFile(File(it)) }
                    ?: ImageHasher.hashUri(app, imageUri)
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
                    imagePath = imagePath,
                    debugJson = debugJson,
                    imageHash = imageHash
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
