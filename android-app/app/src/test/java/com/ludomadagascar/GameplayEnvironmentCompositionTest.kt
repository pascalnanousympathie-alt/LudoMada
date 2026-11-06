package com.ludomadagascar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun blenderGameplayEnvironmentUsesTheAlignedLightweightAssetContract() {
        assertEquals(
            "3d/ludomada-gameplay-environment-v1.glb",
            GameplayEnvironmentComposition.BLENDER_ASSET_PATH
        )
        assertEquals(1.1666667f, GameplayEnvironmentComposition.BLENDER_ROOT_TRANSLATION_X, 0.0001f)
        assertTrue(GameplayEnvironmentComposition.acceptsBlenderGameplayPart("GMENV_Tabletop_Walnut"))
        assertTrue(GameplayEnvironmentComposition.acceptsBlenderGameplayPart("GMENV_Studio_Floor"))
        assertFalse(GameplayEnvironmentComposition.acceptsBlenderGameplayPart("LM_Board_Gold_Frame"))
        assertTrue(
            GameplayEnvironmentComposition.hasRequiredBlenderGameplayParts(
                setOf("GMENV_Tabletop_Walnut", "GMENV_Studio_Floor")
            )
        )
        assertFalse(
            GameplayEnvironmentComposition.hasRequiredBlenderGameplayParts(
                setOf("GMENV_Tabletop_Walnut")
            )
        )
    }

    @Test
    fun blenderEnvironmentDrawOrderKeepsFloorLegsApronAndTopInStableLayers() {
        val names = listOf(
            "GMENV_Tabletop_Walnut",
            "GMENV_Brass_Pin_01",
            "GMENV_Apron_Front",
            "GMENV_Table_Leg_FL",
            "GMENV_Studio_Floor",
            "GMENV_Leg_Ferrule_FL",
            "GMENV_Perimeter_Inlay_01",
            "GMENV_DiceTray_Frame_01",
            "GMENV_DiceTray_Insert_01",
            "GMENV_Baobab_Brass_Inlay"
        )
        assertEquals(
            listOf(
                "GMENV_Studio_Floor",
                "GMENV_Table_Leg_FL",
                "GMENV_Leg_Ferrule_FL",
                "GMENV_Apron_Front",
                "GMENV_Tabletop_Walnut",
                "GMENV_Perimeter_Inlay_01",
                "GMENV_Brass_Pin_01",
                "GMENV_DiceTray_Insert_01",
                "GMENV_DiceTray_Frame_01",
                "GMENV_Baobab_Brass_Inlay"
            ),
            names.sortedBy(GameplayEnvironmentComposition::blenderPartDrawOrder)
        )
    }

    @Test
    fun blenderWalnutAndBrassMaterialsKeepTheirPbrRoles() {
        val edge = GameplayEnvironmentComposition.blenderMaterialColor(
            "LM_Walnut_Edge",
            floatArrayOf(0.115f, 0.038f, 0.016f, 1f)
        )
        val top = GameplayEnvironmentComposition.blenderMaterialColor(
            "LM_Walnut_Dark",
            floatArrayOf(0.22f, 0.085f, 0.032f, 1f)
        )
        val brass = GameplayEnvironmentComposition.blenderMaterialColor(
            "LM_Champagne_Brass",
            floatArrayOf(0.72f, 0.46f, 0.19f, 1f)
        )
        assertTrue(edge[0] > 0.30f && edge[0] < top[0])
        assertTrue(top[0] > top[1] && top[1] > top[2])
        assertTrue(brass[0] > brass[1] && brass[1] > brass[2])
        assertEquals(
            GameplayEnvironmentComposition.WOOD_MATERIAL_KIND,
            GameplayEnvironmentComposition.blenderMaterialKind("LM_Walnut_Dark")
        )
        assertTrue(GameplayEnvironmentComposition.blenderPartReceivesGameplayShadow("GMENV_Tabletop_Walnut"))
        assertFalse(GameplayEnvironmentComposition.blenderPartReceivesGameplayShadow("GMENV_Studio_Floor"))
    }

    @Test
    fun blenderGameplayEnvironmentRequiresAllFourCompleteDiceTraysForPremiumAcceptance() {
        val allTrayParts = (1..4).flatMap { index ->
            val suffix = index.toString().padStart(2, '0')
            listOf(
                "GMENV_DiceTray_Frame_$suffix",
                "GMENV_DiceTray_Insert_$suffix"
            )
        }.toSet()

        assertTrue(GameplayEnvironmentComposition.hasCompleteBlenderDiceTraySet(allTrayParts))
        assertTrue(
            GameplayEnvironmentComposition.hasCompleteBlenderDiceTraySet(
                allTrayParts + setOf("GMENV_Tabletop_Walnut", "GMENV_Studio_Floor")
            )
        )
        assertFalse(
            GameplayEnvironmentComposition.hasCompleteBlenderDiceTraySet(
                allTrayParts - "GMENV_DiceTray_Insert_03"
            )
        )
        assertFalse(
            GameplayEnvironmentComposition.hasCompleteBlenderDiceTraySet(
                allTrayParts - "GMENV_DiceTray_Frame_04"
            )
        )
    }

    @Test
    fun blenderChromeDiceTraysUseDedicatedPbrChromeAndMetalTexture() {
        assertTrue(GameplayEnvironmentComposition.acceptsBlenderGameplayPart("GMENV_DiceTray_Frame_01"))
        assertTrue(GameplayEnvironmentComposition.acceptsBlenderGameplayPart("GMENV_DiceTray_Insert_01"))
        assertEquals(4, GameplayEnvironmentComposition.BLENDER_CHROME_MATERIAL_KIND)
        assertEquals(4, GameplayEnvironmentComposition.BLENDER_CHROME_TEXTURE_KIND)
        assertEquals(
            4,
            GameplayEnvironmentComposition.blenderMaterialKind("LM_Tray_Chrome")
        )
        assertEquals(
            4,
            GameplayEnvironmentComposition.blenderTextureKind("LM_Tray_Chrome")
        )
        val chrome = GameplayEnvironmentComposition.blenderMaterialColor(
            "LM_Tray_Chrome",
            floatArrayOf(0.56f, 0.64f, 0.72f, 1f)
        )
        assertEquals(0.78f, chrome[0], 0.001f)
        assertEquals(0.82f, chrome[1], 0.001f)
        assertEquals(0.88f, chrome[2], 0.001f)
        assertTrue(GameplayEnvironmentComposition.blenderPartReceivesGameplayShadow("GMENV_DiceTray_Frame_01"))
        assertTrue(GameplayEnvironmentComposition.blenderPartReceivesGameplayShadow("GMENV_DiceTray_Insert_01"))
    }
}
