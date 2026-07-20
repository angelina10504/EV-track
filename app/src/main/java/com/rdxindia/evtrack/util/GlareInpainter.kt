package com.rdxindia.evtrack.util

/**
 * Simple glare inpainting: blown-out regions (luminance > 250) that are large
 * enough to matter get filled with the median of a thin surrounding ring, so
 * a reflection band doesn't dominate thresholding in later preprocessing.
 * Pure Kotlin — JVM-testable.
 */
object GlareInpainter {

    data class Result(
        val luminance: IntArray,
        val regionCount: Int,
        val coveredFraction: Double
    )

    /**
     * Returns the inpainted luminance plus stats, or null when no glare region
     * of at least [minRegionFraction] of the image exists. Small specular dots
     * are left untouched.
     */
    fun inpaint(
        luminance: IntArray,
        width: Int,
        height: Int,
        threshold: Int = 250,
        minRegionFraction: Double = 0.005
    ): Result? {
        val n = width * height
        if (n == 0 || luminance.size < n) return null
        val glare = BooleanArray(n) { luminance[it] > threshold }
        if (!glare.any { it }) return null

        val visited = BooleanArray(n)
        val stack = IntArray(n)
        val component = IntArray(n)
        val out = luminance.copyOf()
        val minRegion = minRegionFraction * n
        var regions = 0
        var covered = 0

        for (seed in 0 until n) {
            if (!glare[seed] || visited[seed]) continue

            var size = 0
            var sp = 0
            stack[sp++] = seed
            visited[seed] = true
            while (sp > 0) {
                val index = stack[--sp]
                component[size++] = index
                val x = index % width
                val y = index / width
                if (x > 0) { val m = index - 1; if (glare[m] && !visited[m]) { visited[m] = true; stack[sp++] = m } }
                if (x < width - 1) { val m = index + 1; if (glare[m] && !visited[m]) { visited[m] = true; stack[sp++] = m } }
                if (y > 0) { val m = index - width; if (glare[m] && !visited[m]) { visited[m] = true; stack[sp++] = m } }
                if (y < height - 1) { val m = index + width; if (glare[m] && !visited[m]) { visited[m] = true; stack[sp++] = m } }
            }
            if (size <= minRegion) continue

            // Median of the non-glare ring within Chebyshev distance 2.
            val ringSeen = HashSet<Int>()
            val ringValues = mutableListOf<Int>()
            for (ci in 0 until size) {
                val index = component[ci]
                val x = index % width
                val y = index / width
                for (dy in -2..2) {
                    val ny = y + dy
                    if (ny < 0 || ny >= height) continue
                    for (dx in -2..2) {
                        val nx = x + dx
                        if (nx < 0 || nx >= width) continue
                        val ni = ny * width + nx
                        if (!glare[ni] && ringSeen.add(ni)) ringValues += luminance[ni]
                    }
                }
            }
            val fill = if (ringValues.isEmpty()) 128 else ringValues.sorted()[ringValues.size / 2]
            for (ci in 0 until size) out[component[ci]] = fill
            regions++
            covered += size
        }

        return if (regions == 0) null else Result(out, regions, covered.toDouble() / n)
    }
}
