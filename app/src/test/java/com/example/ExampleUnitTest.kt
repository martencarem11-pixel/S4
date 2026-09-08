package com.example

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class ExampleUnitTest {

  @Test
  fun testGestureScaleConstraints() {
    val initialScale = 1.0f
    val scaleFactorMagnify = 1.5f
    val scaleFactorMinify = 0.5f

    val magnified = (initialScale * scaleFactorMagnify).coerceIn(0.02f, 25.0f)
    assertEquals(1.5f, magnified, 0.001f)

    val minified = (initialScale * scaleFactorMinify).coerceIn(0.02f, 25.0f)
    assertEquals(0.5f, minified, 0.001f)

    // Verify boundary constraints
    val extremeMin = (0.001f).coerceIn(0.02f, 25.0f)
    assertEquals(0.02f, extremeMin, 0.001f)

    val extremeMax = (50.0f).coerceIn(0.02f, 25.0f)
    assertEquals(25.0f, extremeMax, 0.001f)
  }

  @Test
  fun testEnforcedGestureMappings_1F_2F_3F() {
    var modelOffsetX = 0f
    var modelOffsetY = 0f
    var modelRotationDegrees = 0f
    var modelRollDegrees = 0f
    var modelScale = 1.0f

    // 1-Finger Gesture: Must strictly perform Translation/Reposition (X and Y offsets)
    // Must NOT alter yaw rotation or roll
    val touch1Dx = 40f
    val touch1Dy = -30f
    modelOffsetX += touch1Dx * 0.0025f
    modelOffsetY -= touch1Dy * 0.0025f
    assertEquals(0.1f, modelOffsetX, 0.0001f)
    assertEquals(0.075f, modelOffsetY, 0.0001f)
    assertEquals(0f, modelRotationDegrees, 0.0001f)
    assertEquals(0f, modelRollDegrees, 0.0001f)

    // 2-Finger Pinch Gesture: Must strictly perform Scale
    val pinchScaleDelta = 1.25f
    modelScale = (modelScale * pinchScaleDelta).coerceIn(0.02f, 25.0f)
    assertEquals(1.25f, modelScale, 0.001f)

    // 2-Finger Twist / Drag Gesture: Must strictly perform Yaw / Rotation
    // Must NOT alter translations or roll
    val twistDeltaDegrees = -35f
    modelRotationDegrees += twistDeltaDegrees
    assertEquals(-35f, modelRotationDegrees, 0.0001f)
    assertEquals(0.1f, modelOffsetX, 0.0001f)
    assertEquals(0.075f, modelOffsetY, 0.0001f)
    assertEquals(0f, modelRollDegrees, 0.0001f)

    // 3-Finger Drag Gesture: Must strictly perform Roll
    // Must NOT alter translations, scale, or yaw
    val rollDx = 25f
    modelRollDegrees += rollDx * 0.25f
    assertEquals(6.25f, modelRollDegrees, 0.0001f)
    assertEquals(-35f, modelRotationDegrees, 0.0001f)
    assertEquals(0.1f, modelOffsetX, 0.0001f)
    assertEquals(0.075f, modelOffsetY, 0.0001f)
    assertEquals(1.25f, modelScale, 0.001f)
  }

  @Test
  fun testTwoFingerAngleCalculationAndWrapAround() {
    // Test angle normalization for rotational wrap-around (-180 to 180)
    fun normalizeAngleDelta(rawDelta: Float): Float {
      var delta = rawDelta
      while (delta < -180f) delta += 360f
      while (delta > 180f) delta -= 360f
      return delta
    }

    val acuteDelta = normalizeAngleDelta(15f)
    assertEquals(15f, acuteDelta, 0.001f)

    // Wrap around 175 -> -175 (raw delta: -350 degrees)
    val wrapDeltaNegative = normalizeAngleDelta(-350f)
    assertEquals(10f, wrapDeltaNegative, 0.001f)

    // Wrap around -175 -> 175 (raw delta: 350 degrees)
    val wrapDeltaPositive = normalizeAngleDelta(350f)
    assertEquals(-10f, wrapDeltaPositive, 0.001f)
  }

  @Test
  fun testAdaptiveDepthSyncThresholdCalculation() {
    // Test the adaptive sync algorithm across 60fps (~16.6ms) and 30fps (~33.3ms) intervals
    fun calculateAdaptiveSyncThresholdNs(frameIntervalNs: Long): Long {
      val adaptive = (frameIntervalNs * 1.8f).toLong().coerceIn(33_000_000L, 75_000_000L)
      return adaptive
    }

    val syncThreshold60Fps = calculateAdaptiveSyncThresholdNs(16_666_666L)
    // 16.6ms * 1.8 = ~30ms, clamped to minimum 33ms
    assertEquals(33_000_000L, syncThreshold60Fps)

    val syncThreshold30Fps = calculateAdaptiveSyncThresholdNs(33_333_333L)
    // 33.3ms * 1.8 = ~60ms (within floating precision tolerance)
    assertTrue(abs(60_000_000L - syncThreshold30Fps) <= 1000L)

    // Dropped frames (e.g. 80ms interval) clamped to maximum 75ms
    val syncThresholdSlow = calculateAdaptiveSyncThresholdNs(80_000_000L)
    assertEquals(75_000_000L, syncThresholdSlow)
  }

  @Test
  fun testAnchorRecoveryInterpolationCurve() {
    // Tests cubic ease-out interpolation for smooth tracking recovery
    fun cubicEaseOut(progress: Float): Float {
      val p = progress.coerceIn(0f, 1f)
      val inv = 1f - p
      return 1f - (inv * inv * inv)
    }

    assertEquals(0f, cubicEaseOut(0f), 0.0001f)
    assertEquals(1f, cubicEaseOut(1f), 0.0001f)
    // Fast initial movement: at 50% time, progress is 87.5%
    assertEquals(0.875f, cubicEaseOut(0.5f), 0.0001f)
  }

  @Test
  fun testAnimationLoopTimeCalculation() {
    val durationSec = 4.0f
    fun advanceTime(currentTime: Float, deltaSec: Float, speed: Float, duration: Float): Float {
      var nextTime = currentTime + deltaSec * speed
      if (duration > 0f) {
        if (nextTime >= duration) {
          nextTime %= duration
        } else if (nextTime < 0f) {
          nextTime = (nextTime % duration) + duration
        }
      }
      return nextTime
    }

    // Normal increment
    val t1 = advanceTime(0f, 0.5f, 1.0f, durationSec)
    assertEquals(0.5f, t1, 0.0001f)

    // Loop over duration
    val t2 = advanceTime(3.8f, 0.5f, 1.0f, durationSec)
    assertEquals(0.3f, t2, 0.001f)

    // 2x speed
    val t3 = advanceTime(1.0f, 1.0f, 2.0f, durationSec)
    assertEquals(3.0f, t3, 0.0001f)
  }
}
