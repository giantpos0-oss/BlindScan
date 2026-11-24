package com.willwalsh.blindscan.ai

import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.SystemInstruction
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.willwalsh.blindscan.AskEngineChoice
import com.willwalsh.blindscan.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Which AI can answer right now. */
sealed class AskStatus {
    /** The phone's built-in AI (Gemini Nano) is ready: private, nothing leaves the phone. */
    data object BuiltInReady : AskStatus()
    /** The phone has built-in AI, but Android must fetch its model first. */
    data object BuiltInDownloadable : AskStatus()
    /** Android is fetching the built-in AI model now. */
    data object BuiltInDownloading : AskStatus()
    /** BlindScan's online AI will answer (the document's text and photo go to it). */
    data object OnlineReady : AskStatus()
}

/** One earlier question and its answer, so follow-up questions make sense ("and the total?"). */
data class Turn(val question: String, val answer: String)

/** A checked answer. */
data class Answer(
    /** What to say. */
    val text: String,
    /** The document's own words that back it up (empty when there's nothing to quote). */
    val quote: String,
    /** True when the document doesn't hold the answer, or the AI's answer couldn't be confirmed. */
    val notFound: Boolean,
    /** When the photo shows something different from the scanned text (a misread), what it says. */
    val photoNote: String = "",
    /** General information about the document's subject, not taken from the document itself. */
    val related: Boolean = false,
)

/**
 * Answers questions about a scanned document, checking every answer against the page.
 *
 * The AI must reply with a short answer and the exact words from the document that support it.
 * BlindScan then looks for those words in the scanned text, and makes sure every number in the
 * answer appears in the document. If either check fails, you hear that the answer couldn't be
 * found in the document, instead of something the AI may have made up.
 */
object AskEngine {
    private val SYSTEM = """
        You help a blind person with a document they scanned with their phone's camera.
        Answer any question that has to do with this document or what it is about. Questions about
        something that has nothing to do with the document are not answered.
        Reply in exactly this form, with these labels on their own lines:
        SOURCE: DOCUMENT if the answer comes from the document. RELATED if the question is about the
        document or its subject but the document doesn't hold the answer (what a word on it means, what a
        medicine on it is for). UNRELATED if it has nothing to do with the document.
        ANSWER: a short, direct answer in one to three plain sentences, for listening (no markdown).
        For DOCUMENT use only the document and never guess. For RELATED give brief, general,
        well-established information and never invent details about this document, person or account.
        For UNRELATED write: That question is not about this document.
        QUOTE: for DOCUMENT, the exact words copied from the document that show the answer, word for
        word, at most about forty words; several places joined with " ... ". NONE if the document
        doesn't contain the answer. For RELATED, the words of the document the question is about, or NONE.
        If a question asks for something from the document that it doesn't contain, reply:
        SOURCE: DOCUMENT
        ANSWER: The document doesn't say.
        QUOTE: NONE
        Copy names, dates, amounts, prices and numbers exactly as the document writes them.
        The text was read by a camera, so a few words may be misread.
    """.trimIndent()

    // ------------------------------------------------------------ built-in AI (Gemini Nano)

    private var nano: GenerativeModel? = null
    private fun nanoClient(): GenerativeModel? = try {
        nano ?: Generation.getClient().also { nano = it }
    } catch (_: Throwable) { null }

    private suspend fun nanoStatus(): Int = try {
        if (android.os.Build.VERSION.SDK_INT < 31) FeatureStatus.UNAVAILABLE
        else nanoClient()?.checkStatus() ?: FeatureStatus.UNAVAILABLE
    } catch (_: Throwable) { FeatureStatus.UNAVAILABLE }

    /** Asks Android to fetch the built-in AI's model. */
    suspend fun downloadBuiltIn(): Boolean {
        val client = nanoClient() ?: return false
        var ok = false
        try {
            client.download().collect { s ->
                if (s is DownloadStatus.DownloadCompleted) ok = true
            }
        } catch (_: Throwable) { }
        return ok || nanoStatus() == FeatureStatus.AVAILABLE
    }

    private suspend fun askBuiltIn(prompt: String): String {
        val client = nanoClient() ?: throw IllegalStateException("Built-in AI isn't available")
        val request = generateContentRequest(SystemInstruction(SYSTEM), TextPart(prompt)) {
            temperature = 0.1f
            topK = 8
            maxOutputTokens = 300
        }
        return client.generateContent(request).candidates.firstOrNull()?.text ?: ""
    }

    // ------------------------------------------------------------ BlindScan's online AI

    /** BlindScan's own AI service: holds the key, so nobody needs one (see tools/blindscan-ai). */
    private const val SERVICE = "https://blindscan-ai.voicecompanionapp.com/v1/ask"

    class ServiceError(val code: Int, val error: String) : Exception("HTTP $code: $error")

    private fun askOnline(context: android.content.Context, document: String, history: List<Turn>, question: String, photo: java.io.File?): String {
        val body = JSONObject()
            .put("document", document.take(190000))
            .put("question", question.take(1900))
            .put("history", JSONArray().apply {
                for (t in history.takeLast(2)) put(JSONObject().put("question", t.question.take(1900)).put("answer", t.answer.take(900)))
            })
        photoData(photo)?.let { body.put("image", it) }
        val c = URL(SERVICE).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 15000; c.readTimeout = 90000
            c.doOutput = true
            c.setRequestProperty("content-type", "application/json")
            // The request names itself: Cloudflare can turn away a request with Android's default
            // name before BlindScan's service sees it (it did that to Voice Companion, error 1010).
            c.setRequestProperty("User-Agent", "BlindScan/" + appVersion(context) + " (Android)")
            c.setRequestProperty("X-BlindScan-Install", installId(context))
            c.setRequestProperty("X-BlindScan-Version", appVersion(context))
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            val r = runCatching { JSONObject(text) }.getOrNull()
            if (code !in 200..299 || r == null || !r.optBoolean("ok")) throw ServiceError(code, r?.optString("error") ?: text.take(200))
            return r.optString("text")
        } finally { c.disconnect() }
    }

    /** The page photo, made small enough to send quickly (longest side 2000 pixels, JPEG). */
    private fun photoData(file: java.io.File?): String? = try {
        if (file == null || !file.exists()) null else {
            val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(file.path, opts)
            var sample = 1
            while (maxOf(opts.outWidth, opts.outHeight) / (sample * 2) >= 2000) sample *= 2
            val bmp = android.graphics.BitmapFactory.decodeFile(file.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
            val scale = 2000f / maxOf(bmp.width, bmp.height)
            val small = if (scale < 1f) android.graphics.Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
            val out = java.io.ByteArrayOutputStream()
            small.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
            if (small !== bmp) small.recycle(); bmp.recycle()
            "data:image/jpeg;base64," + android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
        }
    } catch (_: Throwable) { null }

    private fun appVersion(context: android.content.Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "0"

    /** A random id for this installed copy (not tied to you or the phone). */
    private fun installId(context: android.content.Context): String {
        val prefs = context.getSharedPreferences("ask", android.content.Context.MODE_PRIVATE)
        prefs.getString("install", null)?.let { return it }
        val id = java.util.UUID.randomUUID().toString().replace("-", "")
        prefs.edit().putString("install", id).apply()
        return id
    }

    // ------------------------------------------------------------ choosing

    /**
     * The phone's built-in AI when it's ready and chosen; otherwise BlindScan's online AI, which
     * works on every phone (rooted ones included) and needs no key.
     */
    suspend fun status(settings: Settings): AskStatus {
        if (settings.askEngine == AskEngineChoice.ONLINE) return AskStatus.OnlineReady
        val n = nanoStatus()
        return when {
            n == FeatureStatus.AVAILABLE -> AskStatus.BuiltInReady
            settings.askEngine == AskEngineChoice.BUILT_IN && n == FeatureStatus.DOWNLOADING -> AskStatus.BuiltInDownloading
            settings.askEngine == AskEngineChoice.BUILT_IN && n == FeatureStatus.DOWNLOADABLE -> AskStatus.BuiltInDownloadable
            else -> AskStatus.OnlineReady
        }
    }

    /** Answers [question] about [document], checked against the document. */
    suspend fun answer(
        context: android.content.Context, status: AskStatus, document: String, history: List<Turn>, question: String,
        photos: List<java.io.File>,
    ): Answer = withContext(Dispatchers.IO) {
        if (status == AskStatus.OnlineReady) {
            // One photo goes with the text: the first page's for a one-page document. For several
            // pages the text alone is sent (sending every page's photo would be slow).
            val raw = askOnline(context, document, history, question, photos.singleOrNull())
            check(raw, document)
        } else {
            check(askBuiltIn(buildPrompt(document, history, question, 7000)), document)
        }
    }

    // ------------------------------------------------------------ checking the answer

    private fun norm(t: String) = t.lowercase()
        .replace(Regex("[‘’`´]"), "'").replace(Regex("[“”]"), "\"")
        .split(Regex("[^\\p{L}\\p{N}$%'.,/:-]+"))
        .map { it.trim('.', ',', ':', '\'', '-', '/') }
        .filter { it.isNotEmpty() }

    /** Are these words (in this order, allowing a misread word or two) in the document? */
    private fun foundIn(docWords: List<String>, part: List<String>): Boolean {
        if (part.isEmpty()) return true
        val need = if (part.size <= 3) part.size else Math.ceil(part.size * 0.85).toInt()
        for (start in 0..(docWords.size - part.size).coerceAtLeast(0)) {
            var hit = 0
            for (k in part.indices) if (start + k < docWords.size && docWords[start + k] == part[k]) hit++
            if (hit >= need) return true
        }
        return false
    }

    private fun digits(t: String) = Regex("\\d[\\d,.:/-]*\\d|\\d").findAll(t).map { it.value.replace(Regex("[,]"), "") }.toList()

    fun check(raw: String, document: String): Answer {
        val cleaned = raw.replace(Regex("\\*\\*|__|`"), "")
        val source = Regex("(?i)SOURCE:\\s*([A-Z]+)").find(cleaned)?.groupValues?.get(1)?.uppercase() ?: "DOCUMENT"
        val ans = Regex("(?is)ANSWER:\\s*(.*?)(?:\\n\\s*QUOTE:|$)").find(cleaned)?.groupValues?.get(1)?.trim()
            ?: cleaned.substringBefore("QUOTE:").trim()
        val quote = Regex("(?is)QUOTE:\\s*(.*?)(?:\\n\\s*CHECK:|$)").find(cleaned)?.groupValues?.get(1)?.trim()?.trim('"', '“', '”') ?: ""
        val photoCheck = Regex("(?is)CHECK:\\s*(.*)$").find(cleaned)?.groupValues?.get(1)?.trim() ?: ""
        val photoNote = if (photoCheck.isBlank() || photoCheck.trimEnd('.').equals("OK", true)) "" else photoCheck
        // A question that has nothing to do with the document isn't answered.
        if (source == "UNRELATED") return Answer("That question isn't about this document. Ask something about what you scanned.", "", true)
        // About the document's subject, but not in it: general information, said plainly as such.
        // Its quote, if any, must still be on the page; it isn't checked for numbers, since it
        // isn't claiming to come from the document.
        if (source == "RELATED") {
            val docW = norm(document)
            val pieces = quote.split(Regex("\\s*(\\.\\.\\.|…)\\s*")).map { norm(it) }.filter { it.isNotEmpty() }
            val q = if (quote.equals("NONE", true) || pieces.isEmpty() || !pieces.all { foundIn(docW, it) }) "" else quote
            val text = ans.replace(Regex("(?m)^\\s*[*•-]\\s+"), "").trim()
            if (text.isBlank()) return Answer("I couldn't answer that. Try asking another way.", "", true)
            return Answer("This isn't in the document, but: $text", q, false, photoNote, related = true)
        }
        val doesntSay = ans.contains("doesn't say", true) || ans.contains("does not say", true) ||
            quote.equals("NONE", true) || quote.isBlank()
        if (doesntSay) return Answer("The document doesn't say.", "", true)

        val docWords = norm(document)
        // Every quoted piece must really be in the document.
        val pieces = quote.split(Regex("\\s*(\\.\\.\\.|…)\\s*")).map { norm(it) }.filter { it.isNotEmpty() }
        val quoted = pieces.isNotEmpty() && pieces.all { foundIn(docWords, it) }
        // Every number in the answer must be in the document (amounts, dates, phone numbers).
        val docDigits = digits(document.replace(",", "")).toSet() + docWords.toSet()
        // (A small whole number can be a count the AI made, like "there are 3 vegetarian dishes",
        // but not a day, an amount, a time or a percentage.)
        val months = Regex("(?i)(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\.?\\s*$")
        val numbersOk = Regex("\\d[\\d,.:/-]*\\d|\\d").findAll(ans).all { m ->
            val d = m.value.replace(",", "")
            if (d in docDigits || document.replace(",", "").contains(d) || photoNote.replace(",", "").contains(d)) return@all true
            val before = ans.substring(0, m.range.first).takeLast(12)
            val after = ans.substring(m.range.last + 1).take(3)
            val isCount = d.all { it.isDigit() } && d.length <= 2 && !before.trimEnd().endsWith("$") &&
                !months.containsMatchIn(before) && !after.startsWith("%") && !after.startsWith(":") &&
                !Regex("(?i)^\\s*(st|nd|rd|th|am|pm|a\\.m|p\\.m)").containsMatchIn(after)
            isCount
        }
        if (!quoted || !numbersOk) {
            return Answer("I couldn't find that in the document, so I won't guess. Try asking another way.", "", true)
        }
        return Answer(ans.replace(Regex("(?m)^\\s*[*•-]\\s+"), "").trim(), quote, false, photoNote)
    }

    // ------------------------------------------------------------ the prompt

    private fun buildPrompt(document: String, history: List<Turn>, question: String, budget: Int) = buildString {
        append("DOCUMENT:\n")
        append(fitDocument(document, question + " " + history.takeLast(2).joinToString(" ") { it.question }, budget))
        append("\nEND OF DOCUMENT\n\n")
        for (t in history.takeLast(2)) {
            append("Earlier question: ").append(t.question).append('\n')
            append("Earlier answer: ").append(t.answer.take(400)).append("\n\n")
        }
        append("QUESTION: ").append(question.trim())
    }

    private val STOP = setOf(
        "the", "a", "an", "and", "or", "of", "to", "in", "on", "for", "is", "are", "was", "were", "be", "it",
        "this", "that", "what", "which", "who", "whom", "when", "where", "why", "how", "do", "does", "did",
        "i", "my", "me", "you", "your", "there", "any", "much", "many", "about", "with", "from", "at", "by",
        "can", "could", "would", "should", "tell", "say", "says", "please", "document", "page",
    )

    private fun words(t: String) = norm(t).filter { it.length > 1 && it !in STOP }

    /**
     * The whole document when it fits; otherwise the parts that best match the question, kept in
     * page order (the built-in AI reads about 3,000 words at a time).
     */
    private fun fitDocument(document: String, question: String, budget: Int): String {
        if (document.length <= budget) return document
        val pieces = ArrayList<String>()
        for (p in document.split(Regex("\n\\s*\n")).filter { it.isNotBlank() }) {
            if (p.length <= 900) { pieces.add(p); continue }
            val sb = StringBuilder()
            for (sentence in p.split(Regex("(?<=[.!?])\\s+"))) {
                if (sb.length + sentence.length > 600 && sb.isNotEmpty()) { pieces.add(sb.toString()); sb.clear() }
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(sentence)
            }
            if (sb.isNotEmpty()) pieces.add(sb.toString())
        }
        val q = words(question).toSet()
        val pieceWords = pieces.map { words(it).toSet() }
        val df = HashMap<String, Int>()
        for (ws in pieceWords) for (w in ws) df[w] = (df[w] ?: 0) + 1
        fun matches(qw: String, w: String) = w == qw || (qw.length >= 4 && w.length >= 4 && (w.startsWith(qw) || qw.startsWith(w)))
        val scores = pieceWords.mapIndexed { i, ws ->
            var s = 0.0
            for (qw in q) {
                val hit = ws.firstOrNull { matches(qw, it) } ?: continue
                s += Math.log(1.0 + pieces.size.toDouble() / (df[hit] ?: 1))
            }
            s + (if (i == 0) 0.5 else 0.0)
        }
        val chosen = HashSet<Int>()
        var used = 0
        for (i in scores.indices.sortedByDescending { scores[it] }) {
            if (used + pieces[i].length > budget) continue
            chosen.add(i); used += pieces[i].length + 2
        }
        return pieces.indices.filter { it in chosen }.joinToString("\n\n") { pieces[it] } +
            "\n(Only the parts of a longer document that match the question are shown.)"
    }
}
