package com.ludomadagascar

import kotlin.math.max
import kotlin.math.min

/**
 * Lane C material / lighting contract.
 *
 * Pure rendering data and small color helpers only: no game state, turn,
 * movement, wallet, auth, or payment behavior belongs here.
 *
 * The runtime renderer can consume these presets incrementally without
 * coupling gameplay code to shader tuning.
 */
data class PbrMaterialProfile(
    val metallic: Float,
    val roughness: Float,
    val ao: Float = 1f,
    val emission: Float = 0f,
    val normalStrength: Float = 0.20f
) {
    init {
        require(metallic in 0f..1f)
        require(roughness in 0.045f..1f)
        require(ao in 0f..1f)
        require(emission >= 0f)
        require(normalStrength in 0f..1f)
    }
}

object LudoMaterialLighting {

    // Physically motivated starting points. Final visual tuning remains
    // device/profile dependent and belongs to the renderer.
    val WOOD = PbrMaterialProfile(
        metallic = 0.02f,
        roughness = 0.52f,
        ao = 0.96f,
        emission = 0f,
        normalStrength = 0.28f
    )

    val LACQUERED_PLAYER = PbrMaterialProfile(
        metallic = 0.06f,
        roughness = 0.22f,
        ao = 1.0f,
        emission = 0.010f,
        normalStrength = 0.18f
    )

    val IVORY_BOARD = PbrMaterialProfile(
        metallic = 0.0f,
        roughness = 0.43f,
        ao = 0.98f,
        emission = 0.0f,
        normalStrength = 0.16f
    )

    val DARK_METAL_TRAY = PbrMaterialProfile(
        metallic = 0.92f,
        roughness = 0.14f,
        ao = 0.98f,
        emission = 0.008f,
        normalStrength = 0.08f
    )

    val HYDRO_CHROME_DIE = PbrMaterialProfile(
        metallic = 1.0f,
        roughness = 0.055f,
        ao = 1.0f,
        emission = 0.020f,
        normalStrength = 0.06f
    )

    val GOLD_ACCENT = PbrMaterialProfile(
        metallic = 0.88f,
        roughness = 0.16f,
        ao = 1.0f,
        emission = 0.010f,
        normalStrength = 0.08f
    )

    /**
     * Main studio-style light directions used by the GLES3 path.
     *
     * Values are intentionally kept in world-space and independent of
     * gameplay state so Lane A can map them into uniforms or a lighting rig.
     */
    data class KeyLight(
        val x: Float,
        val y: Float,
        val z: Float,
        val intensity: Float
    )

    data class LightingRig(
        val key: KeyLight,
        val fill: KeyLight,
        val rim: KeyLight,
        val ambientIntensity: Float,
        val shadowBias: Float,
        val shadowNormalBias: Float
    ) {
        init {
            require(ambientIntensity >= 0f)
            require(shadowBias >= 0f)
            require(shadowNormalBias >= 0f)
        }
    }

    val PREMIUM_RIG = LightingRig(
        key = KeyLight(-6.0f, 14.0f, 8.0f, 1.18f),
        fill = KeyLight(5.5f, 8.0f, 1.5f, 0.28f),
        rim = KeyLight(0.0f, 9.0f, -8.0f, 0.44f),
        ambientIntensity = 0.62f,
        // Tuned conservatively for a 512² depth shadow map on mobile.
        shadowBias = 0.0016f,
        shadowNormalBias = 0.0035f
    )

    /**
     * Post-processing contract for GLES3 devices.
     *
     * AO and bloom remain bounded for Android 8 / mid-range GPU use.
     */
    data class PostProcess(
        val exposure: Float,
        val saturation: Float,
        val contrast: Float,
        val warmBias: Float,
        val ssao: Float,
        val bloom: Float,
        val bloomThreshold: Float
    ) {
        init {
            require(ssao in 0f..1f)
            require(bloom >= 0f)
            require(bloomThreshold >= 0f)
        }
    }

    val PREMIUM_POST = PostProcess(
        exposure = 1.04f,
        saturation = 1.045f,
        contrast = 1.035f,
        warmBias = 0.020f,
        ssao = 0.30f,
        bloom = 0.12f,
        bloomThreshold = 0.72f
    )

    /**
     * Fresnel/surface response helpers shared by renderer tuning code.
     * These are CPU-side reference calculations, useful for tests and
     * for keeping material semantics consistent with shader code.
     */
    fun schlickFresnel(cosTheta: Float, f0: Float): Float {
        val c = clamp(cosTheta, 0f, 1f)
        val base = clamp(f0, 0f, 1f)
        return base + (1f - base) * pow5(1f - c)
    }

    fun specularPower(roughness: Float): Float {
        val r = clamp(roughness, 0.045f, 1f)
        return 2f / max(0.001f, r * r) - 2f
    }

    fun clampExposure(exposure: Float): Float =
        clamp(exposure, 0.70f, 1.40f)

    private fun pow5(x: Float): Float {
        val y = clamp(x, 0f, 1f)
        val y2 = y * y
        return y2 * y2 * y
    }

    private fun clamp(value: Float, low: Float, high: Float): Float =
        min(high, max(low, value))
}
