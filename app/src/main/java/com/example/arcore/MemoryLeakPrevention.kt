package com.example.arcore

import android.util.Log
import com.google.ar.core.Session
import com.google.ar.core.Anchor
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Memory leak prevention utilities for ARCore and Filament resources.
 * Ensures proper cleanup of anchors, meshes, and GLB asset data.
 */
class MemoryLeakPrevention {
  companion object {
    private const val TAG = "MemoryLeakPrevention"
  }

  /**
   * Safely detach and clear all anchors without dangling references.
   */
  fun safelyClearAnchors(anchors: CopyOnWriteArrayList<Anchor>) {
    val iterator = anchors.iterator()
    while (iterator.hasNext()) {
      try {
        val anchor = iterator.next()
        anchor.detach()
        iterator.remove()
        Log.d(TAG, "Anchor safely detached and removed")
      } catch (e: Exception) {
        Log.w(TAG, "Error detaching anchor: ${e.message}")
      }
    }
    anchors.clear()
  }

  /**
   * Safely close ARCore session and release all associated resources.
   */
  fun safelyCloseSession(session: Session?) {
    try {
      if (session != null && !session.isClosed) {
        session.close()
        Log.d(TAG, "ARCore session safely closed")
      }
    } catch (e: Exception) {
      Log.w(TAG, "Error closing ARCore session: ${e.message}")
    }
  }

  /**
   * Clear mesh data without memory leaks.
   */
  fun safelyClearMeshData(meshDataMap: MutableMap<String, Any>) {
    val iterator = meshDataMap.entries.iterator()
    while (iterator.hasNext()) {
      try {
        val (_, meshData) = iterator.next()
        // If mesh has native resources, release them
        if (meshData is AutoCloseable) {
          meshData.close()
        }
        iterator.remove()
      } catch (e: Exception) {
        Log.w(TAG, "Error clearing mesh data: ${e.message}")
      }
    }
    meshDataMap.clear()
  }

  /**
   * Release GLB asset buffers immediately after loading to prevent accumulation.
   */
  fun releaseGlbBuffer(buffer: java.nio.ByteBuffer?) {
    try {
      buffer?.clear()
      Log.d(TAG, "GLB buffer released")
    } catch (e: Exception) {
      Log.w(TAG, "Error releasing GLB buffer: ${e.message}")
    }
  }
}
