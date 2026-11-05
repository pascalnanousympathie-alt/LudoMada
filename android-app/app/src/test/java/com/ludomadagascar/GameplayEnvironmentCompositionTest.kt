package com.ludomadagascar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GameplayEnvironmentCompositionTest {
    @Test
    fun tabletopUsesWalnutRatherThanIvoryBeigeTint() {
        val material = GameplayEnvironmentComposition.tabletop()
        val color = material.baseColor

        assertEquals(4, color.size)
        assertTrue("walnut red channel should dominate", color[0] > color[1])
        assertTrue("walnut green channel should dominate blue", color[1] > color[2])
        assertTrue("tabletop must remain medium/dark, not pale beige", color[0] < 0.75f)
        assertEquals(0.44f, material.roughness, 0.001f)
        assertEquals(0.035f, material.metallic, 0.001f)
        assertEquals(0f, material.emission, 0f)
    }

    @Test
    fun tabletopReusesExistingWoodTextureAndReceivesGameplayShadows() {
        assertEquals(1, GameplayEnvironmentComposition.WOOD_MATERIAL_KIND)
        assertTrue(GameplayEnvironmentComposition.PRESERVE_ALBEDO)
        assertTrue(GameplayEnvironmentComposition.RECEIVES_SHADOW)
        assertEquals(1f, PbrShadowReception.uniformValue(
            GameplayEnvironmentComposition.RECEIVES_SHADOW
        ), 0f)
    }
}
