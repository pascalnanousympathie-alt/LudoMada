package com.ludomadagascar

/**
 * Avoids recomputing and resubmitting PBR model/view/projection uniforms when
 * consecutive meshes share the same immutable model transform in one scene.
 *
 * The cache owns a copy because callers commonly reuse and mutate FloatArray
 * instances for animated models. Reset it at every PBR frame after the camera
 * matrices have been updated.
 */
internal class PbrModelMatrixCache {
    private val previous = FloatArray(16)
    private var hasPrevious = false

    fun reset() {
        hasPrevious = false
    }

    fun shouldUpload(modelMatrix: FloatArray): Boolean {
        require(modelMatrix.size == 16) { "A model matrix must contain 16 values" }
        if (hasPrevious && previous.contentEquals(modelMatrix)) return false
        System.arraycopy(modelMatrix, 0, previous, 0, 16)
        hasPrevious = true
        return true
    }
}
