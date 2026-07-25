package com.rdxindia.evtrack.ui.batch

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.rdxindia.evtrack.batch.BatchAggregator
import com.rdxindia.evtrack.batch.BatchCsv
import com.rdxindia.evtrack.batch.BatchReport
import com.rdxindia.evtrack.batch.BatchRow
import com.rdxindia.evtrack.batch.GroundTruth
import com.rdxindia.evtrack.batch.GroundTruthSet
import com.rdxindia.evtrack.data.EngineMode
import com.rdxindia.evtrack.data.ReadingRepository
import com.rdxindia.evtrack.ocr.OcrService
import com.rdxindia.evtrack.pipeline.ExtractionPipeline
import com.rdxindia.evtrack.util.ImageHasher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed class BatchState {
    data object Idle : BatchState()
    data class Running(val done: Int, val total: Int, val current: String) : BatchState()
    data class Done(val report: String, val csvFile: File?, val rowCount: Int) : BatchState()
    data class Failed(val message: String) : BatchState()
}

/**
 * Debug-only evaluation harness: runs every picked image through the real
 * pipeline once per engine mode and aggregates how each engine, variant, and
 * retry rung actually performed.
 */
class BatchTestViewModel(
    private val app: Application,
    private val repository: ReadingRepository
) : AndroidViewModel(app) {

    private val _state = MutableStateFlow<BatchState>(BatchState.Idle)
    val state: StateFlow<BatchState> = _state

    /** Ground truth exported from user-corrected readings, when requested. */
    var useReadingsAsTruth: Boolean = false

    fun runBatch(images: List<Uri>) {
        if (images.isEmpty()) {
            _state.value = BatchState.Failed("No images selected")
            return
        }
        viewModelScope.launch {
            try {
                runInternal(images)
            } catch (t: Throwable) {
                _state.value = BatchState.Failed(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    private suspend fun runInternal(images: List<Uri>) {
        // A ground_truth.csv in the picked set is data, not an image to score.
        val truthUri = images.firstOrNull {
            displayNameOf(it).equals(GroundTruth.FILE_NAME, ignoreCase = true)
        }
        val imageUris = images.filter { it != truthUri }

        var groundTruth = GroundTruthSet()
        if (truthUri != null) {
            groundTruth = withContext(Dispatchers.IO) {
                app.contentResolver.openInputStream(truthUri)?.use {
                    GroundTruth.parseCsv(it.readBytes().decodeToString())
                } ?: GroundTruthSet()
            }
        } else if (useReadingsAsTruth) {
            // Backfill hashes for readings saved before hashing existed, then
            // use every saved reading — corrected and confirmed-as-is alike.
            withContext(Dispatchers.IO) { repository.backfillImageHashes() }
            groundTruth = GroundTruth.fromReadings(repository.getAllOnce())
        }

        val modes = listOf(EngineMode.ML_KIT, EngineMode.PADDLE, EngineMode.BOTH)
        val lastOdo = repository.getMaxOdo()
        val rows = mutableListOf<BatchRow>()
        val total = imageUris.size * modes.size
        var done = 0

        for (uri in imageUris) {
            val name = displayNameOf(uri)
            // Hash once per image: this, not the filename, is what matches a
            // picked image back to a saved reading.
            val hash = withContext(Dispatchers.IO) { ImageHasher.hashUri(app, uri) }
            for (mode in modes) {
                _state.value = BatchState.Running(done, total, "$name — ${mode.name}")
                // A fresh service per mode; previews are off so a long batch
                // doesn't accumulate bitmaps.
                val pipeline = ExtractionPipeline(app, OcrService.forMode(app, mode))
                val run = pipeline.run(uri, lastOdo = lastOdo, collectPreviews = false)
                rows += BatchRow(
                    imageName = name,
                    mode = mode,
                    odo = run.extraction.odo,
                    battery = run.extraction.battery,
                    range = run.extraction.range,
                    provenance = run.provenance,
                    rungEvents = run.rungEvents,
                    ensembleOutcomes = run.ensembleOutcomes,
                    totalMillis = run.totalMillis,
                    pass1Millis = run.pass1Millis,
                    ladderMillis = run.ladderMillis,
                    imageHash = hash
                )
                done++
            }
        }

        val (resolvedRows, match) = BatchAggregator.applyGroundTruth(rows, groundTruth)
        val stats = BatchAggregator.aggregate(resolvedRows)
        val report = BatchReport.render(stats, match)
        val csvFile = withContext(Dispatchers.IO) { writeCsv(resolvedRows) }
        _state.value = BatchState.Done(report, csvFile, resolvedRows.size)
    }

    private fun writeCsv(rows: List<BatchRow>): File? = try {
        val dir = File(app.filesDir, "batch").apply { mkdirs() }
        val file = File(dir, "batch_${System.currentTimeMillis()}.csv")
        file.writeText(BatchCsv.build(rows))
        file
    } catch (_: Exception) {
        null
    }

    private fun displayNameOf(uri: Uri): String {
        app.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) {
                cursor.getString(index)?.let { return it }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: uri.toString()
    }

    /** Lists images (and any ground_truth.csv) inside a picked folder. */
    fun listFolder(treeUri: Uri): List<Uri> {
        val tree = DocumentFile.fromTreeUri(app, treeUri) ?: return emptyList()
        return tree.listFiles().mapNotNull { doc ->
            val name = doc.name ?: return@mapNotNull null
            val isImage = doc.type?.startsWith("image/") == true ||
                name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS
            val isTruth = name.equals(GroundTruth.FILE_NAME, ignoreCase = true)
            if (doc.isFile && (isImage || isTruth)) doc.uri else null
        }
    }

    companion object {
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "heic")

        fun factory(app: Application, repository: ReadingRepository) =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    BatchTestViewModel(app, repository) as T
            }
    }
}
