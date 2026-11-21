package com.willwalsh.blindscan

import android.app.Application
import com.willwalsh.blindscan.ocr.OcrEngine
import com.willwalsh.blindscan.ocr.WordCorrector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class BlindScanApp : Application() {

    lateinit var settings: Settings
        private set

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** English word list for correcting low-confidence words (loaded once). */
    val corrector: WordCorrector by lazy { WordCorrector(assets.open("words_en.txt")) }

    @Volatile private var docEngine: OcrEngine? = null
    @Volatile private var liveEngine: OcrEngine? = null

    /** Best-accuracy engine for document scans. */
    val documentEngine: OcrEngine
        get() = docEngine ?: synchronized(this) {
            docEngine ?: OcrEngine(this, OcrEngine.Model.SMALL, corrector, settings.fastMath).also { docEngine = it }
        }

    /** Fast engine for live mode and positioning guidance. */
    val fastEngine: OcrEngine
        get() = liveEngine ?: synchronized(this) {
            liveEngine ?: OcrEngine(this, OcrEngine.Model.TINY, null, settings.fastMath).also { liveEngine = it }
        }

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        settings.onEngineSettingChanged = { rebuildEngines() }
        warmUp()
    }

    /** Load models in the background so the first scan doesn't wait. */
    private fun warmUp() {
        appScope.launch { fastEngine }
        appScope.launch { documentEngine; corrector }
    }

    private fun rebuildEngines() {
        val oldDoc: OcrEngine?
        val oldLive: OcrEngine?
        synchronized(this) {
            oldDoc = docEngine; oldLive = liveEngine
            docEngine = null; liveEngine = null
        }
        warmUp()
        // Give any read still using the old engines time to finish before closing them.
        appScope.launch { delay(20_000); oldDoc?.close(); oldLive?.close() }
    }
}
