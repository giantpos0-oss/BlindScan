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
import com.willwalsh.blindscan.ai.Turn
import com.willwalsh.blindscan.speech.Announcer
import com.willwalsh.blindscan.speech.Reader
import kotlinx.coroutines.launch

/** Whether the Ask a question screen is showing, and whether a scan should open it when done. */
object AskState {
    var open by mutableStateOf(false)
    /** Set by Scan and ask: when the page has been read, open Ask straight away. */
    var afterScan by mutableStateOf(false)
    /** The conversation about the current document (cleared when a new document is scanned). */
    val turns = mutableStateListOf<Turn>()
    var forDocument = -1L
    /** Documents not to start reading aloud again when you come back from asking about them. */
    val quietDocs = HashSet<Long>()

    fun openFor(docId: Long?) { if (docId != null) quietDocs.add(docId); open = true }
}

/** One question and its answer as shown on screen. */
private class QA(val question: String) {
    var answer by mutableStateOf("")
    var quote by mutableStateOf("")
    var photoNote by mutableStateOf("")
    var thinking by mutableStateOf(true)
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
    var question by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val listState = rememberLazyListState()

    LaunchedEffect(settings.askEngine) {
        status = AskEngine.status(settings)
        if (status == AskStatus.BuiltInReady || status == AskStatus.OnlineReady) {
            say("Ask a question about the document.")
            runCatching { focus.requestFocus() }
        }
    }

    // Pages still being read (a batch, or a page just scanned): wait, then say it's ready.
    val stillReading = doc.pages.any { it.reading }
    LaunchedEffect(stillReading) {
        if (stillReading) say("Still reading the pages. You can ask as soon as they're done.")
    }

    // Questions wait their turn: ask the next one at any time, even while an answer is coming,
    // and they're answered one after another, each knowing the answers before it.
    val queue = remember { kotlinx.coroutines.channels.Channel<Pair<QA, Boolean>>(kotlinx.coroutines.channels.Channel.UNLIMITED) }
    var pending by remember { mutableStateOf(0) }
    var listenNext by remember { mutableStateOf(false) }

    suspend fun answerOne(qa: QA, spokenQuestion: Boolean) {
        val st = status ?: return
        try {
            val a = AskEngine.answer(context, st, doc.text, AskState.turns.toList(), qa.question, doc.pages.mapNotNull { it.photo })
            qa.answer = a.text
            qa.quote = a.quote
            qa.photoNote = a.photoNote
            qa.thinking = false
            // The answer, then the document's own words that back it up, then any misread the
            // photo shows.
            val spoken = StringBuilder(a.text)
            if (a.quote.isNotEmpty()) spoken.append(if (a.related) " It's about where the document says: " else " The document says: ").append(a.quote)
            if (a.photoNote.isNotEmpty()) spoken.append(" Please check: ").append(a.photoNote)
            say(spoken.toString())
            if (!a.notFound) AskState.turns.add(Turn(qa.question, a.text))
            // Spoken question: once the answer has had time to be heard, listen for the next one.
            if (spokenQuestion && settings.askListenAgain && pending <= 1) {
                val words = spoken.split(Regex("\\s+")).size
                kotlinx.coroutines.delay((words / (2.6f * settings.speechRate.coerceAtLeast(0.5f)) * 1000).toLong() + 900)
                listenNext = true
            } else if (pending <= 1) runCatching { focus.requestFocus() }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            qa.thinking = false
            qa.answer = "Sorry, I couldn't get an answer. " + when {
                t is AskEngine.ServiceError && t.code == 503 -> "The online AI isn't available right now. Try again later."
                t is AskEngine.ServiceError && (t.code == 403 || t.code == 400) -> "BlindScan may need an update. Check for a new version."
                t is AskEngine.ServiceError -> "The online AI had a problem. Please try again."
                t is java.io.IOException -> "There's no internet connection. Connect to Wi-Fi or mobile data and try again."
                t.message?.contains("BUSY", true) == true -> "The AI is busy; try again in a moment."
                t.message?.contains("BACKGROUND", true) == true -> "Keep BlindScan open while it answers."
                else -> "Please try again."
            }
            say(qa.answer)
        }
    }

    LaunchedEffect(Unit) {
        for ((qa, spoken) in queue) {
            answerOne(qa, spoken)
            pending = (pending - 1).coerceAtLeast(0)
        }
    }

    fun ask(q: String, spoken: Boolean = false) {
        val text = q.trim()
        if (text.isEmpty() || stillReading || status == null) return
        question = ""
        val qa = QA(text)
        shown.add(qa)
        pending++
        if (pending == 1) { reader.stopSpeaking(); say("Looking.") } else say("Next question saved.")
        queue.trySend(qa to spoken)
    }

    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val heard = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (r.resultCode == Activity.RESULT_OK && !heard.isNullOrBlank()) ask(heard, spoken = true)
    }
    fun speak() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Ask about the document")
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        try { speech.launch(i) } catch (_: Throwable) { say("Speech input isn't available on this phone. Type your question instead.") }
    }

    LaunchedEffect(listenNext) { if (listenNext) { listenNext = false; speak() } }

    LaunchedEffect(shown.size) { if (shown.isNotEmpty()) listState.animateScrollToItem(shown.size - 1) }

    Column(Modifier.fillMaxSize().imePadding()) {
        TopBar("Ask a question", onUp = onClose)
        val st = status
        when {
            st == null -> Row(Modifier.padding(16.dp)) { CircularProgressIndicator(); Text("  Getting the AI ready.", fontSize = 20.sp) }
            st == AskStatus.BuiltInDownloadable || st == AskStatus.BuiltInDownloading -> {
                Text(
                    "This phone has Google's built-in AI, Gemini Nano, but Android hasn't fetched it yet. " +
                        "It's free and private: questions and documents stay on the phone. Android downloads it, not BlindScan.",
                    fontSize = 20.sp, modifier = Modifier.padding(16.dp),
                )
                BigButton(if (st == AskStatus.BuiltInDownloading) "Getting the built-in AI…" else "Get the built-in AI",
                    enabled = st == AskStatus.BuiltInDownloadable, onClick = {
                    status = AskStatus.BuiltInDownloading
                    say("Getting the built-in AI. This can take a few minutes.")
                    scope.launch {
                        val ok = AskEngine.downloadBuiltIn()
                        status = AskEngine.status(settings)
                        say(if (ok) "The built-in AI is ready. Ask your question." else "Couldn't get the built-in AI. Check the internet connection and try again.")
                    }
                })
            }
            else -> {
                if (stillReading) {
                    val done = doc.pages.count { !it.reading }
                    Text("Still reading: page ${done + 1} of ${doc.pages.size}. You can ask as soon as it's done.",
                        fontSize = 20.sp, modifier = Modifier.padding(16.dp))
                }
                LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), state = listState) {
                    itemsIndexed(shown) { _, qa ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            Text("You asked: ${qa.question}", fontSize = 20.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.semantics { heading() })
                            if (qa.thinking) Text("Looking…", fontSize = 20.sp)
                            else {
                                Text(qa.answer, fontSize = 20.sp)
                                if (qa.quote.isNotEmpty()) Text("The document says: “${qa.quote}”", fontSize = 18.sp,
                                    modifier = Modifier.padding(top = 4.dp))
                                if (qa.photoNote.isNotEmpty()) Text("Please check: ${qa.photoNote}", fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                            }
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
                    SmallButton("Ask", enabled = question.isNotBlank() && !stillReading, onClick = { ask(question) })
                    SmallButton("Say your question", enabled = !stillReading, onClick = { speak() })
                    if (pending > 0) SmallButton("Stop", onClick = { reader.stopSpeaking(); say("Stopped.") })
                }
                Text(
                    (if (st == AskStatus.OnlineReady) "Answers by BlindScan's online AI. "
                    else "Answers by the phone's built-in AI, on the phone. ") +
                        "Each answer is checked against the page; anything that can't be found there isn't given.",
                    fontSize = 16.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }
    }
}
