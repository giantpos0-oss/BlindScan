package com.willwalsh.blindscan

import android.app.Application
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
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

    /** Something to tell you when the app opens (for example after a crash), or null. */
    @Volatile var startupNotice: String? = null

    val crashFile: File get() = File(filesDir, "last-crash.txt")
    private val scanMarker: File get() = File(filesDir, "scan-in-progress")

    /** Called around every scan so a crash during reading can be detected on the next start. */
    fun markScanStarted() = runCatching { scanMarker.writeText(System.currentTimeMillis().toString()) }
    fun markScanFinished() = runCatching { scanMarker.delete() }

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)

        // Save details of any crash so it can be shared from Settings.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                crashFile.writeText(
                    "BlindScan crash at ${java.util.Date()}\n" +
                        "Phone: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}\n" +
                        "Thread: ${thread.name}\n\n$sw"
                )
            }
            previous?.uncaughtException(thread, error)
        }

        // The app closed in the middle of a scan last time: play safe.
        if (scanMarker.exists()) {
            scanMarker.delete()
            val hadFastMath = settings.fastMath
            if (hadFastMath) settings.fastMath = false
            startupNotice = "BlindScan closed unexpectedly during the last scan." +
                (if (hadFastMath) " Fast math has been turned off to prevent it happening again." else "") +
                (if (crashFile.exists()) " You can share the crash report from Settings." else "")
        }

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
