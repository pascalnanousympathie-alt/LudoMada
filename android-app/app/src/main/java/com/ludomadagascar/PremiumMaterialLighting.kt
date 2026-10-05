package com.ludomadagascar

import android.graphics.Color
import kotlin.math.max
import kotlin.math.min

/**
 * Lane-C only: physically based material + lighting profiles for the premium
 * gameplay renderer. This file contains no gameplay state, input, wallet, or
 * networking logic.
 *
 * The runtime renderer (Lane A) can map these profiles onto its existing PBR
 * uniforms/textures. Values are intentionally bounded for Android realtime use.
 */
data class PremiumMaterial(
    val baseColor: FloatArray,
    val metallic: Float,
    val roughness: Float,
    val ao: Float = 1f,
    val emission: Float = 0f,
    val normalStrength: Float = 0.20f,
    val environmentStrength: Float = 0.70f,
    val unlit: Boolean = false
) {
    init { require(baseColor.size == 4) { "baseColor must be RGBA" } }

    fun sanitized(): PremiumMaterial = copy(
        baseColor = baseColor.copyOf().also { for (i in 0..3) it[i] = it[i].coerceIn(0f, 1f) },
        metallic = metallic.coerceIn(0f, 1f),
        roughness = roughness.coerceIn(0.045f, 1f),
        ao = ao.coerceIn(0f, 1f),
        emission = emission.coerceIn(0f, 1f),
        normalStrength = normalStrength.coerceIn(0f, 1f),
        environmentStrength = environmentStrength.coerceIn(0f, 2f)
    )

    companion object {
        fun rgba(argb: Int, alpha: Float = 1f): FloatArray = floatArrayOf(
            Color.red(argb) / 255f, Color.green(argb) / 255f,
            Color.blue(argb) / 255f, alpha.coerceIn(0f, 1f)
        )

        fun wood() = PremiumMaterial(rgba(Color.rgb(118, 59, 18)), 0.04f, 0.42f, 0.98f, 0f, 0.24f, 0.62f)
        fun board() = PremiumMaterial(rgba(Color.rgb(226, 214, 183)), 0.02f, 0.47f, 0.98f, 0f, 0.18f, 0.48f)
        fun lacquer(argb: Int) = PremiumMaterial(rgba(argb), 0.06f, 0.23f, 0.98f, 0.006f, 0.14f, 0.78f)

        /** Hydro-plated baseline aligned with the 3D asset contract. */
        fun hydroChrome() = PremiumMaterial(rgba(Color.rgb(244, 247, 250)), 0.95f, 0.08f, 0.99f, 0.012f, 0.06f, 1.00f)

        fun chromeTray() = PremiumMaterial(rgba(Color.rgb(176, 183, 190)), 1.00f, 0.12f, 0.99f, 0.010f, 0.05f, 0.94f)
        fun pip() = PremiumMaterial(rgba(Color.rgb(18, 22, 28)), 0.72f, 0.16f, 1.00f, 0f, 0.05f, 0.58f)
        fun gold() = PremiumMaterial(rgba(Color.rgb(214, 154, 46)), 0.88f, 0.20f, 0.98f, 0.006f, 0.10f, 0.74f)
        fun neutralStone() = PremiumMaterial(rgba(Color.rgb(136, 138, 143)), 0.05f, 0.38f, 0.98f, 0f, 0.16f, 0.52f)

        fun legalMoveGlow() = PremiumMaterial(
            floatArrayOf(0.98f, 0.75f, 0.16f, 0.88f),
            0.55f, 0.10f, 1f, 0.035f, 0f, 0.35f, true
        )
    }
}

/** Physically coherent world-light configuration for the premium scene. */
data class PremiumLightRig(
    val keyDirection: FloatArray,
    val keyColor: FloatArray,
    val keyIntensity: Float,
    val ambientColor: FloatArray,
    val ambientIntensity: Float,
    val shadowBias: Float,
    val shadowMapResolution: Int,
    val iblIntensity: Float
) {
    init { require(keyDirection.size == 3 && keyColor.size == 3 && ambientColor.size == 3) }

    companion object {
        fun mobilePremium() = PremiumLightRig(
            floatArrayOf(-0.42f, 0.92f, 0.36f),
            floatArrayOf(1.00f, 0.94f, 0.82f), 1.05f,
            floatArrayOf(0.12f, 0.15f, 0.22f), 0.72f,
            0.0018f, 512, 0.88f
        )

        fun safeFallback() = PremiumLightRig(
            floatArrayOf(-0.40f, 0.90f, 0.38f),
            floatArrayOf(1.00f, 0.96f, 0.88f), 1.00f,
            floatArrayOf(0.10f, 0.12f, 0.18f), 0.64f,
            0.0022f, 256, 0.62f
        )
    }
}

/** Tunable GLES3 post-process budget for the premium scene. */
data class PremiumPostProcess(
    val exposure: Float,
    val bloom: Float,
    val ssao: Float,
    val saturation: Float,
    val contrast: Float,
    val vignette: Float
) {
    companion object {
        fun mobileHigh() = PremiumPostProcess(1.00f, 0.10f, 0.28f, 1.025f, 1.04f, 0.035f)
        fun mobileSafe() = PremiumPostProcess(0.98f, 0.045f, 0.16f, 1.015f, 1.02f, 0.015f)
    }
}

/** Public Lane-C entry point consumed later by the runtime renderer. */
object PremiumMaterialLighting {
    val lighting = PremiumLightRig.mobilePremium()
    val post = PremiumPostProcess.mobileHigh()

    fun pawn(argb: Int) = PremiumMaterial.lacquer(argb)
    fun die() = PremiumMaterial.hydroChrome()
    fun tray() = PremiumMaterial.chromeTray()
    fun boardBase() = PremiumMaterial.wood()
    fun boardSurface() = PremiumMaterial.board()
    fun frame() = PremiumMaterial.gold()
    fun pip() = PremiumMaterial.pip()
    fun safeLighting() = PremiumLightRig.safeFallback()
    fun safePost() = PremiumPostProcess.mobileSafe()

    fun mix(a: PremiumMaterial, b: PremiumMaterial, t: Float): PremiumMaterial {
        val u = t.coerceIn(0f, 1f)
        val c = FloatArray(4)
        for (i in 0..3) c[i] = a.baseColor[i] + (b.baseColor[i] - a.baseColor[i]) * u
        return PremiumMaterial(
            c,
            a.metallic + (b.metallic - a.metallic) * u,
            max(0.045f, a.roughness + (b.roughness - a.roughness) * u),
            min(1f, max(0f, a.ao + (b.ao - a.ao) * u)),
            a.emission + (b.emission - a.emission) * u,
            a.normalStrength + (b.normalStrength - a.normalStrength) * u,
            a.environmentStrength + (b.environmentStrength - a.environmentStrength) * u,
            if (u < 0.5f) a.unlit else b.unlit
        )
    }
}
