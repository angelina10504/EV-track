package com.rdxindia.evtrack.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rdxindia.evtrack.data.DevSettings
import com.rdxindia.evtrack.data.EngineMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device smoke test for the OCR engines. Its real job is to prove that
 * after ABI filtering the native libraries (`libmlkit_google_ocr_pipeline.so`
 * and `libonnxruntime.so`) still load on the target ABI and produce output.
 */
@RunWith(AndroidJUnit4::class)
class EngineSmokeTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** A synthetic dashboard: bright text on a dark background. */
    private fun dashboardBitmap(): Bitmap {
        val bitmap = Bitmap.createBitmap(900, 420, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(10, 14, 45))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(235, 242, 255)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textSize = 46f
        }
        canvas.drawText("ODO km", 45f, 120f, paint)
        canvas.drawText("12676", 45f, 190f, paint)
        canvas.drawText("BATTERY", 520f, 120f, paint)
        canvas.drawText("99%", 600f, 190f, paint)
        canvas.drawText("RANGE km", 520f, 300f, paint)
        canvas.drawText("91", 640f, 370f, paint)
        return bitmap
    }

    private fun runMode(mode: EngineMode): List<EngineLines> {
        DevSettings.setEngineMode(context, mode)
        val service = OcrService.from(context)
        val results = runBlocking { service.recognizeAll(dashboardBitmap()) }
        Log.i(TAG, "===== mode=$mode =====")
        for (engine in results) {
            Log.i(TAG, "  engine=${engine.engineName} lines=${engine.lines.size}")
            engine.lines.forEach { line ->
                Log.i(TAG, "     \"${line.text}\" conf=${line.confidence} ${line.boxString()}")
            }
        }
        return results
    }

    @Test
    fun mlKitEngineLoadsAndProducesOutput() {
        val results = runMode(EngineMode.ML_KIT)
        assertTrue("expected a single engine, got ${results.size}", results.size == 1)
        assertTrue("mlkit produced no lines", results[0].lines.isNotEmpty())
    }

    @Test
    fun paddleEngineLoadsAndProducesOutput() {
        val results = runMode(EngineMode.PADDLE)
        assertTrue("expected a single engine, got ${results.size}", results.size == 1)
        assertTrue(
            "paddle produced no lines — ONNX Runtime native lib may not have loaded",
            results[0].lines.isNotEmpty()
        )
    }

    @Test
    fun bothEnginesLoadAndProduceOutput() {
        val results = runMode(EngineMode.BOTH)
        assertTrue("expected two engines, got ${results.size}", results.size == 2)
        results.forEach { engine ->
            assertTrue("${engine.engineName} produced no lines", engine.lines.isNotEmpty())
        }
    }

    private companion object {
        const val TAG = "EngineSmokeTest"
    }
}
