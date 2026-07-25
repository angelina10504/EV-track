package com.rdxindia.evtrack.ocr

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Differentiable-Binarization (DB) post-processing for the PP-OCR text
 * detector: threshold the probability map, group foreground pixels into
 * connected components, fit a minimum-area rectangle to each, score it by the
 * mean probability inside, and "unclip" (dilate) it so the box covers the
 * glyphs rather than their skeleton.
 *
 * Pure Kotlin over a float probability grid — no Android or OpenCV, so the
 * whole detector tail is JVM-testable.
 */
object DbPostProcessor {

    data class P(val x: Double, val y: Double)

    data class TextBox(val points: List<P>, val score: Float) {
        /** Axis-aligned bounds as [left, top, right, bottom], rounded. */
        fun bounds(): IntArray {
            val xs = points.map { it.x }
            val ys = points.map { it.y }
            return intArrayOf(
                Math.round(xs.min()).toInt(), Math.round(ys.min()).toInt(),
                Math.round(xs.max()).toInt(), Math.round(ys.max()).toInt()
            )
        }
    }

    /** A minimum-area rectangle in center/axes form, which unclipping needs. */
    private data class RotRect(
        val cx: Double, val cy: Double,
        val ux: Double, val uy: Double,   // unit vector along the width axis
        val halfW: Double, val halfH: Double
    ) {
        fun corners(): List<P> {
            val vx = -uy
            val vy = ux
            val dwx = ux * halfW
            val dwy = uy * halfW
            val dhx = vx * halfH
            val dhy = vy * halfH
            return listOf(
                P(cx - dwx - dhx, cy - dwy - dhy),
                P(cx + dwx - dhx, cy + dwy - dhy),
                P(cx + dwx + dhx, cy + dwy + dhy),
                P(cx - dwx + dhx, cy - dwy + dhy)
            )
        }

        fun area(): Double = (halfW * 2) * (halfH * 2)
        fun perimeter(): Double = 2 * (halfW * 2 + halfH * 2)
    }

    /**
     * @param prob row-major probability map of size [width]×[height]
     * @param scaleX/[scaleY] multiply box coordinates to map back to the
     *   original image (origWidth / netWidth, origHeight / netHeight)
     */
    fun extract(
        prob: FloatArray,
        width: Int,
        height: Int,
        scaleX: Double = 1.0,
        scaleY: Double = 1.0,
        threshold: Float = 0.3f,
        boxThreshold: Float = 0.6f,
        unclipRatio: Double = 1.5,
        minBoxSide: Double = 3.0,
        maxBoxes: Int = 200
    ): List<TextBox> {
        val n = width * height
        if (n <= 0 || prob.size < n) return emptyList()

        val fg = BooleanArray(n) { prob[it] > threshold }
        val visited = BooleanArray(n)
        val stack = IntArray(n)
        val component = IntArray(n)
        val out = mutableListOf<TextBox>()

        for (seed in 0 until n) {
            if (!fg[seed] || visited[seed]) continue

            var size = 0
            var sp = 0
            stack[sp++] = seed
            visited[seed] = true
            while (sp > 0) {
                val index = stack[--sp]
                component[size++] = index
                val x = index % width
                val y = index / width
                if (x > 0) { val m = index - 1; if (fg[m] && !visited[m]) { visited[m] = true; stack[sp++] = m } }
                if (x < width - 1) { val m = index + 1; if (fg[m] && !visited[m]) { visited[m] = true; stack[sp++] = m } }
                if (y > 0) { val m = index - width; if (fg[m] && !visited[m]) { visited[m] = true; stack[sp++] = m } }
                if (y < height - 1) { val m = index + width; if (fg[m] && !visited[m]) { visited[m] = true; stack[sp++] = m } }
            }
            if (size < 4) continue

            // Pixel centers, so a 1px-wide run still has non-zero extent.
            val pts = ArrayList<P>(size)
            for (i in 0 until size) {
                val index = component[i]
                pts += P((index % width).toDouble(), (index / width).toDouble())
            }

            val hull = convexHull(pts)
            if (hull.size < 3) continue
            val rect = minAreaRect(hull) ?: continue
            if (min(rect.halfW, rect.halfH) * 2 < minBoxSide) continue

            val score = meanProbInside(prob, width, height, rect.corners())
            if (score < boxThreshold) continue

            val expanded = unclip(rect, unclipRatio)
            val scaled = expanded.corners().map { P(it.x * scaleX, it.y * scaleY) }
            out += TextBox(scaled, score)
            if (out.size >= maxBoxes) break
        }
        return out
    }

    /**
     * Offsets the rectangle outward by `area * ratio / perimeter`, the same
     * distance PaddleOCR's Clipper-based unclip uses. For a rectangle this is
     * exactly a uniform expansion of both half-extents.
     */
    private fun unclip(rect: RotRect, ratio: Double): RotRect {
        val perimeter = rect.perimeter()
        if (perimeter <= 0.0) return rect
        val d = rect.area() * ratio / perimeter
        return rect.copy(halfW = rect.halfW + d, halfH = rect.halfH + d)
    }

    /** Andrew's monotone chain. */
    internal fun convexHull(points: List<P>): List<P> {
        if (points.size < 3) return points
        val sorted = points.sortedWith(compareBy({ it.x }, { it.y }))
        val lower = ArrayList<P>()
        for (p in sorted) {
            while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], p) <= 0.0) {
                lower.removeAt(lower.size - 1)
            }
            lower += p
        }
        val upper = ArrayList<P>()
        for (p in sorted.asReversed()) {
            while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], p) <= 0.0) {
                upper.removeAt(upper.size - 1)
            }
            upper += p
        }
        lower.removeAt(lower.size - 1)
        upper.removeAt(upper.size - 1)
        return lower + upper
    }

    private fun cross(o: P, a: P, b: P): Double =
        (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)

    /** Rotating calipers: the min-area rect shares an edge with the hull. */
    private fun minAreaRect(hull: List<P>): RotRect? {
        if (hull.size < 3) return null
        var best: RotRect? = null
        var bestArea = Double.MAX_VALUE

        for (i in hull.indices) {
            val a = hull[i]
            val b = hull[(i + 1) % hull.size]
            val ex = b.x - a.x
            val ey = b.y - a.y
            val len = hypot(ex, ey)
            if (len < 1e-9) continue
            val ux = ex / len
            val uy = ey / len
            val vx = -uy
            val vy = ux

            var minU = Double.MAX_VALUE
            var maxU = -Double.MAX_VALUE
            var minV = Double.MAX_VALUE
            var maxV = -Double.MAX_VALUE
            for (p in hull) {
                val du = p.x * ux + p.y * uy
                val dv = p.x * vx + p.y * vy
                minU = min(minU, du); maxU = max(maxU, du)
                minV = min(minV, dv); maxV = max(maxV, dv)
            }
            val w = maxU - minU
            val h = maxV - minV
            val area = w * h
            if (area < bestArea) {
                bestArea = area
                val midU = (minU + maxU) / 2
                val midV = (minV + maxV) / 2
                best = RotRect(
                    cx = ux * midU + vx * midV,
                    cy = uy * midU + vy * midV,
                    ux = ux, uy = uy,
                    halfW = w / 2, halfH = h / 2
                )
            }
        }
        return best
    }

    /** Mean probability over the polygon's interior (even-odd fill). */
    private fun meanProbInside(
        prob: FloatArray, width: Int, height: Int, poly: List<P>
    ): Float {
        val left = max(0, Math.round(poly.minOf { it.x }).toInt())
        val right = min(width - 1, Math.round(poly.maxOf { it.x }).toInt())
        val top = max(0, Math.round(poly.minOf { it.y }).toInt())
        val bottom = min(height - 1, Math.round(poly.maxOf { it.y }).toInt())
        if (right < left || bottom < top) return 0f

        var sum = 0.0
        var count = 0
        for (y in top..bottom) {
            for (x in left..right) {
                if (pointInPolygon(x.toDouble(), y.toDouble(), poly)) {
                    sum += prob[y * width + x]
                    count++
                }
            }
        }
        return if (count == 0) 0f else (sum / count).toFloat()
    }

    private fun pointInPolygon(x: Double, y: Double, poly: List<P>): Boolean {
        var inside = false
        var j = poly.size - 1
        for (i in poly.indices) {
            val pi = poly[i]
            val pj = poly[j]
            if ((pi.y > y) != (pj.y > y)) {
                val t = (y - pi.y) / (pj.y - pi.y)
                if (x < pi.x + t * (pj.x - pi.x)) inside = !inside
            }
            j = i
        }
        return inside
    }

    /**
     * Orders quad corners as top-left, top-right, bottom-right, bottom-left so
     * the recognizer crop is upright.
     */
    fun orderCorners(points: List<P>): List<P> {
        if (points.size != 4) return points
        val bySum = points.sortedBy { it.x + it.y }
        val byDiff = points.sortedBy { it.x - it.y }
        return listOf(bySum.first(), byDiff.last(), bySum.last(), byDiff.first())
    }

    fun distance(a: P, b: P): Double = hypot(b.x - a.x, b.y - a.y)

    internal fun polygonArea(poly: List<P>): Double {
        var s = 0.0
        for (i in poly.indices) {
            val a = poly[i]
            val b = poly[(i + 1) % poly.size]
            s += a.x * b.y - b.x * a.y
        }
        return abs(s) / 2.0
    }
}
