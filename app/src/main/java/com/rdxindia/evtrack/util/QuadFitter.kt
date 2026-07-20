package com.rdxindia.evtrack.util

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Fits a quadrilateral to a bright display region and measures how far it
 * departs from an axis-aligned rectangle. Corners come from the classic
 * document-scanner extremes heuristic (top-left = min(x+y), bottom-right =
 * max(x+y), top-right = max(x−y), bottom-left = min(x−y)), which is robust for
 * roughly-upright quads and captures both rotation and perspective (trapezoid)
 * distortion. Pure Kotlin — JVM-testable, no Android or OpenCV.
 */
object QuadFitter {

    data class Pt(val x: Double, val y: Double)

    /** Corners in reading order; y grows downward (image coordinates). */
    data class Quad(val tl: Pt, val tr: Pt, val br: Pt, val bl: Pt) {

        fun area(): Double {
            val c = listOf(tl, tr, br, bl)
            var s = 0.0
            for (i in c.indices) {
                val a = c[i]
                val b = c[(i + 1) % c.size]
                s += a.x * b.y - b.x * a.y
            }
            return abs(s) / 2.0
        }

        fun boundingBoxArea(): Double {
            val xs = listOf(tl.x, tr.x, br.x, bl.x)
            val ys = listOf(tl.y, tr.y, br.y, bl.y)
            return (xs.max() - xs.min()) * (ys.max() - ys.min())
        }

        /** Average of the two horizontal edge lengths. */
        fun avgWidth(): Double = (dist(tl, tr) + dist(bl, br)) / 2.0

        /** Average of the two vertical edge lengths. */
        fun avgHeight(): Double = (dist(tl, bl) + dist(tr, br)) / 2.0

        /**
         * Largest deviation of any edge from its ideal axis, in degrees:
         * top/bottom compared to horizontal, left/right to vertical.
         */
        fun maxEdgeSkewDegrees(): Double = maxOf(
            edgeSkew(tl, tr, horizontal = true),
            edgeSkew(bl, br, horizontal = true),
            edgeSkew(tl, bl, horizontal = false),
            edgeSkew(tr, br, horizontal = false)
        )

        /** True when the quad is too collapsed or non-rectangular to trust. */
        fun isDegenerate(): Boolean {
            val bbox = boundingBoxArea()
            if (bbox <= 0.0) return true
            if (avgWidth() < 2.0 || avgHeight() < 2.0) return true
            // Spec rule: a quad covering < 60% of its bounding box is a blob,
            // not a rectangle (also caps very large rotations, where the
            // extremes fit is least reliable).
            return area() < 0.6 * bbox
        }

        private fun edgeSkew(a: Pt, b: Pt, horizontal: Boolean): Double {
            val angleFromHorizontal =
                Math.toDegrees(atan2(abs(b.y - a.y), abs(b.x - a.x)))
            return if (horizontal) angleFromHorizontal else 90.0 - angleFromHorizontal
        }

        private fun dist(a: Pt, b: Pt): Double = hypot(b.x - a.x, b.y - a.y)
    }

    fun fromExtremes(minSum: Pt, maxSum: Pt, maxDiff: Pt, minDiff: Pt): Quad =
        Quad(tl = minSum, tr = maxDiff, br = maxSum, bl = minDiff)

    /**
     * Fits a quad to the largest 4-connected foreground component of [mask].
     * Returns null when there is no component. Convenience entry point for
     * standalone use and tests; the detector tracks the same extremes inline.
     */
    fun fit(mask: BooleanArray, width: Int, height: Int): Quad? {
        if (mask.size < width * height) return null
        val visited = BooleanArray(width * height)
        val stack = IntArray(width * height)
        var best: Quad? = null
        var bestArea = 0

        for (seed in 0 until width * height) {
            if (!mask[seed] || visited[seed]) continue
            var area = 0
            var minSumV = Int.MAX_VALUE; var maxSumV = Int.MIN_VALUE
            var minDiffV = Int.MAX_VALUE; var maxDiffV = Int.MIN_VALUE
            var minSumP = seed; var maxSumP = seed; var minDiffP = seed; var maxDiffP = seed
            var sp = 0
            stack[sp++] = seed
            visited[seed] = true
            while (sp > 0) {
                val index = stack[--sp]
                val x = index % width
                val y = index / width
                area++
                val sum = x + y
                val diff = x - y
                if (sum < minSumV) { minSumV = sum; minSumP = index }
                if (sum > maxSumV) { maxSumV = sum; maxSumP = index }
                if (diff < minDiffV) { minDiffV = diff; minDiffP = index }
                if (diff > maxDiffV) { maxDiffV = diff; maxDiffP = index }
                if (x > 0) { val m = index - 1; if (mask[m] && !visited[m]) { visited[m] = true; stack[sp++] = m } }
                if (x < width - 1) { val m = index + 1; if (mask[m] && !visited[m]) { visited[m] = true; stack[sp++] = m } }
                if (y > 0) { val m = index - width; if (mask[m] && !visited[m]) { visited[m] = true; stack[sp++] = m } }
                if (y < height - 1) { val m = index + width; if (mask[m] && !visited[m]) { visited[m] = true; stack[sp++] = m } }
            }
            if (area > bestArea) {
                bestArea = area
                best = fromExtremes(
                    pt(minSumP, width), pt(maxSumP, width), pt(maxDiffP, width), pt(minDiffP, width)
                )
            }
        }
        return best
    }

    private fun pt(index: Int, width: Int): Pt = Pt((index % width).toDouble(), (index / width).toDouble())
}
