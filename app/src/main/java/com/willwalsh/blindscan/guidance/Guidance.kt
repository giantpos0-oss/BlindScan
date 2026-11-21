package com.willwalsh.blindscan.guidance

import android.graphics.Bitmap
import com.willwalsh.blindscan.ocr.TextBox
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Turns what the camera sees into plain, physical instructions.
 *
 * Directions assume the phone is held screen-up over a page on a table, top of the phone pointing
 * away from you. In that pose the top of the camera image is the part of the page farthest from you.
 */
class Guidance {

    enum class Step(val words: String) {
        NO_TEXT("No text in view yet. Hold the phone flat, about a foot above the page."),
        TILT("Hold the phone flatter, screen facing up."),
        LIFT("Lift the phone up a little."),
        CLOSER("Bring the phone a little closer to the page."),
        HOLD_STILL("Hold still."),
        READY("Ready. All the text is in view."),
        MOVE(""), // words built from the directions below
    }

    data class Report(
        val step: Step,
        val instruction: String,
        val ready: Boolean,
        val dark: Boolean,
        val lineCount: Int,
        val cutEdges: List<String>,
        val pageEdgesMissing: List<String>?, // null = page edges couldn't be made out
        val pageFillsView: Boolean,
        val time: Long = System.currentTimeMillis(),
    ) {
        /** The longer spoken answer for the "Check view" button. */
        fun fullReport(lightOn: Boolean): String = buildString {
            if (lineCount == 0) append("I don't see any text. ")
            else append("I can see about $lineCount ${if (lineCount == 1) "line" else "lines"} of text. ")
            if (lineCount > 0) {
                if (cutEdges.isEmpty()) append("All the text is inside the view. ")
                else append("Text runs off the ${joinWords(cutEdges)}. ")
            }
            when {
                pageFillsView -> append("The page fills the whole view, so its edges are out of sight. ")
                pageEdgesMissing == null -> append("I can't make out the page edges. ")
                pageEdgesMissing.isEmpty() -> append("All four page edges are visible. ")
                else -> append("The page edge is out of view at the ${joinWords(pageEdgesMissing)}. ")
            }
            if (dark && !lightOn) append("It's dim. ")
            if (lightOn) append("The light is on. ")
            append(instruction)
        }
    }

    private val sharpHistory = ArrayDeque<Pair<Long, Double>>()

    /**
     * @param frame     upright camera frame
     * @param boxes     text lines found in [frame] (frame pixel coordinates)
     * @param tiltDeg   how far the phone is from lying flat, or null if unknown
     */
    fun analyse(frame: Bitmap, boxes: List<TextBox>, tiltDeg: Float?, tiltHints: Boolean): Report {
        val small = Bitmap.createScaledBitmap(frame, 480, max(1, 480 * frame.height / frame.width), true)
        val gray = toGray(small)
        val sw = small.width; val sh = small.height
        if (small !== frame) small.recycle()

        val brightness = gray.average()
        val dark = brightness < 70

        // Sharpness, compared with the sharpest frame of the last 2 seconds (catches shaky hands).
        val sharp = laplacianVariance(gray, sw, sh)
        val now = System.currentTimeMillis()
        while (sharpHistory.isNotEmpty() && now - sharpHistory.first().first > 2000) sharpHistory.removeFirst()
        val recentBest = sharpHistory.maxOfOrNull { it.second } ?: sharp
        if (boxes.isNotEmpty()) sharpHistory.addLast(now to sharp)
        val shaky = boxes.isNotEmpty() && sharpHistory.size >= 3 && sharp < 0.35 * recentBest

        val page = pageEdges(gray, sw, sh)

        // Text extents in 0..1 frame units. Only real lines of text count: short scraps (keyboard
        // keys, picture labels, page numbers) at the edges shouldn't make you move the phone.
        val fw = frame.width.toFloat(); val fh = frame.height.toFloat()
        var minX = 1f; var minY = 1f; var maxX = 0f; var maxY = 0f
        val longSides = boxes.map { max(it.w, it.h) }.sorted()
        val minLong = if (longSides.isEmpty()) 0f else 0.5f * longSides[(longSides.size * 3 / 4).coerceAtMost(longSides.size - 1)]
        for (b in boxes) {
            if (max(b.w, b.h) < minLong) continue
            val c = cos(b.angle); val s = sin(b.angle)
            val ex = abs(c) * b.w / 2 + abs(s) * b.h / 2
            val ey = abs(s) * b.w / 2 + abs(c) * b.h / 2
            minX = min(minX, (b.cx - ex) / fw); maxX = max(maxX, (b.cx + ex) / fw)
            minY = min(minY, (b.cy - ey) / fh); maxY = max(maxY, (b.cy + ey) / fh)
        }
        val m = 0.02f
        val cutLeft = boxes.isNotEmpty() && minX <= m
        val cutRight = boxes.isNotEmpty() && maxX >= 1 - m
        val cutTop = boxes.isNotEmpty() && minY <= m
        val cutBottom = boxes.isNotEmpty() && maxY >= 1 - m
        val cutEdges = buildList {
            if (cutLeft) add("left"); if (cutRight) add("right")
            if (cutTop) add("top (far side)"); if (cutBottom) add("bottom (near side)")
        }
        val textArea = if (boxes.isEmpty()) 0f else (maxX - minX) * (maxY - minY)

        var step: Step
        var words: String
        when {
            boxes.isEmpty() && tiltHints && tiltDeg != null && tiltDeg > 20 -> { step = Step.TILT; words = step.words }
            boxes.isEmpty() -> { step = Step.NO_TEXT; words = step.words }
            (cutLeft && cutRight) || (cutTop && cutBottom) -> { step = Step.LIFT; words = step.words }
            cutLeft || cutRight || cutTop || cutBottom -> {
                // One movement at a time: side to side first, then away/toward.
                step = Step.MOVE
                words = when {
                    cutLeft -> "Move the phone a little to the left."
                    cutRight -> "Move the phone a little to the right."
                    cutTop -> "Move the phone a little away from you."
                    else -> "Move the phone a little toward you."
                }
            }
            tiltHints && tiltDeg != null && tiltDeg > 25 -> { step = Step.TILT; words = step.words }
            shaky -> { step = Step.HOLD_STILL; words = step.words }
            textArea < 0.2f -> { step = Step.CLOSER; words = step.words }
            else -> { step = Step.READY; words = step.words }
        }

        return Report(
            step = step,
            instruction = words,
            ready = step == Step.READY,
            dark = dark,
            lineCount = countRows(boxes),
            cutEdges = cutEdges,
            pageEdgesMissing = page.missing,
            pageFillsView = page.fills,
        )
    }

    private fun countRows(boxes: List<TextBox>): Int {
        val ys = boxes.map { it.cy to min(it.w, it.h) }.sortedBy { it.first }
        var rows = 0; var last = -1e9f; var lastH = 0f
        for ((y, h) in ys) {
            if (y - last > 0.5f * min(h, if (lastH == 0f) h else lastH)) rows++
            last = y; lastH = h
        }
        return rows
    }

    private fun toGray(b: Bitmap): FloatArray {
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        return FloatArray(px.size) { i ->
            val p = px[i]
            0.299f * (p shr 16 and 0xFF) + 0.587f * (p shr 8 and 0xFF) + 0.114f * (p and 0xFF)
        }
    }

    private fun laplacianVariance(g: FloatArray, w: Int, h: Int): Double {
        var sum = 0.0; var sq = 0.0; var n = 0
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val l = (g[i - 1] + g[i + 1] + g[i - w] + g[i + w] - 4 * g[i]).toDouble()
            sum += l; sq += l * l; n++
        }
        if (n == 0) return 0.0
        val mean = sum / n
        return sq / n - mean * mean
    }

    private data class Page(val missing: List<String>?, val fills: Boolean)

    /**
     * Finds the page as the largest bright region (Otsu threshold on a 64x48 image) and reports
     * which of its edges touch the frame. Works when the page is lighter than what it lies on.
     */
    private fun pageEdges(gray: FloatArray, w: Int, h: Int): Page {
        val gw = 64; val gh = 48
        val g = IntArray(gw * gh)
        for (y in 0 until gh) for (x in 0 until gw) {
            g[y * gw + x] = gray[(y * h / gh) * w + (x * w / gw)].toInt().coerceIn(0, 255)
        }
        val hist = IntArray(256); for (v in g) hist[v]++
        val total = g.size
        var sumAll = 0.0; for (i in 0..255) sumAll += i * hist[i].toDouble()
        var sumB = 0.0; var wB = 0; var best = 0.0; var thr = 128
        for (t in 0..255) {
            wB += hist[t]; if (wB == 0) continue
            val wF = total - wB; if (wF == 0) break
            sumB += t * hist[t].toDouble()
            val mB = sumB / wB; val mF = (sumAll - sumB) / wF
            val between = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (between > best) { best = between; thr = t }
        }
        // Largest 4-connected bright region.
        val seen = BooleanArray(total)
        var bestSize = 0; var bx0 = 0; var by0 = 0; var bx1 = 0; var by1 = 0
        val stack = IntArray(total)
        for (start in 0 until total) {
            if (seen[start] || g[start] <= thr) continue
            var sp = 0; stack[sp++] = start; seen[start] = true
            var size = 0; var x0 = gw; var y0 = gh; var x1 = 0; var y1 = 0
            while (sp > 0) {
                val i = stack[--sp]; size++
                val x = i % gw; val y = i / gw
                x0 = min(x0, x); x1 = max(x1, x); y0 = min(y0, y); y1 = max(y1, y)
                if (x > 0 && !seen[i - 1] && g[i - 1] > thr) { seen[i - 1] = true; stack[sp++] = i - 1 }
                if (x < gw - 1 && !seen[i + 1] && g[i + 1] > thr) { seen[i + 1] = true; stack[sp++] = i + 1 }
                if (y > 0 && !seen[i - gw] && g[i - gw] > thr) { seen[i - gw] = true; stack[sp++] = i - gw }
                if (y < gh - 1 && !seen[i + gw] && g[i + gw] > thr) { seen[i + gw] = true; stack[sp++] = i + gw }
            }
            if (size > bestSize) { bestSize = size; bx0 = x0; by0 = y0; bx1 = x1; by1 = y1 }
        }
        val frac = bestSize.toFloat() / total
        if (frac > 0.97f) return Page(null, fills = true)
        if (frac < 0.15f || best == 0.0) return Page(null, fills = false)
        val missing = buildList {
            if (bx0 == 0) add("left"); if (bx1 == gw - 1) add("right")
            if (by0 == 0) add("top (far side)"); if (by1 == gh - 1) add("bottom (near side)")
        }
        return Page(missing, fills = false)
    }
}

private fun joinWords(items: List<String>): String = when (items.size) {
    0 -> ""
    1 -> items[0]
    2 -> "${items[0]} and ${items[1]}"
    else -> items.dropLast(1).joinToString(", ") + ", and " + items.last()
}

/** Average brightness 0..255 of a frame (sampled on a 32x24 grid). */
fun meanBrightness(b: Bitmap): Float {
    var sum = 0f
    for (y in 0 until 24) for (x in 0 until 32) {
        val p = b.getPixel(x * (b.width - 1) / 31, y * (b.height - 1) / 23)
        sum += 0.299f * (p shr 16 and 0xFF) + 0.587f * (p shr 8 and 0xFF) + 0.114f * (p and 0xFF)
    }
    return sum / (32 * 24)
}

/** How sharp a photo is (variance of the Laplacian on a 640-pixel-wide copy of its middle). */
fun sharpness(b: Bitmap): Double {
    val cw = b.width * 3 / 4; val ch = b.height * 3 / 4
    val mid = Bitmap.createBitmap(b, (b.width - cw) / 2, (b.height - ch) / 2, cw, ch)
    val w = 640; val h = max(1, 640 * ch / cw)
    val small = Bitmap.createScaledBitmap(mid, w, h, true)
    if (mid !== b && mid !== small) mid.recycle()
    val px = IntArray(w * h)
    small.getPixels(px, 0, w, 0, 0, w, h)
    small.recycle()
    val g = FloatArray(px.size) { i -> val p = px[i]; 0.299f * (p shr 16 and 0xFF) + 0.587f * (p shr 8 and 0xFF) + 0.114f * (p and 0xFF) }
    var sum = 0.0; var sq = 0.0; var n = 0
    for (y in 1 until h - 1) for (x in 1 until w - 1) {
        val i = y * w + x
        val l = (g[i - 1] + g[i + 1] + g[i - w] + g[i + w] - 4 * g[i]).toDouble()
        sum += l; sq += l * l; n++
    }
    if (n == 0) return 0.0
    val mean = sum / n
    return sq / n - mean * mean
}
