package com.willwalsh.blindscan.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.lazy.itemsIndexed
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
import com.willwalsh.blindscan.LocalSettings
import com.willwalsh.blindscan.Voice
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DocumentScreen(doc: ScannedDocument, onClose: () -> Unit) {
    val context = LocalContext.current
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
        else { reader.rate = settings.speechRate; if (!readerState.hasText) reader.load(doc.text, true) else reader.play() }
    }
    val save: () -> Unit = { saveLauncher.launch(defaultFileName(doc.time)) }
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
    val discard: () -> Unit = {
        reader.clear()
        DocumentStore.discard()
        say("Document discarded.")
    }
    val newScan: () -> Unit = { reader.clear(); onClose() }

    val a11yActions = listOf(
        CustomAccessibilityAction("Save to device") { save(); true },
        CustomAccessibilityAction("Share") { share(); true },
        CustomAccessibilityAction("Copy text") { copy(); true },
        CustomAccessibilityAction(if (readerState.playing) "Pause reading" else "Read aloud") { readAloud(); true },
        CustomAccessibilityAction("Scan another document") { newScan(); true },
        CustomAccessibilityAction("Discard") { discard(); true },
    )

    BackHandler { newScan() }

    // Read the document straight away, the way the settings say.
    LaunchedEffect(doc) {
        if (settings.documentVoice == Voice.TEXT_TO_SPEECH) {
            reader.rate = settings.speechRate
            reader.load(doc.text, autoPlay = true)
        } else {
            reader.clear()
            delay(700) // let the screen reader finish its own announcement first
            if (announcer.screenReaderOn) {
                @Suppress("DEPRECATION")
                view.announceForAccessibility(doc.text)
            } else {
                reader.load(doc.text, autoPlay = true) // no screen reader running: fall back to speech
            }
        }
    }
    DisposableEffect(Unit) { onDispose { reader.stopSpeaking() } }

    Column(Modifier.fillMaxSize()) {
        // Top bar: title on the left, Document actions at the top right.
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Document",
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
                    MenuItem("Share") { menuOpen = false; share() }
                    MenuItem("Copy text") { menuOpen = false; copy() }
                    MenuItem("Scan another document") { menuOpen = false; newScan() }
                    MenuItem("Discard") { menuOpen = false; discard() }
                }
            }
        }

        LazyColumn(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp)) {
            itemsIndexed(doc.paragraphs) { i, para ->
                Text(
                    para,
                    fontSize = 20.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .semantics { customActions = a11yActions },
                )
                if (i == doc.paragraphs.lastIndex) Text(
                    "End of document.",
                    fontSize = 16.sp,
                    modifier = Modifier.padding(vertical = 12.dp).semantics { customActions = a11yActions },
                )
            }
        }

        if (settings.documentVoice == Voice.TEXT_TO_SPEECH || readerState.hasText) {
            if (readerState.hasText) Text(readerState.position, fontSize = 16.sp, modifier = Modifier.padding(horizontal = 16.dp))
            DvrControls(reader, readerState.playing, enabled = true, onUserControl = {
                if (!readerState.hasText) { reader.rate = settings.speechRate; reader.load(doc.text, autoPlay = false) }
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

private fun defaultFileName(time: Long): String =
    "Scan " + SimpleDateFormat("yyyy-MM-dd HH-mm", Locale.getDefault()).format(Date(time)) + ".txt"
