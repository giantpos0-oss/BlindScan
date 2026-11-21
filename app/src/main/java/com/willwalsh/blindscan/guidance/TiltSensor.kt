package com.willwalsh.blindscan.guidance

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/** How far the phone is from lying flat (screen up or down), in degrees. */
class TiltSensor(context: Context) : SensorEventListener {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    @Volatile
    var tiltDegrees: Float? = null
        private set

    fun start() { sensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) } }
    fun stop() { sm.unregisterListener(this) }

    override fun onSensorChanged(e: SensorEvent) {
        val (x, y, z) = Triple(e.values[0], e.values[1], e.values[2])
        val g = sqrt(x * x + y * y + z * z)
        if (g < 1f) return
        tiltDegrees = Math.toDegrees(acos((abs(z) / g).coerceIn(0f, 1f).toDouble())).toFloat()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
