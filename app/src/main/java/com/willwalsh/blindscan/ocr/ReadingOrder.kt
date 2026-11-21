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
 * The page is straightened first: the tilt is measured from the text lines themselves, so a page
 * photographed at an angle still reads line by line in the right order.
 */
object ReadingOrder {

    /** One printed line: the boxes on it (left to right), its position and extent on the straightened page. */
    class Row(val v: Float, val h: Float, val left: Float, val right: Float, val boxes: List<Int>) {
        val width: Float get() = right - left
    }

    class Layout(val rows: List<Row>, val typicalHeight: Float, val typicalWidth: Float, val tilt: Float)

    /** Direction of a box's long side, folded into -90..+90 degrees (radians). */
    private fun lineAngle(b: TextBox): Float {
        var a = if (b.w >= b.h) b.angle else b.angle + (PI / 2).toFloat()
        while (a > PI / 2) a -= PI.toFloat()
        while (a <= -PI / 2) a += PI.toFloat()
        return a
    }

    fun layout(boxes: List<TextBox>): Layout {
        if (boxes.isEmpty()) return Layout(emptyList(), 0f, 0f, 0f)
        // Page tilt = median direction of the clearly line-shaped boxes.
        val angles = boxes.filter { max(it.w, it.h) > 2 * min(it.w, it.h) }.map { lineAngle(it) }.sorted()
        val tilt = if (angles.isEmpty()) 0f else angles[angles.size / 2]
        val c = cos(tilt); val s = sin(tilt)
        // Straightened coordinates: u runs along the lines, v down the page.
        val us = FloatArray(boxes.size); val vs = FloatArray(boxes.size)
        for ((i, b) in boxes.withIndex()) { us[i] = b.cx * c + b.cy * s; vs[i] = -b.cx * s + b.cy * c }

        class Acc(val v: Float, val h: Float) { val items = ArrayList<Int>() }
        val accs = ArrayList<Acc>()
        for (i in boxes.indices.sortedBy { vs[it] }) {
            val h = min(boxes[i].w, boxes[i].h)
            val row = accs.firstOrNull { abs(it.v - vs[i]) < 0.5f * min(it.h, h) }
            if (row != null) row.items.add(i) else accs.add(Acc(vs[i], h).also { it.items.add(i) })
        }
        val rows = accs.sortedBy { it.v }.map { a ->
            val sorted = a.items.sortedBy { us[it] }
            var l = Float.MAX_VALUE; var r = -Float.MAX_VALUE
            for (i in sorted) { val half = max(boxes[i].w, boxes[i].h) / 2; l = min(l, us[i] - half); r = max(r, us[i] + half) }
            Row(a.v, a.h, l, r, sorted)
        }
        val heights = rows.map { it.h }.sorted()
        val widths = rows.map { it.width }.sorted()
        return Layout(rows, heights[heights.size / 2], widths[(widths.size * 8 / 10).coerceAtMost(widths.size - 1)], tilt)
    }

    private fun endsSentence(t: String) = t.trimEnd().lastOrNull()?.let { it in ".!?:;" } ?: false
    private fun startsNew(t: String) = t.firstOrNull()?.let { it.isUpperCase() || it.isDigit() || it in "•·*-–—(\"“" } ?: false

    /**
     * Joins recognised rows into paragraphs of naturally flowing text (no line breaks inside),
     * so speech doesn't pause at the end of every printed line and a screen reader moves
     * a whole paragraph at a time. A new paragraph starts:
     *  - after a vertical gap of more than ~1.8 line heights,
     *  - after a short line (well under the column width) when the next line starts like a new item,
     *  - after a heading: a first line with no closing punctuation, narrower than the line below it,
 *  - after a line ending in a number (prices, receipts) when the next line starts a new item.
     * A word broken across lines with a hyphen is mended when the joined word is a real word
     * ("pack- age" -> "package"); a real hyphen is kept ("state- of-the-art" -> "state-of-the-art").
     */
    fun paragraphs(layout: Layout, rowTexts: List<String>, isWord: (String) -> Boolean = { false }): List<String> {
        val paragraphs = ArrayList<String>()
        val cur = ArrayList<Int>() // row indices in the current paragraph
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
            paragraphs.add(sb.toString())
            cur.clear()
        }
        var last = -1
        for (i in rowTexts.indices) {
            val text = rowTexts[i].trim()
            if (text.isEmpty()) continue
            if (last >= 0) {
                val prev = layout.rows[last]; val row = layout.rows[i]
                val prevText = rowTexts[last].trim()
                val gap = row.v - prev.v > layout.typicalHeight * 1.8f
                val shortLine = prev.width < 0.75f * layout.typicalWidth && startsNew(text) && !prevText.endsWith("-")
                val heading = cur.size == 1 && !endsSentence(prevText) && !prevText.endsWith(",") &&
                    !prevText.endsWith("-") && startsNew(text) && prev.width < 0.92f * row.width
                // Receipts, price lists, labels: a line ending in a number, then a line starting a new item.
                val itemLine = prevText.lastOrNull()?.isDigit() == true && startsNew(text)
                if (gap || shortLine || heading || itemLine) flush()
            }
            cur.add(i)
            last = i
        }
        flush()
        return paragraphs
    }
}
