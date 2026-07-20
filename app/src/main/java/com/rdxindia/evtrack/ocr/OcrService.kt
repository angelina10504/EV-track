package com.rdxindia.evtrack.ocr

import android.graphics.Bitmap
import com.rdxindia.evtrack.parser.OcrLine

/**
 * Recognition facade used by the extraction pipeline. Depends only on
 * [OcrEngine]; swap the engine to change recognizers without touching the
 * parser or the retry stages.
 */
class OcrService(private val engine: OcrEngine = MlKitOcrEngine()) {

    val engineName: String get() = engine.name

    suspend fun recognize(bitmap: Bitmap): List<OcrLine> = engine.recognize(bitmap)
}
