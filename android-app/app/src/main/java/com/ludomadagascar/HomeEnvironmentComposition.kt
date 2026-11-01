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
