package com.willwalsh.blindscan.ocr

import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** A rotated rectangle around one line of text. Angle is in radians, along the `w` side. */
data class TextBox(
    val cx: Float,
    val cy: Float,
    val w: Float,
    val h: Float,
    val angle: Float,
    val score: Float,
)

/**
 * Turns the detector's probability map into text-line boxes.
 * Same steps as the tested Python reference (tools/mine.py):
 * threshold -> 8-connected components -> per-row extents -> convex hull ->
 * minimum-area rectangle -> score filter -> "unclip" (grow the box by area*ratio/perimeter).
 */
object Geometry {

    private class Comp {
        var count = 0
        var sum = 0.0
        var lastY = -1
        val ys = IntList()
        val minX = IntList()
        val maxX = IntList()
    }

    fun boxesFromProbability(
        prob: FloatArray,
        w: Int,
        h: Int,
        thresh: Float = 0.3f,
        boxThresh: Float = 0.6f,
        unclipRatio: Float = 1.5f,
    ): List<TextBox> {
        val labels = IntArray(w * h)
        val parent = IntList().apply { add(0) } // label 0 = background
        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) r = parent[r]
            var c = x
            while (parent[c] != r) { val n = parent[c]; parent[c] = r; c = n }
            return r
        }
        fun union(a: Int, b: Int) {
            val ra = find(a); val rb = find(b)
            if (ra != rb) { if (ra < rb) parent[rb] = ra else parent[ra] = rb }
        }

        // Pass 1: provisional labels, 8-connectivity (left, up-left, up, up-right).
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                if (prob[row + x] <= thresh) continue
                var best = 0
                val n1 = if (x > 0) labels[row + x - 1] else 0
                val n2 = if (y > 0 && x > 0) labels[row - w + x - 1] else 0
                val n3 = if (y > 0) labels[row - w + x] else 0
                val n4 = if (y > 0 && x < w - 1) labels[row - w + x + 1] else 0
                for (n in intArrayOf(n1, n2, n3, n4)) if (n != 0 && (best == 0 || n < best)) best = n
                if (best == 0) {
                    best = parent.size
                    parent.add(best)
                } else {
                    for (n in intArrayOf(n1, n2, n3, n4)) if (n != 0 && n != best) union(best, n)
                }
                labels[row + x] = best
            }
        }

        // Pass 2: gather per-component stats and per-row extents.
        val comps = HashMap<Int, Comp>()
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val l = labels[row + x]
                if (l == 0) continue
                val r = find(l)
                val c = comps.getOrPut(r) { Comp() }
                c.count++
                c.sum += prob[row + x]
                if (c.lastY != y) {
                    c.lastY = y
                    c.ys.add(y); c.minX.add(x); c.maxX.add(x)
                } else {
                    c.maxX[c.maxX.size - 1] = x
                }
            }
        }

        val out = ArrayList<TextBox>()
        for (c in comps.values) {
            if (c.count < 6) continue
            val score = (c.sum / c.count).toFloat()
            if (score < boxThresh) continue
            // Pixel corners of each row's extent -> hull -> min-area rect.
            val n = c.ys.size
            val px = DoubleArray(n * 4)
            val py = DoubleArray(n * 4)
            for (i in 0 until n) {
                val y = c.ys[i].toDouble(); val a = c.minX[i].toDouble(); val b = c.maxX[i] + 1.0
                px[i * 4] = a; py[i * 4] = y
                px[i * 4 + 1] = b; py[i * 4 + 1] = y
                px[i * 4 + 2] = a; py[i * 4 + 2] = y + 1
                px[i * 4 + 3] = b; py[i * 4 + 3] = y + 1
            }
            val rect = minAreaRect(px, py) ?: continue
            var (cx, cy, rw, rh, ang) = rect
            if (min(rw, rh) < 3) continue
            val d = rw * rh * unclipRatio / (2 * (rw + rh))
            rw += 2 * d; rh += 2 * d
            if (min(rw, rh) < 5) continue
            out.add(TextBox(cx.toFloat(), cy.toFloat(), rw.toFloat(), rh.toFloat(), ang.toFloat(), score))
        }
        return out
    }

    data class Rect5(val cx: Double, val cy: Double, val w: Double, val h: Double, val angle: Double)

    /** Minimum-area enclosing rectangle via convex hull + rotating edges. */
    fun minAreaRect(xs: DoubleArray, ys: DoubleArray): Rect5? {
        val hull = convexHull(xs, ys)
        if (hull.size < 3) return null
        var bestArea = Double.MAX_VALUE
        var best: Rect5? = null
        for (i in hull.indices) {
            val p = hull[i]; val q = hull[(i + 1) % hull.size]
            val ex = q.first - p.first; val ey = q.second - p.second
            val len = hypot(ex, ey)
            if (len == 0.0) continue
            val ux = ex / len; val uy = ey / len
            var minU = Double.MAX_VALUE; var maxU = -Double.MAX_VALUE
            var minV = Double.MAX_VALUE; var maxV = -Double.MAX_VALUE
            for ((x, y) in hull) {
                val u = x * ux + y * uy
                val v = -x * uy + y * ux
                minU = min(minU, u); maxU = max(maxU, u)
                minV = min(minV, v); maxV = max(maxV, v)
            }
            val area = (maxU - minU) * (maxV - minV)
            if (area < bestArea) {
                bestArea = area
                val mu = (maxU + minU) / 2; val mv = (maxV + minV) / 2
                best = Rect5(mu * ux - mv * uy, mu * uy + mv * ux, maxU - minU, maxV - minV, atan2(uy, ux))
            }
        }
        return best
    }

    /** Andrew's monotone chain. */
    fun convexHull(xs: DoubleArray, ys: DoubleArray): List<Pair<Double, Double>> {
        val pts = xs.indices.map { Pair(xs[it], ys[it]) }.distinct()
            .sortedWith(compareBy({ it.first }, { it.second }))
        if (pts.size < 3) return pts
        fun cross(o: Pair<Double, Double>, a: Pair<Double, Double>, b: Pair<Double, Double>) =
            (a.first - o.first) * (b.second - o.second) - (a.second - o.second) * (b.first - o.first)
        val lower = ArrayList<Pair<Double, Double>>()
        for (p in pts) {
            while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], p) <= 0) lower.removeAt(lower.size - 1)
            lower.add(p)
        }
        val upper = ArrayList<Pair<Double, Double>>()
        for (p in pts.asReversed()) {
            while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], p) <= 0) upper.removeAt(upper.size - 1)
            upper.add(p)
        }
        lower.removeAt(lower.size - 1)
        upper.removeAt(upper.size - 1)
        return lower + upper
    }
}

/** Small growable int array (avoids boxing in the hot loops). */
class IntList(initial: Int = 16) {
    private var data = IntArray(initial)
    var size = 0
        private set
    fun add(v: Int) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = v
    }
    operator fun get(i: Int) = data[i]
    operator fun set(i: Int, v: Int) { data[i] = v }
}
