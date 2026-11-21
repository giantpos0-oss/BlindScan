package com.willwalsh.blindscan.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.willwalsh.blindscan.LocalSettings
import com.willwalsh.blindscan.Voice
import java.util.Locale

@Composable
fun SettingsScreen() {
    val s = LocalSettings.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        ScreenHeading("Settings")

        Section("Reading scanned documents")
        VoiceChoice(s.documentVoice) { s.documentVoice = it }

        Section("Reading in live mode")
        VoiceChoice(s.liveVoice) { s.liveVoice = it }
        Toggle("Best accuracy in live mode (slower)", s.liveBestAccuracy) { s.liveBestAccuracy = it }

        Section("Accuracy and speed")
        Toggle("Correct unclear words using an English dictionary (numbers are never changed)", s.wordCorrection) { s.wordCorrection = it }
        Toggle("Experimental fast math engine (XNNPACK). May be faster; turns itself off if a scan fails", s.fastMath) { s.fastMath = it }
        Toggle("Batch scanning: scan several pages, then read them together", s.batchMode) { s.batchMode = it }

        Section("Speech")
        val rateText = String.format(Locale.getDefault(), "%.1f times normal speed", s.speechRate)
        Text("Speech rate for Android text to speech: $rateText", fontSize = 19.sp, modifier = Modifier.padding(horizontal = 16.dp))
        Slider(
            value = s.speechRate,
            onValueChange = { s.speechRate = (Math.round(it * 10) / 10f) },
            valueRange = 0.5f..3.0f,
            steps = 24,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .semantics { contentDescription = "Speech rate"; stateDescription = rateText },
        )

        val context = androidx.compose.ui.platform.LocalContext.current
        val crash = (context.applicationContext as com.willwalsh.blindscan.BlindScanApp).crashFile
        if (crash.exists()) {
            Section("Problems")
            BigButton("Share crash report", onClick = {
                val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(android.content.Intent.EXTRA_SUBJECT, "BlindScan crash report")
                    putExtra(android.content.Intent.EXTRA_TEXT, runCatching { crash.readText() }.getOrDefault("(could not read the report)"))
                }
                context.startActivity(android.content.Intent.createChooser(send, "Share crash report"))
            })
        }

        Section("Camera and positioning")
        Toggle("Spoken positioning prompts", s.guidance) { s.guidance = it }
        Toggle("Vibrate when ready to scan", s.vibration) { s.vibration = it }
        Toggle("Volume buttons take the picture", s.volumeCapture) { s.volumeCapture = it }
        Toggle("Turn the light on automatically in dim places", s.autoLight) { s.autoLight = it }
        Toggle("Tilt prompts (for pages lying flat)", s.tiltHints) { s.tiltHints = it }
        Toggle("Scan automatically when ready", s.autoScan) { s.autoScan = it }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title, fontSize = 21.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp).semantics { heading() },
    )
}

@Composable
private fun VoiceChoice(current: Voice, onChange: (Voice) -> Unit) {
    Column(Modifier.selectableGroup()) {
        for ((v, label) in listOf(Voice.SCREEN_READER to "My screen reader", Voice.TEXT_TO_SPEECH to "Android text to speech, with DVR controls")) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .selectable(selected = current == v, role = Role.RadioButton, onClick = { onChange(v) })
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = current == v, onClick = null)
                Text(label, fontSize = 19.sp, modifier = Modifier.padding(start = 12.dp))
            }
        }
    }
}

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = value, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 19.sp, modifier = Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = null)
    }
}
