package com.rdxindia.evtrack.util

/**
 * Pure-Kotlin image filters over row-major luminance grids (0–255 ints).
 * No Android or OpenCV dependencies — JVM-testable. Bitmap glue lives in
 * [Preprocessor].
 */
object GridFilters {

    fun otsu(lum: IntArray, n: Int): Int {
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

    /**
     * Contrast-limited adaptive histogram equalization: per-tile clipped
     * histogram equalization with bilinear interpolation between tile maps.
     */
    fun clahe(lum: IntArray, width: Int, height: Int, tiles: Int = 8, clipLimit: Double = 2.0): IntArray {
        if (width < tiles * 2 || height < tiles * 2) return lum.copyOf()
        val tileW = (width + tiles - 1) / tiles
        val tileH = (height + tiles - 1) / tiles
        val maps = Array(tiles * tiles) { IntArray(256) }

        for (ty in 0 until tiles) {
            for (tx in 0 until tiles) {
                val x0 = tx * tileW
                val x1 = minOf(width, x0 + tileW)
                val y0 = ty * tileH
                val y1 = minOf(height, y0 + tileH)
                val map = maps[ty * tiles + tx]
                val count = (x1 - x0) * (y1 - y0)
                if (count <= 0) {
                    for (i in 0..255) map[i] = i
                    continue
                }
                val hist = IntArray(256)
                for (y in y0 until y1) {
                    val row = y * width
                    for (x in x0 until x1) hist[lum[row + x].coerceIn(0, 255)]++
                }
                val clip = maxOf(1, (clipLimit * count / 256).toInt())
                var excess = 0
                for (i in 0..255) if (hist[i] > clip) { excess += hist[i] - clip; hist[i] = clip }
                val bonus = excess / 256
                for (i in 0..255) hist[i] += bonus
                // Normalize by the clipped histogram's actual mass — using the
                // original pixel count loses the redistribution round-off and
                // can collapse the output range.
                var total = 0
                for (i in 0..255) total += hist[i]
                if (total <= 0) total = 1
                var cdf = 0
                for (i in 0..255) {
                    cdf += hist[i]
                    map[i] = (cdf * 255L / total).toInt().coerceIn(0, 255)
                }
            }
        }

        val out = IntArray(width * height)
        val maxTile = (tiles - 1).toDouble()
        for (y in 0 until height) {
            val gy = ((y.toDouble() - tileH / 2.0) / tileH).coerceIn(0.0, maxTile)
            val ty0 = gy.toInt()
            val ty1 = minOf(ty0 + 1, tiles - 1)
            val fy = gy - ty0
            val row = y * width
            for (x in 0 until width) {
                val gx = ((x.toDouble() - tileW / 2.0) / tileW).coerceIn(0.0, maxTile)
                val tx0 = gx.toInt()
                val tx1 = minOf(tx0 + 1, tiles - 1)
                val fx = gx - tx0
                val v = lum[row + x].coerceIn(0, 255)
                val m00 = maps[ty0 * tiles + tx0][v]
                val m10 = maps[ty0 * tiles + tx1][v]
                val m01 = maps[ty1 * tiles + tx0][v]
                val m11 = maps[ty1 * tiles + tx1][v]
                val top = m00 * (1 - fx) + m10 * fx
                val bottom = m01 * (1 - fx) + m11 * fx
                out[row + x] = (top * (1 - fy) + bottom * fy).toInt().coerceIn(0, 255)
            }
        }
        return out
    }

    /** Separable box blur with clamped edges, O(n) via prefix sums. */
    fun boxBlur(lum: IntArray, width: Int, height: Int, radius: Int): IntArray {
        if (radius <= 0) return lum.copyOf()
        val tmp = IntArray(width * height)
        val prefix = IntArray(maxOf(width, height) + 1)

        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) prefix[x + 1] = prefix[x] + lum[row + x]
            for (x in 0 until width) {
                val lo = maxOf(0, x - radius)
                val hi = minOf(width - 1, x + radius)
                tmp[row + x] = (prefix[hi + 1] - prefix[lo]) / (hi - lo + 1)
            }
        }
        val out = IntArray(width * height)
        for (x in 0 until width) {
            for (y in 0 until height) prefix[y + 1] = prefix[y] + tmp[y * width + x]
            for (y in 0 until height) {
                val lo = maxOf(0, y - radius)
                val hi = minOf(height - 1, y + radius)
                out[y * width + x] = (prefix[hi + 1] - prefix[lo]) / (hi - lo + 1)
            }
        }
        return out
    }

    /** 5×5 Gaussian (separable binomial 1-4-6-4-1). */
    fun gaussian5(lum: IntArray, width: Int, height: Int): IntArray {
        val weights = intArrayOf(1, 4, 6, 4, 1)
        val tmp = IntArray(width * height)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                var acc = 0
                for (k in -2..2) acc += weights[k + 2] * lum[row + (x + k).coerceIn(0, width - 1)]
                tmp[row + x] = acc / 16
            }
        }
        val out = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var acc = 0
                for (k in -2..2) acc += weights[k + 2] * tmp[(y + k).coerceIn(0, height - 1) * width + x]
                out[y * width + x] = acc / 16
            }
        }
        return out
    }

    /** Morphological close (dilate then erode) with a square kernel of [radius]. */
    fun morphClose(mask: BooleanArray, width: Int, height: Int, radius: Int): BooleanArray {
        if (radius <= 0) return mask.copyOf()
        return erode(dilate(mask, width, height, radius), width, height, radius)
    }

    private fun dilate(mask: BooleanArray, width: Int, height: Int, radius: Int): BooleanArray =
        windowOp(mask, width, height, radius, requireAll = false)

    private fun erode(mask: BooleanArray, width: Int, height: Int, radius: Int): BooleanArray =
        windowOp(mask, width, height, radius, requireAll = true)

    private fun windowOp(
        mask: BooleanArray, width: Int, height: Int, radius: Int, requireAll: Boolean
    ): BooleanArray {
        val prefix = IntArray(maxOf(width, height) + 1)
        val tmp = BooleanArray(width * height)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) prefix[x + 1] = prefix[x] + if (mask[row + x]) 1 else 0
            for (x in 0 until width) {
                val lo = maxOf(0, x - radius)
                val hi = minOf(width - 1, x + radius)
                val sum = prefix[hi + 1] - prefix[lo]
                tmp[row + x] = if (requireAll) sum == hi - lo + 1 else sum > 0
            }
        }
        val out = BooleanArray(width * height)
        for (x in 0 until width) {
            for (y in 0 until height) prefix[y + 1] = prefix[y] + if (tmp[y * width + x]) 1 else 0
            for (y in 0 until height) {
                val lo = maxOf(0, y - radius)
                val hi = minOf(height - 1, y + radius)
                val sum = prefix[hi + 1] - prefix[lo]
                out[y * width + x] = if (requireAll) sum == hi - lo + 1 else sum > 0
            }
        }
        return out
    }

    /** Median height of 4-connected foreground components, or null when empty. */
    fun medianComponentHeight(mask: BooleanArray, width: Int, height: Int): Int? {
        val visited = BooleanArray(width * height)
        val stack = IntArray(width * height)
        val heights = mutableListOf<Int>()
        for (seed in 0 until width * height) {
            if (!mask[seed] || visited[seed]) continue
            var top = height
            var bottom = 0
            var sp = 0
            stack[sp++] = seed
            visited[seed] = true
            while (sp > 0) {
                val index = stack[--sp]
                val x = index % width
                val y = index / width
                if (y < top) top = y
                if (y > bottom) bottom = y
                if (x > 0) { val n = index - 1; if (mask[n] && !visited[n]) { visited[n] = true; stack[sp++] = n } }
                if (x < width - 1) { val n = index + 1; if (mask[n] && !visited[n]) { visited[n] = true; stack[sp++] = n } }
                if (y > 0) { val n = index - width; if (mask[n] && !visited[n]) { visited[n] = true; stack[sp++] = n } }
                if (y < height - 1) { val n = index + width; if (mask[n] && !visited[n]) { visited[n] = true; stack[sp++] = n } }
            }
            heights += bottom - top + 1
        }
        if (heights.isEmpty()) return null
        heights.sort()
        return heights[heights.size / 2]
    }

    /**
     * Removes an illumination gradient by dividing the image by a heavily
     * blurred copy of itself, then min-max normalizing to 0–255.
     */
    fun illuminationFlatten(lum: IntArray, width: Int, height: Int): IntArray {
        val radius = maxOf(4, width / 8)
        val background = boxBlur(boxBlur(lum, width, height, radius), width, height, radius)
        val flat = IntArray(width * height) { i ->
            (lum[i] * 128) / maxOf(1, background[i])
        }
        var min = Int.MAX_VALUE
        var max = 0
        for (v in flat) { if (v < min) min = v; if (v > max) max = v }
        val span = maxOf(1, max - min)
        return IntArray(flat.size) { ((flat[it] - min) * 255 / span).coerceIn(0, 255) }
    }

    /** Foreground = pixels brighter than their local mean by [c]. */
    fun adaptiveThreshold(lum: IntArray, width: Int, height: Int, blockSize: Int, c: Int): BooleanArray {
        val mean = boxBlur(lum, width, height, maxOf(1, blockSize / 2))
        return BooleanArray(width * height) { lum[it] > mean[it] + c }
    }
}
