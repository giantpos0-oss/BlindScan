package com.willwalsh.blindscan

import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.sp
import com.willwalsh.blindscan.speech.Announcer
import com.willwalsh.blindscan.speech.Reader
import com.willwalsh.blindscan.ui.CameraPermissionGate
import com.willwalsh.blindscan.ui.DocumentStore
import com.willwalsh.blindscan.ui.ScanPhotos
import com.willwalsh.blindscan.ui.HelpScreen
import com.willwalsh.blindscan.ui.LiveScreen
import com.willwalsh.blindscan.ui.LocalAnnouncer
import com.willwalsh.blindscan.ui.LocalReader
import com.willwalsh.blindscan.ui.BlindScanTheme
import com.willwalsh.blindscan.ui.ScanScreen
import com.willwalsh.blindscan.ui.SettingsScreen
import com.willwalsh.blindscan.ui.VolumeKeys

/** Files shared to BlindScan from another app, waiting to be read. */
object SharedFiles {
    var pending by androidx.compose.runtime.mutableStateOf<List<android.net.Uri>>(emptyList())
}

enum class Tab(val label: String, val icon: ImageVector) {
    LIVE("Live mode", Icons.Filled.RecordVoiceOver),
    SCAN("Scan document", Icons.Filled.DocumentScanner),
    HELP("Help", Icons.AutoMirrored.Filled.Help),
    SETTINGS("Settings", Icons.Filled.Settings),
}

class MainActivity : ComponentActivity() {

    private lateinit var reader: Reader

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val app = application as BlindScanApp
        reader = Reader(this).also { it.rate = app.settings.speechRate }
        val announcer = Announcer(this, reader)
        takeSharedFiles(intent)
        setContent {
            BlindScanTheme {
                CompositionLocalProvider(
                    LocalSettings provides app.settings,
                    LocalReader provides reader,
                    LocalAnnouncer provides announcer,
                ) { AppRoot() }
            }
        }
    }

    /**
     * Volume buttons take the picture while the scan camera is showing. Handling them here,
     * before the views see them, means they work the same with or without TalkBack.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val isVolume = event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        val handler = VolumeKeys.handler
        if (isVolume && handler != null && (application as BlindScanApp).settings.volumeCapture) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) handler()
            return true // swallow both press and release so the volume doesn't change
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        takeSharedFiles(intent)
    }

    /** A picture or PDF shared to BlindScan, or opened with it, from another app. */
    private fun takeSharedFiles(intent: android.content.Intent?) {
        if (intent == null) return
        val uris = ArrayList<android.net.Uri>()
        when (intent.action) {
            android.content.Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                (intent.getParcelableExtra<android.net.Uri>(android.content.Intent.EXTRA_STREAM))?.let { uris.add(it) }
            }
            android.content.Intent.ACTION_SEND_MULTIPLE -> {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra<android.net.Uri>(android.content.Intent.EXTRA_STREAM)?.let { uris.addAll(it) }
            }
            android.content.Intent.ACTION_VIEW -> intent.data?.let { uris.add(it) }
        }
        if (uris.isNotEmpty()) SharedFiles.pending = uris
    }

    override fun onDestroy() {
        reader.shutdown()
        super.onDestroy()
    }
}

@Composable
private fun AppRoot() {
    var tab by rememberSaveable { androidx.compose.runtime.mutableStateOf(Tab.LIVE) }
    val reader = LocalReader.current
    val announcer = LocalAnnouncer.current
    val settings = LocalSettings.current
    val context = LocalContext.current
    val view = LocalView.current
    // Anything the app needs to tell you on opening (for example after an unexpected close).
    androidx.compose.runtime.LaunchedEffect(Unit) {
        val app = context.applicationContext as BlindScanApp
        val notice = app.startupNotice
        if (notice != null) {
            kotlinx.coroutines.delay(1500)
            announcer.say(notice, view, true)
            app.startupNotice = null
        }
    }
    // Files shared to BlindScan: read them as a document on the Scan document tab.
    val shared = SharedFiles.pending
    androidx.compose.runtime.LaunchedEffect(shared) {
        if (shared.isEmpty()) return@LaunchedEffect
        SharedFiles.pending = emptyList()
        reader.clear()
        tab = Tab.SCAN
        com.willwalsh.blindscan.ui.importFiles(context.applicationContext as BlindScanApp, context, shared) {
            announcer.say(it, view, settings.documentVoice == Voice.SCREEN_READER)
        }
    }
    val goHome = { tab = Tab.LIVE }
    // The document questions are about: the one showing, or else the most recent.
    val askDoc = (DocumentStore.open ?: DocumentStore.last)?.takeIf { d -> d.pages.any { it.paragraphs.isNotEmpty() } }
    Scaffold(
        // Ask a question: bottom right on every screen once there's a scanned document.
        floatingActionButton = {
            if (askDoc != null && !com.willwalsh.blindscan.ui.AskState.open) {
                androidx.compose.material3.ExtendedFloatingActionButton(
                    onClick = { reader.stopSpeaking(); com.willwalsh.blindscan.ui.AskState.open = true },
                    icon = { Icon(Icons.Filled.QuestionAnswer, contentDescription = null) },
                    text = { Text("Ask a question", fontSize = 18.sp) },
                )
            }
        },
        bottomBar = {
            NavigationBar {
                for (t in Tab.entries) {
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = {
                            com.willwalsh.blindscan.ui.AskState.open = false
                            if (tab == Tab.SCAN && t == Tab.SCAN) {
                                // Pressing Scan document again starts fresh: the scanned page(s) are cleared.
                                val hadSomething = DocumentStore.open != null || DocumentStore.batch.isNotEmpty() ||
                                    DocumentStore.addingTo != null
                                reader.clear()
                                DocumentStore.clearAll()
                                ScanPhotos.tidy(context)
                                announcer.say(
                                    if (hadSomething) "Scan cleared. Ready for a new scan." else "Ready to scan.",
                                    view, settings.documentVoice == Voice.SCREEN_READER,
                                )
                            }
                            tab = t
                        },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(t.label, fontSize = 14.sp) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (com.willwalsh.blindscan.ui.AskState.open && askDoc != null) {
                com.willwalsh.blindscan.ui.AskScreen(askDoc, reader, announcer, onClose = {
                    reader.stopSpeaking(); com.willwalsh.blindscan.ui.AskState.open = false
                })
            } else when (tab) {
                Tab.LIVE -> CameraPermissionGate { LiveScreen() }
                Tab.SCAN -> if (DocumentStore.open != null) ScanScreen(onUp = goHome) else CameraPermissionGate { ScanScreen(onUp = goHome) }
                Tab.HELP -> HelpScreen(onUp = goHome)
                Tab.SETTINGS -> SettingsScreen(onUp = goHome)
            }
        }
    }
}
