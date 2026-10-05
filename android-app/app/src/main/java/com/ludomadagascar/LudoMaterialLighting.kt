package com.ludomadagascar

import kotlin.math.max
import kotlin.math.min

/**
 * Lane C material / lighting contract.
 *
 * Pure rendering data and small color helpers only: no game state, turn,
 * movement, wallet, auth, or payment behavior belongs here.
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

enum class MaterialQualityTier {
    SAFE,
    BALANCED,
    PREMIUM
}

data class ReflectionProfile(
    val intensity: Float,
    val fresnelBoost: Float,
    val maxMip: Float,
    val roughnessFloor: Float
) {
    init {
        require(intensity >= 0f)
        require(fresnelBoost >= 0f)
        require(maxMip >= 0f)
        require(roughnessFloor in 0.045f..1f)
    }
}

data class RenderBudget(
    val shadowMapSize: Int,
    val environmentResolution: Int,
    val ssao: Float,
    val bloom: Float,
    val particleCap: Int
) {
    init {
        require(shadowMapSize >= 128)
        require(environmentResolution >= 16)
        require(ssao in 0f..1f)
        require(bloom >= 0f)
        require(particleCap >= 0)
    }
}

data class RenderQualityProfile(
    val tier: MaterialQualityTier,
    val wood: PbrMaterialProfile,
    val pawn: PbrMaterialProfile,
    val board: PbrMaterialProfile,
    val tray: PbrMaterialProfile,
    val chromeDie: PbrMaterialProfile,
    val gold: PbrMaterialProfile,
    val reflections: ReflectionProfile,
    val budget: RenderBudget
)

object LudoMaterialLighting {

    val WOOD = PbrMaterialProfile(
        metallic = 0.02f,
        roughness = 0.52f,
        ao = 0.96f,
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

    val PREMIUM_REFLECTIONS = ReflectionProfile(
        intensity = 0.92f,
        fresnelBoost = 1.18f,
        maxMip = 5f,
        roughnessFloor = 0.05f
    )

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
        shadowBias = 0.0016f,
        shadowNormalBias = 0.0035f
    )

    val POST_PREMIUM = PostProcess(
        exposure = 1.04f,
        saturation = 1.045f,
        contrast = 1.035f,
        warmBias = 0.020f,
        ssao = 0.30f,
        bloom = 0.12f,
        bloomThreshold = 0.72f
    )

    val SAFE_PROFILE = RenderQualityProfile(
        tier = MaterialQualityTier.SAFE,
        wood = WOOD.copy(roughness = 0.58f, normalStrength = 0.18f),
        pawn = LACQUERED_PLAYER.copy(roughness = 0.28f, normalStrength = 0.10f),
        board = IVORY_BOARD.copy(normalStrength = 0.08f),
        tray = DARK_METAL_TRAY.copy(metallic = 0.82f),
        chromeDie = HYDRO_CHROME_DIE.copy(roughness = 0.11f),
        gold = GOLD_ACCENT.copy(roughness = 0.20f),
        reflections = PREMIUM_REFLECTIONS.copy(intensity = 0.45f, maxMip = 2f),
        budget = RenderBudget(256, 32, 0.08f, 0.04f, 48)
    )

    val BALANCED_PROFILE = RenderQualityProfile(
        tier = MaterialQualityTier.BALANCED,
        wood = WOOD,
        pawn = LACQUERED_PLAYER,
        board = IVORY_BOARD,
        tray = DARK_METAL_TRAY,
        chromeDie = HYDRO_CHROME_DIE.copy(roughness = 0.065f),
        gold = GOLD_ACCENT,
        reflections = PREMIUM_REFLECTIONS.copy(intensity = 0.72f, maxMip = 4f),
        budget = RenderBudget(384, 48, 0.18f, 0.07f, 96)
    )

    val PREMIUM_PROFILE = RenderQualityProfile(
        tier = MaterialQualityTier.PREMIUM,
        wood = WOOD,
        pawn = LACQUERED_PLAYER,
        board = IVORY_BOARD,
        tray = DARK_METAL_TRAY,
        chromeDie = HYDRO_CHROME_DIE,
        gold = GOLD_ACCENT,
        reflections = PREMIUM_REFLECTIONS,
        budget = RenderBudget(512, 64, 0.30f, 0.12f, 128)
    )

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

    fun profileFor(tier: MaterialQualityTier): RenderQualityProfile = when (tier) {
        MaterialQualityTier.SAFE -> SAFE_PROFILE
        MaterialQualityTier.BALANCED -> BALANCED_PROFILE
        MaterialQualityTier.PREMIUM -> PREMIUM_PROFILE
    }

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

    /**
     * Converts a material roughness into an IBL mip level.
     * The floor keeps the chrome die from losing highlight contrast.
     */
    fun reflectionMip(roughness: Float, profile: ReflectionProfile): Float {
        val r = clamp(roughness, profile.roughnessFloor, 1f)
        return (r * r * profile.maxMip).coerceIn(0f, profile.maxMip)
    }

    /**
     * Fresnel-weighted reflection multiplier for metallic materials.
     */
    fun reflectionStrength(
        cosTheta: Float,
        metallic: Float,
        profile: ReflectionProfile
    ): Float {
        val m = clamp(metallic, 0f, 1f)
        val fresnel = schlickFresnel(cosTheta, 0.04f + 0.96f * m)
        return (fresnel * profile.fresnelBoost * profile.intensity).coerceIn(0f, 2f)
    }

    private fun pow5(x: Float): Float {
        val y = clamp(x, 0f, 1f)
        val y2 = y * y
        return y2 * y2 * y
    }

    private fun clamp(value: Float, low: Float, high: Float): Float =
        min(high, max(low, value))
}
