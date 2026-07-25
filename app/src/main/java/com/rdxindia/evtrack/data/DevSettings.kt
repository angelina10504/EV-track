package com.rdxindia.evtrack.data

import android.content.Context

/** Which OCR engine(s) run during extraction. */
enum class EngineMode {
    /** ML Kit only. */
    ML_KIT,

    /** PaddleOCR (PP-OCRv5 via ONNX Runtime) only. */
    PADDLE,

    /**
     * Both engines run. ML Kit stays the engine whose lines feed the parser,
     * so extraction behaviour is unchanged; PaddleOCR's lines are captured for
     * side-by-side comparison in the debug panel.
     */
    BOTH
}

/** Developer-only toggles, backed by SharedPreferences. */
object DevSettings {

    private const val PREFS = "evtrack_dev"
    private const val KEY_ENGINE = "engine_mode"

    fun engineMode(context: Context): EngineMode {
        val raw = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ENGINE, null) ?: return EngineMode.BOTH
        return runCatching { EngineMode.valueOf(raw) }.getOrDefault(EngineMode.BOTH)
    }

    fun setEngineMode(context: Context, mode: EngineMode) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ENGINE, mode.name)
            .apply()
    }
}
