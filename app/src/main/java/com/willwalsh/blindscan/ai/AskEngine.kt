package com.willwalsh.blindscan.ai

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.SystemInstruction
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.willwalsh.blindscan.AskEngineChoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Where answers can come from right now. */
sealed class AskStatus {
    /** The phone's built-in AI (Gemini Nano) is ready. */
    data object BuiltInReady : AskStatus()
    /** The phone has built-in AI, but its model must be fetched first (by Android, not by BlindScan). */
    data object BuiltInDownloadable : AskStatus()
    /** Android is fetching the built-in AI model now. */
    data object BuiltInDownloading : AskStatus()
    /** BlindScan's own model is downloaded and ready. */
    data object OwnReady : AskStatus()
    /** BlindScan's own model can be downloaded (about 1.6 GB, once). */
    data object OwnDownloadable : AskStatus()
}

/** One earlier question and its answer, so follow-up questions make sense ("and the total?"). */
data class Turn(val question: String, val answer: String)

/**
 * Answers questions about a scanned document, entirely on the phone. Uses the phone's built-in
 * AI (Gemini Nano, through Android's AICore) when the phone has it, and otherwise BlindScan's own
 * model (Qwen2.5 1.5B Instruct, Apache 2.0), downloaded once. Nothing is sent anywhere.
 */
object AskEngine {
    private val SYSTEM = """
        You help a blind person understand a document they scanned with their phone's camera.
        Answer the question using only the document. Be brief and direct: one to three short sentences,
        or a short list when the question asks for several things. Give names, dates, times, amounts,
        prices and phone numbers exactly as the document writes them. If the document doesn't say,
        answer that the document doesn't say, and don't guess. The text was read by a camera, so a few
        words may be misread; if something important looks garbled, say it may be misread.
        Write plain sentences for listening: no markdown, no asterisks, no headings.
    """.trimIndent().replace("\n", " ")

    /** About how much document text fits beside the question (both models: a 4,000-token window). */
    private const val DOC_BUDGET_CHARS = 7500

    // ------------------------------------------------------------ built-in AI (Gemini Nano)

    private var nano: GenerativeModel? = null
    private fun nanoClient(): GenerativeModel? = try {
        nano ?: Generation.getClient().also { nano = it }
    } catch (_: Throwable) { null }

    /** FeatureStatus of the built-in AI, or UNAVAILABLE when the phone (or Android) has none. */
    private suspend fun nanoStatus(): Int = try {
        if (android.os.Build.VERSION.SDK_INT < 31) FeatureStatus.UNAVAILABLE
        else nanoClient()?.checkStatus() ?: FeatureStatus.UNAVAILABLE
    } catch (_: Throwable) { FeatureStatus.UNAVAILABLE }

    /** Asks Android to fetch the built-in AI model; reports progress as a fraction, or -1 if unknown. */
    suspend fun downloadBuiltIn(onProgress: (Float) -> Unit): Boolean {
        val client = nanoClient() ?: return false
        var ok = false
        try {
            client.download().collect { s ->
                when (s) {
                    is DownloadStatus.DownloadProgress -> onProgress(-1f)
                    is DownloadStatus.DownloadCompleted -> ok = true
                    is DownloadStatus.DownloadFailed -> ok = false
                    else -> {}
                }
            }
        } catch (_: Throwable) { return false }
        return ok || nanoStatus() == FeatureStatus.AVAILABLE
    }

    // ------------------------------------------------------------ BlindScan's own model

    private var engine: Engine? = null
    private val engineLock = Mutex()

    private suspend fun ownEngine(context: Context): Engine = engineLock.withLock {
        engine?.let { return it }
        val path = OwnModel.file(context).absolutePath
        val cache = context.cacheDir.absolutePath
        // The phone's graphics chip is much faster; fall back to the processor if it can't run it.
        val e = withContext(Dispatchers.Default) {
            try {
                Engine(EngineConfig(modelPath = path, backend = Backend.GPU(), cacheDir = cache)).also { it.initialize() }
            } catch (_: Throwable) {
                Engine(EngineConfig(modelPath = path, backend = Backend.CPU(), cacheDir = cache)).also { it.initialize() }
            }
        }
        engine = e
        e
    }

    /** Frees BlindScan's own model from memory (it is loaded again when next needed). */
    fun release() {
        try { engine?.close() } catch (_: Throwable) { }
        engine = null
    }

    // ------------------------------------------------------------ choosing

    suspend fun status(context: Context, choice: AskEngineChoice): AskStatus {
        val ownReady = OwnModel.isReady(context)
        if (choice == AskEngineChoice.BLINDSCAN) return if (ownReady) AskStatus.OwnReady else AskStatus.OwnDownloadable
        val n = nanoStatus()
        return when {
            n == FeatureStatus.AVAILABLE -> AskStatus.BuiltInReady
            choice == AskEngineChoice.AUTOMATIC && ownReady -> AskStatus.OwnReady
            n == FeatureStatus.DOWNLOADING -> AskStatus.BuiltInDownloading
            n == FeatureStatus.DOWNLOADABLE -> AskStatus.BuiltInDownloadable
            else -> if (ownReady) AskStatus.OwnReady else AskStatus.OwnDownloadable
        }
    }

    /**
     * Streams the answer to [question] about [document] as pieces of text. [history] holds
     * earlier questions in this conversation.
     */
    fun answer(context: Context, status: AskStatus, document: String, history: List<Turn>, question: String): Flow<String> {
        val prompt = buildPrompt(document, history, question)
        return if (status == AskStatus.BuiltInReady) askBuiltIn(prompt) else askOwn(context, prompt)
    }

    private fun askBuiltIn(prompt: String): Flow<String> {
        val client = nanoClient() ?: throw IllegalStateException("Built-in AI isn't available")
        val request = generateContentRequest(SystemInstruction(SYSTEM), TextPart(prompt)) {
            temperature = 0.2f
            topK = 16
            maxOutputTokens = 300
        }
        return client.generateContentStream(request).mapNotNull { it.candidates.firstOrNull()?.text }
    }

    private fun askOwn(context: Context, prompt: String): Flow<String> = callbackFlow {
        val e = ownEngine(context)
        val conversation = e.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(SYSTEM),
                samplerConfig = SamplerConfig(topK = 16, topP = 0.9, temperature = 0.2),
            )
        )
        conversation.sendMessageAsync(prompt, object : MessageCallback {
            override fun onMessage(message: Message) { trySend(message.toString()) }
            override fun onDone() { close() }
            override fun onError(throwable: Throwable) { close(throwable) }
        })
        awaitClose {
            try { conversation.cancelProcess() } catch (_: Throwable) { }
            try { conversation.close() } catch (_: Throwable) { }
        }
    }.flowOn(Dispatchers.Default)

    // ------------------------------------------------------------ the prompt

    private fun buildPrompt(document: String, history: List<Turn>, question: String) = buildString {
        append("DOCUMENT:\n")
        append(fitDocument(document, question + " " + history.takeLast(2).joinToString(" ") { it.question }))
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

    private fun words(t: String) = t.lowercase().split(Regex("[^\\p{L}\\p{N}$.,]+"))
        .map { it.trim('.', ',') }.filter { it.length > 1 && it !in STOP }

    /**
     * The whole document when it fits; otherwise the parts that best match the question (sharing
     * its words), kept in page order, so a long book or many-page letter still gets an answer.
     */
    private fun fitDocument(document: String, question: String): String {
        if (document.length <= DOC_BUDGET_CHARS) return document
        val paras = document.split(Regex("\n\\s*\n")).filter { it.isNotBlank() }
        // Pieces of about 600 characters (paragraphs, long ones split at sentences).
        val pieces = ArrayList<String>()
        for (p in paras) {
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
        // Rarer words count for more (a name or "deductible" beats "total").
        val df = HashMap<String, Int>()
        val pieceWords = pieces.map { words(it).toSet() }
        for (ws in pieceWords) for (w in ws) df[w] = (df[w] ?: 0) + 1
        fun matches(qw: String, w: String) = w == qw || (qw.length >= 4 && (w.startsWith(qw) || qw.startsWith(w) && w.length >= 4))
        val scores = pieceWords.mapIndexed { i, ws ->
            var s = 0.0
            for (qw in q) {
                val hit = ws.firstOrNull { matches(qw, it) } ?: continue
                s += Math.log(1.0 + pieces.size.toDouble() / (df[hit] ?: 1))
            }
            s + (if (i == 0) 0.5 else 0.0) // the start often says what the document is (letterhead, title)
        }
        val chosen = HashSet<Int>()
        var used = 0
        for (i in scores.indices.sortedByDescending { scores[it] }) {
            if (used + pieces[i].length > DOC_BUDGET_CHARS) continue
            chosen.add(i); used += pieces[i].length + 2
        }
        return pieces.indices.filter { it in chosen }.joinToString("\n\n") { pieces[it] } +
            "\n(Only the parts of a longer document that match the question are shown.)"
    }

    /** Makes an answer pleasant to listen to: drops markdown marks a model sometimes adds anyway. */
    fun clean(answer: String): String = answer
        .replace(Regex("\\*\\*|__|`"), "")
        .replace(Regex("(?m)^\\s*#+\\s*"), "")
        .replace(Regex("(?m)^\\s*[*•-]\\s+"), "")
        .trim()
}
