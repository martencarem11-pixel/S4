package com.example.arcore

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.ar.core.Anchor
import com.google.ar.core.Config
import com.google.ar.core.Earth
import com.google.ar.core.GeospatialPose
import com.google.ar.core.Session
import com.google.ar.core.StreetscapeGeometry
import com.google.ar.core.TrackingState
import com.google.ar.core.VpsAvailability

/**
 * Production-Grade Google ARCore Geospatial, VPS & Earth Localization Manager.
 * 1. Configures Earth VPS localization and checks availability worldwide.
 * 2. Checks fine location permissions before initializing Earth.
 * 3. Accurately reports separate Earth Tracking State and VPS Availability.
 * 4. Provides WGS84 global latitude, longitude, altitude, and heading pose.
 * 5. Anchors 3D models to real-world global geographic coordinates (Terrain & Geospatial anchors).
 * 6. Tracks Streetscape Geometry (buildings, terrain meshes) for outdoor occlusion and physics.
 */
data class GeospatialStatus(
  val isSupported: Boolean = false,
  val isEnabled: Boolean = false,
  val locationPermissionGranted: Boolean = false,
  val earthState: String = "DISABLED",
  val trackingState: String = "STOPPED",
  val vpsAvailability: String = "UNKNOWN",
  val isVpsLocalized: Boolean = false,
  val latitude: Double = 0.0,
  val longitude: Double = 0.0,
  val altitudeMeters: Double = 0.0,
  val headingDegrees: Double = 0.0,
  val horizontalAccuracyMeters: Double = 0.0,
  val verticalAccuracyMeters: Double = 0.0,
  val headingAccuracyDegrees: Double = 0.0,
  val streetscapeGeometriesCount: Int = 0,
  val guidanceMessage: String = "Geospatial VPS Initializing"
) {
  val isAccuracySufficientForPlacement: Boolean
    get() = trackingState == "TRACKING" &&
            horizontalAccuracyMeters > 0.0 && horizontalAccuracyMeters <= 12.0 &&
            verticalAccuracyMeters > 0.0 && verticalAccuracyMeters <= 15.0
}

class ArCoreGeospatialManager {

  companion object {
    private const val TAG = "ArCoreGeospatialManager"
    private const val VPS_CHECK_INTERVAL_MS = 4000L
  }

  var status: GeospatialStatus = GeospatialStatus()
    private set

  private val geospatialAnchors = mutableListOf<Anchor>()
  private var lastVpsCheckMs: Long = 0L
  private var isVpsCheckPending: Boolean = false

  /**
   * Configures Geospatial mode in ARCore Session configuration.
   * Checks ACCESS_FINE_LOCATION before enabling Geospatial mode.
   */
  fun configureGeospatialMode(context: Context, session: Session, config: Config): Boolean {
    val hasFineLocation = ContextCompat.checkSelfPermission(
      context,
      Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    if (!hasFineLocation) {
      config.geospatialMode = Config.GeospatialMode.DISABLED
      status = status.copy(
        isSupported = false,
        locationPermissionGranted = false,
        earthState = "LOCATION_PERMISSION_DENIED",
        vpsAvailability = "UNAVAILABLE"
      )
      Log.w(TAG, "Geospatial API requires ACCESS_FINE_LOCATION permission. Kept disabled.")
      return false
    }

    return try {
      if (session.isGeospatialModeSupported(Config.GeospatialMode.ENABLED)) {
        config.geospatialMode = Config.GeospatialMode.ENABLED
        config.streetscapeGeometryMode = Config.StreetscapeGeometryMode.ENABLED
        status = status.copy(
          isSupported = true,
          isEnabled = true,
          locationPermissionGranted = true,
          earthState = "ENABLED"
        )
        Log.i(TAG, "ARCore Geospatial API & Streetscape Geometry configured.")
        true
      } else {
        config.geospatialMode = Config.GeospatialMode.DISABLED
        status = status.copy(
          isSupported = false,
          isEnabled = false,
          locationPermissionGranted = true,
          earthState = "UNSUPPORTED"
        )
        Log.i(TAG, "ARCore Geospatial mode unsupported on this device.")
        false
      }
    } catch (e: Exception) {
      Log.w(TAG, "Geospatial configuration skipped: ${e.message}")
      false
    }
  }

  /**
   * Updates Earth localization and VPS telemetry from current ARCore session.
   * Separates Earth Tracking State from VPS Availability.
   */
  fun updateGeospatialState(session: Session?) {
    if (session == null) {
      status = status.copy(
        earthState = "SESSION_UNAVAILABLE",
        trackingState = "STOPPED",
        vpsAvailability = "UNAVAILABLE",
        guidanceMessage = "ARCore session unavailable"
      )
      return
    }
    val earth = session.earth
    if (earth == null) {
      status = status.copy(
        earthState = "SESSION_UNAVAILABLE",
        trackingState = "STOPPED",
        vpsAvailability = "UNAVAILABLE",
        guidanceMessage = "ARCore Earth tracking unavailable"
      )
      return
    }
    try {
      val earthTracking = earth.trackingState
      val trackingName = earthTracking.name

      if (earthTracking == TrackingState.TRACKING) {
        val cameraGeospatialPose: GeospatialPose = earth.cameraGeospatialPose

        // Count active streetscape building/terrain geometries
        val streetscapeCount = try {
          session.getAllTrackables(StreetscapeGeometry::class.java).size
        } catch (_: Throwable) { 0 }

        // Query real VPS availability asynchronously without blocking or making false assumptions
        val now = System.currentTimeMillis()
        if (!isVpsCheckPending && (now - lastVpsCheckMs > VPS_CHECK_INTERVAL_MS)) {
          isVpsCheckPending = true
          lastVpsCheckMs = now
          try {
            session.checkVpsAvailabilityAsync(
              cameraGeospatialPose.latitude,
              cameraGeospatialPose.longitude
            ) { availability ->
              isVpsCheckPending = false
              val isLocalized = (availability == VpsAvailability.AVAILABLE) && (cameraGeospatialPose.horizontalAccuracy in 0.01..5.0)
              status = status.copy(
                vpsAvailability = availability.name,
                isVpsLocalized = isLocalized
              )
            }
          } catch (e: Exception) {
            isVpsCheckPending = false
            status = status.copy(vpsAvailability = "ERROR_NETWORK", isVpsLocalized = false)
          }
        }

        val isLocalized = (status.vpsAvailability == "AVAILABLE") && (cameraGeospatialPose.horizontalAccuracy in 0.01..5.0)

        val guidance = when {
          isLocalized -> "VPS localized with high-accuracy pose"
          cameraGeospatialPose.horizontalAccuracy in 0.01..5.0 -> "High accuracy GPS pose; querying VPS streetscape"
          cameraGeospatialPose.horizontalAccuracy in 5.01..15.0 -> "Refining VPS: Point camera at buildings or landmarks"
          else -> "Localizing Earth pose: Scan physical surroundings"
        }

        status = status.copy(
          isSupported = true,
          isEnabled = true,
          earthState = "EARTH_TRACKING",
          trackingState = trackingName,
          isVpsLocalized = isLocalized,
          latitude = cameraGeospatialPose.latitude,
          longitude = cameraGeospatialPose.longitude,
          altitudeMeters = cameraGeospatialPose.altitude,
          headingDegrees = cameraGeospatialPose.heading,
          horizontalAccuracyMeters = cameraGeospatialPose.horizontalAccuracy,
          verticalAccuracyMeters = cameraGeospatialPose.verticalAccuracy,
          headingAccuracyDegrees = cameraGeospatialPose.headingAccuracy,
          streetscapeGeometriesCount = streetscapeCount,
          guidanceMessage = guidance
        )
      } else {
        val guidance = when (earthTracking) {
          TrackingState.PAUSED -> "Earth tracking paused - move device slowly across surroundings"
          TrackingState.STOPPED -> "Earth tracking stopped"
          else -> "Geospatial VPS initializing"
        }
        status = status.copy(
          trackingState = trackingName,
          isVpsLocalized = false,
          earthState = if (earthTracking == TrackingState.PAUSED) "EARTH_PAUSED" else "EARTH_STOPPED",
          guidanceMessage = guidance
        )
      }
    } catch (e: Exception) {
      Log.d(TAG, "Geospatial update transient: ${e.message}")
    }
  }

  /**
   * Creates a Geospatial anchor pinned to exact WGS84 real-world Earth coordinates.
   */
  fun createGeospatialAnchor(
    session: Session,
    latitude: Double,
    longitude: Double,
    altitudeMeters: Double,
    headingDegrees: Double
  ): Anchor? {
    val earth = session.earth ?: return null
    if (earth.trackingState != TrackingState.TRACKING) {
      Log.w(TAG, "Cannot create Geospatial anchor: Earth is not yet in TRACKING state.")
      return null
    }

    return try {
      // Calculate rotation quaternion for given heading
      val halfRad = Math.toRadians(headingDegrees / 2.0)
      val qy = Math.sin(halfRad).toFloat()
      val qw = Math.cos(halfRad).toFloat()

      val anchor = earth.createAnchor(
        latitude,
        longitude,
        altitudeMeters,
        0f, qy, 0f, qw
      )
      geospatialAnchors.add(anchor)
      Log.i(TAG, "Created Earth Geospatial Anchor at ($latitude, $longitude, alt=$altitudeMeters m)")
      anchor
    } catch (e: Exception) {
      Log.e(TAG, "Failed creating Geospatial anchor: ${e.message}")
      null
    }
  }

  /**
   * Resolves a Geospatial Terrain Anchor asynchronously.
   */
  fun createTerrainAnchorAsync(
    session: Session,
    latitude: Double,
    longitude: Double,
    altitudeAboveTerrainMeters: Double,
    headingDegrees: Double,
    onComplete: (Anchor?, Anchor.TerrainAnchorState) -> Unit
  ) {
    val earth = session.earth ?: run {
      onComplete(null, Anchor.TerrainAnchorState.ERROR_NOT_AUTHORIZED)
      return
    }
    if (earth.trackingState != TrackingState.TRACKING) {
      Log.w(TAG, "Cannot create terrain anchor: Earth is not yet in TRACKING state.")
      onComplete(null, Anchor.TerrainAnchorState.TASK_IN_PROGRESS)
      return
    }
    try {
      val halfRad = Math.toRadians(headingDegrees / 2.0)
      val qy = Math.sin(halfRad).toFloat()
      val qw = Math.cos(halfRad).toFloat()
      earth.resolveAnchorOnTerrainAsync(
        latitude,
        longitude,
        altitudeAboveTerrainMeters,
        0f, qy, 0f, qw
      ) { anchor, state ->
        if (state == Anchor.TerrainAnchorState.SUCCESS && anchor != null) {
          geospatialAnchors.add(anchor)
          Log.i(TAG, "Resolved Terrain Anchor at ($latitude, $longitude)")
        }
        onComplete(anchor, state)
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed resolving terrain anchor: ${e.message}")
      onComplete(null, Anchor.TerrainAnchorState.ERROR_INTERNAL)
    }
  }

  /**
   * Checks VPS availability for given location asynchronously.
   */
  fun checkVpsAvailability(session: Session, latitude: Double, longitude: Double, onResult: (Boolean) -> Unit) {
    try {
      session.checkVpsAvailabilityAsync(latitude, longitude) { availability ->
        val isVpsOk = (availability == VpsAvailability.AVAILABLE)
        status = status.copy(vpsAvailability = availability.name)
        onResult(isVpsOk)
      }
    } catch (e: Exception) {
      Log.w(TAG, "VPS check failed: ${e.message}")
      onResult(false)
    }
  }

  fun clearAnchors() {
    geospatialAnchors.forEach { it.detach() }
    geospatialAnchors.clear()
  }

  fun clear() {
    clearAnchors()
    status = GeospatialStatus(
      isSupported = status.isSupported,
      isEnabled = status.isEnabled,
      locationPermissionGranted = status.locationPermissionGranted
    )
  }
}
