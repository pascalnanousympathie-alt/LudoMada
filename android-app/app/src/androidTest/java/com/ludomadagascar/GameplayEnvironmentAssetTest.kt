package com.ludomadagascar

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GameplayEnvironmentAssetTest {
    @Test
    fun blenderGameplayEnvironmentGlbLoadsTheCompleteTableSet() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val parts = GlbRuntimeLoader(context).loadGameplayEnvironmentParts()

        assertEquals("All authored GMENV nodes must be admitted", 23, parts.size)
        assertTrue(parts.containsKey("GMENV_Tabletop_Walnut"))
        assertTrue(parts.containsKey("GMENV_Studio_Floor"))
        assertTrue(parts.containsKey("GMENV_Apron_X_1"))
        assertTrue(parts.containsKey("GMENV_Table_Leg_L_N"))
        assertTrue(parts.containsKey("GMENV_Leg_Ferrule_R_S"))

        val tabletop = parts["GMENV_Tabletop_Walnut"]
        assertNotNull("The tabletop node must keep its Blender material", tabletop)
        assertEquals("LM_Walnut_Dark", tabletop!!.materialName)
        assertEquals(0.035f, tabletop.metallic, 0.001f)
        assertEquals(0.42f, tabletop.roughness, 0.001f)
        assertTrue("The tabletop must have indexed 3D geometry", tabletop.mesh.indices.size >= 500)
    }
}
