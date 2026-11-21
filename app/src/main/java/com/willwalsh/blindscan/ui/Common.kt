package com.willwalsh.blindscan.ui

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.willwalsh.blindscan.speech.Announcer
import com.willwalsh.blindscan.speech.Reader

val LocalReader = staticCompositionLocalOf<Reader> { error("Reader not provided") }
val LocalAnnouncer = staticCompositionLocalOf<Announcer> { error("Announcer not provided") }

/** High-contrast dark theme: yellow on black. */
@Composable
fun BlindScanTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFFFFD54F),
            onPrimary = Color.Black,
            secondaryContainer = Color(0xFF263238),
            onSecondaryContainer = Color.White,
            background = Color.Black,
            onBackground = Color.White,
            surface = Color(0xFF101010),
            onSurface = Color.White,
        ),
        content = content,
    )
}

/** Whoever is showing the camera puts its "take picture" action here; MainActivity calls it. */
object VolumeKeys {
    @Volatile var handler: (() -> Unit)? = null
}

/** One scanned page: its paragraphs, the photo it came from, and how the reading went. */
data class ScannedPage(
    val paragraphs: List<String> = emptyList(),
    val key: Long = System.nanoTime(),
    val photo: File? = null,
    val reading: Boolean = false,
    val detectMs: Long = 0,
    val recognizeMs: Long = 0,
    val lineConfidences: List<Float> = emptyList(),
    /** A note for the test report about the light, e.g. which of two photos was used. */
    val lightNote: String = "",
    val altPhoto: File? = null,
) {
    val text: String get() = paragraphs.joinToString("\n\n")
}

/**
 * A scanned document of one or more pages. [readFrom] is the first page to read aloud when the
 * document opens (a newly added page is read on its own). [revision] changes on every update.
 */
data class ScannedDocument(
    val pages: List<ScannedPage>,
    val id: Long = System.nanoTime(),
    val readFrom: Int = 0,
    val revision: Int = 0,
) {
    val reading: Boolean get() = pages.any { it.reading }
    val text: String get() = textOf(0)
    /** Text of the pages from [from] on, with "Page N" headings when there is more than one page. */
    fun textOf(from: Int): String =
        if (pages.size == 1) pages[0].text
        else pages.withIndex().drop(from).joinToString("\n\n") { (i, p) -> "Page ${i + 1}\n\n${p.text}" }
}

object DocumentStore {
    /** The document on screen right now, or null when the camera is showing. */
    var open by mutableStateOf<ScannedDocument?>(null)
    /** The most recent document, so "Open last document" can bring it back. */
    var last by mutableStateOf<ScannedDocument?>(null)
    /** Pages scanned in batch mode that haven't been opened as a document yet. */
    var batch by mutableStateOf<List<ScannedPage>>(emptyList())
    /** When set, the next scan is added to this document as a new page. */
    var addingTo by mutableStateOf<ScannedDocument?>(null)

    /** Opens a document for a page that is being read right now (or adds it to [addingTo]). */
    fun startPage(photo: File?): ScannedDocument {
        val base = addingTo
        addingTo = null
        val page = ScannedPage(photo = photo, reading = true)
        val doc = if (base != null) base.copy(pages = base.pages + page, readFrom = base.pages.size, revision = base.revision + 1, id = System.nanoTime())
        else ScannedDocument(listOf(page))
        open = doc; last = doc
        return doc
    }

    /** Replaces the last page of the open document (same id: reading carries on). */
    fun updateLastPage(docId: Long, change: (ScannedPage) -> ScannedPage) {
        // The document may still be on screen, or you may have gone back to the camera meanwhile.
        val d = open?.takeIf { it.id == docId } ?: last?.takeIf { it.id == docId } ?: return
        val pages = d.pages.toMutableList()
        pages[pages.size - 1] = change(pages.last())
        val nd = d.copy(pages = pages, revision = d.revision + 1)
        if (open?.id == docId) open = nd
        last = nd
    }

    fun close() { open = null }

    /** Removes the open document, any pages waiting in a batch, and any half-added page. */
    fun clearAll() { open = null; last = null; batch = emptyList(); addingTo = null }
}

/** Photos of recent scans, kept in the app's private cache so they can be shared for testing. */
object ScanPhotos {
    fun dir(context: Context) = File(context.cacheDir, "scans").also { it.mkdirs() }

    fun save(context: Context, bitmap: Bitmap): File? = try {
        val f = File(dir(context), "scan-${System.currentTimeMillis()}.jpg")
        f.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        f
    } catch (_: Throwable) { null }

    /** Deletes photos no document refers to any more. */
    fun tidy(context: Context) {
        val keep = HashSet<String>()
        listOfNotNull(DocumentStore.open, DocumentStore.last, DocumentStore.addingTo).forEach { d ->
            d.pages.forEach { p -> p.photo?.let { keep.add(it.name) }; p.altPhoto?.let { keep.add(it.name) } }
        }
        DocumentStore.batch.forEach { p -> p.photo?.let { keep.add(it.name) }; p.altPhoto?.let { keep.add(it.name) } }
        dir(context).listFiles()?.forEach { if (it.name !in keep) it.delete() }
    }
}

fun vibrate(context: Context, pattern: LongArray) {
    val v: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
    if (v?.hasVibrator() == true) v.vibrate(VibrationEffect.createWaveform(pattern, -1))
}

val READY_BUZZ = longArrayOf(0, 40, 70, 40)
val TICK_BUZZ = longArrayOf(0, 25)

@Composable
fun ScreenHeading(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        fontSize = 24.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp).semantics { heading() },
    )
}

/** Large, easy-to-find button. */
@Composable
fun BigButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = 16.dp, vertical = 4.dp),
        colors = ButtonDefaults.buttonColors(),
    ) { Text(text, fontSize = 22.sp, fontWeight = FontWeight.Bold) }
}

@Composable
fun RowScope.SmallButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.weight(1f).heightIn(min = 60.dp).padding(4.dp),
    ) { Text(text, fontSize = 18.sp) }
}

/** Scribe-style DVR controls: rewind a sentence, play/pause, fast forward a sentence. */
@Composable
fun DvrControls(reader: Reader, playing: Boolean, enabled: Boolean, onUserControl: () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilledTonalButton(
            onClick = { onUserControl(); reader.rewind() }, enabled = enabled,
            modifier = Modifier.weight(1f).heightIn(min = 64.dp),
        ) { Icon(Icons.Filled.FastRewind, contentDescription = "Rewind one sentence") }
        Button(
            onClick = { onUserControl(); reader.togglePlay() }, enabled = enabled,
            modifier = Modifier.weight(1.3f).heightIn(min = 64.dp),
        ) {
            Icon(
                if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (playing) "Pause" else "Play",
            )
        }
        FilledTonalButton(
            onClick = { onUserControl(); reader.fastForward() }, enabled = enabled,
            modifier = Modifier.weight(1f).heightIn(min = 64.dp),
        ) { Icon(Icons.Filled.FastForward, contentDescription = "Fast forward one sentence") }
    }
}
