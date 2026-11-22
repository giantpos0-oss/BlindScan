package com.willwalsh.blindscan.ai

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.delay
import java.io.File

/**
 * BlindScan's own question-answering model: Qwen2.5 1.5B Instruct (Apache 2.0) in LiteRT-LM form,
 * from the LiteRT community on Hugging Face, pinned to one exact version. Downloaded once, only
 * when you ask for it, by Android's download manager (it carries on if you leave the app).
 */
object OwnModel {
    const val NAME = "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm"
    private const val URL = "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/" +
        "19edb84c69a0212f29a6ef17ba0d6f278b6a1614/$NAME?download=true"
    /** Its size, to tell you before downloading and to know the download is complete. */
    const val BYTES = 1_597_931_520L
    private const val PREFS = "ownModel"

    fun file(context: Context) = File(context.getExternalFilesDir("models") ?: File(context.filesDir, "models"), NAME)

    /** Ready when the whole file is there (within 2% of the expected size, as the server reports it). */
    fun isReady(context: Context): Boolean {
        val f = file(context)
        return f.exists() && f.length() > BYTES * 0.98 && downloadId(context) < 0
    }

    private fun downloadId(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("downloadId", -1L)

    private fun setDownloadId(context: Context, id: Long) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("downloadId", id).apply()

    /** True while a download is under way (started earlier, maybe before the app was closed). */
    fun isDownloading(context: Context) = downloadId(context) >= 0

    /** Starts the download. [wifiOnly]: wait for Wi-Fi rather than use mobile data. */
    fun start(context: Context, wifiOnly: Boolean) {
        if (isDownloading(context)) return
        val dm = context.getSystemService(DownloadManager::class.java)
        val target = file(context)
        target.parentFile?.mkdirs()
        if (target.exists()) target.delete()
        val req = DownloadManager.Request(Uri.parse(URL))
            .setTitle("BlindScan question model")
            .setDescription("About 1.6 GB, downloaded once")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationUri(Uri.fromFile(target))
            .setAllowedOverMetered(!wifiOnly)
            .setAllowedOverRoaming(false)
        setDownloadId(context, dm.enqueue(req))
    }

    /** Download progress. */
    data class Progress(val fraction: Float, val done: Boolean, val failed: Boolean, val waitingForWifi: Boolean)

    fun progress(context: Context): Progress {
        val id = downloadId(context)
        if (id < 0) return Progress(if (isReady(context)) 1f else 0f, isReady(context), false, false)
        val dm = context.getSystemService(DownloadManager::class.java)
        dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
            if (!c.moveToFirst()) { setDownloadId(context, -1); return Progress(0f, false, true, false) }
            val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val got = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)).takeIf { it > 0 } ?: BYTES
            val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            return when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> { setDownloadId(context, -1); Progress(1f, isReady(context), !isReady(context), false) }
                DownloadManager.STATUS_FAILED -> { setDownloadId(context, -1); file(context).delete(); Progress(0f, false, true, false) }
                else -> Progress(
                    (got.toFloat() / total).coerceIn(0f, 1f), false, false,
                    status == DownloadManager.STATUS_PAUSED && reason == DownloadManager.PAUSED_QUEUED_FOR_WIFI,
                )
            }
        }
        return Progress(0f, false, true, false)
    }

    /** Waits for the download to finish, reporting progress about once a second. */
    suspend fun await(context: Context, onProgress: (Progress) -> Unit): Boolean {
        while (true) {
            val p = progress(context)
            onProgress(p)
            if (p.done) return true
            if (p.failed) return false
            delay(1000)
        }
    }

    fun cancel(context: Context) {
        val id = downloadId(context)
        if (id >= 0) context.getSystemService(DownloadManager::class.java).remove(id)
        setDownloadId(context, -1)
        file(context).delete()
    }

    /** Deletes the downloaded model to free space. */
    fun delete(context: Context) {
        AskEngine.release()
        cancel(context)
    }
}
