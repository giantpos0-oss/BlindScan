package com.willwalsh.blindscan.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
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
 *  SMALL = best accuracy, used for document scans.   (0.15% character errors on our hard test set)
 *  TINY  = about 4x faster, used for live mode.      (0.8% character errors on the same set)
 */
class OcrEngine(context: Context, val model: Model) : AutoCloseable {

    enum class Model(val det: String, val rec: String, val dict: String) {
        SMALL("models/det_small.onnx", "models/rec_small.onnx", "models/dict_small.txt"),
        TINY("models/det_tiny.onnx", "models/rec_tiny.onnx", "models/dict_tiny.txt"),
    }

    data class Line(val text: String, val box: TextBox, val confidence: Float)

    data class Result(val lines: List<Line>, val paragraphs: List<String>) {
        val text: String get() = paragraphs.joinToString("\n\n")
        val isEmpty: Boolean get() = paragraphs.isEmpty()
    }

    private val env = OrtEnvironment.getEnvironment()
    private val options = OrtSession.SessionOptions().apply {
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(2, 4))
    }
    private val det: OrtSession = env.createSession(context.assets.open(model.det).use { it.readBytes() }, options)
    private val rec: OrtSession = env.createSession(context.assets.open(model.rec).use { it.readBytes() }, options)
    private val cls: OrtSession = env.createSession(context.assets.open("models/cls.onnx").use { it.readBytes() }, options)
    // CTC classes: 0 = blank, 1..n = dictionary, n+1 = space.
    private val keys: List<String> =
        listOf("") + context.assets.open(model.dict).bufferedReader().readLines() + listOf(" ")

    private val detInput = det.inputNames.first()
    private val recInput = rec.inputNames.first()
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

    /** Reads one cropped line. Returns text and mean character confidence. */
    fun recognize(crop: Bitmap): Pair<String, Float> {
        val width = max(320, ceil(48f * crop.width / crop.height).toInt())
        val input = ImageTensors.recognition(crop, width)
        OnnxTensor.createTensor(env, input, longArrayOf(1, 3, 48, width.toLong())).use { t ->
            rec.run(mapOf(recInput to t)).use { res ->
                val out = res.get(0) as OnnxTensor
                val shape = out.info.shape // [1, T, C]
                val steps = shape[1].toInt(); val classes = shape[2].toInt()
                val buf = out.floatBuffer
                val sb = StringBuilder()
                var prev = 0; var confSum = 0f; var confN = 0
                for (t2 in 0 until steps) {
                    var best = 0; var bestP = -1f
                    val base = t2 * classes
                    for (c in 0 until classes) {
                        val p = buf.get(base + c)
                        if (p > bestP) { bestP = p; best = c }
                    }
                    if (best != 0 && best != prev) {
                        if (best < keys.size) sb.append(keys[best])
                        confSum += bestP; confN++
                    }
                    prev = best
                }
                return Pair(sb.toString().trim(), if (confN == 0) 0f else confSum / confN)
            }
        }
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
     */
    fun read(
        source: Bitmap,
        limit: Int = 1600,
        orient: Boolean = true,
        adaptive: Boolean = true,
        retryBlurry: Boolean = true,
    ): Result {
        var img = source
        var boxes = detect(img, limit)
        if (boxes.isEmpty()) return Result(emptyList(), emptyList())

        if (orient) {
            // Sideways page: most lines run up/down. Turn the image 90 degrees clockwise.
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
                img = ImageTensors.rotate(img, 180)
                boxes = detect(img, limit)
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

        val lines = ArrayList<Line>()
        for (b in boxes) {
            val crop = crop(img, b)
            var (text, conf) = recognize(crop)
            if (retryBlurry && conf < 0.8f) {
                val sharp = ImageTensors.sharpenForRetry(crop)
                val (t2, c2) = recognize(sharp)
                sharp.recycle()
                if (c2 > conf) { text = t2; conf = c2 }
            }
            crop.recycle()
            if (text.isNotEmpty() && conf >= 0.5f) lines.add(Line(text, b, conf))
        }
        if (img !== source) img.recycle()
        return Result(lines, ReadingOrder.paragraphs(lines))
    }

    private fun isVertical(b: TextBox): Boolean {
        val dir = if (b.w >= b.h) b.angle else b.angle + (Math.PI / 2).toFloat()
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
        det.close(); rec.close(); cls.close(); options.close()
    }
}
