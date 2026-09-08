package com.example.engine

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

class SensorsManager(
  context: Context,
  private val onOrientationChange: (pitch: Float, roll: Float, yaw: Float) -> Unit
) : SensorEventListener {

  private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
  private val rotationSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
  private val rotationMatrix = FloatArray(9)
  private val orientationAngles = FloatArray(3)

  fun start() {
    rotationSensor?.let {
      sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
    }
  }

  fun stop() {
    sensorManager?.unregisterListener(this)
  }

  override fun onSensorChanged(event: SensorEvent?) {
    if (event?.sensor?.type == Sensor.TYPE_ROTATION_VECTOR) {
      SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
      SensorManager.getOrientation(rotationMatrix, orientationAngles)

      val yaw = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
      val pitch = Math.toDegrees(orientationAngles[1].toDouble()).toFloat()
      val roll = Math.toDegrees(orientationAngles[2].toDouble()).toFloat()

      onOrientationChange(pitch, roll, yaw)
    }
  }

  override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
