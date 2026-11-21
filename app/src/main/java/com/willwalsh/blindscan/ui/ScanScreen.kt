package com.willwalsh.blindscan.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.willwalsh.blindscan.LocalSettings
import com.willwalsh.blindscan.BlindScanApp
import com.willwalsh.blindscan.Voice
import com.willwalsh.blindscan.camera.CameraHolder
import com.willwalsh.blindscan.guidance.Guidance
import com.willwalsh.blindscan.guidance.TiltSensor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ScanScreen() {
    val doc = DocumentStore.open
    if (doc != null) {
        DocumentScreen(doc, onClose = { DocumentStore.close() })
    } else {
        ScanCamera()
    }
}

@Composable
private fun ScanCamera() {
    val context = LocalContext.current
    val app = context.applicationContext as BlindScanApp
    val settings = LocalSettings.current
    val announcer = LocalAnnouncer.current
    val reader = LocalReader.current
    val view = LocalView.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val camera = remember { CameraHolder(context) }
    val tilt = remember { TiltSensor(context) }
    val guidance = remember { Guidance() }
    val talk = remember { GuidanceTalker() }

    var busy by remember { mutableStateOf(false) }
    var lightOn by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Hold the phone flat over the page, about a foot above it.") }
    var latest by remember { mutableStateOf<Guidance.Report?>(null) }

    var userChoseLight by remember { mutableStateOf(false) } // once you press Light, auto light leaves it alone
    fun say(text: String) = announcer.say(text, view, settings.documentVoice == Voice.SCREEN_READER)

    fun scan() {
        if (busy) return
        busy = true
        status = "Scanning. Hold still."
        say("Scanning. Hold still.")
        scope.launch {
            try {
                if (settings.autoLight && !userChoseLight && latest?.dark == true && camera.hasLight && !camera.torchOn) {
                    camera.setTorch(true); lightOn = true
                }
                val photo = camera.capture()
                status = "Reading the document."
                say("Reading.")
                val result = withContext(Dispatchers.Default) { app.documentEngine.read(photo, limit = 1600) }
                photo.recycle()
                if (result.isEmpty) {
                    status = "No text found. Try Check view to position the page, then scan again."
                    say(status)
                } else {
                    if (settings.vibration) vibrate(context, READY_BUZZ)
                    DocumentStore.show(result)
                }
            } catch (t: Throwable) {
                status = "Couldn't take the picture. Please try again."
                say(status)
            } finally {
                busy = false
            }
        }
    }

    DisposableEffect(Unit) {
        tilt.start()
        VolumeKeys.handler = { scope.launch(Dispatchers.Main) { scan() } }
        var readySince = 0L
        camera.start(owner) { frame ->
            if (busy) return@start
            val boxes = app.fastEngine.detect(frame, 640)
            val report = guidance.analyse(frame, boxes, tilt.tiltDegrees, settings.tiltHints)
            scope.launch(Dispatchers.Main) {
                if (busy) return@launch
                latest = report
                status = report.instruction
                if (settings.autoLight && !userChoseLight && report.dark && camera.hasLight && !camera.torchOn) {
                    camera.setTorch(true); lightOn = true
                    say("It's dim, light on.")
                }
                if (settings.guidance) {
                    talk.next(report)?.let { words ->
                        say(words)
                        if (report.ready && settings.vibration) vibrate(context, READY_BUZZ)
                    }
                }
                if (settings.autoScan && report.ready) {
                    if (readySince == 0L) readySince = System.currentTimeMillis()
                    else if (System.currentTimeMillis() - readySince > 1200) { readySince = 0L; scan() }
                } else readySince = 0L
            }
        }
        onDispose {
            VolumeKeys.handler = null
            tilt.stop()
            camera.release()
            reader.stopSpeaking()
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeading("Scan document")
        CameraPreview(camera, Modifier.fillMaxWidth().weight(1f))
        Row(
            Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (busy) CircularProgressIndicator()
            Text(status, fontSize = 20.sp, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        BigButton("Scan document now", onClick = { scan() }, enabled = !busy)
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            SmallButton("Check view", enabled = !busy, onClick = {
                val r = latest
                say(r?.fullReport(camera.torchOn) ?: "The camera is still starting. Try again in a moment.")
            })
            SmallButton(if (lightOn) "Light off" else "Light on", onClick = {
                lightOn = !lightOn; camera.setTorch(lightOn); userChoseLight = true
                say(if (lightOn) "Light on." else "Light off.")
            })
        }
        val last = DocumentStore.last
        if (last != null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                SmallButton("Open last document", onClick = { DocumentStore.open = last })
            }
        }
    }
}

/**
 * Decides when to speak a guidance prompt so it helps without chattering:
 * new instructions at most every 1.5 s, the same one repeated every 4 s, "ready" said once.
 */
private class GuidanceTalker {
    private var lastWords = ""
    private var lastTime = 0L
    private var saidReady = false

    fun next(r: Guidance.Report): String? {
        val now = System.currentTimeMillis()
        if (r.ready) {
            if (saidReady) return null
            saidReady = true; lastWords = r.instruction; lastTime = now
            return r.instruction
        }
        saidReady = false
        val changed = r.instruction != lastWords
        if ((changed && now - lastTime > 1500) || (!changed && now - lastTime > 4000)) {
            lastWords = r.instruction; lastTime = now
            return r.instruction
        }
        return null
    }
}
