package com.ludomadagascar

import android.graphics.Color
import kotlin.math.max
import kotlin.math.min

/**
 * Lane C only: physically based material, lighting and post-process contract.
 *
 * Runtime integration belongs to Lane A. No gameplay state, input, wallet,
 * authentication or payment behavior is defined here.
 */
data class PremiumMaterial(
    val baseColor: FloatArray,
    val metallic: Float,
    val roughness: Float,
    val ao: Float = 1f,
    val emission: Float = 0f,
    val normalStrength: Float = 0.20f,
    val environmentStrength: Float = 0.70f,
    val clearCoat: Float = 0f,
    val clearCoatRoughness: Float = 0.12f,
    val unlit: Boolean = false
) {
    init {
        require(baseColor.size == 4) { "baseColor must be RGBA" }
    }

    fun sanitized(): PremiumMaterial = copy(
        baseColor = baseColor.copyOf().also {
            for (i in 0..3) it[i] = it[i].coerceIn(0f, 1f)
        },
        metallic = metallic.coerceIn(0f, 1f),
        roughness = roughness.coerceIn(0.045f, 1f),
        ao = ao.coerceIn(0f, 1f),
        emission = emission.coerceIn(0f, 1f),
        normalStrength = normalStrength.coerceIn(0f, 1f),
        environmentStrength = environmentStrength.coerceIn(0f, 2f),
        clearCoat = clearCoat.coerceIn(0f, 1f),
        clearCoatRoughness = clearCoatRoughness.coerceIn(0.035f, 1f)
    )

    companion object {
        fun rgba(argb: Int, alpha: Float = 1f): FloatArray = floatArrayOf(
            Color.red(argb) / 255f,
            Color.green(argb) / 255f,
            Color.blue(argb) / 255f,
            alpha.coerceIn(0f, 1f)
        )

        fun wood() = PremiumMaterial(
            rgba(Color.rgb(118, 59, 18)),
            metallic = 0.04f,
            roughness = 0.42f,
            ao = 0.98f,
            normalStrength = 0.24f,
            environmentStrength = 0.62f,
            clearCoat = 0.05f,
            clearCoatRoughness = 0.18f
        )

        fun board() = PremiumMaterial(
            rgba(Color.rgb(226, 214, 183)),
            metallic = 0.02f,
            roughness = 0.47f,
            ao = 0.98f,
            normalStrength = 0.18f,
            environmentStrength = 0.48f,
            clearCoat = 0.03f,
            clearCoatRoughness = 0.20f
        )

        fun lacquer(argb: Int) = PremiumMaterial(
            rgba(argb),
            metallic = 0.06f,
            roughness = 0.23f,
            ao = 0.98f,
            emission = 0.006f,
            normalStrength = 0.14f,
            environmentStrength = 0.78f,
            clearCoat = 0.22f,
            clearCoatRoughness = 0.075f
        )

        /** Hydro-plated silver/chrome target from the asset contract. */
        fun hydroChrome() = PremiumMaterial(
            rgba(Color.rgb(244, 247, 250)),
            metallic = 0.95f,
            roughness = 0.08f,
            ao = 0.99f,
            emission = 0.012f,
            normalStrength = 0.06f,
            environmentStrength = 1.00f,
            clearCoat = 0.34f,
            clearCoatRoughness = 0.060f
        )

        fun chromeTray() = PremiumMaterial(
            rgba(Color.rgb(176, 183, 190)),
            metallic = 1.00f,
            roughness = 0.12f,
            ao = 0.99f,
            emission = 0.010f,
            normalStrength = 0.05f,
            environmentStrength = 0.94f,
            clearCoat = 0.28f,
            clearCoatRoughness = 0.075f
        )

        fun pip() = PremiumMaterial(
            rgba(Color.rgb(18, 22, 28)),
            metallic = 0.72f,
            roughness = 0.16f,
            ao = 1.00f,
            normalStrength = 0.05f,
            environmentStrength = 0.58f,
            clearCoat = 0.12f,
            clearCoatRoughness = 0.10f
        )

        fun gold() = PremiumMaterial(
            rgba(Color.rgb(214, 154, 46)),
            metallic = 0.88f,
            roughness = 0.20f,
            ao = 0.98f,
            emission = 0.006f,
            normalStrength = 0.10f,
            environmentStrength = 0.74f,
            clearCoat = 0.12f,
            clearCoatRoughness = 0.10f
        )

        fun neutralStone() = PremiumMaterial(
            rgba(Color.rgb(136, 138, 143)),
            metallic = 0.05f,
            roughness = 0.38f,
            ao = 0.98f,
            normalStrength = 0.16f,
            environmentStrength = 0.52f
        )

        fun legalMoveGlow() = PremiumMaterial(
            floatArrayOf(0.98f, 0.75f, 0.16f, 0.88f),
            metallic = 0.55f,
            roughness = 0.10f,
            ao = 1f,
            emission = 0.035f,
            normalStrength = 0f,
            environmentStrength = 0.35f,
            unlit = true
        )
    }
}

data class PremiumLightRig(
    val keyDirection: FloatArray,
    val keyColor: FloatArray,
    val keyIntensity: Float,
    val fillDirection: FloatArray,
    val fillColor: FloatArray,
    val fillIntensity: Float,
    val rimDirection: FloatArray,
    val rimColor: FloatArray,
    val rimIntensity: Float,
    val ambientColor: FloatArray,
    val ambientIntensity: Float,
    val shadowBias: Float,
    val shadowNormalBias: Float,
    val shadowMapResolution: Int,
    val iblIntensity: Float
) {
    init {
        require(keyDirection.size == 3 && keyColor.size == 3)
        require(fillDirection.size == 3 && fillColor.size == 3)
        require(rimDirection.size == 3 && rimColor.size == 3)
        require(ambientColor.size == 3)
    }

    companion object {
        fun mobilePremium() = PremiumLightRig(
            keyDirection = floatArrayOf(-0.42f, 0.92f, 0.36f),
            keyColor = floatArrayOf(1.00f, 0.94f, 0.82f),
            keyIntensity = 1.05f,
            fillDirection = floatArrayOf(0.62f, 0.54f, -0.55f),
            fillColor = floatArrayOf(0.32f, 0.42f, 0.58f),
            fillIntensity = 0.42f,
            rimDirection = floatArrayOf(0.0f, 0.75f, -0.66f),
            rimColor = floatArrayOf(1.00f, 0.84f, 0.60f),
            rimIntensity = 0.44f,
            ambientColor = floatArrayOf(0.12f, 0.15f, 0.22f),
            ambientIntensity = 0.72f,
            shadowBias = 0.0018f,
            shadowNormalBias = 0.0035f,
            shadowMapResolution = 512,
            iblIntensity = 0.88f
        )

        fun safeFallback() = PremiumLightRig(
            keyDirection = floatArrayOf(-0.40f, 0.90f, 0.38f),
            keyColor = floatArrayOf(1.00f, 0.96f, 0.88f),
            keyIntensity = 1.00f,
            fillDirection = floatArrayOf(0.55f, 0.58f, -0.58f),
            fillColor = floatArrayOf(0.36f, 0.43f, 0.54f),
            fillIntensity = 0.24f,
            rimDirection = floatArrayOf(0.0f, 0.75f, -0.66f),
            rimColor = floatArrayOf(1.00f, 0.88f, 0.68f),
            rimIntensity = 0.24f,
            ambientColor = floatArrayOf(0.10f, 0.12f, 0.18f),
            ambientIntensity = 0.64f,
            shadowBias = 0.0022f,
            shadowNormalBias = 0.0045f,
            shadowMapResolution = 256,
            iblIntensity = 0.62f
        )
    }
}

data class PremiumPostProcess(
    val exposure: Float,
    val bloom: Float,
    val ssao: Float,
    val saturation: Float,
    val contrast: Float,
    val warmBias: Float,
    val vignette: Float
) {
    companion object {
        fun mobileHigh() = PremiumPostProcess(
            exposure = 1.00f,
            bloom = 0.10f,
            ssao = 0.28f,
            saturation = 1.025f,
            contrast = 1.04f,
            warmBias = 0.018f,
            vignette = 0.035f
        )

        fun mobileSafe() = PremiumPostProcess(
            exposure = 0.98f,
            bloom = 0.045f,
            ssao = 0.16f,
            saturation = 1.015f,
            contrast = 1.02f,
            warmBias = 0.010f,
            vignette = 0.015f
        )
    }
}

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

    fun mix(
        a: PremiumMaterial,
        b: PremiumMaterial,
        t: Float
    ): PremiumMaterial {
        val u = t.coerceIn(0f, 1f)
        val c = FloatArray(4)
        for (i in 0..3) {
            c[i] = a.baseColor[i] + (b.baseColor[i] - a.baseColor[i]) * u
        }

        return PremiumMaterial(
            baseColor = c,
            metallic = a.metallic + (b.metallic - a.metallic) * u,
            roughness = max(0.045f, a.roughness + (b.roughness - a.roughness) * u),
            ao = (a.ao + (b.ao - a.ao) * u).coerceIn(0f, 1f),
            emission = a.emission + (b.emission - a.emission) * u,
            normalStrength = a.normalStrength + (b.normalStrength - a.normalStrength) * u,
            environmentStrength = a.environmentStrength +
                (b.environmentStrength - a.environmentStrength) * u,
            clearCoat = a.clearCoat + (b.clearCoat - a.clearCoat) * u,
            clearCoatRoughness = max(
                0.035f,
                a.clearCoatRoughness +
                    (b.clearCoatRoughness - a.clearCoatRoughness) * u
            ),
            unlit = if (u < 0.5f) a.unlit else b.unlit
        )
    }

    fun reflectionMip(roughness: Float, maxMip: Float = 5f): Float {
        val r = roughness.coerceIn(0.045f, 1f)
        return (r * r * maxMip).coerceIn(0f, maxMip)
    }

    fun reflectionStrength(
        cosTheta: Float,
        metallic: Float,
        environmentStrength: Float
    ): Float {
        val c = cosTheta.coerceIn(0f, 1f)
        val m = metallic.coerceIn(0f, 1f)
        val f0 = 0.04f + 0.96f * m
        val fresnel = f0 + (1f - f0) * pow5(1f - c)
        return (fresnel * environmentStrength.coerceIn(0f, 2f)).coerceIn(0f, 2f)
    }

    private fun pow5(x: Float): Float {
        val y = x.coerceIn(0f, 1f)
        val y2 = y * y
        return y2 * y2 * y
    }
}
