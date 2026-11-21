package com.willwalsh.blindscan

import android.app.Application
import com.willwalsh.blindscan.ocr.OcrEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BlindScanApp : Application() {

    lateinit var settings: Settings
        private set

    /** Best-accuracy engine for document scans. Loaded in the background at start-up. */
    val documentEngine: OcrEngine by lazy { OcrEngine(this, OcrEngine.Model.SMALL) }

    /** Fast engine for live mode and positioning guidance. */
    val fastEngine: OcrEngine by lazy { OcrEngine(this, OcrEngine.Model.TINY) }

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        // Warm both engines up so the first scan doesn't wait for model loading.
        appScope.launch { fastEngine }
        appScope.launch { documentEngine }
    }
}
