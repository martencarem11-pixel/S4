package com.example.arcore

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.ar.core.Anchor
import com.google.ar.core.Config
import com.google.ar.core.Pose
import com.google.ar.core.Session
import java.util.UUID

/**
 * Cloud Anchor Lifecycle State Machine according to official Google ARCore specification.
 */
enum class CloudAnchorLifecycleState {
  NOT_HOSTED,
  HOSTING_IN_PROGRESS,
  HOSTED_SUCCESS,
  NOT_RESOLVED,
  RESOLVING_IN_PROGRESS,
  RESOLVED_SUCCESS,
  ERROR_NOT_AUTHORIZED,
  ERROR_RESOURCE_EXHAUSTED,
  ERROR_HOSTING_DATASET_PROCESSING_FAILED,
  ERROR_CLOUD_ANCHORS_NOT_CONFIGURED,
  ERROR_LOCALIZATION_FAILED,
  ERROR_TIMEOUT,
  ERROR_CANCELLED,
  ERROR_SDK_UNSUPPORTED
}

/**
 * Distinguishes local vs remote origins of a resolved Cloud Anchor.
 */
enum class CloudAnchorResolutionSource {
  LOCAL_DEVICE_HOSTED,
  LOCAL_DEVICE_CACHE,
  CROSS_DEVICE_REMOTE_RESOLVED
}

/**
 * Complete lifecycle verification states for cross-device Cloud Anchor sharing:
 * Device A (Host) -> Cloud Anchor ID -> Share ID -> Device B (Client) -> Confirmed Resolve.
 */
enum class CloudAnchorCrossDeviceState {
  LOCAL_ONLY,
  HOSTED_DEVICE_A,
  SHARED_ID_GENERATED,
  RESOLVING_DEVICE_B,
  CONFIRMED_CROSS_DEVICE_RESOLVED,
  CROSS_DEVICE_RESOLUTION_FAILED
}

/**
 * Shared Spatial AR State synchronized between host and client devices (Decoupled Multiplayer Layer).
 * NOTE: Cloud Anchors provide shared spatial persistence across devices.
 * Cloud Anchor hosting/resolving alone is not a complete multiplayer system.
 * Full multiplayer requires an active real-time backend transport (e.g. WebSockets or Firebase).
 */
data class SharedSpatialExhibit(
  val sessionRoomIdentifier: String,
  val exhibitIdentifier: String,
  val cloudAnchorId: String,
  val modelId: String,
  val position: FloatArray,
  val rotation: FloatArray,
  val scale: Float,
  val hostDeviceId: String = "${android.os.Build.MANUFACTURER}_${android.os.Build.MODEL}",
  val hostClientIdentity: String = hostDeviceId,
  val isHost: Boolean = true,
  val syncTimestampMs: Long = System.currentTimeMillis(),
  val syncVersion: Long = 1L,
  val isRealtimeBackendConnected: Boolean = false
) {
  // Aliases for seamless backward compatibility
  val sessionCode: String get() = sessionRoomIdentifier
  val poseTranslation: FloatArray get() = position
  val poseRotation: FloatArray get() = rotation
  val modelScale: Float get() = scale
  val timestamp: Long get() = syncTimestampMs
  val version: Long get() = syncVersion
  val isMultiplayerActive: Boolean get() = isRealtimeBackendConnected

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false
    other as SharedSpatialExhibit
    return sessionRoomIdentifier == other.sessionRoomIdentifier &&
           exhibitIdentifier == other.exhibitIdentifier &&
           cloudAnchorId == other.cloudAnchorId &&
           modelId == other.modelId
  }

  override fun hashCode(): Int {
    var result = sessionRoomIdentifier.hashCode()
    result = 31 * result + exhibitIdentifier.hashCode()
    result = 31 * result + cloudAnchorId.hashCode()
    result = 31 * result + modelId.hashCode()
    return result
  }
}

data class CloudAnchorRecord(
  val cloudAnchorId: String,
  val anchor: Anchor?,
  val state: CloudAnchorLifecycleState,
  val errorMessage: String? = null,
  val hostTimeMs: Long = System.currentTimeMillis(),
  val ttlDays: Int = 1,
  val retryCount: Int = 0
)

/**
 * Production-Grade ARCore Cloud Anchors Manager.
 * Features:
 * 1. Full lifecycle state tracking (NOT_HOSTED -> HOSTING -> HOSTED / ERROR).
 * 2. Timeout protection and explicit cancellation.
 * 3. Granular error reporting.
 * 4. Standalone operation decoupled from multiplayer sessions.
 */
class CloudAnchorManager(context: Context? = null) {

  companion object {
    private const val TAG = "CloudAnchorManager"
    private const val DEFAULT_TIMEOUT_MS = 30000L
    private const val PREFS_NAME = "arcore_cloud_anchors_cache"
  }

  private val mainHandler = Handler(Looper.getMainLooper())
  private val sharedPrefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
  private val activeRecords = mutableMapOf<String, CloudAnchorRecord>()
  private val pendingTimeouts = mutableMapOf<String, Runnable>()

  var activeSharedExhibit: SharedSpatialExhibit? = null
    private set

  var isCloudAnchorModeSupported: Boolean = false
    private set
  var isConfigured: Boolean = false
    private set
  var unsupportedReason: String? = null
    private set
  var isRealtimeBackendConnected: Boolean = false
    private set
  var isCrossDeviceValidated: Boolean = false
    private set
  var crossDeviceState: CloudAnchorCrossDeviceState = CloudAnchorCrossDeviceState.LOCAL_ONLY
    private set
  var resolutionSource: CloudAnchorResolutionSource? = null
    private set

  val isMultiplayerActive: Boolean
    get() = isRealtimeBackendConnected && activeSharedExhibit != null

  fun setRealtimeBackendConnected(connected: Boolean) {
    isRealtimeBackendConnected = connected
    activeSharedExhibit?.let {
      activeSharedExhibit = it.copy(isRealtimeBackendConnected = connected)
    }
  }

  /**
   * Configures CloudAnchorMode on Session config.
   * When Cloud Anchors are requested and supported, configure:
   * Config.CloudAnchorMode.ENABLED before calling Session.configure().
   * If unsupported, gracefully disable the feature and expose the real reason.
   */
  fun configureCloudAnchorMode(session: Session, config: Config): Boolean {
    return try {
      val testConfig = Config(session)
      testConfig.cloudAnchorMode = Config.CloudAnchorMode.ENABLED
      if (session.isSupported(testConfig)) {
        config.cloudAnchorMode = Config.CloudAnchorMode.ENABLED
        isCloudAnchorModeSupported = true
        isConfigured = true
        unsupportedReason = null
        Log.i(TAG, "ARCore Cloud Anchors configured and ENABLED.")
        true
      } else {
        config.cloudAnchorMode = Config.CloudAnchorMode.DISABLED
        isCloudAnchorModeSupported = false
        isConfigured = false
        unsupportedReason = "CloudAnchorMode.ENABLED is not supported on this device or ARCore APK version."
        Log.w(TAG, unsupportedReason!!)
        false
      }
    } catch (e: Exception) {
      config.cloudAnchorMode = Config.CloudAnchorMode.DISABLED
      isCloudAnchorModeSupported = false
      isConfigured = false
      unsupportedReason = e.message ?: "Failed to check CloudAnchorMode support."
      Log.w(TAG, "Failed configuring Cloud Anchors: ${e.message}")
      false
    }
  }

  /**
   * Only successfully hosted or resolved anchors qualify as active Cloud Anchors.
   * Pending operations and local-only anchors are strictly separated.
   */
  val cloudAnchorsCount: Int
    get() = activeRecords.values.count {
      it.state == CloudAnchorLifecycleState.HOSTED_SUCCESS ||
      it.state == CloudAnchorLifecycleState.RESOLVED_SUCCESS
    }

  val pendingCloudAnchorsCount: Int
    get() = activeRecords.values.count {
      it.state == CloudAnchorLifecycleState.HOSTING_IN_PROGRESS ||
      it.state == CloudAnchorLifecycleState.RESOLVING_IN_PROGRESS
    }

  val totalRecordsCount: Int
    get() = activeRecords.size

  fun clear() {
    clearAll()
  }

  /**
   * Standalone Cloud Anchor Hosting.
   * Works purely with the local anchor without requiring multiplayer or matchmaking.
   */
  fun hostCloudAnchor(
    session: Session,
    localAnchor: Anchor,
    ttlDays: Int = 1,
    timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    onStatusChange: (CloudAnchorRecord) -> Unit
  ): String {
    // Check if Cloud Anchors mode is configured in session
    if (session.config.cloudAnchorMode == Config.CloudAnchorMode.DISABLED) {
      val record = CloudAnchorRecord(
        cloudAnchorId = "unconfigured",
        anchor = localAnchor,
        state = CloudAnchorLifecycleState.ERROR_CLOUD_ANCHORS_NOT_CONFIGURED,
        errorMessage = "CloudAnchorMode is DISABLED in ARCore Session configuration."
      )
      onStatusChange(record)
      return "unconfigured"
    }

    val operationId = "host_${UUID.randomUUID()}"
    val initialRecord = CloudAnchorRecord(
      cloudAnchorId = operationId,
      anchor = localAnchor,
      state = CloudAnchorLifecycleState.HOSTING_IN_PROGRESS,
      ttlDays = ttlDays
    )
    activeRecords[operationId] = initialRecord
    onStatusChange(initialRecord)

    // Schedule timeout
    val timeoutRunnable = Runnable {
      val cur = activeRecords[operationId]
      if (cur != null && cur.state == CloudAnchorLifecycleState.HOSTING_IN_PROGRESS) {
        val timedOut = cur.copy(
          state = CloudAnchorLifecycleState.ERROR_TIMEOUT,
          errorMessage = "Cloud Anchor hosting timed out after ${timeoutMs / 1000}s."
        )
        activeRecords[operationId] = timedOut
        onStatusChange(timedOut)
        Log.w(TAG, "Hosting timed out for operation $operationId")
      }
    }
    pendingTimeouts[operationId] = timeoutRunnable
    mainHandler.postDelayed(timeoutRunnable, timeoutMs)

    try {
      session.hostCloudAnchorAsync(localAnchor, ttlDays) { cloudAnchorId, state ->
        // Cancel timeout
        pendingTimeouts.remove(operationId)?.let { mainHandler.removeCallbacks(it) }

        val stateName = state.name
        Log.i(TAG, "ARCore Cloud Anchor Host callback: state=$stateName, id=$cloudAnchorId")

        when (state) {
          Anchor.CloudAnchorState.SUCCESS -> {
            if (!cloudAnchorId.isNullOrEmpty()) {
              activeRecords.remove(operationId)
              val record = CloudAnchorRecord(
                cloudAnchorId = cloudAnchorId,
                anchor = localAnchor,
                state = CloudAnchorLifecycleState.HOSTED_SUCCESS,
                ttlDays = ttlDays
              )
              activeRecords[cloudAnchorId] = record
              crossDeviceState = CloudAnchorCrossDeviceState.HOSTED_DEVICE_A
              resolutionSource = CloudAnchorResolutionSource.LOCAL_DEVICE_HOSTED
              isCrossDeviceValidated = false
              onStatusChange(record)
            } else {
              val record = initialRecord.copy(
                state = CloudAnchorLifecycleState.ERROR_LOCALIZATION_FAILED,
                errorMessage = "Host succeeded but cloudAnchorId was null or empty."
              )
              activeRecords[operationId] = record
              onStatusChange(record)
            }
          }
          Anchor.CloudAnchorState.ERROR_NOT_AUTHORIZED -> {
            val record = initialRecord.copy(
              state = CloudAnchorLifecycleState.ERROR_NOT_AUTHORIZED,
              errorMessage = "Google Cloud API key unauthorized for ARCore Cloud Anchors."
            )
            activeRecords[operationId] = record
            onStatusChange(record)
          }
          Anchor.CloudAnchorState.ERROR_RESOURCE_EXHAUSTED -> {
            val record = initialRecord.copy(
              state = CloudAnchorLifecycleState.ERROR_RESOURCE_EXHAUSTED,
              errorMessage = "ARCore Cloud Anchor API quota exceeded."
            )
            activeRecords[operationId] = record
            onStatusChange(record)
          }
          Anchor.CloudAnchorState.ERROR_HOSTING_DATASET_PROCESSING_FAILED -> {
            val record = initialRecord.copy(
              state = CloudAnchorLifecycleState.ERROR_HOSTING_DATASET_PROCESSING_FAILED,
              errorMessage = "Insufficient visual features in environment to host Cloud Anchor."
            )
            activeRecords[operationId] = record
            onStatusChange(record)
          }
          else -> {
            val record = initialRecord.copy(
              state = CloudAnchorLifecycleState.ERROR_LOCALIZATION_FAILED,
              errorMessage = "Hosting failed: $stateName"
            )
            activeRecords[operationId] = record
            onStatusChange(record)
          }
        }
      }
    } catch (e: Exception) {
      pendingTimeouts.remove(operationId)?.let { mainHandler.removeCallbacks(it) }
      Log.e(TAG, "Exception initiating hostCloudAnchor: ${e.message}", e)
      val errRecord = initialRecord.copy(
        state = CloudAnchorLifecycleState.ERROR_SDK_UNSUPPORTED,
        errorMessage = e.message
      )
      activeRecords[operationId] = errRecord
      onStatusChange(errRecord)
    }

    return operationId
  }

  /**
   * Standalone Cloud Anchor Resolving.
   * Resolves an anchor purely from its cloudAnchorId without requiring multiplayer matchmaking.
   */
  fun resolveCloudAnchor(
    session: Session,
    cloudAnchorId: String,
    clientDeviceId: String = "${android.os.Build.MANUFACTURER}_${android.os.Build.MODEL}",
    remoteHostDeviceId: String? = null,
    timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    onStatusChange: (CloudAnchorRecord) -> Unit
  ): String {
    if (session.config.cloudAnchorMode == Config.CloudAnchorMode.DISABLED) {
      val record = CloudAnchorRecord(
        cloudAnchorId = cloudAnchorId,
        anchor = null,
        state = CloudAnchorLifecycleState.ERROR_CLOUD_ANCHORS_NOT_CONFIGURED,
        errorMessage = "CloudAnchorMode is DISABLED in ARCore Session configuration."
      )
      onStatusChange(record)
      return cloudAnchorId
    }

    val initialRecord = CloudAnchorRecord(
      cloudAnchorId = cloudAnchorId,
      anchor = null,
      state = CloudAnchorLifecycleState.RESOLVING_IN_PROGRESS
    )
    activeRecords[cloudAnchorId] = initialRecord
    onStatusChange(initialRecord)

    // Schedule timeout
    val timeoutRunnable = Runnable {
      val cur = activeRecords[cloudAnchorId]
      if (cur != null && cur.state == CloudAnchorLifecycleState.RESOLVING_IN_PROGRESS) {
        val timedOut = cur.copy(
          state = CloudAnchorLifecycleState.ERROR_TIMEOUT,
          errorMessage = "Cloud Anchor resolving timed out after ${timeoutMs / 1000}s."
        )
        activeRecords[cloudAnchorId] = timedOut
        crossDeviceState = CloudAnchorCrossDeviceState.CROSS_DEVICE_RESOLUTION_FAILED
        onStatusChange(timedOut)
        Log.w(TAG, "Resolving timed out for anchor $cloudAnchorId")
      }
    }
    pendingTimeouts[cloudAnchorId] = timeoutRunnable
    mainHandler.postDelayed(timeoutRunnable, timeoutMs)

    try {
      session.resolveCloudAnchorAsync(cloudAnchorId) { anchor, state ->
        pendingTimeouts.remove(cloudAnchorId)?.let { mainHandler.removeCallbacks(it) }

        val stateName = state.name
        Log.i(TAG, "ARCore Cloud Anchor Resolve callback: state=$stateName, id=$cloudAnchorId")

        when (state) {
          Anchor.CloudAnchorState.SUCCESS -> {
            val originHost = activeSharedExhibit?.hostDeviceId ?: remoteHostDeviceId
            val isRemoteHost = !originHost.isNullOrEmpty() &&
                originHost != clientDeviceId

            if (isRemoteHost) {
              isCrossDeviceValidated = true
              crossDeviceState = CloudAnchorCrossDeviceState.CONFIRMED_CROSS_DEVICE_RESOLVED
              resolutionSource = CloudAnchorResolutionSource.CROSS_DEVICE_REMOTE_RESOLVED
              Log.i(TAG, "CONFIRMED cross-device resolution: Device B ($clientDeviceId) resolved anchor from Device A ($originHost)")
            } else {
              isCrossDeviceValidated = false
              crossDeviceState = CloudAnchorCrossDeviceState.LOCAL_ONLY
              resolutionSource = CloudAnchorResolutionSource.LOCAL_DEVICE_HOSTED
              Log.i(TAG, "Resolved on local/originating device ($clientDeviceId). Local state preserved, not cross-device.")
            }
            val successRecord = CloudAnchorRecord(
              cloudAnchorId = cloudAnchorId,
              anchor = anchor,
              state = CloudAnchorLifecycleState.RESOLVED_SUCCESS
            )
            activeRecords[cloudAnchorId] = successRecord
            onStatusChange(successRecord)
          }
          Anchor.CloudAnchorState.ERROR_NOT_AUTHORIZED -> {
            crossDeviceState = CloudAnchorCrossDeviceState.CROSS_DEVICE_RESOLUTION_FAILED
            val failRecord = initialRecord.copy(
              state = CloudAnchorLifecycleState.ERROR_NOT_AUTHORIZED,
              errorMessage = "Google Cloud API key unauthorized for ARCore Cloud Anchors."
            )
            activeRecords[cloudAnchorId] = failRecord
            onStatusChange(failRecord)
          }
          Anchor.CloudAnchorState.ERROR_RESOLVING_LOCALIZATION_NO_MATCH -> {
            crossDeviceState = CloudAnchorCrossDeviceState.CROSS_DEVICE_RESOLUTION_FAILED
            val failRecord = initialRecord.copy(
              state = CloudAnchorLifecycleState.ERROR_LOCALIZATION_FAILED,
              errorMessage = "Current camera view does not match the visual features of this Cloud Anchor."
            )
            activeRecords[cloudAnchorId] = failRecord
            onStatusChange(failRecord)
          }
          else -> {
            crossDeviceState = CloudAnchorCrossDeviceState.CROSS_DEVICE_RESOLUTION_FAILED
            val failRecord = initialRecord.copy(
              state = CloudAnchorLifecycleState.ERROR_LOCALIZATION_FAILED,
              errorMessage = "Resolving failed: $stateName"
            )
            activeRecords[cloudAnchorId] = failRecord
            onStatusChange(failRecord)
          }
        }
      }
    } catch (e: Exception) {
      pendingTimeouts.remove(cloudAnchorId)?.let { mainHandler.removeCallbacks(it) }
      Log.e(TAG, "Exception resolving cloud anchor: ${e.message}", e)
      crossDeviceState = CloudAnchorCrossDeviceState.CROSS_DEVICE_RESOLUTION_FAILED
      val errRecord = initialRecord.copy(
        state = CloudAnchorLifecycleState.ERROR_SDK_UNSUPPORTED,
        errorMessage = e.message
      )
      activeRecords[cloudAnchorId] = errRecord
      onStatusChange(errRecord)
    }

    return cloudAnchorId
  }

  /**
   * Device A -> Host Anchor -> Cloud Anchor ID -> Share ID.
   * Generates a shareable spatial exhibit with an explicit share ID for Device B.
   */
  fun shareCloudAnchor(
    cloudAnchorId: String,
    sessionCode: String,
    modelId: String,
    modelScale: Float,
    pose: Pose,
    hostDeviceId: String = "${android.os.Build.MANUFACTURER}_${android.os.Build.MODEL}"
  ): SharedSpatialExhibit {
    val shareId = "share_${sessionCode}_${cloudAnchorId.take(8)}"
    val exhibit = SharedSpatialExhibit(
      sessionRoomIdentifier = sessionCode,
      exhibitIdentifier = shareId,
      cloudAnchorId = cloudAnchorId,
      hostDeviceId = hostDeviceId,
      modelId = modelId,
      scale = modelScale,
      position = floatArrayOf(pose.tx(), pose.ty(), pose.tz()),
      rotation = floatArrayOf(pose.qx(), pose.qy(), pose.qz(), pose.qw()),
      syncTimestampMs = System.currentTimeMillis(),
      isRealtimeBackendConnected = isRealtimeBackendConnected
    )
    activeSharedExhibit = exhibit
    crossDeviceState = CloudAnchorCrossDeviceState.SHARED_ID_GENERATED
    isCrossDeviceValidated = false
    sharedPrefs?.edit()?.putString(sessionCode, cloudAnchorId)?.apply()
    Log.i(TAG, "Device A shared Cloud Anchor ID $cloudAnchorId with share ID '$shareId'")
    return exhibit
  }

  /**
   * Device B -> Resolve Anchor using shared ID / SharedSpatialExhibit.
   * STRICT REQUIREMENT: Only mark cross-device resolution as confirmed when Device B
   * successfully resolves an anchor that originated from a different host device (Device A).
   * Keep local/cache states strictly separate from confirmed cross-device resolution.
   */
  fun resolveSharedCloudAnchor(
    session: Session,
    sharedExhibit: SharedSpatialExhibit,
    clientDeviceId: String = "${android.os.Build.MANUFACTURER}_${android.os.Build.MODEL}",
    timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    onStatusChange: (CloudAnchorRecord) -> Unit
  ): String {
    activeSharedExhibit = sharedExhibit
    crossDeviceState = CloudAnchorCrossDeviceState.RESOLVING_DEVICE_B
    return resolveCloudAnchor(
      session = session,
      cloudAnchorId = sharedExhibit.cloudAnchorId,
      clientDeviceId = clientDeviceId,
      remoteHostDeviceId = sharedExhibit.hostDeviceId,
      timeoutMs = timeoutMs,
      onStatusChange = onStatusChange
    )
  }

  /**
   * Strictly validates a completed Device A -> Host -> Cloud ID -> Device B -> Resolve flow.
   * Ensures that cross-device resolution is marked as confirmed ONLY when Device B
   * successfully resolves an anchor that originated from a different host device (Device A).
   * Local hosting, ID creation, and local cache do NOT mark cross-device as valid.
   */
  fun verifyCrossDeviceResolution(
    cloudAnchorId: String,
    originHostDeviceId: String,
    resolvedByDeviceId: String,
    isArCoreResolveSuccess: Boolean
  ): Boolean {
    val isRealRemoteResolve = isArCoreResolveSuccess &&
        cloudAnchorId.isNotEmpty() &&
        originHostDeviceId.isNotEmpty() &&
        resolvedByDeviceId.isNotEmpty() &&
        originHostDeviceId != resolvedByDeviceId

    if (isRealRemoteResolve) {
      isCrossDeviceValidated = true
      crossDeviceState = CloudAnchorCrossDeviceState.CONFIRMED_CROSS_DEVICE_RESOLVED
      resolutionSource = CloudAnchorResolutionSource.CROSS_DEVICE_REMOTE_RESOLVED
      Log.i(TAG, "Cross-device resolution verified: Device B ($resolvedByDeviceId) resolved anchor ($cloudAnchorId) hosted by Device A ($originHostDeviceId)")
      return true
    } else {
      isCrossDeviceValidated = false
      crossDeviceState = CloudAnchorCrossDeviceState.LOCAL_ONLY
      resolutionSource = CloudAnchorResolutionSource.LOCAL_DEVICE_HOSTED
      Log.w(TAG, "Cross-device resolution verification rejected: not a valid remote resolve flow.")
      return false
    }
  }

  /**
   * Cancels an ongoing hosting or resolving operation.
   */
  fun cancelOperation(operationOrAnchorId: String) {
    pendingTimeouts.remove(operationOrAnchorId)?.let { mainHandler.removeCallbacks(it) }
    val record = activeRecords[operationOrAnchorId]
    if (record != null) {
      activeRecords[operationOrAnchorId] = record.copy(
        state = CloudAnchorLifecycleState.ERROR_CANCELLED,
        errorMessage = "Operation cancelled by user."
      )
    }
  }

  /**
   * Optional Multi-User Shared AR helper (Decoupled from core Cloud Anchor hosting/resolving).
   */
  fun attachSharedExhibitMetadata(
    sessionCode: String,
    cloudAnchorId: String,
    modelId: String,
    modelScale: Float,
    pose: Pose,
    exhibitIdentifier: String = "exhibit_${modelId}"
  ) {
    sharedPrefs?.edit()?.putString(sessionCode, cloudAnchorId)?.apply()
    activeSharedExhibit = SharedSpatialExhibit(
      sessionRoomIdentifier = sessionCode,
      exhibitIdentifier = exhibitIdentifier,
      cloudAnchorId = cloudAnchorId,
      hostDeviceId = "${android.os.Build.MANUFACTURER}_${android.os.Build.MODEL}",
      modelId = modelId,
      scale = modelScale,
      position = floatArrayOf(pose.tx(), pose.ty(), pose.tz()),
      rotation = floatArrayOf(pose.qx(), pose.qy(), pose.qz(), pose.qw()),
      syncTimestampMs = System.currentTimeMillis(),
      isRealtimeBackendConnected = false
    )
  }

  fun getCachedAnchorId(sessionCode: String): String? {
    val cached = sharedPrefs?.getString(sessionCode, null)
    if (cached != null) {
      // STRICT REQUIREMENT: Keep local/cache states separate from confirmed cross-device resolution
      resolutionSource = CloudAnchorResolutionSource.LOCAL_DEVICE_CACHE
      isCrossDeviceValidated = false
    }
    return cached
  }

  fun clearAll() {
    pendingTimeouts.values.forEach { mainHandler.removeCallbacks(it) }
    pendingTimeouts.clear()
    activeRecords.values.forEach { it.anchor?.detach() }
    activeRecords.clear()
    activeSharedExhibit = null
    isCrossDeviceValidated = false
    crossDeviceState = CloudAnchorCrossDeviceState.LOCAL_ONLY
    resolutionSource = null
  }
}
