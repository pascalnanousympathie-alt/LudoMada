package com.ludomadagascar

import kotlin.math.sqrt

/** A mesh's CPU-side geometry together with its local-to-world transform. */
internal data class MeshBatchInput(
    val positions: FloatArray,
    val normals: FloatArray,
    val uvs: FloatArray,
    val indices: ShortArray,
    val transform: FloatArray
)

/** Interleaving is intentionally avoided: GLES renderer uploads the same separate arrays as before. */
internal data class MeshBatchGeometry(
    val positions: FloatArray,
    val normals: FloatArray,
    val uvs: FloatArray,
    val indices: ShortArray
)

/**
 * Combines static authored GLB parts into fewer indexed meshes at surface-load time.
 * Each input transform is baked into positions; normals use inverse-transpose.
 * No per-frame allocation or GL state changes are introduced by this helper.
 */
internal object MeshBatcher {
    const val DEFAULT_MAX_VERTICES = 65_535

    fun merge(
        inputs: List<MeshBatchInput>,
        maxVertices: Int = DEFAULT_MAX_VERTICES
    ): List<MeshBatchGeometry> {
        require(maxVertices in 3..DEFAULT_MAX_VERTICES) { "maxVertices must fit GLES unsigned-short indices" }
        if (inputs.isEmpty()) return emptyList()

        val result = ArrayList<MeshBatchGeometry>()
        var chunk = ArrayList<MeshBatchInput>()
        var chunkVertices = 0

        fun flush() {
            if (chunk.isEmpty()) return
            val vertexCount = chunk.sumOf { it.positions.size / 3 }
            val indexCount = chunk.sumOf { it.indices.size }
            val positions = FloatArray(vertexCount * 3)
            val normals = FloatArray(vertexCount * 3)
            val uvs = FloatArray(vertexCount * 2)
            val indices = ShortArray(indexCount)
            var vertexOffset = 0
            var indexOffset = 0
            for (input in chunk) {
                val sourceVertices = input.positions.size / 3
                require(input.positions.size % 3 == 0 && input.normals.size == input.positions.size) {
                    "Mesh positions/normals have inconsistent dimensions"
                }
                require(input.uvs.size == sourceVertices * 2) { "Mesh UV count does not match its vertices" }
                require(input.transform.size == 16) { "Mesh transform must be a 4x4 matrix" }
                val normalMatrix = inverseTranspose3x3(input.transform)
                for (vertex in 0 until sourceVertices) {
                    val p = vertex * 3
                    val u = vertex * 2
                    val x = input.positions[p]
                    val y = input.positions[p + 1]
                    val z = input.positions[p + 2]
                    positions[p + vertexOffset * 3] = input.transform[0] * x + input.transform[4] * y + input.transform[8] * z + input.transform[12]
                    positions[p + vertexOffset * 3 + 1] = input.transform[1] * x + input.transform[5] * y + input.transform[9] * z + input.transform[13]
                    positions[p + vertexOffset * 3 + 2] = input.transform[2] * x + input.transform[6] * y + input.transform[10] * z + input.transform[14]

                    val nx = input.normals[p]
                    val ny = input.normals[p + 1]
                    val nz = input.normals[p + 2]
                    val tx = normalMatrix[0] * nx + normalMatrix[1] * ny + normalMatrix[2] * nz
                    val ty = normalMatrix[3] * nx + normalMatrix[4] * ny + normalMatrix[5] * nz
                    val tz = normalMatrix[6] * nx + normalMatrix[7] * ny + normalMatrix[8] * nz
                    val length = sqrt(tx * tx + ty * ty + tz * tz).coerceAtLeast(0.000001f)
                    normals[p + vertexOffset * 3] = tx / length
                    normals[p + vertexOffset * 3 + 1] = ty / length
                    normals[p + vertexOffset * 3 + 2] = tz / length
                    uvs[u + vertexOffset * 2] = input.uvs[u]
                    uvs[u + vertexOffset * 2 + 1] = input.uvs[u + 1]
                }
                for (index in input.indices) {
                    val local = index.toInt() and 0xffff
                    require(local < sourceVertices) { "Mesh index $local exceeds vertex count $sourceVertices" }
                    val mergedIndex = vertexOffset + local
                    require(mergedIndex < DEFAULT_MAX_VERTICES) { "Merged mesh index exceeds GLES unsigned-short range" }
                    indices[indexOffset++] = mergedIndex.toShort()
                }
                vertexOffset += sourceVertices
            }
            result += MeshBatchGeometry(positions, normals, uvs, indices)
            chunk = ArrayList()
            chunkVertices = 0
        }

        for (input in inputs) {
            require(input.positions.size % 3 == 0) { "Mesh positions must contain XYZ triplets" }
            val vertices = input.positions.size / 3
            require(vertices in 1..maxVertices) { "One source mesh exceeds the configured batch vertex limit" }
            if (chunk.isNotEmpty() && chunkVertices + vertices > maxVertices) flush()
            chunk += input
            chunkVertices += vertices
        }
        flush()
        return result
    }

    /** Returns the inverse-transpose of the upper-left 3x3 in row-major layout. */
    private fun inverseTranspose3x3(m: FloatArray): FloatArray =
        FloatArray(9).also { writeInverseTranspose3x3(m, it, columnMajor = false) }

    /**
     * Writes the exact inverse-transpose normal matrix in OpenGL column-major layout.
     * The output is caller-owned so the renderer can reuse one 9-float scratch array
     * and upload this matrix once per changed model transform instead of inverting
     * mat3(uModel) for every vertex in the shader.
     */
    internal fun inverseTranspose3x3ColumnMajor(m: FloatArray, output: FloatArray) {
        writeInverseTranspose3x3(m, output, columnMajor = true)
    }

    private fun writeInverseTranspose3x3(
        m: FloatArray,
        output: FloatArray,
        columnMajor: Boolean
    ) {
        require(m.size == 16) { "Model matrix must contain 16 values" }
        require(output.size == 9) { "Normal matrix output must contain 9 values" }

        val a = m[0]; val b = m[4]; val c = m[8]
        val d = m[1]; val e = m[5]; val f = m[9]
        val g = m[2]; val h = m[6]; val i = m[10]
        val c00 = e * i - f * h
        val c01 = f * g - d * i
        val c02 = d * h - e * g
        val c10 = c * h - b * i
        val c11 = a * i - c * g
        val c12 = b * g - a * h
        val c20 = b * f - c * e
        val c21 = c * d - a * f
        val c22 = a * e - b * d
        val determinant = a * c00 + b * c01 + c * c02
        require(kotlin.math.abs(determinant) > 0.0000001f) {
            "Mesh transform has a singular normal matrix"
        }
        val invDet = 1f / determinant

        if (columnMajor) {
            output[0] = c00 * invDet
            output[1] = c10 * invDet
            output[2] = c20 * invDet
            output[3] = c01 * invDet
            output[4] = c11 * invDet
            output[5] = c21 * invDet
            output[6] = c02 * invDet
            output[7] = c12 * invDet
            output[8] = c22 * invDet
        } else {
            output[0] = c00 * invDet
            output[1] = c01 * invDet
            output[2] = c02 * invDet
            output[3] = c10 * invDet
            output[4] = c11 * invDet
            output[5] = c12 * invDet
            output[6] = c20 * invDet
            output[7] = c21 * invDet
            output[8] = c22 * invDet
        }
    }
}
