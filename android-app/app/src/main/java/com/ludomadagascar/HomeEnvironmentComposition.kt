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
    private val placements = listOf(
        HomeBaobabPlacement(-7.4f, -1.05f, -5.8f, 0.62f),
        HomeBaobabPlacement(7.2f, -1.05f, -5.9f, 0.64f),
        HomeBaobabPlacement(-4.2f, -1.02f, -10.5f, 0.42f),
        HomeBaobabPlacement(4.1f, -1.02f, -10.7f, 0.44f),
        HomeBaobabPlacement(0f, -1.02f, -13.6f, 0.32f)
    )

    fun baobabPlacements(): List<HomeBaobabPlacement> = placements

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

    fun hasClearBrandSightline(): Boolean = placements.none {
        abs(it.x) < 2.5f && it.z in -9.0f..-4.0f && it.scale >= 0.42f
    }
}
