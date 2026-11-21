package com.willwalsh.blindscan.ocr

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Reading order, worked out from the text boxes alone (before anything is recognised), so lines
 * can be read top to bottom and spoken while the rest of the page is still being read.
 *
 * 1. The page is straightened: its tilt is measured from the text lines themselves.
 * 2. Pieces of text are joined into a line only when they belong together: two long pieces with
 *    a gap between them are separate columns, so columns and picture labels are never stitched
 *    into one sentence. A decorative drop capital is joined to its first word.
 * 3. Lines are grouped into blocks (columns, captions, labels): stacked closely and overlapping
 *    side to side.
 * 4. Blocks are read top to bottom and columns left to right. Small scraps (picture labels,
 *    page numbers) come after the main text so they don't interrupt it.
 */
object ReadingOrder {

    /** One printed line: its boxes (left to right), position on the straightened page, and its block. */
    class Row(
        val v: Float, val h: Float, val left: Float, val right: Float, val boxes: List<Int>,
        val block: Int, val blockWidth: Float,
        /** Box index of a decorative drop capital starting this line (joined to the next word), or -1. */
        val dropCap: Int = -1,
    ) {
        val width: Float get() = right - left
    }

    class Layout(val rows: List<Row>, val typicalHeight: Float, val tilt: Float)

    /** Direction of a box's long side, folded into -90..+90 degrees (radians). */
    private fun lineAngle(b: TextBox): Float {
        var a = if (b.w >= b.h) b.angle else b.angle + (PI / 2).toFloat()
        while (a > PI / 2) a -= PI.toFloat()
        while (a <= -PI / 2) a += PI.toFloat()
        return a
    }

    private class Line(var v: Float, var h: Float, var left: Float, var right: Float) {
        val items = ArrayList<Int>()
        var dropCap = -1
    }

    private class Block {
        val lines = ArrayList<Line>()
        var left = Float.MAX_VALUE; var right = -Float.MAX_VALUE
        var top = Float.MAX_VALUE; var bottom = -Float.MAX_VALUE
        var chars = 0
    }

    fun layout(boxes: List<TextBox>): Layout {
        if (boxes.isEmpty()) return Layout(emptyList(), 0f, 0f)
        val angles = boxes.filter { max(it.w, it.h) > 2 * min(it.w, it.h) }.map { lineAngle(it) }.sorted()
        val tilt = if (angles.isEmpty()) 0f else angles[angles.size / 2]
        val c = cos(tilt); val s = sin(tilt)
        val n = boxes.size
        val us = FloatArray(n); val vs = FloatArray(n); val hs = FloatArray(n); val ls = FloatArray(n)
        for ((i, b) in boxes.withIndex()) {
            us[i] = b.cx * c + b.cy * s; vs[i] = -b.cx * s + b.cy * c
            hs[i] = min(b.w, b.h); ls[i] = max(b.w, b.h)
        }
        val typicalH = hs.sorted()[n / 2]

        // Each box's extent across (u) and down (v) the straightened page.
        val extU = FloatArray(n); val extV = FloatArray(n)
        for ((i, b) in boxes.withIndex()) {
            val d = b.angle - tilt
            extU[i] = b.w * abs(cos(d)) + b.h * abs(sin(d))
            extV[i] = b.w * abs(sin(d)) + b.h * abs(cos(d))
        }
        // Drop capitals: one big letter, more than ~1.8 lines tall and not much wider than tall.
        // Set aside, then attached to the first line beside it.
        val isDropCap = BooleanArray(n) {
            extV[it] > 1.8f * typicalH && extU[it] < 1.5f * extV[it] && extU[it] < 3f * typicalH
        }

        // 2. Lines: same height on the page AND close side to side. Two long pieces with a real gap
        //    between them are different columns (an article's columns can be less than a line height
        //    apart); a short piece beside a long one (a price, a page number) belongs to the line,
        //    unless a column gutter runs between them.
        fun buildLines(gutters: List<Float>, found: MutableList<Float>?): ArrayList<Line> {
            val lines = ArrayList<Line>()
            for (i in (0 until n).sortedBy { vs[it] }) {
                if (isDropCap[i]) continue
                val l0 = us[i] - ls[i] / 2; val l1 = us[i] + ls[i] / 2
                val line = lines.firstOrNull { ln ->
                    val h = max(ln.h, hs[i])
                    if (abs(ln.v - vs[i]) >= 0.5f * min(ln.h, hs[i])) return@firstOrNull false
                    val gapL = min(ln.right, l1); val gapR = max(ln.left, l0) // the gap, if any, is gapL..gapR
                    val gap = max(0f, gapR - gapL)
                    val bothLong = (ln.right - ln.left) > 4f * h && ls[i] > 4f * h
                    if (bothLong && gap > 0.5f * h) { found?.add((gapL + gapR) / 2); return@firstOrNull false }
                    if (gap >= 3f * h) return@firstOrNull false
                    gutters.none { g -> g > gapL - 0.3f * h && g < gapR + 0.3f * h && gap > 0.3f * h }
                }
                if (line != null) {
                    line.items.add(i); line.left = min(line.left, l0); line.right = max(line.right, l1)
                } else lines.add(Line(vs[i], hs[i], l0, l1).also { it.items.add(i) })
            }
            return lines
        }
        // First pass finds where columns split; a position seen on 3+ lines is a real gutter.
        val splits = ArrayList<Float>()
        buildLines(emptyList(), splits)
        splits.sort()
        val gutters = ArrayList<Float>()
        var k = 0
        while (k < splits.size) {
            var e = k
            while (e + 1 < splits.size && splits[e + 1] - splits[k] < 1.5f * typicalH) e++
            if (e - k + 1 >= 3) gutters.add(splits.subList(k, e + 1).average().toFloat())
            k = e + 1
        }
        val lines = buildLines(gutters, null)
        for (i in 0 until n) {
            if (!isDropCap[i]) continue
            val capLeft = us[i] - extU[i] / 2; val capRight = us[i] + extU[i] / 2
            val capTop = vs[i] - extV[i] / 2; val capBottom = vs[i] + extV[i] / 2
            val target = lines.filter { ln ->
                ln.v > capTop - 0.5f * typicalH && ln.v < capBottom &&
                    ln.left - capRight > -1.2f * typicalH && ln.left - capRight < 1.5f * typicalH
            }.minByOrNull { it.v }
            if (target != null) {
                target.items.add(i); target.dropCap = i; target.left = min(target.left, capLeft)
            } else lines.add(Line(vs[i], extV[i], capLeft, capRight).also { it.items.add(i) })
        }
        lines.sortBy { it.v }

        // 3. Blocks: link each line to the nearest line above it that overlaps it side to side
        //    and is no more than ~2.6 line heights higher.
        val parent = IntArray(lines.size) { it }
        fun find(x: Int): Int { var r = x; while (parent[r] != r) r = parent[r]; return r }
        for (j in lines.indices) {
            val lj = lines[j]
            var best = -1; var bestDv = Float.MAX_VALUE
            for (i in 0 until j) {
                val li = lines[i]
                val dv = lj.v - li.v
                if (dv <= 0.3f * lj.h || dv > 2.6f * max(li.h, lj.h)) continue
                if (max(li.h, lj.h) > 1.6f * min(li.h, lj.h)) continue // different text sizes: not one block
                val overlap = min(li.right, lj.right) - max(li.left, lj.left)
                if (overlap < 0.4f * min(li.right - li.left, lj.right - lj.left)) continue
                if (dv < bestDv) { bestDv = dv; best = i }
            }
            if (best >= 0) { val a = find(best); val b = find(j); if (a != b) parent[max(a, b)] = min(a, b) }
        }
        val blockMap = LinkedHashMap<Int, Block>()
        for ((i, ln) in lines.withIndex()) {
            val blk = blockMap.getOrPut(find(i)) { Block() }
            blk.lines.add(ln)
            blk.left = min(blk.left, ln.left); blk.right = max(blk.right, ln.right)
            blk.top = min(blk.top, ln.v - ln.h / 2); blk.bottom = max(blk.bottom, ln.v + ln.h / 2)
            blk.chars += ln.items.sumOf { (ls[it] / max(1f, hs[it]) * 2).toInt() } // rough letter count
        }
        val blocks = blockMap.values.toList()

        // 4. Order: main text first, then small scraps (labels, page numbers). Within each group a
        //    block comes first if it is above another it overlaps side to side, or to the left of
        //    one it sits beside.
        fun before(a: Block, b: Block): Boolean {
            val hOverlap = min(a.right, b.right) - max(a.left, b.left) > 0
            val vOverlap = min(a.bottom, b.bottom) - max(a.top, b.top) > 0
            return when {
                hOverlap -> a.top < b.top
                vOverlap -> a.left < b.left
                else -> a.top < b.top
            }
        }
        fun order(group: List<Block>): List<Block> {
            val remaining = group.toMutableList(); val out = ArrayList<Block>()
            while (remaining.isNotEmpty()) {
                // A block nothing else must come before; on a tie or a cycle, the topmost.
                val free = remaining.filter { b -> remaining.none { a -> a !== b && before(a, b) } }
                val next = (if (free.isNotEmpty()) free else remaining).minWith(compareBy({ it.top }, { it.left }))
                out.add(next); remaining.remove(next)
            }
            return out
        }
        // Scraps: a line or two of short text in ordinary-size type. Big, wide type is a headline
        // and stays with the main text; a big short word (on a sign or drum in a photo) is a scrap.
        val isSmall = { b: Block ->
            val headline = b.lines.maxOf { it.h } > 1.3f * typicalH && (b.right - b.left) > 8f * typicalH
            b.lines.size <= 2 && b.chars < 40 && !headline
        }
        val ordered = order(blocks.filterNot(isSmall)) + order(blocks.filter(isSmall))

        val rows = ArrayList<Row>()
        for ((bi, blk) in ordered.withIndex()) {
            val widths = blk.lines.map { it.right - it.left }.sorted()
            val bw = widths[(widths.size * 8 / 10).coerceAtMost(widths.size - 1)]
            for (ln in blk.lines.sortedBy { it.v }) {
                val lineItems = ln.items.sortedBy { if (it == ln.dropCap) -Float.MAX_VALUE else us[it] }
                rows.add(Row(ln.v, ln.h, ln.left, ln.right, lineItems, bi, bw, ln.dropCap))
            }
        }
        return Layout(rows, typicalH, tilt)
    }

    private fun endsSentence(t: String) =
        t.trimEnd().trimEnd('"', '”', '’', '\'', ')', ']').lastOrNull()?.let { it in ".!?:;" } ?: false
    private fun startsNew(t: String) = t.firstOrNull()?.let { it.isUpperCase() || it.isDigit() || it in "•·*-–—(\"“" } ?: false

    /**
     * Joins recognised rows into paragraphs of naturally flowing text (no line breaks inside),
     * so speech doesn't pause at the end of every printed line and a screen reader moves
     * a whole paragraph at a time. A new paragraph starts:
     *  - at a new block (column, caption, label),
     *  - after a vertical gap of more than ~1.8 line heights,
     *  - after a short line (well under the column width) when the next line starts like a new item,
     *  - after a heading: a first line with no closing punctuation, narrower than the line below it,
     *  - after a line ending in a number (prices, receipts) when the next line starts a new item,
     *  - around a heading in the middle of a column (short, after a finished sentence).
     * A word broken across lines with a hyphen is mended when the joined word is a real word
     * ("pack- age" -> "package"); a real hyphen is kept ("state- of-the-art" -> "state-of-the-art").
     */
    fun paragraphs(layout: Layout, rowTexts: List<String>, isWord: (String) -> Boolean = { false }): List<String> {
        val paragraphs = ArrayList<String>()
        val cur = ArrayList<Int>()
        fun flush() {
            if (cur.isEmpty()) return
            val sb = StringBuilder()
            for (i in cur) {
                val t = rowTexts[i].trim()
                if (sb.isEmpty()) { sb.append(t); continue }
                val prevWordStart = sb.lastIndexOf(" ") + 1
                if (sb.endsWith("-") && t.firstOrNull()?.isLowerCase() == true) {
                    val head = sb.substring(prevWordStart, sb.length - 1).trimStart('(', '"', '“')
                    val tail = t.takeWhile { it.isLetter() }
                    if (head.isNotEmpty() && head.all { it.isLetter() } && isWord(head + tail)) sb.setLength(sb.length - 1)
                    sb.append(t)
                } else sb.append(' ').append(t)
            }
            // A lone letter or symbol on its own is almost always a speck or a picture detail.
            val t = sb.toString()
            if (!(t.length == 1 && !t[0].isDigit())) paragraphs.add(t)
            cur.clear()
        }
        var last = -1
        for (i in rowTexts.indices) {
            val text = rowTexts[i].trim()
            if (text.isEmpty()) continue
            if (last >= 0) {
                val prev = layout.rows[last]; val row = layout.rows[i]
                val prevText = rowTexts[last].trim()
                val newBlock = prev.block != row.block
                val gap = row.v - prev.v > layout.typicalHeight * 1.8f
                val shortLine = prev.width < 0.75f * prev.blockWidth && startsNew(text) && !prevText.endsWith("-")
                val heading = cur.size == 1 && !endsSentence(prevText) && !prevText.endsWith(",") &&
                    !prevText.endsWith("-") && startsNew(text) && prev.width < 0.92f * row.width
                val itemLine = prevText.lastOrNull()?.isDigit() == true && startsNew(text)
                // A heading in the middle of a column: after a finished sentence, a short line
                // that starts like a title and doesn't end like a sentence.
                val midHeading = endsSentence(prevText) && startsNew(text) && !endsSentence(text) &&
                    !text.endsWith(",") && row.width < 0.75f * row.blockWidth
                if (newBlock || gap || shortLine || heading || itemLine || midHeading) flush()
            }
            cur.add(i)
            last = i
        }
        flush()
        return paragraphs
    }
}
