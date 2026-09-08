package com.example.renderer

import android.view.MotionEvent
import android.view.ScaleGestureDetector
import com.example.engine.TwoFingerRotateDetector
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantReadWriteLock

/**
 * Thread-safe touch event handler preventing race conditions between UI and render threads.
 * Synchronizes gesture processing with atomic operations and read-write locks.
 */
class ThreadSafeTouchHandler(
  val onRotate: (deltaDegrees: Float) -> Unit,
  val onScale: (scaleFactor: Float) -> Unit,
  val onDrag: (deltaX: Float, deltaY: Float) -> Unit,
  val onTap: (x: Float, y: Float) -> Unit
) {
  private val rotateDetector: TwoFingerRotateDetector
  private val scaleDetector: ScaleGestureDetector
  
  private val activePointerCountAtomic = AtomicInteger(0)
  private val touchStateLock = ReentrantReadWriteLock()
  
  private var lastTouchX = 0f
  private var lastTouchY = 0f
  private var touchStartX = 0f
  private var touchStartY = 0f
  private var touchStartTimeMs = 0L
  private var lastMidX = 0f
  private var lastMidY = 0f

  init {
    rotateDetector = TwoFingerRotateDetector { delta ->
      onRotate(delta)
    }
    
    scaleDetector = ScaleGestureDetector(
      android.content.Context(),
      object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
          onScale(detector.scaleFactor)
          return true
        }
      }
    )
  }

  fun onTouchEvent(event: MotionEvent): Boolean {
    return touchStateLock.writeLock().run {
      try {
        // Safely update active pointer count BEFORE gesture processing
        val newPointerCount = event.pointerCount
        activePointerCountAtomic.set(newPointerCount)
        
        rotateDetector.onTouchEvent(event)
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
          MotionEvent.ACTION_DOWN -> {
            lastTouchX = event.x
            lastTouchY = event.y
            touchStartX = event.x
            touchStartY = event.y
            touchStartTimeMs = System.currentTimeMillis()
            true
          }

          MotionEvent.ACTION_POINTER_DOWN -> {
            if (newPointerCount >= 2) {
              lastMidX = (event.getX(0) + event.getX(1)) * 0.5f
              lastMidY = (event.getY(0) + event.getY(1)) * 0.5f
            }
            true
          }

          MotionEvent.ACTION_MOVE -> {
            when (newPointerCount) {
              1 -> {
                val dx = event.x - lastTouchX
                val dy = event.y - lastTouchY
                onDrag(dx, dy)
                lastTouchX = event.x
                lastTouchY = event.y
              }
              2 -> {
                if (!scaleDetector.isInProgress && !rotateDetector.isActivelyTwisting) {
                  val midX = (event.getX(0) + event.getX(1)) * 0.5f
                  val midY = (event.getY(0) + event.getY(1)) * 0.5f
                  val dMidX = midX - lastMidX
                  val dMidY = midY - lastMidY
                  onDrag(dMidX, dMidY)
                  lastMidX = midX
                  lastMidY = midY
                }
              }
            }
            true
          }

          MotionEvent.ACTION_UP -> {
            val duration = System.currentTimeMillis() - touchStartTimeMs
            val movedDist = kotlin.math.abs(event.x - touchStartX) + kotlin.math.abs(event.y - touchStartY)
            if (movedDist < 30 && duration < 200) {
              onTap(event.x, event.y)
            }
            activePointerCountAtomic.set(0)
            true
          }

          MotionEvent.ACTION_POINTER_UP -> {
            val upIndex = event.actionIndex
            val remainCount = newPointerCount - 1
            if (remainCount == 1) {
              val remainIndex = if (upIndex == 0) 1 else 0
              if (remainIndex < newPointerCount) {
                lastTouchX = event.getX(remainIndex)
                lastTouchY = event.getY(remainIndex)
                touchStartX = lastTouchX
                touchStartY = lastTouchY
              }
            } else if (remainCount == 2) {
              var sumX = 0f
              var sumY = 0f
              for (i in 0 until newPointerCount) {
                if (i != upIndex) {
                  sumX += event.getX(i)
                  sumY += event.getY(i)
                }
              }
              lastMidX = sumX * 0.5f
              lastMidY = sumY * 0.5f
            }
            true
          }

          MotionEvent.ACTION_CANCEL -> {
            activePointerCountAtomic.set(0)
            true
          }

          else -> false
        }
      } finally {
        touchStateLock.writeLock().unlock()
      }
    }
  }

  fun getActivePointerCount(): Int {
    return activePointerCountAtomic.get()
  }
}
