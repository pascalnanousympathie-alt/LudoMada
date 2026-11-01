package com.ludomadagascar

/**
 * The authored Madagascar environment GLB is Z-up. The gameplay renderer uses
 * Android/OpenGL's Y-up convention with its ground plane on X/Z. Rotate the full
 * authored node transform by +90 degrees around X: source -Z becomes world +Y.
 */
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

    fun transformPoint(matrix: FloatArray, x: Float, y: Float, z: Float): FloatArray {
        require(matrix.size == 16) { "glTF transform must be a 4x4 matrix" }
        return floatArrayOf(
            matrix[0] * x + matrix[4] * y + matrix[8] * z + matrix[12],
            matrix[1] * x + matrix[5] * y + matrix[9] * z + matrix[13],
            matrix[2] * x + matrix[6] * y + matrix[10] * z + matrix[14]
        )
    }
}
