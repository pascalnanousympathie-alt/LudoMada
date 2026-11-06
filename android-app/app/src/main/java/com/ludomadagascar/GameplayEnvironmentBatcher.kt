package com.ludomadagascar

/**
 * Merges static Blender gameplay-environment meshes only when their complete
 * PBR render state and draw layer match. Transforms are baked once at surface
 * setup; no merge work or geometry allocation occurs in the frame loop.
 */
internal object GameplayEnvironmentBatcher {
    data class Input(
        val name: String,
        val drawOrder: Int,
        val mesh: LudoBoardView.Mesh,
        val transform: FloatArray,
        val baseColor: FloatArray,
        val roughness: Float,
        val metallic: Float,
        val materialName: String,
        val materialKind: Int,
        val albedoTexture: Int,
        val normalTexture: Int,
        val emission: Float,
        val receivesShadow: Boolean,
        val doubleSided: Boolean
    )

    data class Batch(
        val mesh: LudoBoardView.Mesh,
        val baseColor: FloatArray,
        val roughness: Float,
        val metallic: Float,
        val materialKind: Int,
        val albedoTexture: Int,
        val normalTexture: Int,
        val emission: Float,
        val receivesShadow: Boolean,
        val doubleSided: Boolean,
        val drawOrder: Int,
        val sourcePartCount: Int
    )

    private data class Key(
        val materialName: String,
        val drawOrder: Int,
        val baseColorBits: List<Int>,
        val roughnessBits: Int,
        val metallicBits: Int,
        val materialKind: Int,
        val albedoTexture: Int,
        val normalTexture: Int,
        val emissionBits: Int,
        val receivesShadow: Boolean,
        val doubleSided: Boolean
    )

    fun merge(inputs: List<Input>): List<Batch> {
        if (inputs.isEmpty()) return emptyList()
        val groups = inputs.groupBy { input ->
            require(input.baseColor.size == 4) { "Environment base color must be RGBA" }
            Key(
                materialName = input.materialName,
                drawOrder = input.drawOrder,
                baseColorBits = input.baseColor.map { it.toBits() },
                roughnessBits = input.roughness.toBits(),
                metallicBits = input.metallic.toBits(),
                materialKind = input.materialKind,
                albedoTexture = input.albedoTexture,
                normalTexture = input.normalTexture,
                emissionBits = input.emission.toBits(),
                receivesShadow = input.receivesShadow,
                doubleSided = input.doubleSided
            )
        }
        return groups.values.flatMap { group ->
            val first = group.first()
            val geometry = MeshBatcher.merge(group.map { draw ->
                MeshBatchInput(
                    positions = draw.mesh.positions,
                    normals = draw.mesh.normals,
                    uvs = draw.mesh.uvsCopy(),
                    indices = draw.mesh.indices,
                    transform = draw.transform
                )
            })
            geometry.map { merged ->
                Batch(
                    mesh = LudoBoardView.Mesh(
                        merged.positions,
                        merged.normals,
                        merged.indices,
                        merged.uvs
                    ),
                    baseColor = first.baseColor.copyOf(),
                    roughness = first.roughness,
                    metallic = first.metallic,
                    materialKind = first.materialKind,
                    albedoTexture = first.albedoTexture,
                    normalTexture = first.normalTexture,
                    emission = first.emission,
                    receivesShadow = first.receivesShadow,
                    doubleSided = first.doubleSided,
                    drawOrder = first.drawOrder,
                    sourcePartCount = group.size
                )
            }
        }
    }
}
