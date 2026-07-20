package com.rdxindia.evtrack.util

/**
 * Blur estimation via variance of the 4-neighbour Laplacian: sharp images have
 * strong local intensity changes (high variance), blurred ones don't. Pure
 * Kotlin over a luminance grid — JVM-testable.
 */
object SharpnessMeter {

    /** Advisory threshold: below this, offer the user a retake. */
    const val BLURRY_BELOW = 60.0

    fun score(luminance: IntArray, width: Int, height: Int): Double {
        if (width < 3 || height < 3 || luminance.size < width * height) return 0.0
        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        for (y in 1 until height - 1) {
            val row = y * width
            for (x in 1 until width - 1) {
                val i = row + x
                val lap = (4 * luminance[i] - luminance[i - 1] - luminance[i + 1] -
                    luminance[i - width] - luminance[i + width]).toDouble()
                sum += lap
                sumSq += lap * lap
                n++
            }
        }
        if (n == 0) return 0.0
        val mean = sum / n
        return sumSq / n - mean * mean
    }
}
