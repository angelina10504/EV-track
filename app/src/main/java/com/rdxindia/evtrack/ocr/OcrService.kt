package com.rdxindia.evtrack.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.rdxindia.evtrack.parser.OcrLine
import kotlinx.coroutines.tasks.await

/** Wraps ML Kit on-device text recognition behind a suspend function. */
class OcrService {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun recognize(bitmap: Bitmap): List<OcrLine> {
        val result = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
        return result.textBlocks
            .flatMap { it.lines }
            .map { line ->
                val box = line.boundingBox
                OcrLine(
                    text = line.text,
                    left = box?.left ?: 0,
                    top = box?.top ?: 0,
                    right = box?.right ?: 0,
                    bottom = box?.bottom ?: 0
                )
            }
    }
}
