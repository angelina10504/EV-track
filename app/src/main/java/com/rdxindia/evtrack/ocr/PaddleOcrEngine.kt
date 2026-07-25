package com.rdxindia.evtrack.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.rdxindia.evtrack.parser.OcrLine
import com.rdxindia.evtrack.util.ImageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * [OcrEngine] backed by PaddleOCR PP-OCRv5 mobile running on ONNX Runtime:
 * DB text detection → per-box perspective crop → CTC text recognition.
 *
 * Models live in `assets/models/ppocr/`:
 *  - `det.onnx` — PP-OCRv5 mobile detector, input `x` (1,3,H,W), output a
 *    (1,1,H,W) probability map.
 *  - `rec.onnx` — en_PP-OCRv5 mobile recognizer, input `x` (1,3,48,W) with a
 *    **fixed height of 48**, output (1,T,438) class probabilities.
 *  - `en_dict.txt` — 436-line recognition dictionary; with the CTC blank and
 *    the trailing space that accounts for all 438 classes.
 *
 * Sessions are created lazily, shared for the process, warmed up on first use,
 * and inference is serialized by a mutex to bound peak memory on device.
 */
class PaddleOcrEngine(context: Context) : OcrEngine {

    override val name: String = "paddle"

    private val appContext = context.applicationContext
    private val mutex = Mutex()

    private var environment: OrtEnvironment? = null
    private var detSession: OrtSession? = null
    private var recSession: OrtSession? = null
    private var charset: List<String> = emptyList()
    private var initFailed = false

    override suspend fun recognize(bitmap: Bitmap): List<OcrLine> = withContext(Dispatchers.Default) {
        mutex.withLock {
            if (!ensureInitialized()) return@withLock emptyList()
            try {
                detectAndRecognize(bitmap)
            } catch (t: Throwable) {
                Log.w(TAG, "PaddleOCR inference failed", t)
                emptyList()
            }
        }
    }

    /** Lazy init + warm-up. Returns false when the models can't be loaded. */
    private fun ensureInitialized(): Boolean {
        if (initFailed) return false
        if (detSession != null && recSession != null) return true
        return try {
            val env = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(INTRA_OP_THREADS)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            val det = env.createSession(readAsset(DET_ASSET), options)
            val rec = env.createSession(readAsset(REC_ASSET), options)
            val dictionary = appContext.assets.open(DICT_ASSET).bufferedReader().useLines { lines ->
                lines.map { it.trimEnd('\n', '\r') }.toList()
            }
            environment = env
            detSession = det
            recSession = rec
            charset = CtcDecoder.buildCharset(dictionary)
            warmUp(env, det, rec)
            true
        } catch (t: Throwable) {
            Log.e(TAG, "PaddleOCR init failed", t)
            initFailed = true
            releaseQuietly()
            false
        }
    }

    private fun warmUp(env: OrtEnvironment, det: OrtSession, rec: OrtSession) {
        try {
            runFloat(env, det, FloatArray(3 * WARMUP_SIDE * WARMUP_SIDE), 1, 3, WARMUP_SIDE, WARMUP_SIDE)
            runFloat(env, rec, FloatArray(3 * REC_HEIGHT * WARMUP_REC_WIDTH), 1, 3, REC_HEIGHT, WARMUP_REC_WIDTH)
        } catch (t: Throwable) {
            Log.w(TAG, "PaddleOCR warm-up failed (continuing)", t)
        }
    }

    private fun readAsset(path: String): ByteArray =
        appContext.assets.open(path).use { it.readBytes() }

    private fun detectAndRecognize(bitmap: Bitmap): List<OcrLine> {
        val env = environment ?: return emptyList()
        val det = detSession ?: return emptyList()
        val rec = recSession ?: return emptyList()
        if (bitmap.width < 8 || bitmap.height < 8) return emptyList()

        // ---- detection ----
        val longEdge = maxOf(bitmap.width, bitmap.height)
        val ratio = minOf(DET_LIMIT_SIDE.toDouble() / longEdge, 1.0)
        val netW = roundToMultipleOf32(bitmap.width * ratio)
        val netH = roundToMultipleOf32(bitmap.height * ratio)
        val resized = Bitmap.createScaledBitmap(bitmap, netW, netH, true)

        val detInput = normalizeImageNet(resized, netW, netH)
        val (probMap, mapW, mapH) = runFloat(env, det, detInput, 1, 3, netH, netW)
            ?.let { (data, shape) ->
                val h = if (shape.size >= 4) shape[2].toInt() else netH
                val w = if (shape.size >= 4) shape[3].toInt() else netW
                Triple(data, w, h)
            } ?: return emptyList()

        val boxes = DbPostProcessor.extract(
            prob = probMap,
            width = mapW,
            height = mapH,
            scaleX = bitmap.width.toDouble() / mapW,
            scaleY = bitmap.height.toDouble() / mapH,
            threshold = DB_THRESHOLD,
            boxThreshold = DB_BOX_THRESHOLD,
            unclipRatio = DB_UNCLIP_RATIO
        )
        if (boxes.isEmpty()) return emptyList()

        // ---- recognition, one crop at a time ----
        val lines = mutableListOf<OcrLine>()
        for (box in boxes.take(MAX_BOXES)) {
            val corners = DbPostProcessor.orderCorners(box.points)
            if (corners.size != 4) continue
            val cropW = ((DbPostProcessor.distance(corners[0], corners[1]) +
                DbPostProcessor.distance(corners[3], corners[2])) / 2).roundToInt()
            val cropH = ((DbPostProcessor.distance(corners[0], corners[3]) +
                DbPostProcessor.distance(corners[1], corners[2])) / 2).roundToInt()
            if (cropW < MIN_CROP || cropH < MIN_CROP) continue

            val crop = ImageUtils.warpQuadToRect(
                bitmap,
                floatArrayOf(corners[0].x.toFloat(), corners[0].y.toFloat()),
                floatArrayOf(corners[1].x.toFloat(), corners[1].y.toFloat()),
                floatArrayOf(corners[2].x.toFloat(), corners[2].y.toFloat()),
                floatArrayOf(corners[3].x.toFloat(), corners[3].y.toFloat()),
                cropW.coerceAtMost(MAX_CROP_WIDTH), cropH.coerceAtMost(MAX_CROP_HEIGHT)
            ) ?: continue

            val decoded = recognizeCrop(env, rec, crop) ?: continue
            if (decoded.text.isBlank()) continue

            val bounds = box.bounds()
            lines += OcrLine(
                text = decoded.text,
                left = bounds[0].coerceIn(0, bitmap.width),
                top = bounds[1].coerceIn(0, bitmap.height),
                right = bounds[2].coerceIn(0, bitmap.width),
                bottom = bounds[3].coerceIn(0, bitmap.height),
                confidence = decoded.confidence
            )
        }
        // Reading order: top-to-bottom, then left-to-right.
        return lines.sortedWith(compareBy({ it.top }, { it.left }))
    }

    private fun recognizeCrop(env: OrtEnvironment, rec: OrtSession, crop: Bitmap): CtcDecoder.Decoded? {
        val targetW = ceil(REC_HEIGHT.toDouble() * crop.width / crop.height).toInt()
            .coerceIn(MIN_REC_WIDTH, MAX_REC_WIDTH)
        val scaled = Bitmap.createScaledBitmap(crop, targetW, REC_HEIGHT, true)
        val input = normalizeSymmetric(scaled, targetW, REC_HEIGHT)
        val (data, shape) = runFloat(env, rec, input, 1, 3, REC_HEIGHT, targetW) ?: return null
        if (shape.size < 3) return null
        val timeSteps = shape[1].toInt()
        val numClasses = shape[2].toInt()
        return CtcDecoder.decode(data, timeSteps, numClasses, charset)
    }

    /**
     * Runs a (n,c,h,w) float input and returns the flat output plus its shape.
     * The result is read straight out of the native FloatBuffer — materializing
     * it through boxed values would allocate one Float per pixel of the
     * detector's full-size probability map.
     */
    private fun runFloat(
        env: OrtEnvironment, session: OrtSession,
        input: FloatArray, n: Int, c: Int, h: Int, w: Int
    ): Pair<FloatArray, LongArray>? {
        val shape = longArrayOf(n.toLong(), c.toLong(), h.toLong(), w.toLong())
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape).use { tensor ->
            val inputName = session.inputNames.first()
            session.run(mapOf(inputName to tensor)).use { results ->
                val output = results[0] as? OnnxTensor ?: return null
                val outShape = output.info.shape
                val buffer = output.floatBuffer ?: return null
                val flat = FloatArray(buffer.remaining())
                buffer.get(flat)
                return flat to outShape
            }
        }
    }

    /** ImageNet mean/std normalization, RGB, CHW — the detector's convention. */
    private fun normalizeImageNet(bitmap: Bitmap, w: Int, h: Int): FloatArray {
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = FloatArray(3 * w * h)
        val plane = w * h
        for (i in 0 until plane) {
            val p = pixels[i]
            val r = ((p shr 16) and 0xFF) / 255f
            val g = ((p shr 8) and 0xFF) / 255f
            val b = (p and 0xFF) / 255f
            out[i] = (r - 0.485f) / 0.229f
            out[plane + i] = (g - 0.456f) / 0.224f
            out[2 * plane + i] = (b - 0.406f) / 0.225f
        }
        return out
    }

    /** (x/255 - 0.5) / 0.5, RGB, CHW — the recognizer's convention. */
    private fun normalizeSymmetric(bitmap: Bitmap, w: Int, h: Int): FloatArray {
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = FloatArray(3 * w * h)
        val plane = w * h
        for (i in 0 until plane) {
            val p = pixels[i]
            out[i] = (((p shr 16) and 0xFF) / 255f - 0.5f) / 0.5f
            out[plane + i] = (((p shr 8) and 0xFF) / 255f - 0.5f) / 0.5f
            out[2 * plane + i] = ((p and 0xFF) / 255f - 0.5f) / 0.5f
        }
        return out
    }

    private fun roundToMultipleOf32(value: Double): Int =
        (Math.round(value / 32.0).toInt() * 32).coerceAtLeast(32)

    private fun releaseQuietly() {
        try { detSession?.close() } catch (_: Throwable) {}
        try { recSession?.close() } catch (_: Throwable) {}
        detSession = null
        recSession = null
    }

    private companion object {
        const val TAG = "PaddleOcrEngine"
        const val DET_ASSET = "models/ppocr/det.onnx"
        const val REC_ASSET = "models/ppocr/rec.onnx"
        const val DICT_ASSET = "models/ppocr/en_dict.txt"

        const val DET_LIMIT_SIDE = 960
        const val DB_THRESHOLD = 0.3f
        const val DB_BOX_THRESHOLD = 0.6f
        const val DB_UNCLIP_RATIO = 1.5

        /** PP-OCRv5 recognition input height is fixed at 48 (not 32). */
        const val REC_HEIGHT = 48
        const val MIN_REC_WIDTH = 16
        const val MAX_REC_WIDTH = 1600
        const val MIN_CROP = 4
        const val MAX_CROP_WIDTH = 2400
        const val MAX_CROP_HEIGHT = 480
        const val MAX_BOXES = 64
        const val INTRA_OP_THREADS = 2
        const val WARMUP_SIDE = 32
        const val WARMUP_REC_WIDTH = 64
    }
}
