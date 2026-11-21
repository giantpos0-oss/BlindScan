package com.willwalsh.blindscan.speech

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.View
import android.view.accessibility.AccessibilityManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Android text-to-speech with DVR-style controls (rewind / play-pause / fast forward),
 * moving one sentence at a time, like Scribe.
 */
class Reader(context: Context) {

    data class State(
        val chunks: List<String> = emptyList(),
        val index: Int = 0,
        val playing: Boolean = false,
        /** More text is still arriving (the page is still being read). */
        val streaming: Boolean = false,
    ) {
        val hasText get() = chunks.isNotEmpty()
        val waiting get() = streaming && index >= chunks.size
        val position get() = when {
            chunks.isEmpty() && streaming -> "Reading the page."
            chunks.isEmpty() -> ""
            index >= chunks.size -> "Reading more of the page."
            streaming -> "Sentence ${index + 1}, more coming"
            else -> "Sentence ${index + 1} of ${chunks.size}"
        }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var ready = false
    private var pendingPlay = false
    var rate: Float = 1f
        set(v) { field = v; tts.setSpeechRate(v) }

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status -> onEngineReady(status) }

    private fun onEngineReady(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            tts.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            tts.setSpeechRate(rate)
            if (pendingPlay) { pendingPlay = false; speakCurrent() }
        }
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                val id = utteranceId ?: return
                if (!id.startsWith("chunk-")) return
                val s = _state.value
                if (!s.playing || id != "chunk-${s.index}") return
                if (s.index + 1 < s.chunks.size) {
                    _state.update { it.copy(index = it.index + 1) }
                    speakCurrent()
                } else if (s.streaming) {
                    // Caught up with the reading: wait here; appendStream() carries on.
                    _state.update { it.copy(index = it.chunks.size) }
                } else {
                    _state.update { it.copy(playing = false) }
                }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {}
        })
    }

    /** Loads new text, split into sentences. */
    fun load(text: String, autoPlay: Boolean) {
        tts.stop()
        _state.value = State(chunks = Chunker.sentences(text), index = 0, playing = false)
        if (autoPlay) play()
    }

    /** Starts an empty reading that text will be added to as the page is read. */
    fun startStream(autoPlay: Boolean) {
        tts.stop()
        _state.value = State(chunks = emptyList(), index = 0, playing = autoPlay, streaming = true)
    }

    /** Adds finished sentences. If speech was waiting for them, it carries on straight away. */
    fun appendStream(more: List<String>) {
        if (more.isEmpty()) return
        val before = _state.value
        _state.update { it.copy(chunks = it.chunks + more) }
        if (before.playing && before.index >= before.chunks.size) {
            _state.update { it.copy(index = before.chunks.size) }
            if (ready) speakCurrent() else pendingPlay = true
        }
    }

    /** No more text is coming. */
    fun endStream() {
        val s = _state.value
        _state.update {
            if (s.index >= s.chunks.size) it.copy(streaming = false, playing = false, index = (s.chunks.size - 1).coerceAtLeast(0))
            else it.copy(streaming = false)
        }
    }

    fun play() {
        if (!_state.value.hasText) return
        if (_state.value.index >= _state.value.chunks.size) _state.update { it.copy(index = 0) }
        _state.update { it.copy(playing = true) }
        if (ready) speakCurrent() else pendingPlay = true
    }

    fun pause() {
        _state.update { it.copy(playing = false) }
        tts.stop()
    }

    fun togglePlay() = if (_state.value.playing) pause() else play()

    /** Back one sentence / forward one sentence / back to the start. Each starts speaking right away. */
    fun rewind() = jump(-1)
    fun fastForward() = jump(+1)
    fun restart() = jump(-_state.value.index)

    private fun jump(delta: Int) {
        val s = _state.value
        if (!s.hasText) return
        val i = (s.index.coerceAtMost(s.chunks.size - 1) + delta).coerceIn(0, s.chunks.size - 1)
        _state.update { it.copy(index = i, playing = true) }
        if (ready) speakCurrent() else pendingPlay = true
    }

    fun clear() {
        tts.stop()
        _state.value = State()
    }

    private fun speakCurrent() {
        val s = _state.value
        if (s.index !in s.chunks.indices) return
        tts.speak(s.chunks[s.index], TextToSpeech.QUEUE_FLUSH, Bundle(), "chunk-${s.index}")
    }

    /** Short one-off message (guidance prompts) that interrupts whatever is playing. */
    fun sayNow(text: String) {
        if (!ready) return
        _state.update { it.copy(playing = false) }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), "msg-${System.nanoTime()}")
    }

    fun stopSpeaking() = tts.stop()

    fun shutdown() {
        tts.stop(); tts.shutdown()
    }
}

/** Splits text into sentence-sized pieces for DVR-style navigation. */
object Chunker {
    private val sentenceEnd = Regex("(?<=[.!?])\\s+(?=[\"'(\\[]?[A-Z0-9])")

    fun sentences(text: String): List<String> {
        val out = ArrayList<String>()
        for (para in text.split(Regex("\n\\s*\n"))) {
            val lines = para.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) continue
            // Rejoin lines of a paragraph; mend words hyphenated across a line break.
            val sb = StringBuilder()
            for (l in lines) {
                if (sb.isEmpty()) sb.append(l)
                else if (sb.endsWith("-") && l.first().isLowerCase()) { sb.setLength(sb.length - 1); sb.append(l) }
                else {
                    // Lines that don't end a sentence and look like separate items (receipts, labels)
                    // stay separate pieces so they can be stepped through one by one.
                    val prevEnds = sb.last() in ".!?:;"
                    val prevComma = sb.last() == ','
                    val startsUpper = l.first().isUpperCase()
                    // "Dear Mr. Walsh," / "Sincerely," / "Milk 3.49": short lines followed by a capital stand alone.
                    if (startsUpper && ((!prevEnds && !prevComma && sb.length < 60) || (prevComma && sb.length < 40))) {
                        out.addAll(splitSentences(sb.toString())); sb.setLength(0); sb.append(l)
                    } else sb.append(' ').append(l)
                }
            }
            out.addAll(splitSentences(sb.toString()))
        }
        return out.filter { it.isNotBlank() }
    }

    private val abbreviations = setOf(
        "mr", "mrs", "ms", "dr", "st", "jr", "sr", "prof", "vs", "etc", "inc", "ltd", "co", "no",
        "ave", "rd", "blvd", "mt", "ft", "dept", "approx", "e.g", "i.e", "u.s", "a.m", "p.m", "jan", "feb",
        "mar", "apr", "aug", "sept", "sep", "oct", "nov", "dec", "fig", "vol", "pg", "tel", "ext",
    )

    private fun splitSentences(s: String): List<String> {
        val pieces = s.split(sentenceEnd).map { it.trim() }.filter { it.isNotEmpty() }
        // Re-join pieces that were split after an abbreviation such as "Mr." or "Dr.".
        val out = ArrayList<String>()
        for (p in pieces) {
            val prev = out.lastOrNull()
            val lastWord = prev?.substringAfterLast(' ')?.trimEnd('.')?.lowercase()
            if (prev != null && lastWord != null && (lastWord in abbreviations || lastWord.length == 1)) {
                out[out.size - 1] = "$prev $p"
            } else out.add(p)
        }
        return out
    }
}

/**
 * Feeds a page that is still being read to the Reader: each call returns only the sentences
 * that are finished and haven't been handed out yet. The last sentence is held back until
 * more text arrives (it may not be complete), and released when [done] is true.
 */
class StreamChunker {
    private var given = 0
    fun take(textSoFar: String, done: Boolean): List<String> {
        val all = Chunker.sentences(textSoFar)
        val ready = if (done) all else all.dropLast(1)
        if (ready.size <= given) return emptyList()
        val out = ready.subList(given, ready.size).toList()
        given = ready.size
        return out
    }
}

/** Speaks short messages through the screen reader if one is running, otherwise through the Reader. */
class Announcer(private val context: Context, private val reader: Reader) {
    private val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager

    val screenReaderOn: Boolean get() = am.isEnabled && am.isTouchExplorationEnabled

    fun say(text: String, view: View?, preferScreenReader: Boolean) {
        if (preferScreenReader && screenReaderOn && view != null) {
            @Suppress("DEPRECATION")
            view.announceForAccessibility(text)
        } else {
            reader.sayNow(text)
        }
    }
}
