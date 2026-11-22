package com.willwalsh.blindscan.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.willwalsh.blindscan.BlindScanApp
import com.willwalsh.blindscan.LocalSettings
import com.willwalsh.blindscan.Voice
import com.willwalsh.blindscan.camera.CameraHolder
import com.willwalsh.blindscan.ocr.OcrEngine
import com.willwalsh.blindscan.guidance.Guidance
import com.willwalsh.blindscan.guidance.TiltSensor
import com.willwalsh.blindscan.guidance.sharpness
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Composable
fun ScanScreen(onUp: () -> Unit) {
    val doc = DocumentStore.open
    if (doc != null) {
        DocumentScreen(doc, onClose = { DocumentStore.close() })
    } else {
        ScanCamera(onUp)
    }
}

/**
 * Taking a picture by hand, steadily:
 * 1. wait (up to 2.5 s) until the phone has been still for a quarter of a second,
 * 2. check a fresh camera view: if pressing the button knocked the page out of view or tipped
 *    the phone, say how to fix it and wait for you to settle again (up to about 4 s in all),
 * 3. take the picture while watching the gyroscope; if the phone moved during it, take a
 *    second one and keep the sharper of the two.
 */
private suspend fun steadyCapture(
    camera: CameraHolder,
    motion: TiltSensor,
    latest: () -> Guidance.Report?,
    say: (String) -> Unit,
    setCapturing: (Boolean) -> Unit,
): Pair<Bitmap, Bitmap?> {
    suspend fun waitStill(maxMs: Long) {
        if (!motion.canSenseMotion) return
        val start = System.currentTimeMillis()
        while (motion.stillForMs() < 250 && System.currentTimeMillis() - start < maxMs) delay(30)
    }
    waitStill(2500)
    val checkStart = System.currentTimeMillis()
    var lastSaid = ""
    while (System.currentTimeMillis() - checkStart < 4000) {
        val r = latest()
        if (r != null && r.time >= checkStart) {
            val fixable = r.step == Guidance.Step.MOVE || r.step == Guidance.Step.LIFT || r.step == Guidance.Step.TILT
            if (!fixable) break
            if (r.instruction != lastSaid) { say(r.instruction); lastSaid = r.instruction; delay(900) }
        }
        delay(60)
    }
    if (lastSaid.isNotEmpty()) waitStill(1500)

    setCapturing(true)
    try {
        motion.resetPeak()
        val first = camera.capture()
        var best = first
        if (motion.canSenseMotion && motion.peakMotion() >= TiltSensor.RETAKE_LIMIT) {
            motion.resetPeak()
            val second = try { camera.capture() } catch (_: Throwable) { null }
            if (second != null) {
                val a = withContext(Dispatchers.Default) { sharpness(first) }
                val b = withContext(Dispatchers.Default) { sharpness(second) }
                best = if (b > a) { first.recycle(); second } else { second.recycle(); first }
            }
        }
        // Light on: also take one with the light off, in case the light glared on shiny paper.
        var lightOff: Bitmap? = null
        if (camera.torchOn) {
            camera.setTorch(false)
            try {
                delay(600) // let the exposure adjust to the darker scene
                lightOff = camera.capture()
            } catch (_: Throwable) {
            } finally {
                camera.setTorch(true)
            }
        }
        return Pair(best, lightOff)
    } finally {
        setCapturing(false)
    }
}

/** Pages are read one at a time, in the background, so scanning can carry on meanwhile. */
private val readingLock = Mutex()

/**
 * Reads [photo] in the background. [onProgress] gets the paragraphs so far (main thread);
 * [onDone] gets the finished page (main thread). The photo is saved for "Share scan for testing".
 */
private fun readInBackground(
    app: BlindScanApp,
    context: Context,
    photo: Bitmap,
    onProgress: (List<String>) -> Unit,
    onDone: (ScannedPage) -> Unit,
    lightOffPhoto: Bitmap? = null,
) {
    app.appScope.launch {
        val page = readPage(app, context, photo, onProgress, lightOffPhoto)
        withContext(Dispatchers.Main) { onDone(page) }
    }
}

/** Reads [photo] (one page at a time across the app) and returns the finished page. */
internal suspend fun readPage(
    app: BlindScanApp,
    context: Context,
    photo: Bitmap,
    onProgress: (List<String>) -> Unit,
    lightOffPhoto: Bitmap? = null,
): ScannedPage {
        return readingLock.withLock {
            app.markScanStarted()
            val file = ScanPhotos.save(context, photo)
            val altFile = lightOffPhoto?.let { ScanPhotos.save(context, it) }
            fun readOne(b: Bitmap, progress: Boolean) = try {
                app.documentEngine.read(
                    b, limit = 1600, correct = app.settings.wordCorrection,
                    onProgress = if (!progress) null else { paras -> app.appScope.launch(Dispatchers.Main) { onProgress(paras) } },
                )
            } catch (t: Throwable) { null }
            var result: OcrEngine.Result?
            var note = ""
            if (lightOffPhoto == null) {
                result = readOne(photo, progress = true)
            } else {
                // The light was on: glossy paper can reflect it and wash words out. Look for text in
                // the photo taken with the light off too (finding text is quick; reading it isn't),
                // then read just the photo that shows more text, speaking as it goes.
                val a = textFound(app, photo); val b = textFound(app, lightOffPhoto)
                val useOff = b > 1.15f * a
                result = readOne(if (useOff) lightOffPhoto else photo, progress = true)
                note = "Light was on: text found with light ${a.toInt()}, without light ${b.toInt()}; read the photo " +
                    (if (useOff) "without the light." else "with the light.")
                lightOffPhoto.recycle()
            }
            photo.recycle()
            app.markScanFinished()
            val page = ScannedPage(
                paragraphs = result?.paragraphs ?: emptyList(),
                headings = result?.headings ?: emptyList(),
                photo = file,
                altPhoto = altFile,
                reading = false,
                detectMs = result?.detectMs ?: 0,
                recognizeMs = result?.recognizeMs ?: 0,
                lineConfidences = result?.lines?.map { it.confidence } ?: emptyList(),
                lightNote = note,
            )
            page
        }
}

/** How much text the page detector finds in a photo: total length of the lines, in line heights. */
private fun textFound(app: BlindScanApp, b: Bitmap): Float = try {
    app.documentEngine.detect(b, 1600).sumOf { (maxOf(it.w, it.h) / maxOf(1f, minOf(it.w, it.h))).toDouble() }.toFloat()
} catch (_: Throwable) { 0f }

@Composable
private fun ScanCamera(onUp: () -> Unit) {
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
    var capturing by remember { mutableStateOf(false) } // the shutter is open: skip camera frames
    var lightOn by remember { mutableStateOf(false) }
    var userChoseLight by remember { mutableStateOf(false) } // once you press Light, auto light leaves it alone
    val adding = DocumentStore.addingTo
    var status by remember {
        mutableStateOf(
            if (adding != null) "Adding page ${adding.pages.size + 1}. Position the next page, then scan."
            else "Hold the phone flat over the page, about a foot above it."
        )
    }
    var latest by remember { mutableStateOf<Guidance.Report?>(null) }
    val batch = DocumentStore.batch

    fun say(text: String) = announcer.say(text, view, settings.documentVoice == Voice.SCREEN_READER)

    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> if (uris.isNotEmpty()) importFiles(app, context, uris, ::say) }

    /** One page, read and shown straight away (or added to the document being built). */
    fun scanSingle(photo: Bitmap, lightOff: Bitmap?) {
        val doc = DocumentStore.startPage(null)
        val docId = doc.id
        readInBackground(app, context, photo,
            onProgress = { paras -> DocumentStore.updateLastPage(docId) { it.copy(paragraphs = paras) } },
            onDone = { page ->
                val d = DocumentStore.open
                if (page.paragraphs.isEmpty() && d != null && d.id == docId) {
                    if (d.pages.size <= 1) {
                        DocumentStore.open = null; DocumentStore.last = null
                        say("No text found. Try Check view to position the page, then scan again.")
                    } else {
                        val back = d.copy(pages = d.pages.dropLast(1), readFrom = 0, revision = d.revision + 1)
                        DocumentStore.open = null; DocumentStore.last = back; DocumentStore.addingTo = back
                        say("No text found on that page. Position it and scan again.")
                    }
                } else {
                    DocumentStore.updateLastPage(docId) { page.copy(key = it.key) }
                }
                ScanPhotos.tidy(context)
            },
            lightOffPhoto = lightOff,
        )
    }

    /** Batch mode: capture, say the page number, read in the background, stay on the camera. */
    fun scanIntoBatch(photo: Bitmap, lightOff: Bitmap?) {
        val placeholder = ScannedPage(reading = true)
        DocumentStore.batch = DocumentStore.batch + placeholder
        val n = DocumentStore.batch.size
        status = "Page $n captured. Turn to the next page, or press Read pages."
        say("Page $n captured.")
        readInBackground(app, context, photo,
            onProgress = { },
            onDone = { page ->
                DocumentStore.batch = DocumentStore.batch.map { if (it.key == placeholder.key) page.copy(key = it.key) else it }
            },
            lightOffPhoto = lightOff,
        )
    }

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
                val (photo, lightOff) = steadyCapture(camera, tilt, { latest }, ::say, { capturing = it })
                if (settings.vibration) vibrate(context, TICK_BUZZ)
                if (settings.batchMode && DocumentStore.addingTo == null) scanIntoBatch(photo, lightOff) else scanSingle(photo, lightOff)
            } catch (t: Throwable) {
                status = "Couldn't take the picture. Please try again."
                say(status)
            } finally {
                busy = false
            }
        }
    }

    fun finishBatch() {
        if (DocumentStore.batch.isEmpty()) return
        scope.launch {
            if (DocumentStore.batch.any { it.reading }) {
                status = "Finishing the last pages."
                say(status)
                snapshotFlow { DocumentStore.batch.any { it.reading } }.first { !it }
            }
            val pages = DocumentStore.batch.filter { it.paragraphs.isNotEmpty() }
            val skipped = DocumentStore.batch.size - pages.size
            DocumentStore.batch = emptyList()
            if (pages.isEmpty()) {
                status = "No text found on any page."
                say(status)
                return@launch
            }
            if (skipped > 0) say("$skipped ${if (skipped == 1) "page had" else "pages had"} no text and ${if (skipped == 1) "was" else "were"} left out.")
            val d = ScannedDocument(pages)
            DocumentStore.open = d; DocumentStore.last = d
            if (settings.vibration) vibrate(context, READY_BUZZ)
        }
    }

    DisposableEffect(Unit) {
        tilt.start()
        VolumeKeys.handler = { scope.launch(Dispatchers.Main) { scan() } }
        var readySince = 0L
        camera.start(owner) { frame ->
            if (capturing) return@start
            val boxes = app.fastEngine.detect(frame, 640)
            val report = guidance.analyse(frame, boxes, tilt.tiltDegrees, settings.tiltHints)
            scope.launch(Dispatchers.Main) {
                latest = report
                if (busy) return@launch // while scanning, the scan itself decides what to say
                if (settings.autoLight && !userChoseLight && report.dark && camera.hasLight && !camera.torchOn) {
                    camera.setTorch(true); lightOn = true
                    say("It's dim, light on.")
                }
                if (settings.guidance) {
                    talk.next(report)?.let { words ->
                        status = words // the screen shows the last prompt spoken, nothing in between
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
        TopBar("Scan document", onUp = onUp)
        CameraPreview(camera, Modifier.fillMaxWidth().weight(1f))
        Row(
            Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (busy) CircularProgressIndicator()
            // Not a live region: prompts are spoken once, paced, by GuidanceTalker. (A live region made
            // TalkBack read every change the camera saw, several times a second.)
            Text(status, fontSize = 20.sp)
        }
        val addingNow = DocumentStore.addingTo
        BigButton(
            when {
                addingNow != null -> "Scan page ${addingNow.pages.size + 1}"
                settings.batchMode -> "Scan page ${batch.size + 1}"
                else -> "Scan document now"
            },
            onClick = { scan() }, enabled = !busy,
        )
        if (batch.isNotEmpty()) {
            BigButton(
                "Read ${batch.size} ${if (batch.size == 1) "page" else "pages"}",
                onClick = { finishBatch() }, enabled = !busy,
            )
        }
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
        if (DocumentStore.addingTo == null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .toggleable(value = settings.batchMode, role = Role.Switch, onValueChange = {
                        settings.batchMode = it
                        say(if (it) "Batch scanning on. Scan each page, then press Read pages." else "Batch scanning off.")
                    })
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Batch scanning", fontSize = 19.sp, modifier = Modifier.weight(1f))
                Switch(checked = settings.batchMode, onCheckedChange = null)
            }
        }
        val last = DocumentStore.last
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            SmallButton("Import image or PDF", enabled = !busy, onClick = {
                importLauncher.launch(arrayOf("image/*", "application/pdf"))
            })
            if (last != null && batch.isEmpty() && DocumentStore.addingTo == null) {
                SmallButton("Open last document", onClick = { DocumentStore.open = last.copy(readFrom = 0) })
            }
        }
    }
}

/**
 * Decides when to speak a positioning prompt, so there is time to act on each one:
 *  - an instruction must stay the same for about half a second before it is spoken (no flicker),
 *  - after speaking, nothing new is said until the words have finished plus about 1.2 seconds,
 *  - the same instruction is repeated only after 5 seconds, and "Ready" is said once.
 */
private class GuidanceTalker {
    private var candidate = ""
    private var candidateSince = 0L
    private var lastWords = ""
    private var lastSpokenAt = 0L
    private var quietUntil = 0L
    private var saidReady = false

    fun next(r: Guidance.Report): String? {
        val now = System.currentTimeMillis()
        if (!r.ready) saidReady = false
        if (r.instruction != candidate) {
            candidate = r.instruction; candidateSince = now
            return null
        }
        if (now - candidateSince < 400) return null      // not steady yet
        if (now < quietUntil) return null                 // still speaking, or giving you time to move
        if (r.ready && saidReady) return null
        if (!r.ready && r.instruction == lastWords && now - lastSpokenAt < 5000) return null
        if (r.ready) saidReady = true
        lastWords = r.instruction; lastSpokenAt = now
        val wordCount = r.instruction.split(' ').size
        quietUntil = now + wordCount * 220L + 1200L
        return r.instruction
    }
}
