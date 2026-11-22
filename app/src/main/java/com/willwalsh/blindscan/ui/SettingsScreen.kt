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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.activity.compose.BackHandler
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

private enum class SettingsPage(val title: String) {
    DOCUMENTS("Reading scanned documents"),
    LIVE("Live mode"),
    SPEECH("Speech"),
    ACCURACY("Accuracy and speed"),
    CAMERA("Camera and positioning"),
    ASK("Ask a question"),
    PROBLEMS("Problems"),
}

/** Settings, grouped: a list of categories, each opening its own screen. */
@Composable
fun SettingsScreen(onUp: () -> Unit) {
    val s = LocalSettings.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val crash = (context.applicationContext as com.willwalsh.blindscan.BlindScanApp).crashFile
    var page by rememberSaveable { mutableStateOf<SettingsPage?>(null) }
    val open = page
    if (open == null) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            TopBar("Settings", onUp = onUp)
            for (p in SettingsPage.entries) {
                if (p == SettingsPage.PROBLEMS && !crash.exists()) continue
                BigButton(p.title, onClick = { page = p })
            }
        }
        return
    }
    BackHandler { page = null }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        TopBar(open.title, onUp = { page = null })
        when (open) {
            SettingsPage.DOCUMENTS -> {
                Section("Read scanned documents with")
                VoiceChoice(s.documentVoice) { s.documentVoice = it }
            }
            SettingsPage.LIVE -> {
                Section("Read live mode with")
                VoiceChoice(s.liveVoice) { s.liveVoice = it }
                Toggle("Best accuracy in live mode (slower)", s.liveBestAccuracy) { s.liveBestAccuracy = it }
            }
            SettingsPage.SPEECH -> {
                val rateText = String.format(Locale.getDefault(), "%.1f times normal speed", s.speechRate)
                Text("Speech rate for Android text to speech: $rateText", fontSize = 19.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
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
            }
            SettingsPage.ACCURACY -> {
                Toggle("Correct unclear words using an English dictionary (numbers are never changed)", s.wordCorrection) { s.wordCorrection = it }
                Toggle("Experimental fast math engine (XNNPACK). May be faster; turns itself off if a scan fails", s.fastMath) { s.fastMath = it }
                Toggle("Batch scanning: scan several pages, then read them together", s.batchMode) { s.batchMode = it }
            }
            SettingsPage.CAMERA -> {
                Toggle("Spoken positioning prompts", s.guidance) { s.guidance = it }
                Toggle("Vibrate when ready to scan", s.vibration) { s.vibration = it }
                Toggle("Volume buttons take the picture", s.volumeCapture) { s.volumeCapture = it }
                Toggle("Turn the light on automatically in dim places", s.autoLight) { s.autoLight = it }
                Toggle("Tilt prompts (for pages lying flat)", s.tiltHints) { s.tiltHints = it }
                Toggle("Scan automatically when ready", s.autoScan) { s.autoScan = it }
            }
            SettingsPage.ASK -> {
                Section("Answers come from")
                Column(Modifier.selectableGroup()) {
                    for (c in com.willwalsh.blindscan.AskEngineChoice.entries) {
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 56.dp)
                                .selectable(selected = s.askEngine == c, role = Role.RadioButton, onClick = { s.askEngine = c })
                                .padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = s.askEngine == c, onClick = null)
                            Text(c.label, fontSize = 19.sp, modifier = Modifier.padding(start = 12.dp))
                        }
                    }
                }
                Section("Cloud AI (optional)")
                Text(
                    "Off until you turn it on. When it's used, the document's text and your question are sent to " +
                        "the company you choose, using your own key; you pay them for what you use. Each answer is " +
                        "still checked against the page.",
                    fontSize = 18.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                Toggle("Use a cloud AI", s.cloudOn) { s.cloudOn = it }
                if (s.cloudOn) {
                    Column(Modifier.selectableGroup()) {
                        for (p in com.willwalsh.blindscan.CloudProvider.entries) {
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 56.dp)
                                    .selectable(selected = s.cloudProvider == p, role = Role.RadioButton, onClick = { s.cloudProvider = p })
                                    .padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = s.cloudProvider == p, onClick = null)
                                Text(p.label, fontSize = 19.sp, modifier = Modifier.padding(start = 12.dp))
                            }
                        }
                    }
                    var key by androidx.compose.runtime.remember { mutableStateOf(s.cloudKey) }
                    androidx.compose.material3.OutlinedTextField(
                        value = key, onValueChange = { key = it },
                        label = { Text("Your ${s.cloudProvider.label} API key", fontSize = 18.sp) },
                        singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    BigButton(if (s.cloudKey.isBlank()) "Save key" else "Replace key", enabled = key.isNotBlank(), onClick = {
                        s.cloudKey = key
                    })
                    if (s.cloudKey.isNotBlank()) BigButton("Remove key", onClick = { s.cloudKey = ""; key = "" })
                    Text(
                        if (s.cloudKey.isBlank()) "No key saved yet." else "A key is saved on this phone only.",
                        fontSize = 18.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
            SettingsPage.PROBLEMS -> {
                BigButton("Share crash report", onClick = {
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_SUBJECT, "BlindScan crash report")
                        putExtra(android.content.Intent.EXTRA_TEXT, runCatching { crash.readText() }.getOrDefault("(could not read the report)"))
                    }
                    context.startActivity(android.content.Intent.createChooser(send, "Share crash report"))
                })
            }
        }
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
