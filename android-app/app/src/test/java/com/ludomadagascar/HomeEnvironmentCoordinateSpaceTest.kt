package com.ludomadagascar

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeEnvironmentCoordinateSpaceTest {
    @Test
    fun negativeZUpAxisBecomesPositiveWorldY() {
        val identity = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f
        )
        val converted = HomeEnvironmentCoordinateSpace.toYUpTransform(identity)
        val up = HomeEnvironmentCoordinateSpace.transformPoint(converted, 0f, 0f, -1f)
        assertEquals(0f, up[0], 0.0001f)
        assertEquals(1f, up[1], 0.0001f)
        assertEquals(0f, up[2], 0.0001f)
    }

    @Test
    fun sourceGroundHeightMapsBelowTheGameplayBoardPlane() {
        val identity = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f
        )
        val converted = HomeEnvironmentCoordinateSpace.toYUpTransform(identity)
        val ground = HomeEnvironmentCoordinateSpace.transformPoint(converted, 2f, 3f, 1f)
        assertEquals(2f, ground[0], 0.0001f)
        assertEquals(-1f, ground[1], 0.0001f)
        assertEquals(3f, ground[2], 0.0001f)
    }

    @Test
    fun authoredNodeTranslationIsRotatedWithItsGeometry() {
        val translated = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 2f, 1f
        )
        val converted = HomeEnvironmentCoordinateSpace.toYUpTransform(translated)
        assertEquals(-2f, converted[13], 0.0001f)
        assertEquals(0f, converted[14], 0.0001f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonMatrixTransforms() {
        HomeEnvironmentCoordinateSpace.toYUpTransform(floatArrayOf(1f, 2f, 3f))
    }
}
