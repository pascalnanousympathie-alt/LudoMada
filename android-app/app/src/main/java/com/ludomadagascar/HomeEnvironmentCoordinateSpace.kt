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
