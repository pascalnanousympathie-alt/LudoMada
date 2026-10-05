package com.ludomadagascar

/**
 * Device-tier policy for premium materials/lighting.
 *
 * The renderer selects one tier from GPU capability; gameplay state never depends
 * on the selected quality. This keeps visual fidelity scalable on older phones.
 */
enum class PremiumRenderQuality {
    MOBILE_SAFE,
    BALANCED,
    PREMIUM
}

data class PremiumRenderSettings(
    val shadowMapSize: Int,
    val shadowPcfRadius: Int,
    val environmentMipLevels: Int,
    val bloomStrength: Float,
    val ssaoStrength: Float,
    val normalStrengthScale: Float,
    val reflectionStrengthScale: Float,
    val maxParticles: Int
) {
    companion object {
        val MOBILE_SAFE = PremiumRenderSettings(
            shadowMapSize = 256,
            shadowPcfRadius = 1,
            environmentMipLevels = 4,
            bloomStrength = 0.07f,
            ssaoStrength = 0.18f,
            normalStrengthScale = 0.70f,
            reflectionStrengthScale = 0.78f,
            maxParticles = 64
        )

        val BALANCED = PremiumRenderSettings(
            shadowMapSize = 512,
            shadowPcfRadius = 1,
            environmentMipLevels = 5,
            bloomStrength = 0.10f,
            ssaoStrength = 0.28f,
            normalStrengthScale = 0.90f,
            reflectionStrengthScale = 0.92f,
            maxParticles = 96
        )

        val PREMIUM = PremiumRenderSettings(
            shadowMapSize = 1024,
            shadowPcfRadius = 2,
            environmentMipLevels = 7,
            bloomStrength = 0.13f,
            ssaoStrength = 0.34f,
            normalStrengthScale = 1.00f,
            reflectionStrengthScale = 1.10f,
            maxParticles = 128
        )
    }

    fun material(profile: PremiumMaterialProfile): PremiumMaterialProfile =
        profile.copy(
            normalStrength = (profile.normalStrength * normalStrengthScale).coerceIn(0.0f, 1.0f),
            environmentIntensity = (profile.environmentIntensity * reflectionStrengthScale).coerceAtLeast(0.0f)
        )
}

object PremiumRenderPolicy {
    fun settings(quality: PremiumRenderQuality): PremiumRenderSettings =
        when (quality) {
            PremiumRenderQuality.MOBILE_SAFE -> PremiumRenderSettings.MOBILE_SAFE
            PremiumRenderQuality.BALANCED -> PremiumRenderSettings.BALANCED
            PremiumRenderQuality.PREMIUM -> PremiumRenderSettings.PREMIUM
        }
}
