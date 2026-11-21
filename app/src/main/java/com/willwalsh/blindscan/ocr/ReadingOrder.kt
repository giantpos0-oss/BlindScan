package com.willwalsh.blindscan.ocr

import kotlin.math.abs
import kotlin.math.min

/**
 * Reading order, worked out from the text boxes alone (before anything is recognised), so that
 * lines can be read top to bottom and spoken while the rest of the page is still being read.
 */
object ReadingOrder {

    /** One printed line: the boxes on it (left to right) and its position. */
    class Row(val cy: Float, val h: Float, val boxes: List<Int>)

    class Layout(val rows: List<Row>, val typicalHeight: Float)

    fun layout(boxes: List<TextBox>): Layout {
        if (boxes.isEmpty()) return Layout(emptyList(), 0f)
        class Acc(val cy: Float, val h: Float) { val items = ArrayList<Int>() }
        val accs = ArrayList<Acc>()
        for (i in boxes.indices.sortedBy { boxes[it].cy }) {
            val b = boxes[i]
            val h = min(b.w, b.h)
            val row = accs.firstOrNull { abs(it.cy - b.cy) < 0.5f * min(it.h, h) }
            if (row != null) row.items.add(i) else accs.add(Acc(b.cy, h).also { it.items.add(i) })
        }
        val rows = accs.sortedBy { it.cy }.map { a -> Row(a.cy, a.h, a.items.sortedBy { boxes[it].cx }) }
        val heights = rows.map { it.h }.sorted()
        return Layout(rows, heights[heights.size / 2])
    }

    /**
     * Joins recognised row texts into paragraphs: a gap of more than ~1.8 line heights starts
     * a new paragraph. Rows whose text came back empty are skipped.
     */
    fun paragraphs(layout: Layout, rowTexts: List<String>): List<String> {
        val paragraphs = ArrayList<String>()
        val current = ArrayList<String>()
        var lastCy = Float.NaN
        for ((i, text) in rowTexts.withIndex()) {
            if (text.isBlank()) continue
            val row = layout.rows[i]
            if (!lastCy.isNaN() && row.cy - lastCy > layout.typicalHeight * 1.8f && current.isNotEmpty()) {
                paragraphs.add(current.joinToString("\n")); current.clear()
            }
            current.add(text)
            lastCy = row.cy
        }
        if (current.isNotEmpty()) paragraphs.add(current.joinToString("\n"))
        return paragraphs
    }
}
