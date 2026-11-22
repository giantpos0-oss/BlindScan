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
 * 1. Straighten: the page's tilt is measured from the text lines themselves.
 * 2. Rows: pieces of text at the same height are found, and each gap between them is judged by
 *    the whole channel of white space it belongs to, down the page:
 *    - a channel between two stacks of long, evenly filled lines is a column gutter: split;
 *    - a channel between short entries of varying width is a table, form, receipt or menu:
 *      join, so each row reads across ("Milk 3.49", "Name: Will Walsh");
 *    - a lone gap: join only if it is small.
 * 3. Blocks: rows stacked closely, overlapping side to side, in similar-size text.
 * 4. Order: blocks top to bottom, columns left to right; small scraps (picture labels, page
 *    numbers) after the main text.
 * 5. Paragraphs (see [paragraphs]) use typesetting rules: a line that ends early, with room left
 *    for the next word, ends its paragraph; indents, bullets and table rows start new ones; a
 *    paragraph that runs off the bottom of one column carries on at the top of the next.
 */
object ReadingOrder {

    /** One printed line on the straightened page, in reading order. */
    class Row(
        val v: Float, val h: Float, val left: Float, val right: Float, val boxes: List<Int>,
        val block: Int,
        /** Left and right edges of the block (column) this row belongs to. */
        val blockLeft: Float, val blockRight: Float,
        /** Box index of a decorative drop capital starting this line (joined to the next word), or -1. */
        val dropCap: Int = -1,
        /** A row of a table, form, receipt or menu: always its own paragraph. */
        val tableRow: Boolean = false,
        /** Usual distance between rows in this block, its number of rows, and the share of its rows that fill its width. */
        val blockPitch: Float = 0f,
        val blockLines: Int = 1,
        val blockFullShare: Float = 0f,
        /** Lines share a right edge but start at ragged left edges (an address set to the right). */
        val blockRightAligned: Boolean = false,
        /** The block's lines are long (many words each), as in prose. */
        val blockLongLines: Boolean = false,
        /** A scrap (a picture label, a page number): its lines are read as one phrase. */
        val scrap: Boolean = false,
    ) {
        val width: Float get() = right - left
        val blockWidth: Float get() = blockRight - blockLeft
    }

    class Layout(val rows: List<Row>, val typicalHeight: Float, val tilt: Float)

    /** Direction of a box's long side, folded into -90..+90 degrees (radians). */
    private fun lineAngle(b: TextBox): Float {
        var a = if (b.w >= b.h) b.angle else b.angle + (PI / 2).toFloat()
        while (a > PI / 2) a -= PI.toFloat()
        while (a <= -PI / 2) a += PI.toFloat()
        return a
    }

    private class Line(var v: Float, var h: Float, var left: Float, var right: Float, val rowKey: Int) {
        val items = ArrayList<Int>()
        var dropCap = -1
        var tableRow = false
        /** Centre of this line's own pieces (the row's centre can be pulled by pieces of another column). */
        var ownV = v
    }

    private class Block {
        val lines = ArrayList<Line>()
        var left = Float.MAX_VALUE; var right = -Float.MAX_VALUE
        var top = Float.MAX_VALUE; var bottom = -Float.MAX_VALUE
        var chars = 0
    }

    private class Gap(val row: Int, val leftPiece: Int, val rightPiece: Int, val from: Float, val to: Float) {
        var channel = -1
    }

    private fun median(xs: List<Float>): Float = xs.sorted().let { if (it.isEmpty()) 0f else it[it.size / 2] }

    /**
     * The largest set of [items] whose [edge] lies along one straight line down the page (within
     * 0.6 text heights), allowing a slow drift (a page photographed at an angle) and ignoring
     * outliers (indented first lines, lines broken by glare). Found by trying the line through each
     * pair of items and keeping the one most items agree with.
     */
    private fun <T> alignedSubset(items: List<T>, edge: (T) -> Float, v: (T) -> Float, h: (T) -> Float): List<T> {
        if (items.size < 3) return items
        val e = items.map(edge); val y = items.map(v); val hh = items.map(h)
        var best: List<Int> = listOf(0)
        for (i in items.indices) for (j in i + 1 until items.size) {
            val dy = y[j] - y[i]
            val slope = if (abs(dy) < 1e-3f) 0f else (e[j] - e[i]) / dy
            if (abs(slope) > 0.15f) continue // a column edge is close to vertical once the page is straightened
            val inl = items.indices.filter { k -> abs(e[k] - (e[i] + slope * (y[k] - y[i]))) < 0.6f * hh[k] }
            if (inl.size > best.size) best = inl
        }
        return best.map { items[it] }
    }

    fun layout(boxes: List<TextBox>): Layout {
        if (boxes.isEmpty()) return Layout(emptyList(), 0f, 0f)
        val n = boxes.size

        // ---- 1. straighten
        val angles = boxes.filter { max(it.w, it.h) > 2 * min(it.w, it.h) }.map { lineAngle(it) }.sorted()
        val tilt = if (angles.isEmpty()) 0f else angles[angles.size / 2]
        val c = cos(tilt); val s = sin(tilt)
        val us = FloatArray(n); val vs = FloatArray(n); val extU = FloatArray(n); val extV = FloatArray(n)
        for ((i, b) in boxes.withIndex()) {
            us[i] = b.cx * c + b.cy * s; vs[i] = -b.cx * s + b.cy * c
            val d = b.angle - tilt
            extU[i] = b.w * abs(cos(d)) + b.h * abs(sin(d))
            extV[i] = b.w * abs(sin(d)) + b.h * abs(cos(d))
        }
        val hs = FloatArray(n) { min(extV[it], min(boxes[it].w, boxes[it].h).coerceAtLeast(1f)) }
        val l0 = FloatArray(n) { us[it] - extU[it] / 2 }
        val l1 = FloatArray(n) { us[it] + extU[it] / 2 }
        val typicalH = hs.sorted()[n / 2]

        // Drop capitals: one big letter, more than ~1.8 lines tall and not much wider than tall.
        val isDropCap = BooleanArray(n) {
            extV[it] > 1.8f * typicalH && extU[it] < 1.5f * extV[it] && extU[it] < 3f * typicalH
        }

        // ---- 2a. rows of pieces at the same height
        val rowsOfPieces = ArrayList<ArrayList<Int>>()
        val rowV = ArrayList<Float>(); val rowH = ArrayList<Float>()
        for (i in (0 until n).sortedBy { vs[it] }) {
            if (isDropCap[i]) continue
            var placed = false
            for (r in rowsOfPieces.indices) {
                if (abs(rowV[r] - vs[i]) < 0.5f * min(rowH[r], hs[i]) &&
                    rowsOfPieces[r].none { j -> min(l1[i], l1[j]) - max(l0[i], l0[j]) > 0.3f * min(extU[i], extU[j]) }
                ) { rowsOfPieces[r].add(i); placed = true; break }
            }
            if (!placed) { rowsOfPieces.add(arrayListOf(i)); rowV.add(vs[i]); rowH.add(hs[i]) }
        }
        for (r in rowsOfPieces) r.sortBy { l0[it] }

        // ---- 2b. gaps between neighbouring pieces, grouped into channels of white space down the page
        val gaps = ArrayList<Gap>()
        for ((r, row) in rowsOfPieces.withIndex()) for (k in 0 until row.size - 1) {
            val a = row[k]; val b = row[k + 1]
            val h = max(hs[a], hs[b])
            // The detector doesn't split a line at ordinary word spaces, so any real gap between
            // two of its boxes (over ~0.2 line heights) is a separation worth judging.
            if (l0[b] - l1[a] >= 0.2f * h) gaps.add(Gap(r, a, b, l1[a], l0[b]))
        }
        run {
            val parent = IntArray(gaps.size) { it }
            fun find(x: Int): Int { var y = x; while (parent[y] != y) y = parent[y]; return y }
            for (i in gaps.indices) for (j in 0 until i) {
                val gi = gaps[i]; val gj = gaps[j]
                val overlap = min(gi.to, gj.to) - max(gi.from, gj.from)
                if (overlap > 0.1f * typicalH) { val a = find(i); val b = find(j); if (a != b) parent[max(a, b)] = min(a, b) }
            }
            for (i in gaps.indices) gaps[i].channel = find(i)
        }
        // Classify each channel. Within a channel, take the longest run of rows whose right-hand
        // pieces line up (left edges: a column margin or left-aligned cells; right edges: prices,
        // numbers), measured row to row so a column photographed at an angle still counts. Stray
        // gaps in the same channel (a line broken by glare, a label) are judged on their own.
        val gapKind = HashMap<Gap, Boolean>() // true = column gutter (split), false = table (join)
        for ((_, members) in gaps.groupBy { it.channel }) {
            if (members.size < 3) continue
            val run = alignedSubset(members, { l0[it.rightPiece] }, { rowV[it.row] }, { hs[it.rightPiece] })
                .let { byLeft ->
                    val byRight = alignedSubset(members, { l1[it.rightPiece] }, { rowV[it.row] }, { hs[it.rightPiece] })
                    if (byLeft.size >= byRight.size) byLeft else byRight
                }
            if (run.map { it.row }.distinct().size < 3) continue
            // The left-hand pieces must line up too (a column margin, the first column of a table,
            // item names on a receipt). Labels scattered over a picture don't.
            val leftAligned = max(
                alignedSubset(run, { l0[it.leftPiece] }, { rowV[it.row] }, { hs[it.leftPiece] }).size,
                alignedSubset(run, { l1[it.leftPiece] }, { rowV[it.row] }, { hs[it.leftPiece] }).size,
            )
            if (leftAligned < max(3, (run.size * 6 + 9) / 10)) continue
            // Long text on both sides: columns. A table needs short entries on at least one side
            // (prices, values, labels); glare-broken fragments don't make an article a table.
            // (Upper quartile, not the middle: a short column's paragraph ends and title are short.)
            fun proseLike(widths: List<Float>, heights: List<Float>) =
                widths.sorted()[(widths.size * 3) / 4] >= 8f * median(heights)
            // Prose on either side is a column beside something else (an article beside a photo's
            // handwritten list of names, a timeline entry beside the next), not a table.
            val proseL = proseLike(run.map { extU[it.leftPiece] }, run.map { hs[it.leftPiece] })
            val proseR = proseLike(run.map { extU[it.rightPiece] }, run.map { hs[it.rightPiece] })
            val wideGap = median(run.map { (it.to - it.from) / hs[it.leftPiece] }) > 12f
            val gutter = (proseL && proseR) || ((proseL || proseR) && wideGap)
            for (g in run) gapKind[g] = gutter
        }
        // A column gutter blocks any joining across it on other rows too (a short word like
        // "soundtrack." at the top of a column, beside a line of the column to its left).
        val gutterSpans = gapKind.filter { it.value }.keys.groupBy { it.channel }.values.map { gs ->
            Pair(median(gs.map { it.from }), median(gs.map { it.to }))
        }

        // ---- 2c. lines: join the pieces of each row except across column gutters and big lone gaps
        val lines = ArrayList<Line>()
        val gapAt = HashMap<Long, Gap>()
        for (g in gaps) gapAt[g.leftPiece.toLong() * 1_000_003L + g.rightPiece] = g
        for ((r, row) in rowsOfPieces.withIndex()) {
            var cur: Line? = null
            for ((k, i) in row.withIndex()) {
                var join = false; var table = false
                if (cur != null) {
                    val prev = row[k - 1]
                    val h = max(hs[prev], hs[i])
                    val g = gapAt[prev.toLong() * 1_000_003L + i]
                    val sizeDiffers = max(hs[prev], hs[i]) > 1.2f * min(hs[prev], hs[i])
                    // Pieces that touch or nearly touch are one line, unless the type is far apart in
                    // size (a big year heading beside a timeline entry's text).
                    if (g == null) join = max(hs[prev], hs[i]) <= 1.6f * min(hs[prev], hs[i])
                    else {
                        val crossesGutter = gutterSpans.any { (from, to) -> g.from < to && g.to > from }
                        when (if (crossesGutter) true else gapKind[g]) {
                            true -> join = false                       // column gutter
                            // Table row. (Sizes aren't compared: a box around "$8" or "City:" is taller
                            // than one around lowercase text in the same type.)
                            false -> { join = true; table = true }
                            null -> if (sizeDiffers) join = false else { // a caption beside text: separate
                                val gap = g.to - g.from
                                val bothLong = extU[prev] > 4f * h && extU[i] > 4f * h
                                // Two short phrases well apart (labels on a map or picture): separate labels.
                                val bothShort = extU[prev] < 4f * h && extU[i] < 4f * h
                                // A wide gap to slightly different type (a sidebar's title or last line
                                // beside the main text) is two lines, not missing words.
                                val mildSize = max(hs[prev], hs[i]) > 1.08f * min(hs[prev], hs[i])
                                join = gap < 3f * h && !(bothLong && gap > 0.3f * h) && !(mildSize && gap > 1f * h) &&
                                    !(bothShort && gap > 1.2f * h)
                            }
                        }
                    }
                }
                if (join && cur != null) {
                    cur.items.add(i); cur.right = max(cur.right, l1[i]); cur.left = min(cur.left, l0[i])
                    if (table) cur.tableRow = true
                } else {
                    cur = Line(rowV[r], rowH[r], l0[i], l1[i], r).also { it.items.add(i); lines.add(it) }
                }
            }
        }
        // Drop capitals join the first line beside them.
        for (i in 0 until n) {
            if (!isDropCap[i]) continue
            val capTop = vs[i] - extV[i] / 2; val capBottom = vs[i] + extV[i] / 2
            val target = lines.filter { ln ->
                ln.v > capTop - 0.5f * typicalH && ln.v < capBottom &&
                    ln.left - l1[i] > -1.2f * typicalH && ln.left - l1[i] < 1.5f * typicalH
            }.minByOrNull { it.v }
            if (target != null) {
                target.items.add(i); target.dropCap = i; target.left = min(target.left, l0[i])
            } else lines.add(Line(vs[i], extV[i], l0[i], l1[i], -1).also { it.items.add(i) })
        }
        // Each line's height position and text size come from its own pieces (not from whichever
        // piece started its row, which may belong to the next column).
        for (ln in lines) {
            var sumH = 0f; var sumV = 0f; var cnt = 0
            for (i in ln.items) if (i != ln.dropCap) { sumH += hs[i]; sumV += vs[i]; cnt++ }
            if (cnt > 0) { ln.h = sumH / cnt; ln.ownV = sumV / cnt }
        }
        lines.sortBy { it.v }

        // ---- 3. blocks: link each line to the nearest line above it that overlaps it side to side,
        //         in similar-size text, no more than ~2.6 line heights higher.
        fun canLink(li: Line, lj: Line): Boolean { // li above lj
            val dv = lj.v - li.v
            if (dv <= 0.3f * lj.h || dv > 2.6f * max(li.h, lj.h)) return false
            if (max(li.h, lj.h) > 1.45f * min(li.h, lj.h)) return false // different type sizes: different blocks
            val overlap = min(li.right, lj.right) - max(li.left, lj.left)
            return overlap >= 0.4f * min(li.right - li.left, lj.right - lj.left)
        }
        // A line sitting over two side-by-side columns (a title or subtitle spanning them) must not
        // join them into one block: columns below don't attach to it.
        val spanning = BooleanArray(lines.size)
        for (i in lines.indices) {
            val below = lines.filter { it !== lines[i] && canLink(lines[i], it) }
            spanning[i] = below.any { a -> below.any { b ->
                a !== b && abs(a.v - b.v) < 0.5f * min(a.h, b.h) && min(a.right, b.right) - max(a.left, b.left) < 0
            } }
        }
        val parent = IntArray(lines.size) { it }
        fun find(x: Int): Int { var r = x; while (parent[r] != r) r = parent[r]; return r }
        for (j in lines.indices) {
            val lj = lines[j]
            var best = -1; var bestDv = Float.MAX_VALUE
            for (i in 0 until j) {
                if (spanning[i] || !canLink(lines[i], lj)) continue
                // Nearest above, but between lines at about the same height (text beside a picture
                // and the picture's caption) prefer the one in the same size type.
                val dv = lj.v - lines[i].v + 2f * abs(lines[i].h - lj.h)
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
            blk.chars += ln.items.sumOf { (extU[it] / max(1f, hs[it]) * 2).toInt() } // rough letter count
        }
        val blocks = blockMap.values.toList()

        // ---- 4. order
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
                val free = remaining.filter { b -> remaining.none { a -> a !== b && before(a, b) } }
                val next = (if (free.isNotEmpty()) free else remaining).minWith(compareBy({ it.top }, { it.left }))
                out.add(next); remaining.remove(next)
            }
            return out
        }
        // Scraps: a line or two of short text in ordinary-size type. Big, wide type is a headline
        // and stays with the main text; a big short word (on a sign or drum in a photo) is a scrap.
        // A short line just above the text it overlaps is a title ("Dinner", "Mains"), not a scrap.
        fun titled(b: Block): Block? {
            if (b.lines.size > 2) return null
            // The nearest block below it (nothing else, such as a subtitle, in between)...
            val o = blocks.filter { o ->
                o !== b && o.top > (b.top + b.bottom) / 2 && min(o.right, b.right) - max(o.left, b.left) > 0
            }.minByOrNull { it.top } ?: return null
            // ...close below, with a few lines of text no bigger than the title.
            return if (o.lines.size >= 2 && o.top - b.bottom < 3f * b.lines.maxOf { it.h } &&
                b.lines.maxOf { it.h } >= 0.9f * median(o.lines.map { it.h })) o else null
        }
        val titleOf = HashMap<Block, Block>()
        for (b in blocks) titled(b)?.let { titleOf[b] = it }
        fun titleOver(b: Block) = b in titleOf
        val isSmall = { b: Block ->
            val headline = b.lines.maxOf { it.h } > 1.3f * typicalH && (b.right - b.left) > 8f * typicalH
            b.lines.size <= 2 && b.chars < 40 && !headline && b.lines.none { it.tableRow } && !titleOver(b) &&
                b !in titleOf.values // text with its own title (a timeline entry) is part of the page
        }
        val main = order(blocks.filterNot(isSmall)); val scraps = order(blocks.filter(isSmall))
        val ordered = ArrayList(main + scraps)
        // A title is read just before the text it heads (a year over each entry of a timeline).
        for ((t, target) in titleOf) {
            if (titleOf.containsKey(target)) continue
            if (t.right - t.left > 1.2f * (target.right - target.left)) continue // a headline over several columns stays first
            ordered.remove(t); ordered.add(ordered.indexOf(target).coerceAtLeast(0), t)
        }

        val rows = ArrayList<Row>()
        // Reading order inside a block: by row, then left to right (pieces of one printed line split
        // by glare stay in order), each line keeping its own position for spacing.
        val rowOrderV = FloatArray(rowsOfPieces.size) { rowV[it] }
        for ((bi, blk) in ordered.withIndex()) {
            val sorted = blk.lines.sortedWith(compareBy({ if (it.rowKey >= 0) rowOrderV[it.rowKey] else it.v }, { it.left }))
            val pitch = median((1 until sorted.size).map { sorted[it].v - sorted[it - 1].v })
            // Share of rows that come close to the right margin of the lines around them: prose does,
            // a list of short lines (a label, an address) doesn't. Measured locally, so a column that
            // narrows around a picture, or is photographed at an angle, still counts as prose.
            val fullShare = sorted.indices.count { k ->
                var m = sorted[k].right
                for (j in max(0, k - 2)..min(sorted.size - 1, k + 2)) m = max(m, sorted[j].right)
                sorted[k].right >= m - 3f * sorted[k].h && sorted[k].right - sorted[k].left >= 8f * sorted[k].h
            }.toFloat() / sorted.size
            // Long lines (many words each), unlike an address or a label.
            val longLines = median(sorted.map { (it.right - it.left) / it.h }) >= 8f
            val hMed = median(sorted.map { it.h })
            val rightAligned = sorted.size >= 2 &&
                sorted.maxOf { it.right } - sorted.minOf { it.right } < 0.5f * hMed &&
                sorted.maxOf { it.left } - sorted.minOf { it.left } > 1.5f * hMed
            for (ln in sorted) {
                val lineItems = ln.items.sortedBy { if (it == ln.dropCap) -Float.MAX_VALUE else us[it] }
                rows.add(Row(ln.ownV, ln.h, ln.left, ln.right, lineItems, bi, blk.left, blk.right, ln.dropCap, ln.tableRow,
                    pitch, sorted.size, fullShare, rightAligned, longLines, blk in scraps))
            }
        }
        return Layout(rows, typicalH, tilt)
    }

    private fun cleanEnd(t: String) = t.trimEnd().trimEnd('"', '”', '’', '\'', ')', ']')
    private fun endsSentence(t: String) = cleanEnd(t).lastOrNull()?.let { it in ".!?:;" } ?: false
    private val marker = Regex("^(•|·|▪|■|◦|\\*|–|—|-|\\(?[0-9]{1,2}[.)]|\\(?[a-zA-Z][.)])\\s")
    private fun startsItem(t: String) = marker.containsMatchIn(t)
    private fun startsLower(t: String) = t.firstOrNull()?.isLowerCase() == true

    /**
     * Joins recognised rows into paragraphs of naturally flowing text (no line breaks inside), so
     * speech doesn't pause at the end of every printed line and a screen reader moves a whole
     * paragraph at a time.
     *
     * Rows are joined when the text simply wrapped: the line above was full (the next word would
     * not have fitted on it), or the next line starts in lowercase, or the line above ends with a
     * hyphen. A new paragraph starts at a table row, a bullet or numbered item, an indented line after
     * a finished sentence, a line after a gap, or a line after one that ended early. A paragraph that
     * runs off the bottom of a column carries on at the top of the next.
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
            val t = sb.toString()
            // A lone letter or symbol on its own is almost always a speck or a picture detail.
            if (!(t.length == 1 && !t[0].isDigit())) paragraphs.add(t)
            cur.clear()
        }

        /**
         * Did [prevRow] simply wrap? It must run from the column's left edge (allowing an indent) to
         * its right edge, so close that the first word of [next] wouldn't have fitted on it. Only
         * trusted in blocks that look like prose (many full rows), not in lists of short lines.
         */
        fun wrapped(at: Int, prevText: String, next: String): Boolean {
            val prevRow = layout.rows[at]
            // The column's margins at this line (the lines just above and below, same block): photos taken at an angle
            // make lines nearer the camera look wider, so the whole column's widest line isn't a fair test.
            val charW = prevRow.width / max(1, prevText.length)
            /** Right margin of this line and the other lines from..to in this block, or null if there are no others. */
            fun rightMargin(from: Int, to: Int): Float? {
                var m: Float? = null
                for (k in max(0, from)..min(layout.rows.size - 1, to)) {
                    val r = layout.rows[k]
                    if (k != at && r.block == prevRow.block) m = max(m ?: r.right, r.right)
                }
                // Lines that stop well short of this one (the column narrows beside a picture there)
                // don't share its margin.
                return if (m != null && m >= prevRow.right - 0.25f * prevRow.width) max(m, prevRow.right) else null
            }
            // The column's left edge here: the middle of the nearby lines' left edges (where text
            // narrows beside a picture, the lines above the picture start farther left).
            val lefts = ArrayList<Float>()
            for (k in max(0, at - 2)..min(layout.rows.size - 1, at + 2)) {
                val r = layout.rows[k]
                if (r.block == prevRow.block) lefts.add(r.left)
            }
            val localLeft = min(prevRow.left, median(lefts))
            val word = next.trim().substringBefore(' ')
            val need = (word.length + 1) * charW - 0.5f * charW
            // It wrapped if the next word wouldn't fit before the margin of the lines just above it,
            // or of the lines just below it (where a column narrows or widens around a picture,
            // only one side shares this line's margin).
            val above = rightMargin(at - 2, at - 1); val below = rightMargin(at + 1, at + 2)
            val reachesRight = (above != null && prevRow.right + need > above) || (below != null && prevRow.right + need > below)
            val fromLeft = prevRow.left - localLeft < 2.5f * prevRow.h
            // Prose: this line is long (many words) and so is another line near it. Judged around the
            // line itself, so a letter whose addresses and body share one block still wraps its body,
            // while short lines (addresses, labels, a signature) are never treated as wrapped.
            val long = prevRow.width >= 8f * prevRow.h
            var longNear = false
            for (k in max(0, at - 2)..min(layout.rows.size - 1, at + 2)) {
                val r = layout.rows[k]
                if (k != at && r.block == prevRow.block && r.width >= 8f * r.h) longNear = true
            }
            val prose = long && (longNear || (prevRow.blockLines <= 3 && prevRow.blockLongLines)) && !prevRow.blockRightAligned
            return reachesRight && fromLeft && prose
        }

        /** The column's left edge around row [at]: the middle of its block's nearby left edges. */
        fun columnLeft(at: Int): Float {
            val b = layout.rows[at].block
            val lefts = ArrayList<Float>()
            for (k in max(0, at - 3)..min(layout.rows.size - 1, at + 3))
                if (layout.rows[k].block == b && rowTexts[k].isNotBlank()) lefts.add(layout.rows[k].left)
            return median(lefts)
        }

        // A narrow block of short lines that reads as one sentence or so: only its last line ends with
        // a full stop (a timeline entry: "Robert G. Smith / became the second / Lumberjack Band /
        // director."). Its lines simply wrapped. An address, a list or a heading over text has a
        // line ending early with no sentence end, or a colon, or an item marker.
        val flowing = HashSet<Int>()
        run {
            val byBlock = layout.rows.indices.filter { rowTexts.getOrNull(it)?.isNotBlank() == true }.groupBy { layout.rows[it].block }
            for ((b, idx) in byBlock) {
                if (idx.size < 2 || idx.size > 8) continue
                val texts = idx.map { rowTexts[it].trim() }
                val rs = idx.map { layout.rows[it] }
                if (!endsSentence(texts.last())) continue
                if (texts.dropLast(1).any { endsSentence(it) || it.endsWith(",") }) continue
                if (texts.any { startsItem(it) } || rs.any { it.tableRow }) continue
                val hMed = median(rs.map { it.h })
                if (rs.any { abs(it.h - hMed) > 0.15f * hMed }) continue
                // Lines of a column, each reaching near the block's right edge (but the last).
                if (rs.dropLast(1).any { it.right < it.blockRight - 0.4f * (it.blockRight - it.blockLeft) }) continue
                if (rs.any { abs(it.left - rs[0].left) > 1.5f * hMed }) continue
                flowing.add(b)
            }
        }

        var last = -1
        for (i in rowTexts.indices) {
            val text = rowTexts[i].trim()
            if (text.isEmpty()) continue
            if (last >= 0) {
                val prev = layout.rows[last]; val row = layout.rows[i]
                val prevText = rowTexts[last].trim()
                val full = wrapped(last, prevText, text)
                val shift = row.left - prev.left
                // Indented from the line above (a drop cap sticks out to the left of its line, so
                // not then), or from the column's edge (a one-line paragraph above was indented too).
                val fromColumn = row.left - columnLeft(i)
                // Lines just below a drop cap are set in beside it: not indents.
                var besideCap = false
                for (k in max(0, i - 3) until i) if (layout.rows[k].block == row.block && layout.rows[k].dropCap >= 0) besideCap = true
                val indented = !besideCap && ((shift > 0.45f * row.h && shift < 4f * row.h) ||
                    (fromColumn > 0.45f * row.h && fromColumn < 4f * row.h))
                val sizeChange = abs(prev.h - row.h) > 0.15f * max(prev.h, row.h)
                val newParagraph = if (prev.block != row.block) {
                    // A new block. Carry the paragraph on only between real columns of text, and only
                    // if it plainly continues: the last line was full and this column's first line
                    // isn't indented, a new item, or a different size.
                    // The next column sits right beside this one, across a gutter, at a similar width.
                    val nextColumn = row.blockLeft > prev.blockRight - 0.2f * prev.blockWidth &&
                        row.blockLeft - prev.blockRight < 0.5f * prev.blockWidth &&
                        row.blockWidth > 0.6f * prev.blockWidth && row.blockWidth < 1.6f * prev.blockWidth
                    !(prev.blockLines >= 3 && row.blockLines >= 3 && row.v < prev.v && nextColumn && full &&
                        !startsItem(text) && !prev.tableRow && !row.tableRow && !sizeChange &&
                        fromColumn < 0.45f * row.h)
                } else {
                    val dv = row.v - prev.v
                    // Line spacing nearby (lines nearer the camera are farther apart, so compare locally).
                    val nearby = ArrayList<Float>()
                    for (k in max(1, last - 3)..min(layout.rows.size - 1, i + 3)) {
                        if (k == i) continue
                        val a = layout.rows[k - 1]; val b = layout.rows[k]
                        if (a.block == prev.block && b.block == prev.block && b.v - a.v > 0.5f * b.h) nearby.add(b.v - a.v) // pieces of one line don't count
                    }
                    // Short paragraphs put many paragraph gaps among the nearby spacings: line spacing is the
                    // smaller ones (lower third), not the middle.
                    val pitch = if (nearby.size >= 2) nearby.sorted()[(nearby.size - 1) / 3] else prev.blockPitch
                    val gap = (pitch > 0f && dv > 1.45f * pitch) ||
                        dv > 1.8f * max(layout.typicalHeight, max(prev.h, row.h)) // big type has bigger spacing
                    when {
                        prev.scrap && dv < 1.8f * max(prev.h, row.h) -> false // a label over two lines is one phrase (closely set)
                        prev.tableRow || row.tableRow -> true
                        startsItem(text) -> true
                        // A list of items ending in numbers (prices, quantities): one line after another
                        // ends in a number. (Prose rarely ends two lines running with a number.)
                        prevText.lastOrNull()?.isDigit() == true && text.lastOrNull()?.isDigit() == true &&
                            (text.first().isUpperCase() || text.first().isDigit()) -> true
                        // ...and the first item of such a list starts a new paragraph.
                        text.lastOrNull()?.isDigit() == true && prevText.lastOrNull()?.isDigit() != true &&
                            rowTexts.getOrNull(i + 1)?.trim()?.lastOrNull()?.isDigit() == true &&
                            (text.first().isUpperCase() || text.first().isDigit()) -> true
                        prev.block in flowing && !gap -> false // (box heights vary with capitals and descenders)
                        sizeChange -> true
                        gap -> true
                        indented && endsSentence(prevText) -> true
                        prevText.endsWith("-") -> false
                        full -> false
                        startsLower(text) -> false
                        else -> true // the line above ended early: its paragraph ended there
                    }
                }
                if (newParagraph) flush()
            }
            cur.add(i)
            last = i
        }
        flush()
        return paragraphs
    }
}
