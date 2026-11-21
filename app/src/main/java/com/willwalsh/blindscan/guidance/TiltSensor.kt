package com.willwalsh.blindscan.guidance

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Motion sensing for scanning by hand:
 *  - tilt: how far the phone is from lying flat (gravity sensor),
 *  - steadiness: how long the phone has been still (gyroscope), so pictures are taken
 *    in a steady moment, and how much it moved while a picture was being taken.
 */
class TiltSensor(context: Context) : SensorEventListener {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gravity: Sensor? = sm.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyro: Sensor? = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    @Volatile var tiltDegrees: Float? = null
        private set

    @Volatile private var lastShakeAt = 0L
    @Volatile private var peak = 0f

    /** True when the phone has a gyroscope (otherwise steadiness can't be measured). */
    val canSenseMotion: Boolean get() = gyro != null

    fun start() {
        gravity?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        gyro?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() { sm.unregisterListener(this) }

    /** Milliseconds since the phone last moved noticeably. */
    fun stillForMs(): Long = if (gyro == null) Long.MAX_VALUE else System.currentTimeMillis() - lastShakeAt

    /** Start measuring the strongest movement (call just before taking a picture). */
    fun resetPeak() { peak = 0f }

    /** Strongest rotation speed since [resetPeak], in radians per second. */
    fun peakMotion(): Float = peak

    override fun onSensorChanged(e: SensorEvent) {
        val x = e.values[0]; val y = e.values[1]; val z = e.values[2]
        if (e.sensor.type == Sensor.TYPE_GYROSCOPE) {
            val w = sqrt(x * x + y * y + z * z)
            peak = max(peak, w)
            if (w > STILL_LIMIT) lastShakeAt = System.currentTimeMillis()
            return
        }
        val g = sqrt(x * x + y * y + z * z)
        if (g < 1f) return
        tiltDegrees = Math.toDegrees(acos((abs(z) / g).coerceIn(0f, 1f).toDouble())).toFloat()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    companion object {
        /** Rotation speed (rad/s) below which the phone counts as still: a steady hand, not a stand. */
        const val STILL_LIMIT = 0.12f
        /** Movement during a picture above this means it may be blurred: take another. */
        const val RETAKE_LIMIT = 0.25f
    }
}
