package com.willwalsh.blindscan.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import com.willwalsh.blindscan.guidance.meanBrightness
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Live mode: point the camera at anything and new text is read out on the spot.
 * Using the DVR controls pauses live reading so you can go back over what was read.
 */
@Composable
fun LiveScreen() {
    val context = LocalContext.current
    val app = context.applicationContext as BlindScanApp
    val settings = LocalSettings.current
    val reader = LocalReader.current
    val announcer = LocalAnnouncer.current
    val view = LocalView.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val camera = remember { CameraHolder(context) }
    val tracker = remember { LiveTextTracker() }
    var liveOn by remember { mutableStateOf(true) }
    var lightOn by remember { mutableStateOf(false) }
    var userChoseLight by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf("Point the camera at some text. New text is read out as soon as it's steady.") }
    val readerState by reader.state.collectAsState()

    DisposableEffect(Unit) {
        var darkFrames = 0
        camera.start(owner) { frame ->
            if (!tracker.active) return@start
            // Auto light: turn the torch on after a few dark frames.
            if (settings.autoLight && !userChoseLight && !camera.torchOn && camera.hasLight) {
                darkFrames = if (meanBrightness(frame) < 60f) darkFrames + 1 else 0
                if (darkFrames >= 3) scope.launch(Dispatchers.Main) {
                    camera.setTorch(true); lightOn = true
                    announcer.say("Light on.", view, settings.liveVoice == Voice.SCREEN_READER)
                }
            }
            val engine = if (settings.liveBestAccuracy) app.documentEngine else app.fastEngine
            val result = engine.read(frame, limit = 960, orient = true, adaptive = false, retryBlurry = false, secondCrop = false)
            val newText = tracker.offer(result.text) ?: return@start
            scope.launch(Dispatchers.Main) {
                if (!tracker.active) return@launch
                shown = newText
                if (settings.liveVoice == Voice.TEXT_TO_SPEECH) {
                    reader.rate = settings.speechRate
                    reader.load(newText, autoPlay = true)
                }
                // Screen reader mode: the text below is a live region, so it is read automatically.
            }
        }
        onDispose {
            camera.release()
            reader.clear()
        }
    }

    fun setLive(on: Boolean) {
        liveOn = on
        tracker.active = on
        if (on) tracker.reset()
        announcer.say(
            if (on) "Live reading on." else "Live reading paused.",
            view, settings.liveVoice == Voice.SCREEN_READER,
        )
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeading("Live mode")
        CameraPreview(camera, Modifier.fillMaxWidth().weight(1f))
        Text(
            shown,
            fontSize = 20.sp,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 80.dp, max = 220.dp)
                .verticalScroll(rememberScrollState())
                .padding(12.dp)
                // Screen reader mode: a live region, so new text is read automatically.
                // Text to speech mode: not a live region, or TalkBack would read it over the voice.
                .semantics { if (settings.liveVoice == Voice.SCREEN_READER) liveRegion = LiveRegionMode.Polite },
        )
        if (settings.liveVoice == Voice.TEXT_TO_SPEECH) {
            if (readerState.hasText) {
                Text(readerState.position, fontSize = 16.sp, modifier = Modifier.padding(horizontal = 16.dp))
            }
            DvrControls(reader, readerState.playing, enabled = readerState.hasText, onUserControl = {
                if (liveOn) { liveOn = false; tracker.active = false }
            })
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            SmallButton(if (liveOn) "Pause live reading" else "Resume live reading", onClick = { setLive(!liveOn) })
            SmallButton(
                if (lightOn) "Light off" else "Light on",
                onClick = {
                    lightOn = !lightOn; camera.setTorch(lightOn); userChoseLight = true
                    announcer.say(if (lightOn) "Light on." else "Light off.", view, settings.liveVoice == Voice.SCREEN_READER)
                },
            )
        }
    }
}

/**
 * Decides when text in live mode is "new": it must look the same in two frames in a row
 * (so it's steady), and be clearly different from what was last read out.
 */
class LiveTextTracker {
    @Volatile var active = true
    private var lastFrame: Set<String> = emptySet()
    private var lastSpoken: Set<String> = emptySet()

    fun reset() { lastFrame = emptySet(); lastSpoken = emptySet() }

    fun offer(text: String): String? {
        val words = words(text)
        if (words.isEmpty()) { lastFrame = emptySet(); return null }
        val steady = similarity(words, lastFrame) >= 0.7f
        lastFrame = words
        if (!steady || similarity(words, lastSpoken) >= 0.6f) return null
        lastSpoken = words
        return text
    }

    private fun words(t: String) =
        t.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }.toSet()

    private fun similarity(a: Set<String>, b: Set<String>): Float {
        if (a.isEmpty() || b.isEmpty()) return 0f
        return (a intersect b).size.toFloat() / (a union b).size
    }
}
