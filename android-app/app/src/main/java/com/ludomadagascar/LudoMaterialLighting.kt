package com.ludomadagascar

/**
 * Lane C: physically-based material and lighting presets shared by the premium
 * gameplay renderer. Values are expressed for a linear-light PBR workflow.
 *
 * Independent from game state, turn logic, wallet, authentication and input.
 */
object LudoMaterialLighting {

    data class MaterialPreset(
        val name: String,
        val metallic: Float,
        val roughness: Float,
        val ao: Float,
        val emission: Float,
        val normalStrength: Float,
        val clearcoat: Float = 0f
    )

    data class LightingProfile(
        val exposure: Float,
        val iblIntensity: Float,
        val keyIntensity: Float,
        val fillIntensity: Float,
        val rimIntensity: Float,
        val shadowBias: Float,
        val shadowNormalBias: Float,
        val bloomThreshold: Float,
        val bloomIntensity: Float,
        val ssaoStrength: Float
    )

    val lacquerPawn = MaterialPreset("pawn-lacquer", 0.18f, 0.22f, 0.98f, 0f, 0.12f, 0.34f)
    val hydroChromeDie = MaterialPreset("hydro-plated-chrome-die", 1.0f, 0.055f, 1.0f, 0f, 0.045f, 0.72f)
    val chromeTray = MaterialPreset("chrome-tray", 0.94f, 0.085f, 1.0f, 0f, 0.04f, 0.55f)
    val goldAccent = MaterialPreset("gold-accent", 0.92f, 0.14f, 1.0f, 0.018f, 0.07f, 0.28f)
    val boardLacquer = MaterialPreset("board-lacquer", 0.08f, 0.31f, 0.96f, 0f, 0.16f, 0.28f)
    val woodTrim = MaterialPreset("dark-wood-trim", 0.02f, 0.38f, 0.94f, 0f, 0.20f, 0.12f)
    val ceramicCell = MaterialPreset("ceramic-cell", 0.04f, 0.42f, 0.98f, 0f, 0.10f, 0.16f)
    val pip = MaterialPreset("die-pip", 0.72f, 0.16f, 1.0f, 0f, 0.04f, 0.36f)

    val premiumOutdoor = LightingProfile(
        exposure = 1.0f,
        iblIntensity = 0.82f,
        keyIntensity = 1.08f,
        fillIntensity = 0.22f,
        rimIntensity = 0.34f,
        shadowBias = 0.00135f,
        shadowNormalBias = 0.0065f,
        bloomThreshold = 0.72f,
        bloomIntensity = 0.16f,
        ssaoStrength = 0.84f
    )

    val balancedMobile = LightingProfile(
        exposure = 1.0f,
        iblIntensity = 0.74f,
        keyIntensity = 1.0f,
        fillIntensity = 0.18f,
        rimIntensity = 0.26f,
        shadowBias = 0.0017f,
        shadowNormalBias = 0.0085f,
        bloomThreshold = 0.76f,
        bloomIntensity = 0.12f,
        ssaoStrength = 0.68f
    )

    fun preset(name: String): MaterialPreset = when (name) {
        lacquerPawn.name -> lacquerPawn
        hydroChromeDie.name -> hydroChromeDie
        chromeTray.name -> chromeTray
        goldAccent.name -> goldAccent
        boardLacquer.name -> boardLacquer
        woodTrim.name -> woodTrim
        ceramicCell.name -> ceramicCell
        pip.name -> pip
        else -> ceramicCell
    }

    fun lightingFor(qualityTier: Int): LightingProfile =
        if (qualityTier >= 2) premiumOutdoor else balancedMobile

    /**
     * PBR helper for renderers using sRGB UI/material values with linear-light
     * BRDF math. The current renderer can adopt this at shader input boundaries.
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
