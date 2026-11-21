package com.willwalsh.blindscan.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Fully on-device OCR: PaddleOCR PP-OCRv6 (Apache 2.0) running in ONNX Runtime.
 * No network access is involved anywhere (the app doesn't even have the internet permission).
 *
 *  SMALL = best accuracy, used for document scans.
 *  TINY  = about 4x faster, used for live mode and positioning guidance.
 *
 * Speed: lines are recognised in parallel on several cores, and the XNNPACK math library is used
 * when the phone supports it. Results are delivered row by row in reading order, so speech can
 * start before the whole page is finished.
 */
class OcrEngine(
    context: Context,
    val model: Model,
    private val corrector: WordCorrector? = null,
    fastMath: Boolean = true,
) : AutoCloseable {

    enum class Model(val det: String, val rec: String, val dict: String) {
        SMALL("models/det_small.onnx", "models/rec_small.onnx", "models/dict_small.txt"),
        TINY("models/det_tiny.onnx", "models/rec_tiny.onnx", "models/dict_tiny.txt"),
    }

    data class Line(val text: String, val box: TextBox, val confidence: Float)

    data class Result(
        val lines: List<Line>,
        val paragraphs: List<String>,
        val detectMs: Long = 0,
        val recognizeMs: Long = 0,
    ) {
        val text: String get() = paragraphs.joinToString("\n\n")
        val isEmpty: Boolean get() = paragraphs.isEmpty()
    }

    private val env = OrtEnvironment.getEnvironment()
    private val cores = Runtime.getRuntime().availableProcessors()
    private val workers = (cores / 2).coerceIn(2, 4)
    private val pool: ExecutorService = Executors.newFixedThreadPool(workers)

    /** True when the XNNPACK math library is in use. */
    var usingFastMath = false
        private set

    private fun createSession(bytes: ByteArray, threads: Int, xnn: Boolean): OrtSession {
        if (xnn) {
            try {
                val o = OrtSession.SessionOptions().apply {
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    setIntraOpNumThreads(1)
                    addConfigEntry("session.intra_op.allow_spinning", "0")
                    addXnnpack(mapOf("intra_op_num_threads" to threads.toString()))
                }
                val s = env.createSession(bytes, o)
                usingFastMath = true
                return s
            } catch (_: Throwable) {
                // XNNPACK not available on this phone: fall through to the standard engine.
            }
        }
        val o = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(threads)
        }
        return env.createSession(bytes, o)
    }

    private fun asset(context: Context, name: String) = context.assets.open(name).use { it.readBytes() }

    // Detection is one big job: give it several threads. Recognition runs many lines side by side,
    // so each line gets one thread.
    private val det: OrtSession = createSession(asset(context, model.det), min(4, cores), fastMath)
    private val cls: OrtSession = createSession(asset(context, "models/cls.onnx"), 1, fastMath)

    // Each thread that reads lines gets its own private recognizer session, so no session is ever
    // used by two threads at once (safe with every math library, including XNNPACK).
    private val recBytes = asset(context, model.rec)
    private val fastMathWanted = fastMath
    private val recSessions = java.util.Collections.synchronizedList(ArrayList<OrtSession>())
    private val recLocal = object : ThreadLocal<OrtSession>() {
        override fun initialValue(): OrtSession =
            createSession(recBytes, 1, fastMathWanted).also { recSessions.add(it) }
    }
    private val rec: OrtSession get() = recLocal.get()!!

    // CTC classes: 0 = blank, 1..n = dictionary, n+1 = space.
    private val keys: List<String> =
        listOf("") + context.assets.open(model.dict).bufferedReader().readLines() + listOf(" ")
    private val keyIndex: Map<String, Int> = HashMap<String, Int>().also { m ->
        for (i in keys.indices) if (keys[i].length == 1 && !m.containsKey(keys[i])) m[keys[i]] = i
    }

    private val detInput = det.inputNames.first()
    private val recInput: String by lazy { rec.inputNames.first() }
    private val clsInput = cls.inputNames.first()

    // ---------------------------------------------------------------- detection

    /** Finds text lines. Box coordinates are in the bitmap's own pixel space. */
    fun detect(bitmap: Bitmap, limit: Int): List<TextBox> {
        val w = bitmap.width; val h = bitmap.height
        val r = min(1f, limit / max(w, h).toFloat())
        val rw = max(32, (w * r / 32f).roundToInt() * 32)
        val rh = max(32, (h * r / 32f).roundToInt() * 32)
        val scaled = Bitmap.createScaledBitmap(bitmap, rw, rh, true)
        val input = ImageTensors.detection(scaled)
        if (scaled !== bitmap) scaled.recycle()
        val prob = FloatArray(rw * rh)
        OnnxTensor.createTensor(env, input, longArrayOf(1, 3, rh.toLong(), rw.toLong())).use { t ->
            det.run(mapOf(detInput to t)).use { res ->
                (res.get(0) as OnnxTensor).floatBuffer.get(prob)
            }
        }
        val sx = w / rw.toFloat(); val sy = h / rh.toFloat()
        return Geometry.boxesFromProbability(prob, rw, rh).map {
            it.copy(cx = it.cx * sx, cy = it.cy * sy, w = it.w * sx, h = it.h * sy)
        }
    }

    // ---------------------------------------------------------------- recognition

    data class LineRead(val text: String, val confidence: Float)

    private class RawRead(val chars: List<DecodedChar>, val confidence: Float, val probs: FrameProbs)

    private fun recognizeRaw(crop: Bitmap): RawRead {
        val width = max(320, ceil(48f * crop.width / crop.height).toInt())
        val input = ImageTensors.recognition(crop, width)
        OnnxTensor.createTensor(env, input, longArrayOf(1, 3, 48, width.toLong())).use { t ->
            rec.run(mapOf(recInput to t)).use { res ->
                val out = res.get(0) as OnnxTensor
                val shape = out.info.shape // [1, T, C]
                val steps = shape[1].toInt(); val classes = shape[2].toInt()
                val buf = out.floatBuffer // a copy: stays valid after the result is closed
                val chars = ArrayList<DecodedChar>()
                var prev = 0
                for (t2 in 0 until steps) {
                    var best = 0; var bestP = -1f
                    val base = t2 * classes
                    for (c in 0 until classes) {
                        val p = buf.get(base + c)
                        if (p > bestP) { bestP = p; best = c }
                    }
                    if (best != 0 && best != prev) chars.add(DecodedChar(if (best < keys.size) keys[best] else "", t2, bestP))
                    prev = best
                }
                while (chars.isNotEmpty() && chars.first().s == " ") chars.removeAt(0)
                while (chars.isNotEmpty() && chars.last().s == " ") chars.removeAt(chars.size - 1)
                val conf = if (chars.isEmpty()) 0f else chars.map { it.prob }.average().toFloat()
                return RawRead(chars, conf, BufferProbs(buf, steps, classes))
            }
        }
    }

    /**
     * Reads one line from one or more crops of it (the most confident reading wins), then a
     * sharpened second try if still unsure, then word correction.
     */
    fun recognize(crops: List<Bitmap>, retryBlurry: Boolean = true, correct: Boolean = true): LineRead {
        var raw = recognizeRaw(crops[0])
        var crop = crops[0]
        for (k in 1 until crops.size) {
            val other = recognizeRaw(crops[k])
            if (other.confidence > raw.confidence) { raw = other; crop = crops[k] }
        }
        if (retryBlurry && raw.confidence < 0.8f) {
            val sharp = ImageTensors.sharpenForRetry(crop)
            val second = recognizeRaw(sharp)
            sharp.recycle()
            if (second.confidence > raw.confidence) raw = second
        }
        val plain = raw.chars.joinToString("") { it.s }
        val text = if (correct && corrector != null && raw.chars.isNotEmpty()) {
            try { corrector.correctLine(raw.chars, raw.probs, keyIndex) } catch (_: Throwable) { plain }
        } else plain
        return LineRead(text.trim(), raw.confidence)
    }

    /** True when the orientation classifier is confident a line is upside down. */
    fun isUpsideDown(crop: Bitmap): Boolean {
        val input = ImageTensors.recognition(crop, 192)
        OnnxTensor.createTensor(env, input, longArrayOf(1, 3, 48, 192)).use { t ->
            cls.run(mapOf(clsInput to t)).use { res ->
                val p = FloatArray(2)
                (res.get(0) as OnnxTensor).floatBuffer.get(p)
                return p[1] > 0.9f
            }
        }
    }

    // ---------------------------------------------------------------- full page

    /**
     * Reads a whole image.
     * @param limit       longest side used for detection (documents 1600, live 960)
     * @param orient      fix sideways / upside-down pages (whole-page majority vote)
     * @param adaptive    re-detect at higher detail when the print is very small
     * @param retryBlurry second pass with sharpening for low-confidence lines
     * @param correct     fix low-confidence words with the dictionary
     * @param secondCrop  also read each line from a tighter crop and keep the more confident reading
     * @param onProgress  called (on a background thread) with the paragraphs read so far,
     *                    after each printed line is finished, top to bottom
     */
    fun read(
        source: Bitmap,
        limit: Int = 1600,
        orient: Boolean = true,
        adaptive: Boolean = true,
        retryBlurry: Boolean = true,
        correct: Boolean = true,
        secondCrop: Boolean = true,
        onProgress: ((List<String>) -> Unit)? = null,
    ): Result {
        val start = System.currentTimeMillis()
        var img = source
        var boxes = detect(img, limit)
        if (boxes.isEmpty()) return Result(emptyList(), emptyList(), System.currentTimeMillis() - start, 0)

        if (orient) {
            // Sideways page: most lines run up/down. Turn the image 90 degrees clockwise and look again.
            val tall = boxes.count { isVertical(it) }
            if (tall > boxes.size - tall) {
                img = ImageTensors.rotate(img, 90)
                boxes = detect(img, limit)
            }
            // Upside down: ask the classifier about the longest lines and take a majority vote.
            val sample = boxes.sortedByDescending { max(it.w, it.h) }.take(9)
            var votes = 0
            for (b in sample) {
                val crop = crop(img, b)
                votes += if (isUpsideDown(crop)) 1 else -1
                crop.recycle()
            }
            if (votes > 0) {
                val w = img.width.toFloat(); val h = img.height.toFloat()
                val turned = ImageTensors.rotate(img, 180)
                if (img !== source) img.recycle()
                img = turned
                // Same boxes, turned with the page: no need to search the page again.
                boxes = boxes.map { it.copy(cx = w - it.cx, cy = h - it.cy, angle = it.angle + PI.toFloat()) }
            }
        }

        if (adaptive && boxes.isNotEmpty()) {
            val r = min(1f, limit / max(img.width, img.height).toFloat())
            val heights = boxes.map { min(it.w, it.h) * r }.sorted()
            if (heights[heights.size / 2] < 16f && max(img.width, img.height) > limit) {
                val more = detect(img, min(max(img.width, img.height), (limit * 1.6f).toInt()))
                if (more.size >= boxes.size) boxes = more
            }
        }
        val detectMs = System.currentTimeMillis() - start

        // Recognise every line in parallel, queued in reading order so the top finishes first.
        val layout = ReadingOrder.layout(boxes)
        val page = img
        val futures = HashMap<Int, Future<LineRead?>>()
        for (row in layout.rows) for (i in row.boxes) {
            futures[i] = pool.submit(Callable {
                try {
                    // Read each line twice: as found, and cropped 15% tighter top and bottom.
                    // Keeping the more confident reading removed about a quarter of the errors
                    // on small, unusual print in testing.
                    val b = boxes[i]
                    val crops = if (secondCrop) listOf(crop(page, b), crop(page, tighter(b))) else listOf(crop(page, b))
                    val r = recognize(crops, retryBlurry, correct)
                    crops.forEach { it.recycle() }
                    r
                } catch (_: Throwable) { null }
            })
        }
        val lines = ArrayList<Line>()
        val rowTexts = ArrayList<String>()
        for (row in layout.rows) {
            val parts = ArrayList<String>()
            for (i in row.boxes) {
                val r = futures[i]?.get() ?: continue
                if (r.text.isNotEmpty() && r.confidence >= 0.5f) {
                    lines.add(Line(r.text, boxes[i], r.confidence))
                    parts.add(r.text)
                }
            }
            rowTexts.add(parts.joinToString(" "))
            onProgress?.invoke(ReadingOrder.paragraphs(layout, rowTexts, ::isKnownWord))
        }
        if (img !== source) img.recycle()
        val total = System.currentTimeMillis() - start
        return Result(lines, ReadingOrder.paragraphs(layout, rowTexts, ::isKnownWord), detectMs, total - detectMs)
    }

    /** The same line box, 15% less tall (the short side). */
    private fun tighter(b: TextBox): TextBox = if (b.w >= b.h) b.copy(h = b.h * 0.85f) else b.copy(w = b.w * 0.85f)

    private fun isKnownWord(w: String): Boolean = corrector?.isKnownWord(w) ?: false

    private fun isVertical(b: TextBox): Boolean {
        val dir = if (b.w >= b.h) b.angle else b.angle + (PI / 2).toFloat()
        val long = max(b.w, b.h); val short = min(b.w, b.h)
        return abs(sin(dir)) > 0.7f && long > 2 * short
    }

    /** Straightens one rotated text line into its own upright bitmap. */
    fun crop(img: Bitmap, b: TextBox): Bitmap {
        var ux = cos(b.angle); var uy = sin(b.angle)
        var w = b.w; var h = b.h
        if (h > w) { val nx = -uy; val ny = ux; ux = nx; uy = ny; val t = w; w = h; h = t }
        if (ux < -1e-3f || (abs(ux) <= 1e-3f && uy < 0)) { ux = -ux; uy = -uy }
        return ImageTensors.affineCrop(img, b.cx, b.cy, w, h, ux, uy)
    }

    override fun close() {
        pool.shutdown()
        det.close(); cls.close()
        synchronized(recSessions) { recSessions.forEach { runCatching { it.close() } } }
    }
}
