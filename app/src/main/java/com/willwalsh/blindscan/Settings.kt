package com.willwalsh.blindscan

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.core.content.edit

enum class Voice { SCREEN_READER, TEXT_TO_SPEECH }

/** Which AI answers questions about a document. */
enum class AskEngineChoice(val label: String) {
    AUTOMATIC("Automatic: the phone's built-in AI when it's ready, otherwise BlindScan's online AI"),
    ONLINE("BlindScan's online AI (most accurate; also checks the photo for misread words)"),
    BUILT_IN("Phone's built-in AI only (private: nothing leaves the phone)"),
}

/** All user settings, saved instantly. Compose screens read these directly. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private fun bool(key: String, def: Boolean) = mutableStateOf(prefs.getBoolean(key, def))
    private fun save(key: String, v: Boolean) = prefs.edit { putBoolean(key, v) }

    private var _documentVoice by mutableStateOf(Voice.valueOf(prefs.getString("documentVoice", Voice.SCREEN_READER.name)!!))
    var documentVoice: Voice
        get() = _documentVoice
        set(v) { _documentVoice = v; prefs.edit { putString("documentVoice", v.name) } }

    private var _liveVoice by mutableStateOf(Voice.valueOf(prefs.getString("liveVoice", Voice.TEXT_TO_SPEECH.name)!!))
    var liveVoice: Voice
        get() = _liveVoice
        set(v) { _liveVoice = v; prefs.edit { putString("liveVoice", v.name) } }

    private var _speechRate by mutableFloatStateOf(prefs.getFloat("speechRate", 1.0f))
    var speechRate: Float
        get() = _speechRate
        set(v) { _speechRate = v; prefs.edit { putFloat("speechRate", v) } }

    private val _volumeCapture = bool("volumeCapture", true)
    var volumeCapture: Boolean
        get() = _volumeCapture.value
        set(v) { _volumeCapture.value = v; save("volumeCapture", v) }

    private val _guidance = bool("guidance", true)
    var guidance: Boolean
        get() = _guidance.value
        set(v) { _guidance.value = v; save("guidance", v) }

    private val _vibration = bool("vibration", true)
    var vibration: Boolean
        get() = _vibration.value
        set(v) { _vibration.value = v; save("vibration", v) }

    private val _autoLight = bool("autoLight", true)
    var autoLight: Boolean
        get() = _autoLight.value
        set(v) { _autoLight.value = v; save("autoLight", v) }

    private val _autoScan = bool("autoScan", false)
    var autoScan: Boolean
        get() = _autoScan.value
        set(v) { _autoScan.value = v; save("autoScan", v) }

    private val _tiltHints = bool("tiltHints", true)
    var tiltHints: Boolean
        get() = _tiltHints.value
        set(v) { _tiltHints.value = v; save("tiltHints", v) }

    private val _liveBestAccuracy = bool("liveBestAccuracy", false)
    var liveBestAccuracy: Boolean
        get() = _liveBestAccuracy.value
        set(v) { _liveBestAccuracy.value = v; save("liveBestAccuracy", v) }
    private val _batchMode = bool("batchMode", false)
    var batchMode: Boolean
        get() = _batchMode.value
        set(v) { _batchMode.value = v; save("batchMode", v) }

    private val _wordCorrection = bool("wordCorrection", true)
    var wordCorrection: Boolean
        get() = _wordCorrection.value
        set(v) { _wordCorrection.value = v; save("wordCorrection", v) }

    // Off by default until proven on more phones. ("fastMathV2": the 0.2.0 setting is not carried over.)
    private val _fastMath = bool("fastMathV2", false)
    var fastMath: Boolean
        get() = _fastMath.value
        set(v) { _fastMath.value = v; save("fastMathV2", v); onEngineSettingChanged?.invoke() }

    private var _askEngine by mutableStateOf(
        runCatching { AskEngineChoice.valueOf(prefs.getString("askEngine", AskEngineChoice.AUTOMATIC.name)!!) }
            .getOrDefault(AskEngineChoice.AUTOMATIC)
    )
    var askEngine: AskEngineChoice
        get() = _askEngine
        set(v) { _askEngine = v; prefs.edit { putString("askEngine", v.name) } }

    private var _ttsEngine by mutableStateOf(prefs.getString("ttsEngine", "") ?: "")
    /** The speech engine BlindScan speaks with: a package name, or blank for the phone's default. */
    var ttsEngine: String
        get() = _ttsEngine
        set(v) { _ttsEngine = v; prefs.edit { putString("ttsEngine", v) } }

    private val _askListenAgain = bool("askListenAgain", true)
    /** After you say a question and hear the answer, start listening for the next question. */
    var askListenAgain: Boolean
        get() = _askListenAgain.value
        set(v) { _askListenAgain.value = v; save("askListenAgain", v) }

    /** Set by the app so engines can be rebuilt when an engine setting changes. */
    var onEngineSettingChanged: (() -> Unit)? = null
}

val LocalSettings = staticCompositionLocalOf<Settings> { error("Settings not provided") }
