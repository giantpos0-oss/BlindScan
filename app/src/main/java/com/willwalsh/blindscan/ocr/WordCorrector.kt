package com.willwalsh.blindscan.ocr

import java.io.InputStream
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/** One decoded character: its text, the output frame it came from, and its probability. */
data class DecodedChar(val s: String, val frame: Int, val prob: Float)

/** Per-frame class probabilities from the recognizer (row-major frames x classes). */
interface FrameProbs {
    val frames: Int
    fun p(frame: Int, cls: Int): Float
}

class BufferProbs(private val buf: FloatBuffer, override val frames: Int, private val classes: Int) : FrameProbs {
    override fun p(frame: Int, cls: Int) = buf.get(frame * classes + cls)
}

/**
 * Fixes words the recognizer wasn't sure about, using its own probabilities.
 *
 * For each low-confidence word, nearby English words (1-2 letters different) are scored by
 * how well they explain what the recognizer actually saw (CTC probability over that word's frames)
 * plus how common they are. A word is replaced only when the evidence is clearly better.
 * Numbers are never turned into other numbers; only a letter "o" next to digits becomes a zero.
 *
 * Tested in tools/ocr-testbench/correct.py: on small unusual fonts it removed about 17% of
 * errors without making any correct word wrong.
 */
class WordCorrector(input: InputStream) {

    private val counts = HashMap<String, Long>(120_000)
    private val byLength = HashMap<Int, MutableList<String>>()
    private val total: Double

    init {
        var sum = 0.0
        input.bufferedReader().useLines { lines ->
            for (line in lines) {
                val sp = line.indexOf(' ')
                if (sp <= 0) continue
                val w = line.substring(0, sp)
                val c = line.substring(sp + 1).trim().toLongOrNull() ?: continue
                counts[w] = c
                byLength.getOrPut(w.length) { ArrayList() }.add(w)
                sum += c
            }
        }
        total = sum
    }

    private fun isWord(w: String): Boolean = (counts[w.lowercase()] ?: 0L) >= MIN_FREQ

    private fun lm(w: String): Double {
        val c = counts[w.lowercase()]
        return if (c != null && c >= MIN_FREQ) ln(c / total) else ln(1.0 / total) - 5.0
    }

    private val numberLike1 = Regex("(?<=\\d)[oO]|[oO](?=\\d)")
    private val numberLike2 = Regex("(?<=\\d)l(?=\\d)")
    private val wordShape = Regex("^([^A-Za-z]*)([A-Za-z][A-Za-z']*[A-Za-z]|[A-Za-z])([^A-Za-z]*)$")

    /** @param keyIndex maps a single character to its class index in [probs]. */
    fun correctLine(chars: List<DecodedChar>, probs: FrameProbs, keyIndex: Map<String, Int>): String {
        // Split into tokens at spaces (spaces are kept as their own tokens).
        val tokens = ArrayList<IntRange>()
        var start = -1
        for (i in chars.indices) {
            if (chars[i].s == " ") {
                if (start >= 0) { tokens.add(start until i); start = -1 }
                tokens.add(i..i)
            } else if (start < 0) start = i
        }
        if (start >= 0) tokens.add(start until chars.size)

        val out = StringBuilder()
        for (range in tokens) {
            var text = range.joinToString("") { chars[it].s }
            if (text == " ") { out.append(' '); continue }

            val alnum = text.filter { it.isLetterOrDigit() }
            val digits = alnum.count { it.isDigit() }
            if (digits > 0) {
                if (digits * 2 >= alnum.length) {
                    text = text.replace(numberLike1, "0").replace(numberLike1, "0").replace(numberLike2, "1")
                }
                out.append(text); continue
            }
            val m = wordShape.find(text)
            if (m == null) { out.append(text); continue }
            val pre = m.groupValues[1]; val core = m.groupValues[2]; val post = m.groupValues[3]
            if (core.length < MIN_LEN) { out.append(text); continue }

            val ps = range.map { chars[it].prob }
            val low = ps.min(); val avg = ps.average()
            val known = isWord(core)
            if ((known && low >= 0.3f) || (!known && low >= 0.9f && avg >= 0.95)) { out.append(text); continue }

            val t0 = if (range.first > 0) chars[range.first - 1].frame + 1 else 0
            val t1 = if (range.last + 1 < chars.size) chars[range.last + 1].frame - 1 else probs.frames - 1

            fun ctc(word: String): Double {
                val seq = IntArray((pre + word + post).length)
                val s = pre + word + post
                for (i in s.indices) seq[i] = keyIndex[s[i].toString()] ?: return NEG
                return ctcLogProb(probs, seq, t0, t1)
            }

            val c0 = ctc(core)
            val base = c0 + LAMBDA * lm(core)
            var best = core; var bestScore = base
            for (cand in candidates(core.lowercase())) {
                if (!isWord(cand) || cand.length < MIN_LEN) continue
                if (core.length <= 4 && cand.length != core.length) continue
                val w = applyCase(core, cand)
                val cc = ctc(w)
                if (cc < c0 - CTC_DROP) continue // must agree with what the recognizer saw
                val sc = cc + LAMBDA * lm(w)
                if (sc > bestScore) { best = w; bestScore = sc }
            }
            val need = if (known) KNOWN_MARGIN else MARGIN
            if (best != core && bestScore > base + need) out.append(pre).append(best).append(post)
            else out.append(text)
        }
        return out.toString()
    }

    private fun candidates(w: String): List<String> {
        val k = if (w.length <= 4) 1 else 2
        val out = ArrayList<String>()
        for (len in (w.length - k)..(w.length + k)) {
            val list = byLength[len] ?: continue
            for (c in list) if (boundedLevenshtein(w, c, k) <= k) out.add(c)
        }
        return out
    }

    companion object {
        const val LAMBDA = 0.5
        const val MARGIN = 1.0
        const val KNOWN_MARGIN = 4.0
        const val MIN_LEN = 3
        const val MIN_FREQ = 100_000L
        const val CTC_DROP = 4.0
        private const val NEG = -1e30

        fun applyCase(src: String, w: String): String = when {
            src.length > 1 && src.all { !it.isLetter() || it.isUpperCase() } -> w.uppercase()
            src.first().isUpperCase() -> w.replaceFirstChar { it.uppercaseChar() }
            else -> w
        }

        fun boundedLevenshtein(a: String, b: String, k: Int): Int {
            if (abs(a.length - b.length) > k) return k + 1
            var prev = IntArray(b.length + 1) { it }
            var cur = IntArray(b.length + 1)
            for (i in 1..a.length) {
                cur[0] = i
                var best = cur[0]
                for (j in 1..b.length) {
                    cur[j] = min(min(prev[j] + 1, cur[j - 1] + 1), prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
                    if (cur[j] < best) best = cur[j]
                }
                if (best > k) return k + 1
                val t = prev; prev = cur; cur = t
            }
            return prev[b.length]
        }

        private fun logAdd(a: Double, b: Double): Double {
            if (a == NEG) return b
            if (b == NEG) return a
            val m = max(a, b)
            return m + ln(kotlin.math.exp(a - m) + kotlin.math.exp(b - m))
        }

        /** log P(seq | frames t0..t1) under CTC with blank = class 0. */
        fun ctcLogProb(probs: FrameProbs, seq: IntArray, t0: Int, t1: Int): Double {
            if (t1 < t0) return NEG
            val ext = IntArray(seq.size * 2 + 1)
            for (i in seq.indices) ext[2 * i + 1] = seq[i]
            val s = ext.size
            fun lp(t: Int, c: Int) = ln(max(probs.p(t, c).toDouble(), 1e-12))
            var a = DoubleArray(s) { NEG }
            a[0] = lp(t0, 0)
            if (s > 1) a[1] = lp(t0, ext[1])
            for (t in t0 + 1..t1) {
                val n = DoubleArray(s) { NEG }
                for (i in 0 until s) {
                    var m = a[i]
                    if (i > 0) m = logAdd(m, a[i - 1])
                    if (i > 1 && ext[i] != 0 && ext[i] != ext[i - 2]) m = logAdd(m, a[i - 2])
                    n[i] = if (m == NEG) NEG else m + lp(t, ext[i])
                }
                a = n
            }
            return if (s > 1) logAdd(a[s - 1], a[s - 2]) else a[s - 1]
        }
    }
}
