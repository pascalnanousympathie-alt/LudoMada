package com.ludomadagascar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeEnvironmentCompositionTest {
    @Test
    fun baobabsFrameTheSceneWithoutBlockingTheBrandSightline() {
        val trees = HomeEnvironmentComposition.baobabPlacements()
        assertEquals(5, trees.size)
        assertTrue(HomeEnvironmentComposition.hasClearBrandSightline())
        assertFalse(trees.any { kotlin.math.abs(it.x) < 2.5f && it.z in -9f..-4f && it.scale >= 0.42f })
    }

    @Test
    fun baobabPlacementIsSymmetricAtTheFrontEdges() {
        val trees = HomeEnvironmentComposition.baobabPlacements()
        assertEquals(trees[0].x, -trees[1].x, 0.25f)
        assertEquals(trees[0].z, trees[1].z, 0.25f)
        assertEquals(trees[0].scale, trees[1].scale, 0.05f)
    }

    @Test
    fun liftedEnvironmentColorIsWarmClampedAndPreservesAlpha() {
        val source = floatArrayOf(0.20f, 0.18f, 0.12f, 0.82f)
        val result = HomeEnvironmentComposition.liftEnvironmentColor(source, 1.30f)
        assertTrue(result[0] > source[0])
        assertTrue(result[1] > source[1])
        assertTrue(result[2] > source[2])
        assertEquals(source[3], result[3], 0f)
        assertTrue(result.all { it in 0f..1f })
    }
}
