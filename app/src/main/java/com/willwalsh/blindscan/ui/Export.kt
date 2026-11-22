package com.willwalsh.blindscan.ui

import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The file types a document can be saved as. */
enum class ExportFormat(val label: String, val extension: String, val mime: String) {
    TXT("Plain text (TXT)", "txt", "text/plain"),
    DOCX("Word document (DOCX)", "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
    PDF("PDF", "pdf", "application/pdf"),
    HTML("Web page (HTML)", "html", "text/html"),
    MARKDOWN("Markdown", "md", "text/markdown"),
    EPUB("E-book (EPUB)", "epub", "application/epub+zip"),
}

/**
 * Writes a scanned document in any [ExportFormat]. Every format keeps the same structure: a
 * title, a "Page N" heading per page when there's more than one page, and the paragraphs.
 */
object Exporter {
    /** A paragraph and its heading level in the saved file: 0 for text, otherwise 2 to 4 (1 is the title). */
    private class P(val text: String, val level: Int)
    private class Section(val heading: String?, val paragraphs: List<P>)

    /**
     * Headings found on the page sit under the document title (level 1) and, with several pages,
     * under each "Page N" heading (level 2).
     */
    private fun sections(doc: ScannedDocument): List<Section> {
        val base = if (doc.pages.size == 1) 1 else 2
        fun ps(page: ScannedPage) = page.paragraphs.mapIndexed { i, t ->
            val h = page.headingOf(i); P(t, if (h > 0) base + h else 0)
        }
        return if (doc.pages.size == 1) listOf(Section(null, ps(doc.pages[0])))
        else doc.pages.mapIndexed { i, p -> Section("Page ${i + 1}", ps(p)) }
    }

    fun write(doc: ScannedDocument, title: String, format: ExportFormat, out: OutputStream) {
        val secs = sections(doc)
        when (format) {
            ExportFormat.TXT -> out.write(doc.text.toByteArray())
            ExportFormat.MARKDOWN -> out.write(markdown(title, secs).toByteArray())
            ExportFormat.HTML -> out.write(html(title, secs).toByteArray())
            ExportFormat.DOCX -> docx(title, secs, out)
            ExportFormat.EPUB -> epub(title, secs, out)
            ExportFormat.PDF -> pdf(title, secs, out)
        }
    }

    // ---------------------------------------------------------------- text formats

    private fun esc(t: String) = t.filter { it == '\t' || it == '\n' || it >= ' ' }.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun markdown(title: String, secs: List<Section>) = buildString {
        append("# ").append(title).append("\n\n")
        for (s in secs) {
            if (s.heading != null) append("## ").append(s.heading).append("\n\n")
            for (p in s.paragraphs) {
                // Keep text that happens to look like Markdown (a line starting "# " or "1. ") as text.
                val safe = p.text.replace(Regex("^([#>*+-]|\\d+\\.)(\\s)"), "\\\\$1$2")
                if (p.level > 0) append("#".repeat(p.level)).append(' ')
                append(safe).append("\n\n")
            }
        }
    }

    private fun htmlBody(title: String, secs: List<Section>) = buildString {
        append("<h1>").append(esc(title)).append("</h1>\n")
        for (s in secs) {
            if (s.heading != null) append("<h2>").append(esc(s.heading)).append("</h2>\n")
            for (p in s.paragraphs) append(htmlPara(p))
        }
    }

    private fun htmlPara(p: P) =
        if (p.level > 0) "<h${p.level}>${esc(p.text)}</h${p.level}>\n" else "<p>${esc(p.text)}</p>\n"

    private fun html(title: String, secs: List<Section>) = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${esc(title)}</title>
<style>body{font-family:sans-serif;font-size:1.2em;line-height:1.6;max-width:40em;margin:1em auto;padding:0 1em}</style>
</head>
<body>
<main>
${htmlBody(title, secs)}</main>
</body>
</html>
"""

    // ---------------------------------------------------------------- Word

    private fun docx(title: String, secs: List<Section>, out: OutputStream) {
        fun para(text: String, style: String? = null) = buildString {
            append("<w:p>")
            if (style != null) append("<w:pPr><w:pStyle w:val=\"").append(style).append("\"/></w:pPr>")
            append("<w:r><w:t xml:space=\"preserve\">").append(esc(text)).append("</w:t></w:r></w:p>")
        }
        val body = buildString {
            append(para(title, "Title"))
            for (s in secs) {
                if (s.heading != null) append(para(s.heading, "Heading1"))
                for (p in s.paragraphs) append(if (p.level > 0) para(p.text, "Heading${p.level - 1}") else para(p.text))
            }
        }
        val document = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>$body<w:sectPr><w:pgSz w:w="12240" w:h="15840"/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440" w:header="720" w:footer="720" w:gutter="0"/></w:sectPr></w:body></w:document>"""
        fun heading(id: String, name: String, size: Int, level: Int?) =
            """<w:style w:type="paragraph" w:styleId="$id"><w:name w:val="$name"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:qFormat/><w:pPr><w:keepNext/><w:spacing w:before="240" w:after="120"/>${if (level != null) "<w:outlineLvl w:val=\"$level\"/>" else ""}</w:pPr><w:rPr><w:b/><w:sz w:val="$size"/></w:rPr></w:style>"""
        val styles = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="Calibri" w:hAnsi="Calibri" w:cs="Calibri" w:eastAsia="Calibri"/><w:sz w:val="24"/><w:lang w:val="en-US"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:after="160" w:line="276" w:lineRule="auto"/></w:pPr></w:pPrDefault></w:docDefaults><w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/><w:qFormat/></w:style>${heading("Title", "Title", 40, null)}${heading("Heading1", "heading 1", 32, 0)}${heading("Heading2", "heading 2", 28, 1)}${heading("Heading3", "heading 3", 26, 2)}</w:styles>"""
        val contentTypes = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/><Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/><Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/></Types>"""
        val rels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/></Relationships>"""
        val docRels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>"""
        val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(Date())
        val core = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"><dc:title>${esc(title)}</dc:title><dc:creator>BlindScan</dc:creator><dc:language>en-US</dc:language><dcterms:created xsi:type="dcterms:W3CDTF">$now</dcterms:created></cp:coreProperties>"""
        ZipOutputStream(out).use { z ->
            fun put(name: String, text: String) { z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry() }
            put("[Content_Types].xml", contentTypes)
            put("_rels/.rels", rels)
            put("word/document.xml", document)
            put("word/styles.xml", styles)
            put("word/_rels/document.xml.rels", docRels)
            put("docProps/core.xml", core)
        }
    }

    // ---------------------------------------------------------------- EPUB 3

    private fun epub(title: String, secs: List<Section>, out: OutputStream) {
        val id = "urn:uuid:" + UUID.randomUUID()
        val modified = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(Date())
        fun xhtml(t: String, body: String) = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" lang="en" xml:lang="en">
<head><meta charset="utf-8"/><title>${esc(t)}</title></head>
<body>
$body</body>
</html>
"""
        // One chapter per page (or one chapter for a one-page document).
        val chapters = secs.mapIndexed { i, s ->
            val body = buildString {
                if (i == 0) append("<h1>").append(esc(title)).append("</h1>\n")
                if (s.heading != null) append("<h2>").append(esc(s.heading)).append("</h2>\n")
                for (p in s.paragraphs) append(htmlPara(p))
            }
            Pair(s.heading ?: title, xhtml(s.heading ?: title, "<section>\n$body</section>\n"))
        }
        val nav = xhtml(title, buildString {
            append("<nav epub:type=\"toc\" id=\"toc\"><h1>Contents</h1><ol>\n")
            chapters.forEachIndexed { i, (name, _) -> append("<li><a href=\"c${i + 1}.xhtml\">").append(esc(name)).append("</a></li>\n") }
            append("</ol></nav>\n")
        })
        val opf = buildString {
            append("""<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid" xml:lang="en">
<metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
<dc:identifier id="bookid">$id</dc:identifier>
<dc:title>${esc(title)}</dc:title>
<dc:language>en</dc:language>
<dc:creator>BlindScan</dc:creator>
<meta property="dcterms:modified">$modified</meta>
<meta property="schema:accessMode">textual</meta>
<meta property="schema:accessModeSufficient">textual</meta>
<meta property="schema:accessibilityFeature">structuralNavigation</meta>
<meta property="schema:accessibilityFeature">tableOfContents</meta>
<meta property="schema:accessibilityHazard">none</meta>
</metadata>
<manifest>
<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
""")
            chapters.indices.forEach { append("<item id=\"c${it + 1}\" href=\"c${it + 1}.xhtml\" media-type=\"application/xhtml+xml\"/>\n") }
            append("</manifest>\n<spine>\n")
            chapters.indices.forEach { append("<itemref idref=\"c${it + 1}\"/>\n") }
            append("</spine>\n</package>\n")
        }
        val container = """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>
"""
        ZipOutputStream(out).use { z ->
            // The "mimetype" file must come first and be stored uncompressed.
            val mt = "application/epub+zip".toByteArray()
            val e = ZipEntry("mimetype").apply {
                method = ZipEntry.STORED; size = mt.size.toLong(); compressedSize = mt.size.toLong()
                crc = CRC32().also { it.update(mt) }.value
            }
            z.putNextEntry(e); z.write(mt); z.closeEntry()
            fun put(name: String, text: String) { z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry() }
            put("META-INF/container.xml", container)
            put("OEBPS/content.opf", opf)
            put("OEBPS/nav.xhtml", nav)
            chapters.forEachIndexed { i, (_, x) -> put("OEBPS/c${i + 1}.xhtml", x) }
        }
    }

    // ---------------------------------------------------------------- PDF

    /** A letter-size PDF with real (selectable, searchable) text. */
    private fun pdf(title: String, secs: List<Section>, out: OutputStream) {
        val pageW = 612; val pageH = 792; val margin = 72
        val width = pageW - 2 * margin
        val bodyPaint = TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 12f }
        val h1 = TextPaint(bodyPaint).apply { textSize = 20f; isFakeBoldText = true }
        val h2 = TextPaint(bodyPaint).apply { textSize = 16f; isFakeBoldText = true }
        val h3 = TextPaint(bodyPaint).apply { textSize = 14f; isFakeBoldText = true }
        val blocks = ArrayList<Pair<String, TextPaint>>()
        blocks.add(title to h1)
        for (s in secs) {
            if (s.heading != null) blocks.add(s.heading to h2)
            for (p in s.paragraphs) blocks.add(p.text to when (p.level) { 0 -> bodyPaint; 2 -> h2; else -> h3 })
        }
        val pdf = PdfDocument()
        var pageNo = 0
        var page: PdfDocument.Page? = null
        var y = 0f
        fun newPage() {
            page?.let { pdf.finishPage(it) }
            pageNo++
            page = pdf.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNo).create())
            y = margin.toFloat()
        }
        newPage()
        for ((text, paint) in blocks) {
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.2f).build()
            val gapAfter = paint.textSize * 0.8f
            // Draw line by line so a long paragraph can carry on onto the next page.
            for (line in 0 until layout.lineCount) {
                val lineH = (layout.getLineBottom(line) - layout.getLineTop(line)).toFloat()
                if (y + lineH > pageH - margin) newPage()
                val c = page!!.canvas
                c.save()
                c.translate(margin.toFloat(), y - layout.getLineTop(line))
                c.clipRect(0f, layout.getLineTop(line).toFloat(), width.toFloat(), layout.getLineBottom(line).toFloat())
                layout.draw(c)
                c.restore()
                y += lineH
            }
            y += gapAfter
        }
        page?.let { pdf.finishPage(it) }
        val buf = ByteArrayOutputStream()
        pdf.writeTo(buf)
        pdf.close()
        out.write(buf.toByteArray())
    }
}
