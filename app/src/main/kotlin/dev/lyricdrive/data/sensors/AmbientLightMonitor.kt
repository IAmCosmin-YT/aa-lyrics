package dev.lyricdrive.data.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AmbientLevel { DAY, DIM, NIGHT }

/**
 * Phone ambient-light sensor → day / dim (tunnel, overcast) / night, smoothed and with
 * hysteresis so it doesn't flicker under streetlights. Reference-counted: the sensor is only
 * registered while some screen is showing lyrics.
 */
class AmbientLightMonitor(context: Context) : SensorEventListener {
    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT)

    private val _level = MutableStateFlow<AmbientLevel?>(null)
    /** null = no sensor or no reading yet. */
    val level: StateFlow<AmbientLevel?> = _level.asStateFlow()

    private var users = 0
    private var smoothedLux = -1f

    fun acquire() {
        if (sensor == null) return
        if (users++ == 0) sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
    }

    fun release() {
        if (sensor == null || users == 0) return
        if (--users == 0) sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val lux = event.values.firstOrNull() ?: return
        smoothedLux = if (smoothedLux < 0) lux else smoothedLux * 0.85f + lux * 0.15f
        val current = _level.value
        _level.value = when (current) {
            AmbientLevel.NIGHT -> if (smoothedLux > 20f) (if (smoothedLux > 250f) AmbientLevel.DAY else AmbientLevel.DIM) else AmbientLevel.NIGHT
            AmbientLevel.DIM -> when {
                smoothedLux < 8f -> AmbientLevel.NIGHT
                smoothedLux > 250f -> AmbientLevel.DAY
                else -> AmbientLevel.DIM
            }
            else -> when {
                smoothedLux < 8f -> AmbientLevel.NIGHT
                smoothedLux < 150f -> AmbientLevel.DIM
                else -> AmbientLevel.DAY
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
