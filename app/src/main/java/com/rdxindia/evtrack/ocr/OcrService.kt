package com.rdxindia.evtrack.ocr

import android.content.Context
import android.graphics.Bitmap
import com.rdxindia.evtrack.data.DevSettings
import com.rdxindia.evtrack.data.EngineMode
import com.rdxindia.evtrack.parser.OcrLine

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

    /** Unchanged contract: the lines the pipeline parses. */
    suspend fun recognize(bitmap: Bitmap): List<OcrLine> = primary.recognize(bitmap)

    /** Runs every configured engine; the primary's result is first. */
    suspend fun recognizeAll(bitmap: Bitmap): List<EngineLines> = buildList {
        add(EngineLines(primary.name, runSafely(primary, bitmap)))
        secondary?.let { add(EngineLines(it.name, runSafely(it, bitmap))) }
    }

    private suspend fun runSafely(engine: OcrEngine, bitmap: Bitmap): List<OcrLine> =
        try {
            engine.recognize(bitmap)
        } catch (_: Exception) {
            emptyList()
        }

    companion object {
        /** Builds the service according to the developer engine setting. */
        fun from(context: Context): OcrService = when (DevSettings.engineMode(context)) {
            EngineMode.ML_KIT -> OcrService(MlKitOcrEngine())
            EngineMode.PADDLE -> OcrService(PaddleOcrEngine(context))
            // ML Kit stays primary so extraction behaviour is unchanged.
            EngineMode.BOTH -> OcrService(MlKitOcrEngine(), PaddleOcrEngine(context))
        }
    }
}
