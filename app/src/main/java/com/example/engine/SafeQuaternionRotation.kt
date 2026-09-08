package com.example.engine

import kotlin.math.sqrt
import kotlin.math.cos
import kotlin.math.sin

/**
 * Thread-safe Quaternion-based rotation system.
 * Eliminates gimbal lock and snapping behavior from Euler angle rotations.
 * Used for 360° unconstrained model rotation in Object/AR/MR modes.
 */
data class SafeQuaternion(
  val x: Float = 0f,
  val y: Float = 0f,
  val z: Float = 0f,
  val w: Float = 1f
) {
  fun normalize(): SafeQuaternion {
    val len = sqrt(x * x + y * y + z * z + w * w)
    return if (len > 0f) {
      SafeQuaternion(x / len, y / len, z / len, w / len)
    } else {
      SafeQuaternion(0f, 0f, 0f, 1f)
    }
  }

  fun conjugate(): SafeQuaternion = SafeQuaternion(-x, -y, -z, w)

  fun multiply(other: SafeQuaternion): SafeQuaternion {
    return SafeQuaternion(
      x = w * other.x + x * other.w + y * other.z - z * other.y,
      y = w * other.y - x * other.z + y * other.w + z * other.x,
      z = w * other.z + x * other.y - y * other.x + z * other.w,
      w = w * other.w - x * other.x - y * other.y - z * other.z
    ).normalize()
  }

  fun slerp(other: SafeQuaternion, t: Float): SafeQuaternion {
    val clampedT = t.coerceIn(0f, 1f)
    var dot = x * other.x + y * other.y + z * other.z + w * other.w
    var targetQuat = other

    if (dot < 0f) {
      targetQuat = SafeQuaternion(-other.x, -other.y, -other.z, -other.w)
      dot = -dot
    }

    val dotClamped = dot.coerceIn(-1f, 1f)
    val theta0 = kotlin.math.acos(dotClamped.toDouble()).toFloat()
    val theta = theta0 * clampedT

    val q2 = SafeQuaternion(
      targetQuat.x - w * dot,
      targetQuat.y - y * dot,
      targetQuat.z - z * dot,
      targetQuat.w - w * dot
    ).normalize()

    val sinTheta = sin(theta.toDouble()).toFloat()
    val cosTheta = cos(theta.toDouble()).toFloat()

    return SafeQuaternion(
      x = w * sinTheta + q2.x * cosTheta,
      y = y * sinTheta + q2.y * cosTheta,
      z = z * sinTheta + q2.z * cosTheta,
      w = w * cosTheta - (x * q2.x + y * q2.y + z * q2.z)
    ).normalize()
  }

  fun toEulerAngles(): FloatArray {
    val roll = kotlin.math.atan2(2 * (w * x + y * z), 1 - 2 * (x * x + y * y)).toFloat()
    val pitch = kotlin.math.asin(2 * (w * y - z * x)).toFloat()
    val yaw = kotlin.math.atan2(2 * (w * z + x * y), 1 - 2 * (y * y + z * z)).toFloat()
    return floatArrayOf(
      Math.toDegrees(roll.toDouble()).toFloat(),
      Math.toDegrees(pitch.toDouble()).toFloat(),
      Math.toDegrees(yaw.toDouble()).toFloat()
    )
  }
}

companion object QuaternionOps {
  fun fromAxisAngle(axis: FloatArray, angleDegrees: Float): SafeQuaternion {
    val angleRad = Math.toRadians(angleDegrees.toDouble()).toFloat()
    val halfAngle = angleRad / 2f
    val sinHalf = sin(halfAngle.toDouble()).toFloat()
    return SafeQuaternion(
      x = axis[0] * sinHalf,
      y = axis[1] * sinHalf,
      z = axis[2] * sinHalf,
      w = cos(halfAngle.toDouble()).toFloat()
    ).normalize()
  }
}

/**
 * Thread-safe rotation state manager for unconstrained 360° rotation.
 */
class RotationStateManager {
  @Volatile
  private var currentRotation = SafeQuaternion()
  
  @Volatile
  private var targetRotation = SafeQuaternion()
  
  private val lock = Any()
  private var lastUpdateTimeNs = 0L

  fun rotateAroundAxis(axisDegrees: FloatArray, deltaDegrees: Float) {
    synchronized(lock) {
      val deltaQuat = QuaternionOps.fromAxisAngle(axisDegrees, deltaDegrees)
      currentRotation = currentRotation.multiply(deltaQuat)
      targetRotation = currentRotation
    }
  }

  fun setTargetRotation(quat: SafeQuaternion) {
    synchronized(lock) {
      targetRotation = quat.normalize()
    }
  }

  fun getCurrentRotation(): SafeQuaternion {
    synchronized(lock) {
      val nowNs = System.nanoTime()
      val dtSec = ((nowNs - lastUpdateTimeNs) / 1_000_000_000.0).toFloat().coerceIn(0f, 0.033f)
      lastUpdateTimeNs = nowNs
      
      // Smooth interpolation toward target
      if (currentRotation != targetRotation) {
        currentRotation = currentRotation.slerp(targetRotation, dtSec * 3f)
      }
      return currentRotation
    }
  }

  fun getEulerAngles(): FloatArray {
    return getCurrentRotation().toEulerAngles()
  }

  fun reset() {
    synchronized(lock) {
      currentRotation = SafeQuaternion()
      targetRotation = SafeQuaternion()
      lastUpdateTimeNs = System.nanoTime()
    }
  }
}
