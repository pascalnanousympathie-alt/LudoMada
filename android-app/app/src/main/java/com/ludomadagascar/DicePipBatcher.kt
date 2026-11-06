package com.ludomadagascar

/** Builds one immutable PBR mesh for the pips of each possible die face. */
internal object DicePipBatcher {
    private const val PIP_SPACING = 0.20f
    private const val PIP_CENTER_Y = 0.51f
    private const val PIP_SCALE_XZ = 0.065f
    private const val PIP_SCALE_Y = 0.028f

    fun build(sourcePip: LudoBoardView.Mesh): Array<LudoBoardView.Mesh> =
        Array(6) { valueIndex ->
            val value = valueIndex + 1
            val merged = MeshBatcher.merge(
                positionsForValue(value).map { (x, z) ->
                    MeshBatchInput(
                        positions = sourcePip.positions,
                        normals = sourcePip.normals,
                        uvs = sourcePip.uvsCopy(),
                        indices = sourcePip.indices,
                        transform = pipTransform(x, z)
                    )
                }
            ).single()
            LudoBoardView.Mesh(
                merged.positions,
                merged.normals,
                merged.indices,
                merged.uvs
            )
        }

    private fun positionsForValue(value: Int): List<Pair<Float, Float>> = when (value) {
        1 -> listOf(0f to 0f)
        2 -> listOf(-PIP_SPACING to -PIP_SPACING, PIP_SPACING to PIP_SPACING)
        3 -> listOf(-PIP_SPACING to -PIP_SPACING, 0f to 0f, PIP_SPACING to PIP_SPACING)
        4 -> listOf(
            -PIP_SPACING to -PIP_SPACING, PIP_SPACING to -PIP_SPACING,
            -PIP_SPACING to PIP_SPACING, PIP_SPACING to PIP_SPACING
        )
        5 -> listOf(
            -PIP_SPACING to -PIP_SPACING, PIP_SPACING to -PIP_SPACING,
            0f to 0f,
            -PIP_SPACING to PIP_SPACING, PIP_SPACING to PIP_SPACING
        )
        6 -> listOf(
            -PIP_SPACING to -PIP_SPACING, -PIP_SPACING to 0f, -PIP_SPACING to PIP_SPACING,
            PIP_SPACING to -PIP_SPACING, PIP_SPACING to 0f, PIP_SPACING to PIP_SPACING
        )
        else -> error("Die value must be between 1 and 6")
    }

    /** Column-major scale + translation; equivalent to the former die * pip transforms. */
    private fun pipTransform(x: Float, z: Float) = floatArrayOf(
        PIP_SCALE_XZ, 0f, 0f, 0f,
        0f, PIP_SCALE_Y, 0f, 0f,
        0f, 0f, PIP_SCALE_XZ, 0f,
        x, PIP_CENTER_Y, z, 1f
    )
}
