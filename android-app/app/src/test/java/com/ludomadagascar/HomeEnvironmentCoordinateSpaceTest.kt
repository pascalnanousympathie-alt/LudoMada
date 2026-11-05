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

    @Test
    fun expandedGroundFootprintScalesPlaneAndUvsButPreservesHeight() {
        val sourcePositions = floatArrayOf(
            -12f, -12f, 0.6f,
            12f, -12f, 0.7f
        )
        val sourceNormals = floatArrayOf(
            0.6f, 0f, -0.8f,
            0f, 0f, -1f
        )
        val sourceUvs = floatArrayOf(0f, 1f, 5f, -4f)

        val expanded = HomeEnvironmentCoordinateSpace.expandGroundFootprint(
            sourcePositions,
            sourceNormals,
            sourceUvs,
            scale = 4f
        )

        assertEquals(-48f, expanded.positions[0], 0f)
        assertEquals(-48f, expanded.positions[1], 0f)
        assertEquals(0.6f, expanded.positions[2], 0f)
        assertEquals(48f, expanded.positions[3], 0f)
        assertEquals(-48f, expanded.positions[4], 0f)
        assertEquals(0.7f, expanded.positions[5], 0f)

        assertEquals(0.1843f, expanded.normals[0], 0.001f)
        assertEquals(0f, expanded.normals[1], 0.0001f)
        assertEquals(-0.9829f, expanded.normals[2], 0.001f)
        assertEquals(-1f, expanded.normals[5], 0f)

        assertEquals(0f, expanded.uvs[0], 0f)
        assertEquals(4f, expanded.uvs[1], 0f)
        assertEquals(20f, expanded.uvs[2], 0f)
        assertEquals(-16f, expanded.uvs[3], 0f)

        assertEquals(-12f, sourcePositions[0], 0f)
        assertEquals(0.6f, sourceNormals[0], 0f)
        assertEquals(5f, sourceUvs[2], 0f)
    }

    @Test
    fun verticalReliefExpandsAroundTheMeanAndUsesInverseTransposeNormals() {
        val sourcePositions = floatArrayOf(
            -12f, -12f, 0.6f,
            12f, -12f, 0.7f
        )
        val sourceNormals = floatArrayOf(
            0.6f, 0f, -0.8f,
            0f, 0f, -1f
        )
        val sourceUvs = floatArrayOf(0f, 1f, 5f, -4f)

        val expanded = HomeEnvironmentCoordinateSpace.expandGroundFootprint(
            sourcePositions,
            sourceNormals,
            sourceUvs,
            scale = 4f,
            verticalReliefScale = 3.0f
        )

        assertEquals(0.50f, expanded.positions[2], 0.0001f)
        assertEquals(0.80f, expanded.positions[5], 0.0001f)
        assertEquals(0.4903f, expanded.normals[0], 0.001f)
        assertEquals(0f, expanded.normals[1], 0.0001f)
        assertEquals(-0.8716f, expanded.normals[2], 0.001f)
        assertEquals(-1f, expanded.normals[5], 0f)
        assertEquals(0.6f, sourcePositions[2], 0f)
        assertEquals(0.7f, sourcePositions[5], 0f)
    }

    @Test
    fun grassBaseAlignsToSlopedGroundAndPreservesBladeHeightAndSourceArrays() {
        val groundPositions = floatArrayOf(
            0f, 0f, 1.0f,
            2f, 0f, 1.5f,
            0f, 2f, 2.0f,
            2f, 2f, 2.5f
        )
        val groundIndices = shortArrayOf(0, 1, 2, 1, 3, 2)
        val sourceGrass = floatArrayOf(
            0.5f, 0.5f, 0.25f,
            1.0f, 0.5f, 0.25f,
            0.75f, 0.75f, 0.80f
        )

        val aligned = HomeEnvironmentCoordinateSpace.alignMeshBaseToGroundSurface(
            sourceGrass,
            groundPositions,
            groundIndices
        )

        // The base centroid is (0.75, 0.5); the sloped ground there is 1.4375.
        assertEquals(1.4375f, aligned[2], 0.0001f)
        assertEquals(1.4375f, aligned[5], 0.0001f)
        assertEquals(1.9875f, aligned[8], 0.0001f)
        assertEquals(0.55f, aligned[8] - aligned[2], 0.0001f)
        assertEquals(0.25f, sourceGrass[2], 0f)
        assertEquals(0.80f, sourceGrass[8], 0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsPropBaseOutsideTheTerrainMesh() {
        HomeEnvironmentCoordinateSpace.alignMeshBaseToGroundSurface(
            floatArrayOf(5f, 5f, 0f, 5.1f, 5f, 0f, 5f, 5.1f, 0.4f),
            floatArrayOf(0f, 0f, 1f, 1f, 0f, 1f, 0f, 1f, 1f),
            shortArrayOf(0, 1, 2)
        )
    }

    @Test
    fun verticalReliefPreservesMeanElevationForAsymmetricTerrain() {
        val sourcePositions = floatArrayOf(
            -2f, 0f, 0.2f,
            0f, 1f, 0.4f,
            2f, 0f, 0.9f
        )
        val sourceNormals = floatArrayOf(
            0f, 0f, -1f,
            0f, 0f, -1f,
            0f, 0f, -1f
        )
        val sourceUvs = floatArrayOf(0f, 0f, 0.5f, 1f, 1f, 0f)

        val expanded = HomeEnvironmentCoordinateSpace.expandGroundFootprint(
            sourcePositions,
            sourceNormals,
            sourceUvs,
            scale = 4f,
            verticalReliefScale = 3.0f
        )

        assertEquals(-0.4f, expanded.positions[2], 0.0001f)
        assertEquals(0.2f, expanded.positions[5], 0.0001f)
        assertEquals(1.7f, expanded.positions[8], 0.0001f)
        val meanElevation = (expanded.positions[2] + expanded.positions[5] + expanded.positions[8]) / 3f
        assertEquals(0.5f, meanElevation, 0.0001f)

        // The input mesh remains immutable for safe reuse across renderer passes.
        assertEquals(0.2f, sourcePositions[2], 0f)
        assertEquals(0.4f, sourcePositions[5], 0f)
        assertEquals(0.9f, sourcePositions[8], 0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonPositiveVerticalReliefScale() {
        HomeEnvironmentCoordinateSpace.expandGroundFootprint(
            floatArrayOf(0f, 0f, 0f),
            floatArrayOf(0f, 0f, -1f),
            floatArrayOf(0f, 1f),
            scale = 1f,
            verticalReliefScale = 0f
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonPositiveGroundFootprintScale() {
        HomeEnvironmentCoordinateSpace.expandGroundFootprint(
            floatArrayOf(0f, 0f, 0f),
            floatArrayOf(0f, 0f, -1f),
            floatArrayOf(0f, 1f),
            scale = 0f
        )
    }

    @Test
    fun terrainNormalsAndWindingAreReversedTowardWorldUp() {
        val sourceNormals = floatArrayOf(
            0f, 0f, 1f,
            0f, 0f, 1f,
            0f, 0f, 1f
        )
        val orientation = HomeEnvironmentCoordinateSpace.faceGroundTowardWorldUp(
            sourceNormals,
            shortArrayOf(0, 1, 2)
        )

        assertEquals(-1f, orientation.normals[2], 0.0001f)
        assertEquals(-1f, orientation.normals[5], 0.0001f)
        assertEquals(-1f, orientation.normals[8], 0.0001f)
        assertEquals(0.toShort(), orientation.indices[0])
        assertEquals(2.toShort(), orientation.indices[1])
        assertEquals(1.toShort(), orientation.indices[2])

        val identity = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f
        )
        val worldNormal = HomeEnvironmentCoordinateSpace.transformPoint(
            HomeEnvironmentCoordinateSpace.toYUpTransform(identity),
            orientation.normals[0],
            orientation.normals[1],
            orientation.normals[2]
        )
        assertEquals(1f, worldNormal[1], 0.0001f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsIncompleteGroundTriangleIndices() {
        HomeEnvironmentCoordinateSpace.faceGroundTowardWorldUp(
            floatArrayOf(0f, 0f, 1f),
            shortArrayOf(0, 1)
        )
    }

    @Test
    fun skyPanoramaUvsCompensateForSharedShaderTilingScale() {
        val source = floatArrayOf(0f, 0f, 0.9791667f, 1f, 0.5f, 0.25f)
        val corrected = HomeEnvironmentCoordinateSpace.correctSkyPanoramaUvsForShader(source)

        assertEquals(0f, corrected[0], 0.0001f)
        assertEquals(0f, corrected[1], 0.0001f)
        assertEquals(0.48958335f, corrected[2], 0.0001f)
        assertEquals(0.5f, corrected[3], 0.0001f)
        assertEquals(0.25f, corrected[4], 0.0001f)
        assertEquals(0.125f, corrected[5], 0.0001f)
        assertEquals(0.9791667f, source[2], 0.0001f)
        assertEquals(1f, source[3], 0.0001f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsIncompleteSkyPanoramaUvPairs() {
        HomeEnvironmentCoordinateSpace.correctSkyPanoramaUvsForShader(floatArrayOf(0f, 1f, 0.5f))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonMatrixTransforms() {
        HomeEnvironmentCoordinateSpace.toYUpTransform(floatArrayOf(1f, 2f, 3f))
    }
}
