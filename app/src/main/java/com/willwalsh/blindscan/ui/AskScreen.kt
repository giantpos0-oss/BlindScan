package com.willwalsh.blindscan.ui

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.willwalsh.blindscan.Voice
import com.willwalsh.blindscan.LocalSettings
import com.willwalsh.blindscan.ai.AskEngine
import com.willwalsh.blindscan.ai.AskStatus
import com.willwalsh.blindscan.ai.OwnModel
import com.willwalsh.blindscan.ai.Turn
import com.willwalsh.blindscan.speech.Announcer
import com.willwalsh.blindscan.speech.Reader
import com.willwalsh.blindscan.speech.StreamChunker
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Whether the Ask a question screen is showing, and whether a scan should open it when done. */
object AskState {
    var open by mutableStateOf(false)
    /** Set by Scan and ask: when the page has been read, open Ask straight away. */
    var afterScan by mutableStateOf(false)
    /** The conversation about the current document (cleared when a new document is scanned). */
    val turns = mutableStateListOf<Turn>()
    var forDocument = -1L
}

/** One question and its answer as shown on screen. */
private class QA(val question: String) {
    var answer by mutableStateOf("")
    var thinking by mutableStateOf(true)
    var failed by mutableStateOf(false)
}

/**
 * Ask a question about the scanned document: type it or say it, and the answer is spoken (with
 * your screen reader or with speech, as set for documents) and shown. Ask follow-up questions as
 * in a conversation. Everything happens on the phone.
 */
@Composable
fun AskScreen(doc: ScannedDocument, reader: Reader, announcer: Announcer, onClose: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val settings = LocalSettings.current
    val scope = rememberCoroutineScope()
    val useScreenReader = settings.documentVoice == Voice.SCREEN_READER
    fun say(t: String) = announcer.say(t, view, useScreenReader)

    BackHandler { onClose() }

    if (AskState.forDocument != doc.id) { AskState.turns.clear(); AskState.forDocument = doc.id }
    val shown = remember(doc.id) { mutableStateListOf<QA>().also { l -> AskState.turns.forEach { t -> l.add(QA(t.question).also { it.answer = t.answer; it.thinking = false }) } } }

    var status by remember { mutableStateOf<AskStatus?>(null) }
    var preparing by remember { mutableStateOf("") }
    var question by remember { mutableStateOf("") }
    var job by remember { mutableStateOf<Job?>(null) }
    val focus = remember { FocusRequester() }
    val listState = rememberLazyListState()

    LaunchedEffect(settings.askEngine) {
        status = AskEngine.status(context, settings.askEngine)
        if (OwnModel.isDownloading(context)) {
            preparing = "Downloading BlindScan's question model."
            if (OwnModel.await(context) { p -> preparing = "Downloading BlindScan's question model: ${(p.fraction * 100).toInt()} percent." })
                status = AskEngine.status(context, settings.askEngine)
            preparing = ""
        }
        if (status == AskStatus.BuiltInReady || status == AskStatus.OwnReady) {
            say("Ask a question about the document.")
            runCatching { focus.requestFocus() }
        }
    }

    fun ask(q: String) {
        val text = q.trim()
        if (text.isEmpty() || job?.isActive == true) return
        val st = status ?: return
        question = ""
        val qa = QA(text)
        shown.add(qa)
        reader.stopSpeaking()
        say("Thinking.")
        val chunker = StreamChunker()
        val ttsStream = !(useScreenReader && announcer.screenReaderOn)
        if (ttsStream) reader.startStream(autoPlay = true)
        job = scope.launch {
            val sb = StringBuilder()
            try {
                AskEngine.answer(context, st, doc.text, AskState.turns.toList(), text).collect { piece ->
                    sb.append(piece)
                    qa.thinking = false
                    qa.answer = AskEngine.clean(sb.toString())
                    val ready = chunker.take(qa.answer, done = false)
                    if (ready.isNotEmpty()) {
                        if (ttsStream) reader.appendStream(ready) else say(ready.joinToString(" "))
                    }
                }
                qa.thinking = false
                qa.answer = AskEngine.clean(sb.toString()).ifBlank { "No answer came back. Try asking another way." }
                val rest = chunker.take(qa.answer, done = true)
                if (ttsStream) { reader.appendStream(rest); reader.endStream() } else if (rest.isNotEmpty()) say(rest.joinToString(" "))
                AskState.turns.add(Turn(text, qa.answer))
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                qa.thinking = false; qa.failed = true
                qa.answer = "Sorry, the AI couldn't answer that. " + when {
                    t.message?.contains("BUSY", true) == true -> "It's busy; try again in a moment."
                    t.message?.contains("BACKGROUND", true) == true -> "Keep BlindScan open while it answers."
                    else -> "Please try again."
                }
                if (ttsStream) reader.endStream()
                say(qa.answer)
            }
        }
    }

    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val heard = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (r.resultCode == Activity.RESULT_OK && !heard.isNullOrBlank()) ask(heard)
    }
    fun speak() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Ask about the document")
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        try { speech.launch(i) } catch (_: Throwable) { say("Speech input isn't available on this phone. Type your question instead.") }
    }

    LaunchedEffect(shown.size) { if (shown.isNotEmpty()) listState.animateScrollToItem(shown.size - 1) }

    Column(Modifier.fillMaxSize().imePadding()) {
        TopBar("Ask a question", onUp = onClose)
        val st = status
        when {
            st == null -> Row(Modifier.padding(16.dp)) { CircularProgressIndicator(); Text("  Getting the AI ready.", fontSize = 20.sp) }
            preparing.isNotEmpty() -> Text(preparing, fontSize = 20.sp, modifier = Modifier.padding(16.dp))
            st == AskStatus.BuiltInDownloadable || st == AskStatus.BuiltInDownloading -> {
                Text(
                    "This phone has Google's built-in AI, Gemini Nano, but Android hasn't fetched it yet. " +
                        "It's free and stays on the phone. Android downloads it, not BlindScan.",
                    fontSize = 20.sp, modifier = Modifier.padding(16.dp),
                )
                BigButton("Get the built-in AI", enabled = st == AskStatus.BuiltInDownloadable, onClick = {
                    status = AskStatus.BuiltInDownloading
                    say("Getting the built-in AI. This can take a few minutes.")
                    scope.launch {
                        val ok = AskEngine.downloadBuiltIn { }
                        status = AskEngine.status(context, settings.askEngine)
                        say(if (ok) "The built-in AI is ready. Ask your question." else "Couldn't get the built-in AI. Check the internet connection and try again.")
                    }
                })
            }
            st == AskStatus.OwnDownloadable -> {
                Text(
                    "To answer questions, BlindScan needs its own AI model on this phone: about 1.6 gigabytes, " +
                        "downloaded once. After that it works without internet and nothing you scan leaves the phone." +
                        if (settings.askWifiOnly) " It downloads over Wi-Fi only (you can change that in Settings, Ask a question)." else "",
                    fontSize = 20.sp, modifier = Modifier.padding(16.dp),
                )
                BigButton("Download the question model", onClick = {
                    OwnModel.start(context, settings.askWifiOnly)
                    say("Downloading. You can leave BlindScan; the download carries on.")
                    scope.launch {
                        var lastSaid = -1
                        val ok = OwnModel.await(context) { p ->
                            val pct = (p.fraction * 100).toInt()
                            preparing = if (p.waitingForWifi) "Waiting for Wi-Fi to download the question model."
                            else "Downloading the question model: $pct percent."
                            if (pct / 10 != lastSaid && pct > 0) { lastSaid = pct / 10; say("$pct percent.") }
                        }
                        preparing = ""
                        status = AskEngine.status(context, settings.askEngine)
                        say(if (ok) "The question model is ready. Ask your question." else "The download didn't finish. Try again.")
                    }
                })
            }
            else -> {
                LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), state = listState) {
                    itemsIndexed(shown) { _, qa ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            Text("You asked: ${qa.question}", fontSize = 20.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.semantics { heading() })
                            if (qa.thinking) Text("Thinking…", fontSize = 20.sp)
                            else Text(qa.answer, fontSize = 20.sp)
                        }
                    }
                }
                OutlinedTextField(
                    value = question, onValueChange = { question = it },
                    label = { Text("Your question", fontSize = 18.sp) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).focusRequester(focus),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 20.sp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { ask(question) }),
                )
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    SmallButton("Ask", enabled = question.isNotBlank() && job?.isActive != true, onClick = { ask(question) })
                    SmallButton("Say your question", enabled = job?.isActive != true, onClick = { speak() })
                    if (job?.isActive == true) SmallButton("Stop", onClick = { job?.cancel(); reader.stopSpeaking(); say("Stopped.") })
                }
                Text(
                    if (st == AskStatus.BuiltInReady) "Answers by the phone's built-in AI. AI can make mistakes; check anything important."
                    else "Answers by BlindScan's own AI. AI can make mistakes; check anything important.",
                    fontSize = 16.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }
    }
}
