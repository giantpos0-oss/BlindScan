package com.willwalsh.blindscan.ai

import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.SystemInstruction
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.willwalsh.blindscan.AskEngineChoice
import com.willwalsh.blindscan.CloudProvider
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
    /** The cloud AI you set up will answer (the document's text is sent to it). */
    data class CloudReady(val provider: CloudProvider) : AskStatus()
    /** Nothing can answer: no built-in AI, and no cloud AI set up (or it was chosen and isn't set up). */
    data class NoneAvailable(val builtInExists: Boolean) : AskStatus()
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
        You answer questions about a document that a blind person scanned with their phone's camera.
        Use only the document. Never use outside knowledge and never guess.
        Reply in exactly this form:
        ANSWER: a short, direct answer in one to three plain sentences, for listening (no markdown).
        QUOTE: the exact words copied from the document that show the answer, word for word, at most
        about forty words. For several separate places, join them with " ... ".
        If the document does not contain the answer, reply exactly:
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

    // ------------------------------------------------------------ cloud AI (your own key)

    private const val CLAUDE_MODEL = "claude-haiku-5-5"
    private const val GEMINI_MODEL = "gemini-3.5-flash-lite"

    private fun post(url: String, headers: Map<String, String>, body: JSONObject): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 15000; c.readTimeout = 45000
            c.doOutput = true
            c.setRequestProperty("content-type", "application/json")
            for ((k, v) in headers) c.setRequestProperty(k, v)
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw CloudError(code, text)
            return JSONObject(text)
        } finally { c.disconnect() }
    }

    class CloudError(val code: Int, body: String) : Exception("HTTP $code: ${body.take(300)}")

    private fun askCloud(provider: CloudProvider, key: String, prompt: String): String = when (provider) {
        CloudProvider.CLAUDE -> {
            val body = JSONObject()
                .put("model", CLAUDE_MODEL)
                .put("max_tokens", 400)
                .put("system", SYSTEM)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
            val r = post("https://api.anthropic.com/v1/messages",
                mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01"), body)
            val parts = r.getJSONArray("content")
            buildString { for (i in 0 until parts.length()) parts.getJSONObject(i).optString("text").let { append(it) } }
        }
        CloudProvider.GEMINI -> {
            val body = JSONObject()
                .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM))))
                .put("contents", JSONArray().put(JSONObject().put("role", "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            val r = post("https://generativelanguage.googleapis.com/v1beta/models/$GEMINI_MODEL:generateContent",
                mapOf("x-goog-api-key" to key), body)
            val parts = r.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts")
            buildString {
                for (i in 0 until parts.length()) {
                    val p = parts.getJSONObject(i)
                    if (!p.optBoolean("thought")) append(p.optString("text"))
                }
            }
        }
    }

    // ------------------------------------------------------------ choosing

    suspend fun status(settings: Settings): AskStatus {
        val cloudSetUp = settings.cloudOn && settings.cloudKey.isNotBlank()
        if (settings.askEngine == AskEngineChoice.CLOUD) {
            return if (cloudSetUp) AskStatus.CloudReady(settings.cloudProvider) else AskStatus.NoneAvailable(nanoStatus() != FeatureStatus.UNAVAILABLE)
        }
        val n = nanoStatus()
        return when {
            n == FeatureStatus.AVAILABLE -> AskStatus.BuiltInReady
            settings.askEngine == AskEngineChoice.AUTOMATIC && cloudSetUp && n == FeatureStatus.UNAVAILABLE ->
                AskStatus.CloudReady(settings.cloudProvider)
            n == FeatureStatus.DOWNLOADING -> AskStatus.BuiltInDownloading
            n == FeatureStatus.DOWNLOADABLE -> AskStatus.BuiltInDownloadable
            else -> AskStatus.NoneAvailable(false)
        }
    }

    /** Answers [question] about [document], checked against the document. */
    suspend fun answer(settings: Settings, status: AskStatus, document: String, history: List<Turn>, question: String): Answer =
        withContext(Dispatchers.IO) {
            val cloud = status is AskStatus.CloudReady
            val prompt = buildPrompt(document, history, question, if (cloud) 60000 else 7000)
            val raw = if (status is AskStatus.CloudReady) askCloud(status.provider, settings.cloudKey, prompt) else askBuiltIn(prompt)
            check(raw, document)
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
        val ans = Regex("(?is)ANSWER:\\s*(.*?)(?:\\n\\s*QUOTE:|$)").find(cleaned)?.groupValues?.get(1)?.trim()
            ?: cleaned.substringBefore("QUOTE:").trim()
        val quote = Regex("(?is)QUOTE:\\s*(.*)$").find(cleaned)?.groupValues?.get(1)?.trim()?.trim('"', '“', '”') ?: ""
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
            if (d in docDigits || document.replace(",", "").contains(d)) return@all true
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
        return Answer(ans.replace(Regex("(?m)^\\s*[*•-]\\s+"), "").trim(), quote, false)
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
