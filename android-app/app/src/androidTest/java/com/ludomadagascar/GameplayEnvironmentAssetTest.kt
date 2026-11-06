package com.ludomadagascar

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GameplayEnvironmentAssetTest {
    @Test
    fun blenderGameplayEnvironmentGlbLoadsTheCompleteRevision7BatchedTableSet() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val parts = GlbRuntimeLoader(context).loadGameplayEnvironmentParts()

        val expectedRevision7Nodes = buildSet {
            addAll(
                listOf(
                    "GMENV_Studio_Floor",
                    "GMENV_Walnut_Structure_Batch",
                    "GMENV_Tabletop_Walnut",
                    "GMENV_Brass_Details_Batch",
                    "GMENV_Baobab_Brass_Inlay"
                )
            )
            for (index in 1..4) {
                val suffix = index.toString().padStart(2, '0')
                add("GMENV_DiceTray_Frame_$suffix")
                add("GMENV_DiceTray_Insert_$suffix")
            }
        }
        assertEquals("Blender r7 must expose exactly 13 optimized gameplay meshes", expectedRevision7Nodes, parts.keys)
        assertTrue(
            "The loaded GLB must contain both static batches and all four dice tray pairs",
            GameplayEnvironmentComposition.hasRevision7BatchedGameplayEnvironment(parts.keys)
        )
        assertTrue(
            "All four Blender PBR materials are authored double-sided",
            parts.values.all { it.doubleSided }
        )

        val tabletop = requireNotNull(parts["GMENV_Tabletop_Walnut"]) {
            "The tabletop node must keep its Blender material"
        }
        assertEquals("LM_Walnut_Dark", tabletop.materialName)
        assertEquals(0.035f, tabletop.metallic, 0.001f)
        assertEquals(0.42f, tabletop.roughness, 0.001f)
        assertTrue("The tabletop must have indexed 3D geometry", tabletop.mesh.indices.size >= 500)
    }
}
