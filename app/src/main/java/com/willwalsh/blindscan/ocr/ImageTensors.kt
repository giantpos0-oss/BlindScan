package com.willwalsh.blindscan.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Bitmap <-> tensor helpers. Channel order is B, G, R because the PaddleOCR models were trained
 * on OpenCV (BGR) images; the tested Python reference does the same.
 */
object ImageTensors {

    private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f) // applied to B, G, R in that order
    private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)

    fun softwareCopy(b: Bitmap): Bitmap =
        if (b.config == Bitmap.Config.ARGB_8888) b else b.copy(Bitmap.Config.ARGB_8888, false)

    /** Detector input: (x/255 - mean) / std, CHW, BGR. */
    fun detection(bitmap: Bitmap): FloatBuffer {
        val b = softwareCopy(bitmap)
        val w = b.width; val h = b.height; val plane = w * h
        val px = IntArray(plane)
        b.getPixels(px, 0, w, 0, 0, w, h)
        if (b !== bitmap) b.recycle()
        val buf = FloatBuffer.allocate(3 * plane)
        for (i in 0 until plane) {
            val p = px[i]
            val r = (p shr 16 and 0xFF) / 255f
            val g = (p shr 8 and 0xFF) / 255f
            val bl = (p and 0xFF) / 255f
            buf.put(i, (bl - MEAN[0]) / STD[0])
            buf.put(plane + i, (g - MEAN[1]) / STD[1])
            buf.put(2 * plane + i, (r - MEAN[2]) / STD[2])
        }
        buf.rewind()
        return buf
    }

    /**
     * Recognizer / classifier input: height 48, text scaled to keep its shape,
     * right side zero-padded up to [width]. Values (x/255 - 0.5) / 0.5, CHW, BGR.
     */
    fun recognition(crop: Bitmap, width: Int): FloatBuffer {
        val h = 48
        val rw = min(width, max(1, ceil(h.toFloat() * crop.width / crop.height).toInt()))
        val scaled = Bitmap.createScaledBitmap(softwareCopy(crop), rw, h, true)
        val px = IntArray(rw * h)
        scaled.getPixels(px, 0, rw, 0, 0, rw, h)
        if (scaled !== crop) scaled.recycle()
        val plane = h * width
        val buf = FloatBuffer.allocate(3 * plane) // zero padded
        for (y in 0 until h) for (x in 0 until rw) {
            val p = px[y * rw + x]
            val o = y * width + x
            buf.put(o, (p and 0xFF) / 127.5f - 1f)
            buf.put(plane + o, (p shr 8 and 0xFF) / 127.5f - 1f)
            buf.put(2 * plane + o, (p shr 16 and 0xFF) / 127.5f - 1f)
        }
        buf.rewind()
        return buf
    }

    /** Rotates clockwise by 90, 180 or 270 degrees. */
    fun rotate(b: Bitmap, degrees: Int): Bitmap {
        if (degrees % 360 == 0) return b
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
    }

    /**
     * Copies the rotated rectangle (centre cx,cy; size w x h; long axis along unit vector ux,uy)
     * into an upright bitmap. Destination x runs along u, y along v = u turned 90 degrees.
     */
    fun affineCrop(src: Bitmap, cx: Float, cy: Float, w: Float, h: Float, ux: Float, uy: Float): Bitmap {
        val vx = -uy; val vy = ux
        val ox = cx - ux * w / 2 - vx * h / 2
        val oy = cy - uy * w / 2 - vy * h / 2
        val outW = max(1, w.roundToInt()); val outH = max(1, h.roundToInt())
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val m = Matrix()
        m.setValues(
            floatArrayOf(
                ux, uy, -(ox * ux + oy * uy),
                vx, vy, -(ox * vx + oy * vy),
                0f, 0f, 1f,
            )
        )
        val canvas = Canvas(out)
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
    }

    /**
     * For blurry lines: scale to the recognizer's 48 px height, then unsharp mask
     * (gaussian sigma 4, amount 1.5). Tested in the Python reference; only used when it
     * raises the recognizer's confidence.
     */
    fun sharpenForRetry(crop: Bitmap): Bitmap {
        val h = 48
        val w = max(1, crop.width * h / max(1, crop.height))
        val up = Bitmap.createScaledBitmap(softwareCopy(crop), w, h, true)
        val px = IntArray(w * h)
        up.getPixels(px, 0, w, 0, 0, w, h)
        if (up !== crop) up.recycle()

        val sigma = 4f; val radius = 12
        val kernel = FloatArray(radius * 2 + 1) { i -> val d = (i - radius).toFloat(); exp(-d * d / (2 * sigma * sigma)) }
        val ks = kernel.sum(); for (i in kernel.indices) kernel[i] /= ks

        val out = IntArray(w * h)
        val chan = FloatArray(w * h); val tmp = FloatArray(w * h); val blur = FloatArray(w * h)
        val result = Array(3) { FloatArray(w * h) }
        for (c in 0 until 3) {
            val shift = c * 8
            for (i in px.indices) chan[i] = (px[i] shr shift and 0xFF).toFloat()
            for (y in 0 until h) for (x in 0 until w) {           // horizontal pass
                var s = 0f
                for (k in -radius..radius) s += chan[y * w + (x + k).coerceIn(0, w - 1)] * kernel[k + radius]
                tmp[y * w + x] = s
            }
            for (y in 0 until h) for (x in 0 until w) {           // vertical pass
                var s = 0f
                for (k in -radius..radius) s += tmp[(y + k).coerceIn(0, h - 1) * w + x] * kernel[k + radius]
                blur[y * w + x] = s
            }
            for (i in chan.indices) result[c][i] = (chan[i] + 1.5f * (chan[i] - blur[i])).coerceIn(0f, 255f)
        }
        for (i in out.indices) {
            out[i] = (0xFF shl 24) or
                (result[2][i].toInt() shl 16) or (result[1][i].toInt() shl 8) or result[0][i].toInt()
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }
}
