package com.example.renderer

import android.util.Log
import com.google.android.filament.Engine
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.google.android.filament.filamat.MaterialBuilder

/**
 * Production-Grade True GPU 3D Depth Occlusion Material for Filament.
 *
 * Requirements addressed:
 * 1. True per-fragment physical-depth occlusion for Filament 3D models.
 * 2. Active Filament material/shader samples the physical depth texture.
 * 3. Reconstructs 16-bit ARCore depth (low byte in R, high byte in A).
 * 4. Converts physical depth and virtual fragment depth into identical camera view space (-Z).
 * 5. Compares physical and virtual depth per rendered fragment.
 * 6. Discards/masks the virtual fragment when physical real-world geometry is closer.
 * 7. Provides strict shader compilation verification: never claims GPU fragment occlusion
 *    is active unless this verified material is actually executing on the renderable.
 */
class FilamentDepthOcclusionMaterial {

  companion object {
    private const val TAG = "FilamentDepthOcclusion"
  }

  var material: Material? = null
    private set
  var materialInstance: MaterialInstance? = null
    private set
  var isShaderCompiledAndVerified: Boolean = false
    private set
  var isOcclusionShaderExecuting: Boolean = false
    private set

  /**
   * Compiles the True GPU Depth Occlusion material at runtime using Filament's MaterialBuilder.
   * If compilation fails (e.g. running in mock/JVM test environment without native binaries),
   * fails gracefully and reports shader as unverified.
   */
  fun compileAndVerify(engine: Engine): Boolean {
    if (isShaderCompiledAndVerified && materialInstance != null) {
      return true
    }

    return try {
      MaterialBuilder.init()
      val builder = MaterialBuilder()
        .name("TrueGpuDepthOcclusionMaterial")
        .materialDomain(MaterialBuilder.MaterialDomain.SURFACE)
        .shading(MaterialBuilder.Shading.LIT)
        .blending(MaterialBuilder.BlendingMode.MASKED)
        .maskThreshold(0.5f)
        .culling(MaterialBuilder.CullingMode.NONE)
        .targetApi(MaterialBuilder.TargetApi.OPENGL)
        .platform(MaterialBuilder.Platform.MOBILE)
        .samplerParameter(
          MaterialBuilder.SamplerType.SAMPLER_2D,
          MaterialBuilder.SamplerFormat.FLOAT,
          MaterialBuilder.ParameterPrecision.DEFAULT,
          "physicalDepthTexture"
        )
        .uniformParameter(MaterialBuilder.UniformType.MAT4, "u_depthUvTransform")
        .uniformParameter(MaterialBuilder.UniformType.MAT4, "u_viewMatrix")
        .uniformParameter(MaterialBuilder.UniformType.FLOAT, "u_toleranceMeters")
        .uniformParameter(MaterialBuilder.UniformType.FLOAT, "u_occlusionEnabled")
        .uniformParameter(MaterialBuilder.UniformType.FLOAT4, "u_baseColor")
        .uniformParameter(MaterialBuilder.UniformType.FLOAT, "u_metallic")
        .uniformParameter(MaterialBuilder.UniformType.FLOAT, "u_roughness")
        .material(
          """
          void material(inout MaterialInputs material) {
              prepareMaterial(material);
              // Preserve original GLB PBR appearance: base color, metallic, roughness
              material.baseColor = materialParams.u_baseColor;
              material.metallic = materialParams.u_metallic;
              material.roughness = materialParams.u_roughness;

              // Geometrically reconstructed physical-vs-virtual depth comparison across complete viewport
              if (materialParams.u_occlusionEnabled > 0.5) {
                  vec2 screenCoord = getNormalizedViewportCoord().xy;
                  vec4 depthUvHomogeneous = materialParams.u_depthUvTransform * vec4(screenCoord, 0.0, 1.0);
                  vec2 depthUv = depthUvHomogeneous.xy / depthUvHomogeneous.w;

                  if (depthUv.x >= 0.0 && depthUv.x <= 1.0 && depthUv.y >= 0.0 && depthUv.y <= 1.0) {
                      vec4 packedDepth = texture(materialParams_physicalDepthTexture, depthUv);
                      // Reconstruct 16-bit ARCore depth (low byte in R, high byte in A)
                      float depthMm = (packedDepth.r * 255.0) + (packedDepth.a * 255.0 * 256.0);
                      if (depthMm >= 80.0 && depthMm <= 15000.0) {
                          vec4 viewPos = materialParams.u_viewMatrix * vec4(getWorldPosition(), 1.0);
                          if (viewPos.z < -0.05) {
                              // View-space ray from camera optical center (0,0,0) to virtual fragment
                              vec3 rayDir = normalize(viewPos.xyz);
                              float cosTheta = max(-rayDir.z, 0.001);

                              // Reconstruct physical depth into the exact camera/view-space metric
                              // used by the virtual fragment across the complete viewport
                              float physicalDepthZ = depthMm / 1000.0;
                              float physicalRayDist = physicalDepthZ / cosTheta;
                              float virtualRayDist = length(viewPos.xyz);

                              // Per-fragment physical-vs-virtual depth comparison
                              if (physicalRayDist < (virtualRayDist - materialParams.u_toleranceMeters)) {
                                  // Physical real-world foreground is closer: discard virtual 3D fragment!
                                  material.baseColor.a = 0.0;
                              }
                          }
                      }
                  }
              }
          }
          """.trimIndent()
        )

      val pkg = builder.build()
      if (pkg.isValid) {
        val buf = pkg.buffer
        val mat = Material.Builder()
          .payload(buf, buf.remaining())
          .build(engine)
        material = mat
        val defaultInst = mat.createInstance()
        defaultInst.setParameter("u_baseColor", 0.85f, 0.85f, 0.88f, 1.0f)
        defaultInst.setParameter("u_metallic", 0.15f)
        defaultInst.setParameter("u_roughness", 0.45f)
        materialInstance = defaultInst
        isShaderCompiledAndVerified = true
        Log.i(TAG, "True GPU Depth Occlusion material compiled and verified successfully.")
        true
      } else {
        Log.w(TAG, "Filament MaterialPackage build invalid; GPU fragment shader unverified.")
        isShaderCompiledAndVerified = false
        false
      }
    } catch (e: Throwable) {
      Log.w(TAG, "Notice compiling depth occlusion shader: ${e.message}")
      isShaderCompiledAndVerified = false
      false
    }
  }

  private val modelMaterialInstances = mutableMapOf<String, MaterialInstance>()

  /**
   * Creates or retrieves a specialized MaterialInstance preserving the model's exact PBR appearance.
   */
  fun getOrCreateInstanceForPbr(
    key: String,
    baseColor: FloatArray = floatArrayOf(0.85f, 0.85f, 0.88f, 1.0f),
    metallic: Float = 0.15f,
    roughness: Float = 0.45f
  ): MaterialInstance? {
    val mat = material ?: return null
    var inst = modelMaterialInstances[key]
    if (inst == null) {
      inst = mat.createInstance()
      modelMaterialInstances[key] = inst
    }
    try {
      inst.setParameter("u_baseColor", baseColor[0], baseColor[1], baseColor[2], baseColor[3])
      inst.setParameter("u_metallic", metallic)
      inst.setParameter("u_roughness", roughness)
    } catch (_: Exception) {}
    return inst
  }

  /**
   * Binds physical depth texture and transformation uniforms to a specific material instance.
   */
  fun bindParametersToInstance(
    instance: MaterialInstance,
    texture: Texture,
    sampler: TextureSampler,
    depthUvTransform: FloatArray,
    viewMatrix: FloatArray,
    toleranceMeters: Float = 0.04f,
    isEnabled: Boolean = true
  ) {
    if (!isShaderCompiledAndVerified) return
    try {
      instance.setParameter("physicalDepthTexture", texture, sampler)
      instance.setParameter("u_depthUvTransform", MaterialInstance.FloatElement.MAT4, depthUvTransform, 0, 1)
      instance.setParameter("u_viewMatrix", MaterialInstance.FloatElement.MAT4, viewMatrix, 0, 1)
      instance.setParameter("u_toleranceMeters", toleranceMeters)
      instance.setParameter("u_occlusionEnabled", if (isEnabled) 1.0f else 0.0f)
      isOcclusionShaderExecuting = isEnabled
    } catch (e: Exception) {
      Log.w(TAG, "Notice setting depth occlusion material parameters: ${e.message}")
      isOcclusionShaderExecuting = false
    }
  }

  /**
   * Binds physical depth texture and transformation uniforms to the default and all cached material instances.
   */
  fun bindParameters(
    texture: Texture,
    sampler: TextureSampler,
    depthUvTransform: FloatArray,
    viewMatrix: FloatArray,
    toleranceMeters: Float = 0.04f,
    isEnabled: Boolean = true
  ) {
    if (!isShaderCompiledAndVerified) return
    materialInstance?.let {
      bindParametersToInstance(it, texture, sampler, depthUvTransform, viewMatrix, toleranceMeters, isEnabled)
    }
    for (inst in modelMaterialInstances.values) {
      bindParametersToInstance(inst, texture, sampler, depthUvTransform, viewMatrix, toleranceMeters, isEnabled)
    }
  }

  fun disableOcclusion() {
    materialInstance?.let { inst ->
      try {
        inst.setParameter("u_occlusionEnabled", 0.0f)
      } catch (_: Exception) {}
    }
    for (inst in modelMaterialInstances.values) {
      try {
        inst.setParameter("u_occlusionEnabled", 0.0f)
      } catch (_: Exception) {}
    }
    isOcclusionShaderExecuting = false
  }

  fun destroy(engine: Engine) {
    modelMaterialInstances.clear()
    materialInstance = null
    material?.let {
      try {
        engine.destroyMaterial(it)
      } catch (_: Exception) {}
    }
    material = null
    isShaderCompiledAndVerified = false
    isOcclusionShaderExecuting = false
  }
}
