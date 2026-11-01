package com.ludomadagascar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class HomeEnvironmentCompositionTest {
    @Test
    fun baobabsFrameTheSceneWithoutBlockingTheBrandSightline() {
        val trees = HomeEnvironmentComposition.baobabPlacements()
        assertEquals(4, trees.size)
        assertTrue(HomeEnvironmentComposition.hasClearBrandSightline())
        assertTrue(trees.all { abs(it.x) >= 3.0f })
    }

    @Test
    fun environmentGlbBaobabNodesAreFilteredButTerrainSkyAndGroundRemain() {
        val embedded = listOf(
            "ENV_Baobab_Branch_00",
            "ENV_Baobab_LeafCluster_03",
            "ENV_Baobab_Root_02",
            "ENV_Baobab_Trunk"
        )
        embedded.forEach { assertTrue(HomeEnvironmentComposition.isEmbeddedBaobabPart(it)) }
        listOf(
            "ENV_Grass_00",
            "ENV_Rock_00",
            "ENV_Sky_Dome",
            "ENV_Terrain_HighDetail"
        ).forEach { assertTrue(!HomeEnvironmentComposition.isEmbeddedBaobabPart(it)) }
    }

    @Test
    fun centrelineRockIsFilteredButPerimeterRocksRemain() {
        assertTrue(HomeEnvironmentComposition.isHomeCentrelineOccluderPart("ENV_Rock_04"))
        listOf("ENV_Rock_00","ENV_Rock_01","ENV_Rock_02","ENV_Rock_03")
            .forEach { assertTrue(!HomeEnvironmentComposition.isHomeCentrelineOccluderPart(it)) }
        listOf("ENV_Grass_00","ENV_Sky_Dome","ENV_Terrain_HighDetail")
            .forEach { assertTrue(!HomeEnvironmentComposition.isHomeCentrelineOccluderPart(it)) }
    }

    @Test
    fun onlyPerimeterRocksCastHomeEnvironmentShadows() {
        listOf("ENV_Rock_00", "ENV_Rock_01", "ENV_Rock_02", "ENV_Rock_03")
            .forEach { assertTrue(HomeEnvironmentComposition.castsHomeEnvironmentShadow(it)) }
        listOf(
            "ENV_Rock_04",
            "ENV_Ground_PBR",
            "ENV_Terrain_HighDetail",
            "ENV_Grass_00",
            "ENV_Sky_Dome"
        ).forEach { assertTrue(!HomeEnvironmentComposition.castsHomeEnvironmentShadow(it)) }
    }

    @Test
    fun baobabPlacementIsPairedOnBothSidesAtFrontAndBack() {
        val trees = HomeEnvironmentComposition.baobabPlacements()
        assertEquals(trees[0].x, -trees[1].x, 0.25f)
        assertEquals(trees[0].z, trees[1].z, 0.35f)
        assertEquals(trees[2].x, -trees[3].x, 0.25f)
        assertEquals(trees[2].z, trees[3].z, 0.35f)
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
