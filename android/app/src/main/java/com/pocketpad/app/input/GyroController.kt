package com.pocketpad.app.input

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Tilt / Gyroscope controller for landscape gaming (steering wheel or gyro aiming).
 * Uses TYPE_GAME_ROTATION_VECTOR with automatic fallback to TYPE_ACCELEROMETER.
 */
class GyroController(
    context: Context,
    private val onTiltChanged: (steerX: Float, aimY: Float) -> Unit,
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val rotSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
    private val accelSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private var running = false
    private var baseRoll = 0f
    private var basePitch = 0f
    private var hasBaseline = false
    private var lastRawRoll = 0f
    private var lastRawPitch = 0f

    var sensitivity: Float = 1.4f

    fun start() {
        if (running) return
        val sm = sensorManager ?: return
        val target = rotSensor ?: accelSensor ?: return
        hasBaseline = false
        sm.registerListener(this, target, SensorManager.SENSOR_DELAY_GAME)
        running = true
    }

    fun stop() {
        if (!running) return
        sensorManager?.unregisterListener(this)
        running = false
        onTiltChanged(0f, 0f)
    }

    fun recalibrate() {
        baseRoll = lastRawRoll
        basePitch = lastRawPitch
        hasBaseline = true
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_GAME_ROTATION_VECTOR) {
            val rotMatrix = FloatArray(9)
            val orientation = FloatArray(3)
            SensorManager.getRotationMatrixFromVector(rotMatrix, event.values)
            SensorManager.getOrientation(rotMatrix, orientation)
            // In landscape orientation: pitch (orientation[1]) is steering left/right, roll (orientation[2]) is tilt up/down
            lastRawRoll = -orientation[1]
            lastRawPitch = orientation[2]
        } else {
            val ax = event.values[0]
            val ay = event.values[1]
            val az = event.values[2]
            lastRawRoll = atan2(ay.toDouble(), sqrt((ax * ax + az * az).toDouble())).toFloat()
            lastRawPitch = atan2(ax.toDouble(), az.toDouble()).toFloat()
        }

        if (!hasBaseline) {
            baseRoll = lastRawRoll
            basePitch = lastRawPitch
            hasBaseline = true
        }

        val deltaSteer = (lastRawRoll - baseRoll) * sensitivity * 1.45f
        val deltaPitch = (lastRawPitch - basePitch) * sensitivity * 1.45f

        val clampedX = deltaSteer.coerceIn(-1f, 1f)
        val clampedY = deltaPitch.coerceIn(-1f, 1f)

        // Small deadzone to keep steering rock-steady straight ahead
        val outX = if (kotlin.math.abs(clampedX) < 0.05f) 0f else clampedX
        val outY = if (kotlin.math.abs(clampedY) < 0.05f) 0f else clampedY
        onTiltChanged(outX, outY)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
