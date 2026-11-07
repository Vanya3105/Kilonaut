package com.example.gymtracker.wear

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat

/**
 * The watch's own heart rate, for the rest screen (seeing it come down says when you're ready) and
 * the overview. The sensor runs only while the app is on screen during a workout: it costs battery.
 */
class HeartRate(private val context: Context) : SensorEventListener {
    /** Beats per minute; null until the sensor has a pulse, and off the wrist. */
    var bpm by mutableStateOf<Int?>(null)
        private set

    private var visible = false
    private var workout = false
    private var listening = false

    fun setVisible(on: Boolean) { visible = on; update() }
    fun setWorkout(on: Boolean) { workout = on; update() }
    fun permissionChanged() = update()

    private fun update() {
        val should = visible && workout && granted(context)
        if (should == listening) return
        val sensors = context.getSystemService(SensorManager::class.java) ?: return
        if (should) {
            val sensor = sensors.getDefaultSensor(Sensor.TYPE_HEART_RATE) ?: return
            listening = sensors.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        } else {
            sensors.unregisterListener(this)
            listening = false
            bpm = null
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val value = event.values.firstOrNull()?.toInt() ?: return
        // 0 while it's still finding the pulse.
        bpm = if (value in 30..240 && event.accuracy != SensorManager.SENSOR_STATUS_NO_CONTACT) value else null
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        if (accuracy == SensorManager.SENSOR_STATUS_NO_CONTACT) bpm = null
    }

    companion object {
        val PERMISSION: String =
            if (Build.VERSION.SDK_INT >= 36) "android.permission.health.READ_HEART_RATE" else Manifest.permission.BODY_SENSORS

        fun granted(context: Context) =
            ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED
    }
}
