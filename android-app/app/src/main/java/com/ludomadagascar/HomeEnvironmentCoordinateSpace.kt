package com.ludomadagascar

/**
 * The authored Madagascar environment GLB is Z-up. The gameplay renderer uses
 * Android/OpenGL's Y-up convention with its ground plane on X/Z. Rotate the full
 * authored node transform by +90 degrees around X: source -Z becomes world +Y.
 */
internal data class GroundSurfaceOrientation(
    val normals: FloatArray,
    val indices: ShortArray
)

internal data class GroundFootprintGeometry(
    val positions: FloatArray,
    val normals: FloatArray,
    val uvs: FloatArray
)

internal object HomeEnvironmentCoordinateSpace {
    private val zUpToYUp = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, -1f, 0f, 0f,
        0f, 0f, 0f, 1f
    )

    fun toYUpTransform(source: FloatArray): FloatArray {
        require(source.size == 16) { "glTF transform must be a 4x4 matrix" }
        val out = FloatArray(16)
        // Column-major multiplication: out = zUpToYUp * source.
        for (column in 0..3) {
            for (row in 0..3) {
                var value = 0f
                for (k in 0..3) {
                    value += zUpToYUp[k * 4 + row] * source[column * 4 + k]
                }
                out[column * 4 + row] = value
            }
        }
        return out
    }

    /**
     * The authored ground triangles/normals face source +Z, but this scene's
     * Z-up contract maps source -Z to world +Y. Reverse both so the terrain's
     * visible face and PBR normals point upward after coordinate conversion.
     */
    fun faceGroundTowardWorldUp(normals: FloatArray, indices: ShortArray): GroundSurfaceOrientation {
        require(normals.isNotEmpty() && normals.size % 3 == 0) {
            "ground normals must contain complete XYZ vectors"
        }
        require(indices.isNotEmpty() && indices.size % 3 == 0) {
            "ground indices must contain complete triangles"
        }
        val correctedNormals = FloatArray(normals.size) { -normals[it] }
        val correctedIndices = indices.copyOf()
        for (i in correctedIndices.indices step 3) {
            val second = correctedIndices[i + 1]
            correctedIndices[i + 1] = correctedIndices[i + 2]
            correctedIndices[i + 2] = second
        }
        return GroundSurfaceOrientation(correctedNormals, correctedIndices)
    }

    /**
     * Expands the terrain in its authored X/Y plane (the gameplay world's X/Z
     * after Z-up conversion). Vertical relief is scaled around the mean source
     * height, not around zero, so the whole landscape is not translated upward
     * or downward. UVs track the horizontal footprint; normals use the inverse
     * transpose of the complete scale before normalization.
     */
    fun expandGroundFootprint(
        positions: FloatArray,
        normals: FloatArray,
        uvs: FloatArray,
        scale: Float,
        verticalReliefScale: Float = 1f
    ): GroundFootprintGeometry {
        require(positions.isNotEmpty() && positions.size % 3 == 0) {
            "ground positions must contain complete XYZ vertices"
        }
        require(normals.size == positions.size) {
            "ground normal count must match positions"
        }
        require(uvs.size == positions.size / 3 * 2) {
            "ground UV count must match positions"
        }
        require(scale.isFinite() && scale > 0f) {
            "ground footprint scale must be finite and positive"
        }
        require(verticalReliefScale.isFinite() && verticalReliefScale > 0f) {
            "ground vertical relief scale must be finite and positive"
        }

        var meanHeight = 0f
        for (i in positions.indices step 3) meanHeight += positions[i + 2]
        meanHeight /= (positions.size / 3).toFloat()

        val expandedPositions = positions.copyOf()
        for (i in expandedPositions.indices step 3) {
            expandedPositions[i] *= scale
            expandedPositions[i + 1] *= scale
            expandedPositions[i + 2] =
                meanHeight + (expandedPositions[i + 2] - meanHeight) * verticalReliefScale
        }

        val expandedNormals = FloatArray(normals.size)
        for (i in normals.indices step 3) {
            val nx = normals[i] / scale
            val ny = normals[i + 1] / scale
            val nz = normals[i + 2] / verticalReliefScale
            val length = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz)
            require(length > 0.000001f) { "ground normal must be non-zero" }
            expandedNormals[i] = nx / length
            expandedNormals[i + 1] = ny / length
            expandedNormals[i + 2] = nz / length
        }

        val expandedUvs = FloatArray(uvs.size) { index -> uvs[index] * scale }
        return GroundFootprintGeometry(expandedPositions, expandedNormals, expandedUvs)
    }

    /**
     * Aligns the lowest vertices of a small authored prop (for example one grass
     * clump) to the already-transformed terrain surface below its base centroid.
     * Only a vertical translation is applied, preserving blade dimensions,
     * normals and UVs. The input arrays remain immutable.
     */
    fun alignMeshBaseToGroundSurface(
        positions: FloatArray,
        groundPositions: FloatArray,
        groundIndices: ShortArray
    ): FloatArray {
        require(positions.isNotEmpty() && positions.size % 3 == 0) {
            "prop positions must contain complete XYZ vertices"
        }
        require(groundPositions.isNotEmpty() && groundPositions.size % 3 == 0) {
            "ground positions must contain complete XYZ vertices"
        }
        require(groundIndices.isNotEmpty() && groundIndices.size % 3 == 0) {
            "ground indices must contain complete triangles"
        }

        var baseHeight = Float.POSITIVE_INFINITY
        for (i in positions.indices step 3) baseHeight = minOf(baseHeight, positions[i + 2])

        val baseTolerance = maxOf(0.0001f, kotlin.math.abs(baseHeight) * 0.00001f)
        var baseX = 0f
        var baseY = 0f
        var baseCount = 0
        for (i in positions.indices step 3) {
            if (positions[i + 2] <= baseHeight + baseTolerance) {
                baseX += positions[i]
                baseY += positions[i + 1]
                baseCount++
            }
        }
        require(baseCount > 0) { "prop base vertices are missing" }
        baseX /= baseCount.toFloat()
        baseY /= baseCount.toFloat()

        val groundHeight = sampleGroundHeight(groundPositions, groundIndices, baseX, baseY)
            ?: throw IllegalArgumentException("prop base falls outside the terrain mesh")
        val offset = groundHeight - baseHeight
        require(offset.isFinite()) { "prop ground alignment offset must be finite" }

        return positions.copyOf().also { aligned ->
            for (i in aligned.indices step 3) aligned[i + 2] += offset
        }
    }

    private fun sampleGroundHeight(
        positions: FloatArray,
        indices: ShortArray,
        x: Float,
        y: Float
    ): Float? {
        val vertexCount = positions.size / 3
        for (i in indices.indices step 3) {
            val ia = indices[i].toInt() and 0xFFFF
            val ib = indices[i + 1].toInt() and 0xFFFF
            val ic = indices[i + 2].toInt() and 0xFFFF
            require(ia < vertexCount && ib < vertexCount && ic < vertexCount) {
                "ground triangle index is outside the position buffer"
            }

            val ax = positions[ia * 3]
            val ay = positions[ia * 3 + 1]
            val az = positions[ia * 3 + 2]
            val bx = positions[ib * 3]
            val by = positions[ib * 3 + 1]
            val bz = positions[ib * 3 + 2]
            val cx = positions[ic * 3]
            val cy = positions[ic * 3 + 1]
            val cz = positions[ic * 3 + 2]

            val denominator = (by - cy) * (ax - cx) + (cx - bx) * (ay - cy)
            if (kotlin.math.abs(denominator) <= 0.000001f) continue
            val wa = ((by - cy) * (x - cx) + (cx - bx) * (y - cy)) / denominator
            val wb = ((cy - ay) * (x - cx) + (ax - cx) * (y - cy)) / denominator
            val wc = 1f - wa - wb
            if (wa >= -0.0001f && wb >= -0.0001f && wc >= -0.0001f) {
                return wa * az + wb * bz + wc * cz
            }
        }
        return null
    }

    /**
     * The shared PBR fragment shader samples albedo at vUv * 2.0 for tiled
     * materials. The authored sky dome already uses a complete 0..1 equirectangular
     * panorama, so halve only its UVs to keep that HDRI mapped exactly once.
     */
    fun correctSkyPanoramaUvsForShader(uvs: FloatArray): FloatArray {
        require(uvs.isNotEmpty() && uvs.size % 2 == 0) {
            "sky panorama UVs must contain complete pairs"
        }
        return FloatArray(uvs.size) { index -> uvs[index] * 0.5f }
    }

    fun transformPoint(matrix: FloatArray, x: Float, y: Float, z: Float): FloatArray {
        require(matrix.size == 16) { "glTF transform must be a 4x4 matrix" }
        return floatArrayOf(
            matrix[0] * x + matrix[4] * y + matrix[8] * z + matrix[12],
            matrix[1] * x + matrix[5] * y + matrix[9] * z + matrix[13],
            matrix[2] * x + matrix[6] * y + matrix[10] * z + matrix[14]
        )
    }
}
