package com.ludomadagascar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class DicePipBatcherTest {
    private fun sourcePip() = LudoBoardView.Mesh(
        positions = floatArrayOf(
            0f, -1f, 0f,
            1f, 0f, 0f,
            0f, 1f, 0f
        ),
        normals = floatArrayOf(
            0f, -1f, 0f,
            1f, 0f, 0f,
            0f, 1f, 0f
        ),
        indices = shortArrayOf(0, 1, 2),
        uvsOverride = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f)
    )

    @Test
    fun buildsOnePbrMeshForEachDieValueWithTheOriginalPipCount() {
        val source = sourcePip()
        val meshes = DicePipBatcher.build(source)

        assertEquals(6, meshes.size)
        meshes.forEachIndexed { index, mesh ->
            val pipCount = index + 1
            assertEquals(pipCount * 3, mesh.positions.size / 3)
            assertEquals(pipCount * 3, mesh.indices.size)
            assertEquals(pipCount * 3 * 2, mesh.uvsCopy().size)
            assertTrue(mesh.indices.all { (it.toInt() and 0xffff) < mesh.positions.size / 3 })
            for (i in mesh.normals.indices step 3) {
                val length = sqrt(
                    mesh.normals[i] * mesh.normals[i] +
                        mesh.normals[i + 1] * mesh.normals[i + 1] +
                        mesh.normals[i + 2] * mesh.normals[i + 2]
                )
                assertEquals(1f, length, 0.0001f)
            }
        }
        assertEquals("the source pip mesh remains immutable", 9, source.positions.size)
    }

    @Test
    fun preservesEachFacePipLayoutAndLocalDieTransform() {
        val meshes = DicePipBatcher.build(sourcePip())
        // The first source vertex is the sphere's bottom pole, scaled and translated.
        val expectedFirstPip = listOf(
            0f to 0f,
            -0.20f to -0.20f,
            -0.20f to -0.20f,
            -0.20f to -0.20f,
            -0.20f to -0.20f,
            -0.20f to -0.20f
        )
        meshes.forEachIndexed { index, mesh ->
            val expected = expectedFirstPip[index]
            assertEquals(expected.first, mesh.positions[0], 0.0001f)
            assertEquals(0.51f - 0.028f, mesh.positions[1], 0.0001f)
            assertEquals(expected.second, mesh.positions[2], 0.0001f)
        }
        assertEquals(0.20f, meshes[1].positions[9], 0.0001f)
        assertEquals(0.20f, meshes[1].positions[11], 0.0001f)
        assertEquals(0f, meshes[2].positions[9], 0.0001f)
        assertEquals(0f, meshes[2].positions[11], 0.0001f)
        assertEquals(0.20f, meshes[2].positions[18], 0.0001f)
        assertEquals(0.20f, meshes[2].positions[20], 0.0001f)
    }
}
