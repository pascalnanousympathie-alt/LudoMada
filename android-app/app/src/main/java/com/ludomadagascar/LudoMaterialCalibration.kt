package com.ludomadagascar

import kotlin.math.pow

/**
 * Lane C compatibility façade for the canonical PremiumMaterialLighting contract.
 *
 * Keep this type for renderer consumers that already use the calibration API.
 * All numerical values are delegated to PremiumMaterialLighting.Calibration so
 * there is one source of truth.
 */
object LudoMaterialCalibration {

    data class Chrome(
        val clearCoat: Float = PremiumMaterialLighting.Calibration.CHROME_CLEAR_COAT,
        val clearCoatRoughness: Float = PremiumMaterialLighting.Calibration.CHROME_CLEAR_COAT_ROUGHNESS,
        val metallic: Float = PremiumMaterialLighting.Calibration.CHROME_METALLIC,
        val roughness: Float = PremiumMaterialLighting.Calibration.CHROME_ROUGHNESS
    )

    data class Lights(
        val keyIntensity: Float = PremiumMaterialLighting.Calibration.KEY_INTENSITY,
        val fillIntensity: Float = PremiumMaterialLighting.Calibration.FILL_INTENSITY,
        val rimIntensity: Float = PremiumMaterialLighting.Calibration.RIM_INTENSITY,
        val ambientIblIntensity: Float = PremiumMaterialLighting.Calibration.AMBIENT_IBL_INTENSITY,
        val shadowBias: Float = PremiumMaterialLighting.Calibration.SHADOW_BIAS
    )

    data class Post(
        val exposure: Float = 1.00f,
        val bloomIntensity: Float = PremiumMaterialLighting.Calibration.BLOOM,
        val ssaoStrength: Float = PremiumMaterialLighting.Calibration.SSAO
    )

    val chrome = Chrome()
    val lights = Lights()
    val post = Post()

    /**
     * Exact sRGB/linear transfer for albedo/base-color boundaries.
     * BRDF data such as metallic, roughness, AO and normal values stay linear.
     */
    fun srgbToLinear(channel: Float): Float = when {
        channel <= 0.04045f -> channel.coerceAtLeast(0f) / 12.92f
        else -> ((channel.coerceIn(0f, 1f) + 0.055f) / 1.055f)
            .toDouble().let { it.pow(2.4) }.toFloat()
    }

    fun linearToSrgb(channel: Float): Float = when {
        channel <= 0.0031308f -> channel.coerceAtLeast(0f) * 12.92f
        else -> (1.055 * channel.coerceIn(0f, 1f).toDouble()
            .let { it.pow(1.0 / 2.4) } - 0.055).toFloat()
    }
}
