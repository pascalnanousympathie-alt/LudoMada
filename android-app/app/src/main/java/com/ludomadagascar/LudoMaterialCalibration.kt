package com.ludomadagascar

import kotlin.math.pow

/**
 * Lane C compatibility façade for the canonical PremiumMaterialLighting contract.
 *
 * Existing consumers can keep this API while all numerical tuning remains in
 * PremiumMaterialLighting.Calibration (one source of truth).
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

    fun srgbToLinear(channel: Float): Float {
        val c = channel.coerceIn(0f, 1f)
        return if (c <= 0.04045f) c / 12.92f
        else ((c + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()
    }

    fun linearToSrgb(channel: Float): Float {
        val c = channel.coerceIn(0f, 1f)
        return if (c <= 0.0031308f) c * 12.92f
        else (1.055 * c.toDouble().pow(1.0 / 2.4) - 0.055).toFloat()
    }
}
