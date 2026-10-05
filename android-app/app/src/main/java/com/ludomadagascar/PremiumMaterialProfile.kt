package com.ludomadagascar

/**
 * Material / lighting contract for the premium 3D gameplay lane.
 *
 * This file is intentionally renderer-agnostic: Chat A can map these values to
 * Filament, GLES3 shaders, or the GLES2 fallback without changing game state.
 */
data class PremiumMaterialProfile(
    val baseR: Float,
    val baseG: Float,
    val baseB: Float,
    val metallic: Float,
    val roughness: Float,
    val ao: Float,
    val emission: Float,
    val normalStrength: Float,
    val clearCoat: Float,
    val clearCoatRoughness: Float,
    val environmentIntensity: Float = 1.0f
) {
    init {
        require(baseR in 0f..4f && baseG in 0f..4f && baseB in 0f..4f)
        require(metallic in 0f..1f)
        require(roughness in 0.035f..1f)
        require(ao in 0f..1f)
        require(emission >= 0f)
        require(normalStrength in 0f..1f)
        require(clearCoat in 0f..1f)
        require(clearCoatRoughness in 0.035f..1f)
        require(environmentIntensity >= 0f)
    }

    fun withEnvironment(multiplier: Float): PremiumMaterialProfile =
        copy(environmentIntensity = (environmentIntensity * multiplier).coerceAtLeast(0f))
}

object PremiumMaterialProfiles {
    /** Deep lacquer over a pigmented polymer pawn. */
    val RED_PAWN = PremiumMaterialProfile(
        baseR = 0.86f, baseG = 0.08f, baseB = 0.10f,
        metallic = 0.04f, roughness = 0.18f, ao = 0.96f,
        emission = 0.0f, normalStrength = 0.16f,
        clearCoat = 0.34f, clearCoatRoughness = 0.10f
    )

    val GREEN_PAWN = RED_PAWN.copy(baseR = 0.07f, baseG = 0.70f, baseB = 0.27f)
    val GOLD_PAWN = RED_PAWN.copy(baseR = 0.95f, baseG = 0.62f, baseB = 0.08f)
    val BLUE_PAWN = RED_PAWN.copy(baseR = 0.08f, baseG = 0.32f, baseB = 0.90f)

    /** White/mercury die body: very low roughness, full metallic response. */
    val HYDRO_CHROME_DIE = PremiumMaterialProfile(
        baseR = 0.92f, baseG = 0.95f, baseB = 1.00f,
        metallic = 1.0f, roughness = 0.055f, ao = 0.99f,
        emission = 0.0f, normalStrength = 0.05f,
        clearCoat = 0.72f, clearCoatRoughness = 0.055f,
        environmentIntensity = 1.35f
    )

    /** Dark chrome/plated die tray and trim. */
    val DARK_CHROME = PremiumMaterialProfile(
        baseR = 0.08f, baseG = 0.10f, baseB = 0.13f,
        metallic = 1.0f, roughness = 0.12f, ao = 0.98f,
        emission = 0.0f, normalStrength = 0.06f,
        clearCoat = 0.28f, clearCoatRoughness = 0.08f,
        environmentIntensity = 1.15f
    )

    /** Warm satin-finished board surface. */
    val BOARD_SATIN = PremiumMaterialProfile(
        baseR = 0.72f, baseG = 0.36f, baseB = 0.09f,
        metallic = 0.06f, roughness = 0.36f, ao = 0.94f,
        emission = 0.0f, normalStrength = 0.24f,
        clearCoat = 0.15f, clearCoatRoughness = 0.17f,
        environmentIntensity = 0.90f
    )

    /** Ivory path/tiles with slight varnish rather than a flat diffuse card. */
    val PATH_IVORY = PremiumMaterialProfile(
        baseR = 0.92f, baseG = 0.86f, baseB = 0.72f,
        metallic = 0.03f, roughness = 0.42f, ao = 0.97f,
        emission = 0.0f, normalStrength = 0.18f,
        clearCoat = 0.10f, clearCoatRoughness = 0.20f,
        environmentIntensity = 0.82f
    )

    /** Neutral cloth/table surround: rougher and reflection-suppressing. */
    val TABLE_CLOTH = PremiumMaterialProfile(
        baseR = 0.025f, baseG = 0.055f, baseB = 0.10f,
        metallic = 0.0f, roughness = 0.78f, ao = 0.99f,
        emission = 0.0f, normalStrength = 0.20f,
        clearCoat = 0.02f, clearCoatRoughness = 0.32f,
        environmentIntensity = 0.45f
    )

    /** Soft-touch dark trim for bevels and recessed frame pieces. */
    val DARK_TRIM = PremiumMaterialProfile(
        baseR = 0.055f, baseG = 0.065f, baseB = 0.08f,
        metallic = 0.45f, roughness = 0.24f, ao = 0.98f,
        emission = 0.0f, normalStrength = 0.15f,
        clearCoat = 0.16f, clearCoatRoughness = 0.13f,
        environmentIntensity = 0.72f
    )
\n    /** Stable runtime mapping used by the gameplay renderer. */
    fun pawn(playerIndex: Int): PremiumMaterialProfile = when (playerIndex and 3) {
        0 -> RED_PAWN
        1 -> GREEN_PAWN
        2 -> GOLD_PAWN
        else -> BLUE_PAWN
    }

    fun surface(kind: Surface): PremiumMaterialProfile = when (kind) {
        Surface.PAWN -> RED_PAWN
        Surface.HYDRO_CHROME_DIE -> HYDRO_CHROME_DIE
        Surface.DARK_CHROME -> DARK_CHROME
        Surface.BOARD -> BOARD_SATIN
        Surface.PATH -> PATH_IVORY
        Surface.TABLE -> TABLE_CLOTH
        Surface.TRIM -> DARK_TRIM
    }

    enum class Surface {
        PAWN, HYDRO_CHROME_DIE, DARK_CHROME, BOARD, PATH, TABLE, TRIM
    }
}

/**
 * sRGB transfer helpers for albedo/base-color boundaries.
 * BRDF parameters (metallic/roughness/AO/normal) remain linear/data values.
 */
object PremiumColorSpace {
    fun srgbToLinear(c: Float): Float {
        val v = c.coerceIn(0f, 1f)
        return if (v <= 0.04045f) v / 12.92f
        else ((v + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()
    }

    fun linearToSrgb(c: Float): Float {
        val v = c.coerceIn(0f, 1f)
        return if (v <= 0.0031308f) v * 12.92f
        else (1.055 * v.toDouble().pow(1.0 / 2.4) - 0.055).toFloat()
    }
}

/**
 * Compact lighting rig contract. Values are world-space directions/intensities
 * and can be mapped directly to Filament Light, GLES uniforms, or baked lightmaps.
 */
data class PremiumLightingRig(
    val keyDirectionX: Float,
    val keyDirectionY: Float,
    val keyDirectionZ: Float,
    val keyIntensity: Float,
    val fillDirectionX: Float,
    val fillDirectionY: Float,
    val fillDirectionZ: Float,
    val fillIntensity: Float,
    val rimDirectionX: Float,
    val rimDirectionY: Float,
    val rimDirectionZ: Float,
    val rimIntensity: Float,
    val ambientIntensity: Float,
    val shadowStrength: Float,
    val shadowBias: Float
) {
    companion object {
        val GAMEPLAY = PremiumLightingRig(
            keyDirectionX = -0.42f,
            keyDirectionY = 0.92f,
            keyDirectionZ = 0.36f,
            keyIntensity = 1.08f,
            fillDirectionX = 0.62f,
            fillDirectionY = 0.54f,
            fillDirectionZ = -0.55f,
            fillIntensity = 0.22f,
            rimDirectionX = 0.18f,
            rimDirectionY = 0.74f,
            rimDirectionZ = -0.62f,
            rimIntensity = 0.34f,
            ambientIntensity = 0.82f,
            shadowStrength = 0.88f,
            shadowBias = 0.00135f
        )
    }
}
