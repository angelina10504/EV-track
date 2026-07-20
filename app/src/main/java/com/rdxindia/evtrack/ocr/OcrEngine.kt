package com.rdxindia.evtrack.ocr

import android.graphics.Bitmap
import com.rdxindia.evtrack.parser.OcrLine

/**
 * A text-recognition engine. Everything downstream of recognition — the
 * spatial parser, retry passes, segment decoding — consumes only
 * [OcrLine]s, so engines are interchangeable (ML Kit today; a custom
 * TFLite model or another OCR library later).
 */
interface OcrEngine {
    /** Short identifier for logs and debug notes, e.g. "mlkit". */
    val name: String

    suspend fun recognize(bitmap: Bitmap): List<OcrLine>
}
