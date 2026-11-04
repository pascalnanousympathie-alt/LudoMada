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

        var meanHeight = 0.0
        for (i in positions.indices step 3) meanHeight += positions[i + 2]
        meanHeight = (meanHeight / (positions.size / 3)).toFloat()

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
