package com.example.engine

import android.view.MotionEvent
import kotlin.math.atan2

/**
 * Custom Two-Finger Twist / Rotation gesture detector.
 * Computes angular rotation delta between two touch pointers in degrees.
 * Strictly tracks pointer IDs to avoid index jumping when fingers lift or extra fingers touch.
 */
class TwoFingerRotateDetector(
  private val onRotateListener: (deltaDegrees: Float) -> Unit
) {

  private var pointerId1 = MotionEvent.INVALID_POINTER_ID
  private var pointerId2 = MotionEvent.INVALID_POINTER_ID
  private var initialAngle: Float = 0f
  private var isRotating: Boolean = false
  var isActivelyTwisting: Boolean = false
    private set

  fun onTouchEvent(event: MotionEvent): Boolean {
    when (event.actionMasked) {
      MotionEvent.ACTION_DOWN -> {
        pointerId1 = event.getPointerId(0)
        pointerId2 = MotionEvent.INVALID_POINTER_ID
        isRotating = false
        isActivelyTwisting = false
      }

      MotionEvent.ACTION_POINTER_DOWN -> {
        if (event.pointerCount >= 2 && !isRotating) {
          pointerId1 = event.getPointerId(0)
          pointerId2 = event.getPointerId(1)
          initialAngle = calculateAngle(event, pointerId1, pointerId2)
          isRotating = true
          isActivelyTwisting = false
        }
      }

      MotionEvent.ACTION_MOVE -> {
        if (isRotating && event.pointerCount >= 2) {
          val idx1 = event.findPointerIndex(pointerId1)
          val idx2 = event.findPointerIndex(pointerId2)
          if (idx1 >= 0 && idx2 >= 0) {
            val currentAngle = calculateAngle(event, pointerId1, pointerId2)
            var delta = currentAngle - initialAngle

            // Normalize delta to [-180, 180]
            if (delta > 180f) delta -= 360f
            if (delta < -180f) delta += 360f

            if (Math.abs(delta) > 0.3f) {
              onRotateListener(delta)
              initialAngle = currentAngle
              isActivelyTwisting = Math.abs(delta) > 0.8f
            } else {
              isActivelyTwisting = false
            }
          } else {
            // Pointers changed, re-bind if at least 2 pointers exist
            pointerId1 = event.getPointerId(0)
            pointerId2 = event.getPointerId(1)
            initialAngle = calculateAngle(event, pointerId1, pointerId2)
          }
        }
      }

      MotionEvent.ACTION_POINTER_UP -> {
        val actionIndex = event.actionIndex
        val releasedPointerId = event.getPointerId(actionIndex)
        if (releasedPointerId == pointerId1 || releasedPointerId == pointerId2) {
          val remainingCount = event.pointerCount - 1
          if (remainingCount >= 2) {
            var newId1 = MotionEvent.INVALID_POINTER_ID
            var newId2 = MotionEvent.INVALID_POINTER_ID
            for (i in 0 until event.pointerCount) {
              if (i != actionIndex) {
                if (newId1 == MotionEvent.INVALID_POINTER_ID) {
                  newId1 = event.getPointerId(i)
                } else if (newId2 == MotionEvent.INVALID_POINTER_ID) {
                  newId2 = event.getPointerId(i)
                  break
                }
              }
            }
            pointerId1 = newId1
            pointerId2 = newId2
            initialAngle = calculateAngle(event, pointerId1, pointerId2)
            isRotating = true
            isActivelyTwisting = false
          } else {
            isRotating = false
            isActivelyTwisting = false
            pointerId1 = MotionEvent.INVALID_POINTER_ID
            pointerId2 = MotionEvent.INVALID_POINTER_ID
          }
        }
      }

      MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> {
        isRotating = false
        isActivelyTwisting = false
        pointerId1 = MotionEvent.INVALID_POINTER_ID
        pointerId2 = MotionEvent.INVALID_POINTER_ID
      }
    }
    return isRotating
  }

  private fun calculateAngle(event: MotionEvent, id1: Int, id2: Int): Float {
    val idx1 = event.findPointerIndex(id1)
    val idx2 = event.findPointerIndex(id2)
    if (idx1 < 0 || idx2 < 0) return 0f
    val dx = event.getX(idx2) - event.getX(idx1)
    val dy = event.getY(idx2) - event.getY(idx1)
    val radians = atan2(dy.toDouble(), dx.toDouble())
    return Math.toDegrees(radians).toFloat()
  }
}
