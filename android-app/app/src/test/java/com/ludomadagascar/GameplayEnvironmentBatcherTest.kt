package com.ludomadagascar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GameplayEnvironmentBatcherTest {
    private fun triangle() = LudoBoardView.Mesh(
        positions = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f),
        normals = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f),
        indices = shortArrayOf(0, 1, 2),
        uvsOverride = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f)
    )

    private fun transform(x: Float = 0f) = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        x, 0f, 0f, 1f
    )

    private fun input(
        name: String,
        order: Int,
        material: String,
        color: FloatArray,
        roughness: Float,
        metallic: Float,
        materialKind: Int,
        albedo: Int,
        normal: Int,
        emission: Float,
        receivesShadow: Boolean,
        doubleSided: Boolean = true,
        x: Float = 0f
    ) = GameplayEnvironmentBatcher.Input(
        name = name,
        drawOrder = order,
        mesh = triangle(),
        transform = transform(x),
        baseColor = color,
        roughness = roughness,
        metallic = metallic,
        materialName = material,
        materialKind = materialKind,
        albedoTexture = albedo,
        normalTexture = normal,
        emission = emission,
        receivesShadow = receivesShadow,
        doubleSided = doubleSided
    )

    @Test
    fun revision7EnvironmentMergesOnlyEquivalentRenderGroups() {
        val inputs = buildList {
            add(input("GMENV_Studio_Floor", 0, "ENV_Studio_Charcoal", floatArrayOf(0.14f, 0.16f, 0.19f, 1f), 0.83f, 0f, 4, 3, 5, 0.006f, false))
            add(input("GMENV_Walnut_Structure_Batch", 1, "LM_Walnut_Edge", floatArrayOf(0.39f, 0.17f, 0.065f, 1f), 0.42f, 0.04f, 1, 1, 6, 0f, false))
            add(input("GMENV_Tabletop_Walnut", 4, "LM_Walnut_Dark", floatArrayOf(154f / 255f, 83f / 255f, 34f / 255f, 1f), 0.36f, 0.035f, 1, 1, 6, 0f, true))
            add(input("GMENV_Brass_Details_Batch", 6, "LM_Champagne_Brass", floatArrayOf(0.72f, 0.46f, 0.19f, 1f), 0.23f, 0.82f, 5, 4, 5, 0.006f, false))
            (1..4).forEach { index ->
                add(input("GMENV_DiceTray_Insert_$index", 7, "LM_Tray_Obsidian", floatArrayOf(0.018f, 0.027f, 0.043f, 1f), 0.34f, 0.24f, 3, 3, 5, 0f, true, x = index.toFloat()))
            }
            (1..4).forEach { index ->
                add(input("GMENV_DiceTray_Frame_$index", 8, "LM_Tray_Chrome", floatArrayOf(0.78f, 0.82f, 0.88f, 1f), 0.045f, 1f, 4, 3, 5, 0f, true, x = index.toFloat()))
            }
            add(input("GMENV_Baobab_Brass_Inlay", 9, "LM_Champagne_Brass", floatArrayOf(0.72f, 0.46f, 0.19f, 1f), 0.23f, 0.82f, 5, 4, 5, 0.006f, false))
        }

        val batches = GameplayEnvironmentBatcher.merge(inputs)

        assertEquals(7, batches.size)
        assertEquals(13, batches.sumOf { it.sourcePartCount })
        assertEquals(listOf(1, 1, 1, 1, 2, 4, 4), batches.map { it.sourcePartCount }.sorted())
        assertEquals(listOf(0, 1, 4, 6, 7, 8, 9), batches.map { it.drawOrder })
        assertEquals(13 * 3, batches.sumOf { it.mesh.positions.size / 3 })
        assertTrue("all source indices remain valid after batch merge", batches.all { batch ->
            batch.mesh.indices.all { (it.toInt() and 0xffff) < batch.mesh.positions.size / 3 }
        })
    }

    @Test
    fun differentShadowOrMaterialPropertiesNeverShareABatch() {
        val color = floatArrayOf(0.72f, 0.46f, 0.19f, 1f)
        val sameMaterial = input("brass-a", 6, "LM_Champagne_Brass", color, 0.23f, 0.82f, 5, 4, 5, 0.006f, false)
        val shadowVariant = sameMaterial.copy(name = "brass-b", mesh = triangle(), transform = transform(1f), receivesShadow = true)
        val orderVariant = sameMaterial.copy(name = "brass-c", mesh = triangle(), transform = transform(2f), drawOrder = 9)
        val batches = GameplayEnvironmentBatcher.merge(listOf(sameMaterial, shadowVariant, orderVariant))
        assertEquals(3, batches.size)
        assertNotEquals(batches[0].receivesShadow, batches[1].receivesShadow)
        assertNotEquals(batches[0].drawOrder, batches[2].drawOrder)
    }
}
