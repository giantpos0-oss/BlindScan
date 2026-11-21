package com.willwalsh.blindscan.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.willwalsh.blindscan.BlindScanApp
import com.willwalsh.blindscan.LocalSettings
import com.willwalsh.blindscan.Voice
import com.willwalsh.blindscan.speech.StreamChunker
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DocumentScreen(doc: ScannedDocument, onClose: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as BlindScanApp
    val settings = LocalSettings.current
    val reader = LocalReader.current
    val announcer = LocalAnnouncer.current
    val view = LocalView.current
    val readerState by reader.state.collectAsState()
    var menuOpen by remember { mutableStateOf(false) }

    fun say(text: String) = announcer.say(text, view, settings.documentVoice == Voice.SCREEN_READER)

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val ok = runCatching {
            context.contentResolver.openOutputStream(uri)?.use { it.write(doc.text.toByteArray()) } != null
        }.getOrDefault(false)
        say(if (ok) "Saved." else "Couldn't save the file.")
    }

    // ---- the document actions, used by the menu AND by the screen reader's Actions list
    val readAloud: () -> Unit = {
        if (readerState.playing) reader.pause()
        else {
            reader.rate = settings.speechRate
            if (!readerState.hasText) reader.load(doc.text, true) else reader.play()
        }
    }
    val save: () -> Unit = { saveLauncher.launch(defaultFileName(doc.id)) }
    val share: () -> Unit = {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, doc.text)
        }
        context.startActivity(Intent.createChooser(send, "Share text"))
    }
    val copy: () -> Unit = {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Scanned text", doc.text))
        say("Text copied.")
    }
    val addPage: () -> Unit = {
        reader.clear()
        DocumentStore.addingTo = doc
        DocumentStore.close()
        say("Scan page ${doc.pages.size + 1}.")
    }
    val shareForTesting: () -> Unit = {
        if (!shareScanForTesting(context, app, doc)) say("The photos for this scan are no longer available.")
    }
    val newScan: () -> Unit = { reader.clear(); onClose() }
    val discard: () -> Unit = {
        reader.clear()
        DocumentStore.clearAll()
        ScanPhotos.tidy(context)
        say("Document discarded.")
    }

    val a11yActions = listOf(
        CustomAccessibilityAction(if (readerState.playing) "Pause reading" else "Read aloud") { readAloud(); true },
        CustomAccessibilityAction("Save to device") { save(); true },
        CustomAccessibilityAction("Share text") { share(); true },
        CustomAccessibilityAction("Copy text") { copy(); true },
        CustomAccessibilityAction("Add another page") { addPage(); true },
        CustomAccessibilityAction("Share scan for testing") { shareForTesting(); true },
        CustomAccessibilityAction("New scan") { newScan(); true },
        CustomAccessibilityAction("Discard") { discard(); true },
    )

    BackHandler { newScan() }

    // ---- Read the document straight away, the way the settings say. With text to speech,
    // speech starts as soon as the first sentences are recognised and keeps up with the reading.
    val textToRead = doc.textOf(doc.readFrom)
    val streamer = remember(doc.id, doc.readFrom) { StreamChunker() }
    var announced by remember(doc.id, doc.readFrom) { mutableStateOf(false) }
    LaunchedEffect(doc.id, doc.readFrom) {
        if (settings.documentVoice == Voice.TEXT_TO_SPEECH) {
            reader.rate = settings.speechRate
            reader.startStream(autoPlay = true)
        } else reader.clear()
    }
    LaunchedEffect(doc.id, doc.readFrom, doc.revision) {
        if (settings.documentVoice == Voice.TEXT_TO_SPEECH) {
            reader.appendStream(streamer.take(textToRead, done = !doc.reading))
            if (!doc.reading) reader.endStream()
        } else if (!doc.reading && !announced) {
            announced = true
            delay(500) // let the screen reader finish its own announcement first
            if (announcer.screenReaderOn) {
                @Suppress("DEPRECATION")
                view.announceForAccessibility(textToRead)
            } else {
                reader.load(textToRead, autoPlay = true) // no screen reader running: fall back to speech
            }
        }
    }
    DisposableEffect(Unit) { onDispose { reader.stopSpeaking() } }

    Column(Modifier.fillMaxSize()) {
        // Top bar: title on the left, Document actions at the top right.
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (doc.pages.size > 1) "Document, ${doc.pages.size} pages" else "Document",
                fontSize = 24.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.heightIn(min = 56.dp)) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Document actions")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    MenuItem(if (readerState.playing) "Pause reading" else "Read aloud") { menuOpen = false; readAloud() }
                    MenuItem("Save to device") { menuOpen = false; save() }
                    MenuItem("Share text") { menuOpen = false; share() }
                    MenuItem("Copy text") { menuOpen = false; copy() }
                    MenuItem("Add another page") { menuOpen = false; addPage() }
                    MenuItem("Share scan for testing") { menuOpen = false; shareForTesting() }
                    MenuItem("New scan") { menuOpen = false; newScan() }
                    MenuItem("Discard") { menuOpen = false; discard() }
                }
            }
        }

        LazyColumn(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp)) {
            doc.pages.forEachIndexed { pi, page ->
                if (doc.pages.size > 1) item(key = "h${page.key}") {
                    Text(
                        "Page ${pi + 1}",
                        fontSize = 22.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                            .semantics { heading(); customActions = a11yActions },
                    )
                }
                page.paragraphs.forEachIndexed { i, para ->
                    item(key = "p${page.key}-$i") {
                        Text(
                            para,
                            fontSize = 20.sp,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                                .semantics { customActions = a11yActions },
                        )
                    }
                }
                if (page.reading) item(key = "r${page.key}") {
                    Text("Reading this page…", fontSize = 18.sp, modifier = Modifier.padding(vertical = 8.dp))
                }
            }
            if (!doc.reading) item(key = "end") {
                Text(
                    "End of document.",
                    fontSize = 16.sp,
                    modifier = Modifier.padding(vertical = 12.dp).semantics { customActions = a11yActions },
                )
            }
        }

        if (settings.documentVoice == Voice.TEXT_TO_SPEECH || readerState.hasText) {
            if (readerState.hasText || readerState.streaming) {
                Text(readerState.position, fontSize = 16.sp, modifier = Modifier.padding(horizontal = 16.dp))
            }
            DvrControls(reader, readerState.playing, enabled = true, onUserControl = {
                if (!readerState.hasText && !readerState.streaming) { reader.rate = settings.speechRate; reader.load(doc.text, autoPlay = false) }
            })
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                SmallButton("Back to start", onClick = { reader.restart() }, enabled = readerState.hasText)
            }
        }
    }
}

@Composable
private fun MenuItem(text: String, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(text, fontSize = 20.sp) }, onClick = onClick, modifier = Modifier.heightIn(min = 56.dp))
}

private fun defaultFileName(id: Long): String =
    "Scan " + SimpleDateFormat("yyyy-MM-dd HH-mm", Locale.getDefault()).format(Date()) + ".txt"

/**
 * Shares the original photo(s) plus a report (recognised text, confidence, timings, phone details)
 * so a scan can be looked at in detail. Returns false if the photos are gone.
 */
private fun shareScanForTesting(context: Context, app: BlindScanApp, doc: ScannedDocument): Boolean {
    val photos = doc.pages.mapNotNull { it.photo }.filter { it.exists() }
    if (photos.isEmpty()) return false
    val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
    val report = buildString {
        appendLine("BlindScan $version scan report")
        appendLine("Phone: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), ${Runtime.getRuntime().availableProcessors()} cores")
        appendLine("Fast math (XNNPACK): ${if (app.documentEngine.usingFastMath) "in use" else "off or unavailable"}")
        appendLine("Word correction: ${if (app.settings.wordCorrection) "on" else "off"}")
        appendLine()
        doc.pages.forEachIndexed { i, p ->
            val conf = p.lineConfidences
            appendLine("=== Page ${i + 1}: photo ${p.photo?.name ?: "missing"}")
            appendLine("Finding text: ${p.detectMs} ms. Reading lines: ${p.recognizeMs} ms. Lines: ${conf.size}.")
            if (conf.isNotEmpty()) {
                appendLine("Average line confidence: ${"%.3f".format(conf.average())}. Lowest: ${conf.sorted().take(5).joinToString { "%.2f".format(it) }}")
            }
            appendLine()
            appendLine(p.text)
            appendLine()
        }
    }
    val reportFile = File(ScanPhotos.dir(context), "BlindScan report ${System.currentTimeMillis()}.txt")
    reportFile.writeText(report)
    val authority = "${context.packageName}.files"
    val uris = ArrayList<Uri>()
    for (f in photos + reportFile) uris.add(FileProvider.getUriForFile(context, authority, f))
    val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = "*/*"
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        putExtra(Intent.EXTRA_SUBJECT, "BlindScan scan for testing")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        clipData = ClipData.newRawUri("scan", uris[0]).also { c -> uris.drop(1).forEach { c.addItem(ClipData.Item(it)) } }
    }
    context.startActivity(Intent.createChooser(send, "Share scan for testing"))
    return true
}
