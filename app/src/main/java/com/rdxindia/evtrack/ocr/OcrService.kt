package com.rdxindia.evtrack.ocr

import android.content.Context
import android.graphics.Bitmap
import com.rdxindia.evtrack.data.DevSettings
import com.rdxindia.evtrack.data.EngineMode
import com.rdxindia.evtrack.parser.OcrLine
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** One engine's output for a single image, for debug display. */
data class EngineLines(val engineName: String, val lines: List<OcrLine>)

/**
 * Recognition facade used by the extraction pipeline. Depends only on
 * [OcrEngine]s, so recognizers can be swapped or compared without the parser
 * or retry stages knowing which one produced a line.
 *
 * [primary] is the engine whose lines drive extraction; [secondary] (when the
 * developer setting asks for BOTH) runs only so the debug panel can show the
 * two engines side by side.
 */
class OcrService(
    private val primary: OcrEngine,
    private val secondary: OcrEngine? = null
) {

    val engineName: String get() = primary.name

    /** True when two engines are configured, so ensemble merging applies. */
    val isEnsemble: Boolean get() = secondary != null

    /** All configured engines, primary first. */
    val engines: List<OcrEngine> get() = listOfNotNull(primary, secondary)

    /**
     * Engines in retry-ladder order. PaddleOCR goes first: it reads the
     * segment-style dashboard fonts the retry stages exist for, and each stage
     * is fill-empty-only so a later engine can still contribute.
     */
    val retryEngines: List<OcrEngine>
        get() = engines.sortedByDescending { it.name == PADDLE_ENGINE }

    /** Unchanged contract: the lines the pipeline parses. */
    suspend fun recognize(bitmap: Bitmap): List<OcrLine> = primary.recognize(bitmap)

    /** Runs every configured engine concurrently; the primary's result is first. */
    suspend fun recognizeAll(bitmap: Bitmap): List<EngineLines> = coroutineScope {
        engines
            .map { engine -> async { EngineLines(engine.name, runSafely(engine, bitmap)) } }
            .awaitAll()
    }

    suspend fun runSafely(engine: OcrEngine, bitmap: Bitmap): List<OcrLine> =
        try {
            engine.recognize(bitmap)
        } catch (_: Exception) {
            emptyList()
        }

    companion object {
        const val PADDLE_ENGINE = "paddle"

        /** Builds the service according to the developer engine setting. */
        fun from(context: Context): OcrService = forMode(context, DevSettings.engineMode(context))

        /** Builds a service for an explicit mode, ignoring the stored setting. */
        fun forMode(context: Context, mode: EngineMode): OcrService = when (mode) {
            EngineMode.ML_KIT -> OcrService(MlKitOcrEngine())
            EngineMode.PADDLE -> OcrService(PaddleOcrEngine(context))
            // ML Kit stays primary so extraction behaviour is unchanged.
            EngineMode.BOTH -> OcrService(MlKitOcrEngine(), PaddleOcrEngine(context))
        }
    }
}
