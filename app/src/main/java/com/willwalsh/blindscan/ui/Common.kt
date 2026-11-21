package com.willwalsh.blindscan.ui

import android.content.Context
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
import com.willwalsh.blindscan.ocr.OcrEngine
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

/** The scanned document, kept while you move between tabs. */
data class ScannedDocument(val paragraphs: List<String>, val time: Long = System.currentTimeMillis()) {
    val text: String get() = paragraphs.joinToString("\n\n")
}

object DocumentStore {
    /** The document on screen right now, or null when the camera is showing. */
    var open by mutableStateOf<ScannedDocument?>(null)
    /** The most recent scan, so "Open last document" can bring it back. */
    var last by mutableStateOf<ScannedDocument?>(null)

    fun show(result: OcrEngine.Result) {
        val d = ScannedDocument(result.paragraphs)
        open = d; last = d
    }
    fun close() { open = null }
    fun discard() { open = null; last = null }
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
