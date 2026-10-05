package com.ludomadagascar

import kotlin.math.pow

/**
 * Lane C calibration contract for the EXISTING GLES3 premium renderer.
 *
 * This is intentionally not a material catalog. It only centralizes the calibrated
 * renderer parameters that Lane A should map to its existing uniforms/post pass.
 * No gameplay state, input, wallet, auth, or payment logic belongs here.
 */
object LudoMaterialCalibration {

    data class Chrome(
        val clearCoat: Float = 0.72f,
        val clearCoatRoughness: Float = 0.055f,
        val metallic: Float = 1.0f,
        val roughness: Float = 0.055f
    )

    data class Lights(
        val keyIntensity: Float = 1.08f,
        val fillIntensity: Float = 0.22f,
        val rimIntensity: Float = 0.34f,
        val ambientIblIntensity: Float = 0.82f,
        val shadowBias: Float = 0.00135f
    )

    data class Post(
        val exposure: Float = 1.00f,
        val bloomIntensity: Float = 0.10f,
        val ssaoStrength: Float = 0.28f
    )

    val chrome = Chrome()
    val lights = Lights()
    val post = Post()

    /**
     * Exact transfer functions for albedo/base-color boundaries.
     * These are color transforms only; BRDF parameters stay linear data.
     */
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
