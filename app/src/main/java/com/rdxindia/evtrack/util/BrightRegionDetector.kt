package com.rdxindia.evtrack.util

/**
 * Finds the backlit dashboard display in a photo: the largest bright,
 * reasonably rectangular, wider-than-tall connected region. Pure Kotlin over
 * a luminance grid — no Android dependencies, JVM-testable.
 */
object BrightRegionDetector {

    data class Region(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width get() = right - left + 1
        val height get() = bottom - top + 1
    }

    fun detect(luminance: IntArray, width: Int, height: Int): Region? {
        if (width < 16 || height < 16 || luminance.size < width * height) return null

        val threshold = otsuThreshold(luminance, width * height)
        val bright = BooleanArray(width * height) { luminance[it] > threshold }
        val brightCount = bright.count { it }
        // No bright pixels, or "bright" is most of the frame (uniform or
        // daylight image): there is no distinct backlit display to isolate.
        if (brightCount == 0 || brightCount > width * height * 8 / 10) return null

        val visited = BooleanArray(width * height)
        val stack = IntArray(width * height)
        var best: Region? = null
        var bestArea = 0

        for (seed in 0 until width * height) {
            if (!bright[seed] || visited[seed]) continue

            // Flood-fill one 4-connected component, tracking bounds and area.
            var top = height
            var bottom = 0
            var left = width
            var right = 0
            var area = 0
            var sp = 0
            stack[sp++] = seed
            visited[seed] = true
            while (sp > 0) {
                val index = stack[--sp]
                val x = index % width
                val y = index / width
                area++
                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                if (y > bottom) bottom = y

                if (x > 0) {
                    val n = index - 1
                    if (bright[n] && !visited[n]) { visited[n] = true; stack[sp++] = n }
                }
                if (x < width - 1) {
                    val n = index + 1
                    if (bright[n] && !visited[n]) { visited[n] = true; stack[sp++] = n }
                }
                if (y > 0) {
                    val n = index - width
                    if (bright[n] && !visited[n]) { visited[n] = true; stack[sp++] = n }
                }
                if (y < height - 1) {
                    val n = index + width
                    if (bright[n] && !visited[n]) { visited[n] = true; stack[sp++] = n }
                }
            }

            val regionW = right - left + 1
            val regionH = bottom - top + 1
            val aspect = regionW.toDouble() / regionH
            val fill = area.toDouble() / (regionW * regionH)
            val areaFraction = area.toDouble() / (width * height)

            // A display is wider than tall, solidly filled, and not a speck.
            val plausible = areaFraction >= 0.02 && aspect in 0.8..4.5 && fill >= 0.45
            if (plausible && area > bestArea) {
                bestArea = area
                best = Region(left, top, right, bottom)
            }
        }
        return best
    }

    private fun otsuThreshold(lum: IntArray, n: Int): Int {
        val hist = IntArray(256)
        for (i in 0 until n) hist[lum[i].coerceIn(0, 255)]++
        var sum = 0L
        for (t in 0..255) sum += t.toLong() * hist[t]
        var sumB = 0L
        var wB = 0L
        var best = 0
        var bestVar = -1.0
        for (t in 0..255) {
            wB += hist[t]
            if (wB == 0L) continue
            val wF = n - wB
            if (wF <= 0L) break
            sumB += t.toLong() * hist[t]
            val mB = sumB.toDouble() / wB
            val mF = (sum - sumB).toDouble() / wF
            val variance = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (variance > bestVar) { bestVar = variance; best = t }
        }
        return best
    }
}
