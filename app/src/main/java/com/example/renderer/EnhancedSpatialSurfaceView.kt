// This file contains the FIXED version of critical SpatialSurfaceView sections
// To apply: Replace corresponding methods in SpatialSurfaceView.kt

// FIX #1: Safer anchor pose handling with atomics
private val lastKnownPrimaryPoseLock = java.util.concurrent.locks.ReentrantReadWriteLock()
private var lastKnownPrimaryPose: com.google.ar.core.Pose? = null

fun safeUpdateAnchorPose(currentAsset: Any?, pose: com.google.ar.core.Pose?, trackingState: com.google.ar.core.TrackingState) {
    if (currentAsset == null || pose == null) return
    
    lastKnownPrimaryPoseLock.writeLock().lock()
    try {
        // Only update on valid tracking state
        if (trackingState == com.google.ar.core.TrackingState.TRACKING) {
            lastKnownPrimaryPose = pose
            filamentEngine.updateAnchorPose(currentAsset, pose)
        } else if (trackingState == com.google.ar.core.TrackingState.PAUSED && lastKnownPrimaryPose != null) {
            // Frozen pose during tracking pause - don't jump
            filamentEngine.updateAnchorPose(currentAsset, lastKnownPrimaryPose!!)
        }
    } finally {
        lastKnownPrimaryPoseLock.writeLock().unlock()
    }
}

// FIX #2: Safe quaternion-based rotation (no snapping)
fun updateRotationWithQuaternion(deltaDegrees: Float) {
    val yAxis = floatArrayOf(0f, 1f, 0f)
    filamentEngine.rotationManager.rotateAroundAxis(yAxis, deltaDegrees)
}

// FIX #3: Thread-safe touch handling
private val touchHandler = ThreadSafeTouchHandler(
    onRotate = { delta ->
        if (displayMode == DisplayMode.OBJECT) {
            filamentEngine.orbitYaw -= delta
        } else {
            updateRotationWithQuaternion(-delta)
        }
    },
    onScale = { factor ->
        filamentEngine.modelScale = (filamentEngine.modelScale * factor).coerceIn(
            FilamentEngineHolder.MIN_MODEL_SCALE,
            FilamentEngineHolder.MAX_MODEL_SCALE
        )
    },
    onDrag = { dx, dy ->
        when (displayMode) {
            DisplayMode.OBJECT -> {
                filamentEngine.modelOffsetX = (filamentEngine.modelOffsetX + dx * 0.0015f).coerceIn(-1.5f, 1.5f)
                filamentEngine.modelOffsetY = (filamentEngine.modelOffsetY - dy * 0.0015f).coerceIn(-1.5f, 1.5f)
            }
            DisplayMode.AR, DisplayMode.MR -> {
                if (placementMode == WorldPlacementMode.PRE_ANCHOR_PREVIEW) {
                    filamentEngine.modelOffsetX += dx * 0.0015f
                    filamentEngine.modelOffsetY -= dy * 0.0015f
                } else {
                    updateRotationWithQuaternion(-dx * 0.35f)
                }
            }
        }
    },
    onTap = { x, y ->
        handleTap(x, y)
    }
)

override fun onTouchEvent(event: MotionEvent): Boolean {
    return touchHandler.onTouchEvent(event)
}

// FIX #4: Depth occlusion properly bound to shader
fun renderWithDepthOcclusion(syncState: com.example.arcore.SynchronizedArRenderState?) {
    if (syncState?.isDepthValid == true) {
        // Bind depth texture BEFORE rendering virtual models
        depthOcclusionManager.bindDepthTextureToShader(
            shaderProgram = filamentEngine.activeShaderProgram,
            uniformName = "u_DepthTexture"
        )
        
        filamentEngine.updateGpuDepthOcclusion(
            textureId = syncState.depthTextureId,
            width = syncState.depthWidth,
            height = syncState.depthHeight,
            timestampNs = syncState.depthTimestampNs,
            minDepth = syncState.minDepthMeters,
            maxDepth = syncState.maxDepthMeters,
            avgDepth = syncState.averageDepthMeters,
            isReady = true,
            occlusionPercentage = syncState.occlusionPercentage,
            depthUvTransformMatrix = syncState.depthUvTransformMatrix,
            viewMatrix = syncState.viewMatrix
        )
    }
}
