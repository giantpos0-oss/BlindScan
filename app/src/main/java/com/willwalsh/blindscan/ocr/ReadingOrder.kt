package com.willwalsh.blindscan.ocr

import kotlin.math.abs
import kotlin.math.min

/** Puts recognised pieces into reading order: top to bottom, left to right, split into paragraphs. */
object ReadingOrder {

    private class Row(val cy: Float, val h: Float) {
        val items = ArrayList<OcrEngine.Line>()
    }

    fun paragraphs(lines: List<OcrEngine.Line>): List<String> {
        if (lines.isEmpty()) return emptyList()
        val rows = ArrayList<Row>()
        for (line in lines.sortedBy { it.box.cy }) {
            val h = min(line.box.w, line.box.h)
            val row = rows.firstOrNull { abs(it.cy - line.box.cy) < 0.5f * min(it.h, h) }
            if (row != null) row.items.add(line) else rows.add(Row(line.box.cy, h).also { it.items.add(line) })
        }
        rows.sortBy { it.cy }
        val heights = rows.map { it.h }.sorted()
        val typical = heights[heights.size / 2]

        val paragraphs = ArrayList<String>()
        val current = ArrayList<String>()
        var lastCy = Float.NaN
        for (row in rows) {
            val text = row.items.sortedBy { it.box.cx }.joinToString(" ") { it.text }
            // A gap of more than ~1.8 line heights starts a new paragraph.
            if (!lastCy.isNaN() && row.cy - lastCy > typical * 1.8f && current.isNotEmpty()) {
                paragraphs.add(current.joinToString("\n")); current.clear()
            }
            current.add(text)
            lastCy = row.cy
        }
        if (current.isNotEmpty()) paragraphs.add(current.joinToString("\n"))
        return paragraphs
    }
}
