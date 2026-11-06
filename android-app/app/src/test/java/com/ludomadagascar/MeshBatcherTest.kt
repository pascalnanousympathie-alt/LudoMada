package com.ludomadagascar

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.math.sqrt

class MeshBatcherTest {
    private fun triangle(transform: FloatArray = identity()) = MeshBatchInput(
        positions = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f),
        normals = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f),
        uvs = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f),
        indices = shortArrayOf(0, 1, 2),
        transform = transform
    )

    private fun identity() = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f
    )

    @Test
    fun combinesIndicesAndPreservesUvWithPerPartTranslation() {
        val second = identity().also { it[12] = 4f }
        val merged = MeshBatcher.merge(listOf(triangle(), triangle(second))).single()

        assertEquals(6, merged.positions.size / 3)
        assertArrayEquals(shortArrayOf(0, 1, 2, 3, 4, 5), merged.indices)
        assertArrayEquals(
            floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f, 4f, 0f, 0f, 5f, 0f, 0f, 4f, 1f, 0f),
            merged.positions,
            0.0001f
        )
        assertArrayEquals(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f), merged.normals, 0.0001f)
        assertArrayEquals(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f, 0f), merged.uvs, 0.0001f)
    }

    @Test
    fun appliesInverseTransposeToNormalsForNonUniformScale() {
        val transform = identity().also {
            it[0] = 2f
            it[5] = 3f
            it[10] = 4f
        }
        val merged = MeshBatcher.merge(listOf(triangle(transform))).single()
        assertArrayEquals(floatArrayOf(2f, 0f, 0f, 0f, 3f, 0f, 0f, 0f, 1f), merged.positions, 0.0001f)
        assertArrayEquals(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f), merged.normals, 0.0001f)
        for (i in merged.normals.indices step 3) {
            val length = sqrt(merged.normals[i] * merged.normals[i] + merged.normals[i + 1] * merged.normals[i + 1] + merged.normals[i + 2] * merged.normals[i + 2])
            assertEquals(1f, length, 0.0001f)
        }
    }

    @Test
    fun appliesInverseTransposeToNonAxisAlignedNormalsUnderNonUniformScale() {
        val transform = identity().also { it[0] = 2f }
        val diagonal = 1f / sqrt(2f)
        val input = triangle(transform).copy(
            normals = floatArrayOf(
                diagonal, diagonal, 0f,
                diagonal, diagonal, 0f,
                diagonal, diagonal, 0f
            )
        )
        val merged = MeshBatcher.merge(listOf(input)).single()
        val expectedX = 1f / sqrt(5f)
        val expectedY = 2f / sqrt(5f)
        for (i in merged.normals.indices step 3) {
            assertEquals(expectedX, merged.normals[i], 0.0001f)
            assertEquals(expectedY, merged.normals[i + 1], 0.0001f)
            assertEquals(0f, merged.normals[i + 2], 0.0001f)
        }
    }

    @Test
    fun transformsPositionsAndNormalsThroughRotationAndTranslation() {
        // A +90 degree rotation around Z, followed by a world translation.
        val transform = identity().also {
            it[0] = 0f; it[1] = 1f
            it[4] = -1f; it[5] = 0f
            it[12] = 2f; it[13] = 3f
        }
        val merged = MeshBatcher.merge(listOf(triangle(transform))).single()
        assertArrayEquals(
            floatArrayOf(2f, 3f, 0f, 2f, 4f, 0f, 1f, 3f, 0f),
            merged.positions,
            0.0001f
        )
        assertArrayEquals(
            floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f),
            merged.normals,
            0.0001f
        )
    }

    @Test
    fun splitsBatchesBeforeUnsignedShortVertexLimit() {
        val merged = MeshBatcher.merge(listOf(triangle(), triangle()), maxVertices = 3)
        assertEquals(2, merged.size)
        assertArrayEquals(shortArrayOf(0, 1, 2), merged[0].indices)
        assertArrayEquals(shortArrayOf(0, 1, 2), merged[1].indices)
    }

    @Test
    fun rejectsSingularTransformsAndOutOfRangeIndices() {
        val singular = identity().also { it[0] = 0f }
        assertThrows(IllegalArgumentException::class.java) {
            MeshBatcher.merge(listOf(triangle(singular)))
        }
        val invalidIndex = triangle().copy(indices = shortArrayOf(0, 1, 3))
        assertThrows(IllegalArgumentException::class.java) {
            MeshBatcher.merge(listOf(invalidIndex))
        }
    }
}
