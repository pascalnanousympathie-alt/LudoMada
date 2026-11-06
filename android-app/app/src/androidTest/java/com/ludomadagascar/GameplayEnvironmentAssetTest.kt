package com.ludomadagascar

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.opengl.Matrix
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
        assertEquals(0.36f, tabletop.roughness, 0.001f)
        assertTrue("The tabletop must have indexed 3D geometry", tabletop.mesh.indices.size >= 500)

        val root = GameplayEnvironmentComposition.blenderGameplayRootTransform()
        val tabletopModel = GameplayEnvironmentComposition.blenderGameplayModelMatrix(root, tabletop.transform)
        val tabletopBounds = transformedBounds(tabletop.mesh.positions, tabletopModel)
        assertEquals("The fitted Blender tabletop must span about 9.75m", 9.747f,
            tabletopBounds[1] - tabletopBounds[0], 0.025f)
        assertEquals("The fitted Blender tabletop must remain square", 9.747f,
            tabletopBounds[5] - tabletopBounds[4], 0.025f)

        val tray01 = requireNotNull(parts["GMENV_DiceTray_Frame_01"])
        val tray03 = requireNotNull(parts["GMENV_DiceTray_Frame_03"])
        val tray01Model = GameplayEnvironmentComposition.blenderGameplayModelMatrix(root, tray01.transform)
        val tray03Model = GameplayEnvironmentComposition.blenderGameplayModelMatrix(root, tray03.transform)
        val tray01Center = transformedOrigin(tray01Model)
        val tray03Center = transformedOrigin(tray03Model)
        assertEquals(-4.0125f, tray01Center[0], 0.003f)
        assertEquals(-4.0125f, tray01Center[2], 0.003f)
        assertEquals(4.0125f, tray03Center[0], 0.003f)
        assertEquals(4.0125f, tray03Center[2], 0.003f)
        assertEquals("Vertical scale must remain unchanged", 1f,
            kotlin.math.sqrt(tray01Model[4] * tray01Model[4] + tray01Model[5] * tray01Model[5] + tray01Model[6] * tray01Model[6]), 0.001f)
    }

    private fun transformedOrigin(matrix: FloatArray): FloatArray {
        val out = FloatArray(4)
        Matrix.multiplyMV(out, 0, matrix, 0, floatArrayOf(0f, 0f, 0f, 1f), 0)
        return out
    }

    private fun transformedBounds(positions: FloatArray, matrix: FloatArray): FloatArray {
        val min = floatArrayOf(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
        val max = floatArrayOf(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)
        var i = 0
        while (i < positions.size) {
            val out = FloatArray(4)
            Matrix.multiplyMV(out, 0, matrix, 0,
                floatArrayOf(positions[i], positions[i + 1], positions[i + 2], 1f), 0)
            for (axis in 0..2) {
                min[axis] = minOf(min[axis], out[axis])
                max[axis] = maxOf(max[axis], out[axis])
            }
            i += 3
        }
        return floatArrayOf(min[0], max[0], min[1], max[1], min[2], max[2])
    }

}
