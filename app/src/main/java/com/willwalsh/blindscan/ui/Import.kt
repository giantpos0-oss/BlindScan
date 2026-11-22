package com.willwalsh.blindscan.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.OpenableColumns
import com.willwalsh.blindscan.BlindScanApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

/** One page to read from an imported file: a picture, or one page of a PDF. */
private class ImportPage(val load: () -> Bitmap?)

/** Pictures bigger than this (longest side, pixels) are scaled down: more detail doesn't help reading. */
private const val MAX_IMAGE_SIDE = 4000
/** PDF pages are drawn this big (longest side), about 240 dots per inch for a letter-size page. */
private const val PDF_PAGE_SIDE = 2600

private fun isPdf(context: Context, uri: Uri): Boolean {
    val type = context.contentResolver.getType(uri)
    if (type == "application/pdf") return true
    return displayName(context, uri)?.lowercase()?.endsWith(".pdf") == true
}

private fun displayName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
}.getOrNull()

private fun pdfPageCount(context: Context, uri: Uri): Int = runCatching {
    context.contentResolver.openFileDescriptor(uri, "r")?.use { fd -> PdfRenderer(fd).use { it.pageCount } } ?: 0
}.getOrDefault(0)

private fun renderPdfPage(context: Context, uri: Uri, index: Int): Bitmap? = runCatching {
    context.contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
        PdfRenderer(fd).use { pdf ->
            pdf.openPage(index).use { page ->
                val scale = PDF_PAGE_SIDE.toFloat() / max(page.width, page.height)
                val bmp = Bitmap.createBitmap(
                    (page.width * scale).roundToInt().coerceAtLeast(1),
                    (page.height * scale).roundToInt().coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888,
                )
                bmp.eraseColor(Color.WHITE) // scanned PDFs can be transparent where there's no ink
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bmp
            }
        }
    }
}.getOrNull()

private fun decodeImage(context: Context, uri: Uri): Bitmap? = runCatching {
    if (android.os.Build.VERSION.SDK_INT >= 28) {
        // ImageDecoder turns the picture the right way up (EXIF orientation) by itself.
        val b = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longSide = max(info.size.width, info.size.height)
            if (longSide > MAX_IMAGE_SIDE) {
                val s = MAX_IMAGE_SIDE.toFloat() / longSide
                decoder.setTargetSize((info.size.width * s).roundToInt(), (info.size.height * s).roundToInt())
            }
        }
        if (b.config != Bitmap.Config.ARGB_8888) b.copy(Bitmap.Config.ARGB_8888, false) else b
    } else {
        val opts = android.graphics.BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        context.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) }
    }
}.getOrNull()

/**
 * Reads pictures and scanned PDFs from the phone into one document, page after page: every
 * picture is a page, and so is every page of a PDF. The document opens straight away and each
 * page is read aloud as it is recognised, just like a scan.
 */
fun importFiles(app: BlindScanApp, context: Context, uris: List<Uri>, say: (String) -> Unit) {
    app.appScope.launch(Dispatchers.Default) {
        val pages = ArrayList<ImportPage>()
        for (uri in uris) {
            if (isPdf(context, uri)) {
                val n = pdfPageCount(context, uri)
                for (i in 0 until n) pages.add(ImportPage { renderPdfPage(context, uri, i) })
            } else pages.add(ImportPage { decodeImage(context, uri) })
        }
        if (pages.isEmpty()) {
            withContext(Dispatchers.Main) { say("Couldn't open that file. Choose a picture or a PDF.") }
            return@launch
        }
        val doc = withContext(Dispatchers.Main) {
            if (pages.size > 1) say("Reading ${pages.size} pages.")
            DocumentStore.startImport(pages.size)
        }
        for ((i, p) in pages.withIndex()) {
            val bmp = p.load()
            val page = if (bmp == null) ScannedPage(paragraphs = listOf("This page couldn't be opened."))
            else readPage(app, context, bmp, onProgress = { paras ->
                DocumentStore.updatePage(doc.id, i) { it.copy(paragraphs = paras) }
            })
            withContext(Dispatchers.Main) {
                DocumentStore.updatePage(doc.id, i) {
                    val paras = page.paragraphs.ifEmpty { listOf("No text found on this page.") }
                    page.copy(key = it.key, paragraphs = paras, reading = false)
                }
            }
        }
        withContext(Dispatchers.Main) { ScanPhotos.tidy(context) }
    }
}

