package com.ludomadagascar

import kotlin.math.abs

/** Scenic placement contract for the Home preview: keep the centre brand/board sightline open. */
internal data class HomeBaobabPlacement(
    val x: Float,
    val y: Float,
    val z: Float,
    val scale: Float
)

internal object HomeEnvironmentComposition {
    // Baobabs deliberately frame both sides of the Home scene. The centreline
    // stays empty at every depth so the wordmark and tabletop never sit behind a trunk.
    private val placements = listOf(
        HomeBaobabPlacement(-7.2f, -1.05f, -5.8f, 0.58f),
        HomeBaobabPlacement(7.1f, -1.05f, -6.1f, 0.60f),
        HomeBaobabPlacement(-8.6f, -1.02f, -11.8f, 0.43f),
        HomeBaobabPlacement(8.7f, -1.02f, -12.0f, 0.45f)
    )

    fun baobabPlacements(): List<HomeBaobabPlacement> = placements

    /** The environment GLB also contains a centreline baobab; the Home renderer uses side-framed real GLB instances instead. */
    fun isEmbeddedBaobabPart(nodeName: String): Boolean =
        nodeName.startsWith("ENV_Baobab_")

    /** ENV_Rock_04 is authored on x≈0/y≈8 in Z-up space and projects behind the Home wordmark. */
    fun isHomeCentrelineOccluderPart(nodeName: String): Boolean =
        nodeName == "ENV_Rock_04"

    /** Only perimeter rocks cast environment shadows; ground/grass/sky remain receivers/background. */
    fun castsHomeEnvironmentShadow(nodeName: String): Boolean =
        nodeName.startsWith("ENV_Rock_") && !isHomeCentrelineOccluderPart(nodeName)

    /**
     * A/B diagnostic: exclude large Home baobab instances from the low-resolution
     * environment shadow map while leaving perimeter-rock and gameplay shadows intact.
     * Keep false until a full-screen Ramus comparison confirms the ground/horizon benefit.
     */
    fun castsHomeBaobabShadows(): Boolean = false

    /**
     * HDRI panoramas are already authored with their final color balance.
     * Preserve the source albedo only for the sky; ordinary PBR surfaces keep
     * the shared shader's neutral blend.
     */
    fun usesSourceAlbedo(materialName: String): Boolean =
        materialName == "ENV_Sky_HDRI"

    /** Warmly lifts dark authored environment albedo without changing alpha or exceeding display range. */
    fun liftEnvironmentColor(source: FloatArray, multiplier: Float): FloatArray {
        require(source.size == 4) { "environment colour must be RGBA" }
        require(multiplier.isFinite() && multiplier > 0f) { "multiplier must be finite and positive" }
        return floatArrayOf(
            (source[0] * multiplier * 1.10f).coerceIn(0f, 1f),
            (source[1] * multiplier * 1.02f).coerceIn(0f, 1f),
            (source[2] * multiplier * 0.94f).coerceIn(0f, 1f),
            source[3].coerceIn(0f, 1f)
        )
    }

    fun hasClearBrandSightline(): Boolean = placements.all { abs(it.x) >= 3.0f }
}
