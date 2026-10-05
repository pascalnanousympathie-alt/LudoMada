package com.ludomadagascar

import android.content.Context
import android.opengl.Matrix
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Small, dependency-free glTF 2.0 / GLB runtime reader for the verified
 * LudoMada rev3 hero asset.
 *
 * Scope is intentionally limited to static mesh attributes used by the
 * gameplay renderer: POSITION, NORMAL, TEXCOORD_0 and UNSIGNED_SHORT indices,
 * plus node TRS and material PBR factors. External images/textures are not
 * required by the current rev3 GLB.
 */
internal class GlbRuntimeLoader(private val context: Context) {

    data class Part(
        val name: String,
        val mesh: LudoBoardView.Mesh,
        val transform: FloatArray,
        val baseColor: FloatArray,
        val metallic: Float,
        val roughness: Float
    )

    private data class Accessor(
        val bufferView: Int,
        val byteOffset: Int,
        val componentType: Int,
        val count: Int,
        val type: String,
        val normalized: Boolean
    )

    private data class BufferView(
        val byteOffset: Int,
        val byteLength: Int,
        val byteStride: Int
    )

    fun loadHeroParts(): Map<String, Part> {
        val bytes = BufferedInputStream(
            context.assets.open("3d/ludomada-premium-3d-rev3.glb")
        ).use { it.readBytes() }

        require(bytes.size >= 20) { "GLB too small" }

        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(header.int == 0x46546C67) { "Invalid GLB magic" }
        require(header.int == 2) { "Unsupported GLB version" }
        val declaredLength = header.int
        require(declaredLength == bytes.size) {
            "GLB length mismatch: declared=\${declaredLength} actual=\${bytes.size}"
        }

        var cursor = 12
        var json: JSONObject? = null
        var bin: ByteArray? = null

        while (cursor + 8 <= bytes.size) {
            val chunkHeader = ByteBuffer.wrap(bytes, cursor, 8).order(ByteOrder.LITTLE_ENDIAN)
            val chunkLength = chunkHeader.int
            val chunkType = chunkHeader.int
            cursor += 8
            require(chunkLength >= 0 && cursor + chunkLength <= bytes.size) {
                "Invalid GLB chunk"
            }
            val payload = bytes.copyOfRange(cursor, cursor + chunkLength)
            cursor += chunkLength

            when (chunkType) {
                0x4E4F534A -> {
                    val text = payload.toString(Charsets.UTF_8).trimEnd('\u0000', ' ', '\n', '\r', '\t')
                    json = JSONObject(text)
                }
                0x004E4942 -> bin = payload
            }
        }

        val root = requireNotNull(json) { "GLB JSON chunk missing" }
        val binary = requireNotNull(bin) { "GLB BIN chunk missing" }

        val bufferViews = parseBufferViews(root.optJSONArray("bufferViews"))
        val accessors = parseAccessors(root.optJSONArray("accessors"))
        val meshes = root.optJSONArray("meshes") ?: JSONArray()
        val materials = root.optJSONArray("materials") ?: JSONArray()
        val nodes = root.optJSONArray("nodes") ?: JSONArray()

        val parts = linkedMapOf<String, Part>()
        var diePivot = floatArrayOf(0f, 0f, 0f)
        for (i in 0 until nodes.length()) {
            val node = nodes.optJSONObject(i) ?: continue
            val meshIndex = node.optInt("mesh", -1)
            if (meshIndex !in 0 until meshes.length()) continue
            val nodeName = node.optString("name", "node_\${i}")

            val hero = nodeName == "LM_Pawn_Base_Weighted" ||
                nodeName == "LM_Pawn_Collar" ||
                nodeName == "LM_Pawn_Spherical_Head" ||
                nodeName == "LM_Pawn_Tapered_Body" ||
                nodeName == "LM_Pawn_Underside_Insert" ||
                nodeName == "LM_Die_HydroChrome_Body"
            if (!hero) continue

            val meshJson = meshes.optJSONObject(meshIndex) ?: continue
            val primitive = meshJson.optJSONArray("primitives")?.optJSONObject(0) ?: continue
            val attributes = primitive.optJSONObject("attributes") ?: continue

            val posIndex = attributes.optInt("POSITION", -1)
            val normalIndex = attributes.optInt("NORMAL", -1)
            val uvIndex = attributes.optInt("TEXCOORD_0", -1)
            val indexIndex = primitive.optInt("indices", -1)
            if (posIndex < 0 || normalIndex < 0 || uvIndex < 0 || indexIndex < 0) continue

            val positions = readFloatAccessor(binary, bufferViews, accessors, posIndex, 3)
            val normals = readFloatAccessor(binary, bufferViews, accessors, normalIndex, 3)
            val uvs = readFloatAccessor(binary, bufferViews, accessors, uvIndex, 2)
            val indices = readIndexAccessor(binary, bufferViews, accessors, indexIndex)

            require(positions.size / 3 == normals.size / 3) {
                "Normal count mismatch for \${nodeName}"
            }
            require(positions.size / 3 == uvs.size / 2) {
                "UV count mismatch for \${nodeName}"
            }

            val translation = node.optJSONArray("translation")?.let { t ->
                if (t.length() == 3) floatArrayOf(
                    t.optDouble(0).toFloat() - (subtractTranslation?.getOrNull(0) ?: 0f),
                    t.optDouble(1).toFloat() - (subtractTranslation?.getOrNull(1) ?: 0f),
                    t.optDouble(2).toFloat() - (subtractTranslation?.getOrNull(2) ?: 0f)
                ) else floatArrayOf(0f, 0f, 0f)
            } ?: floatArrayOf(0f, 0f, 0f)
            if (nodeName == "LM_Die_HydroChrome_Body") diePivot = translation.copyOf()

            val materialIndex = primitive.optInt("material", -1)
            val material = if (materialIndex in 0 until materials.length()) {
                materials.optJSONObject(materialIndex)
            } else {
                null
            }
            val pbr = material?.optJSONObject("pbrMetallicRoughness")
            val baseColor = pbr?.optJSONArray("baseColorFactor")?.let { arr ->
                FloatArray(4) { k -> arr.optDouble(k, 1.0).toFloat() }
            } ?: floatArrayOf(1f, 1f, 1f, 1f)
            val metallic = pbr?.optDouble("metallicFactor", 0.0)?.toFloat() ?: 0f
            val roughness = pbr?.optDouble("roughnessFactor", 1.0)?.toFloat() ?: 1f

            parts[nodeName] = Part(
                name = nodeName,
                mesh = LudoBoardView.Mesh(positions, normals, indices),
                transform = nodeTransform(
                    node,
                    if (nodeName == "LM_Die_HydroChrome_Body") diePivot else null
                ),
                baseColor = baseColor,
                metallic = metallic,
                roughness = roughness
            )
        }

        require(parts.containsKey("LM_Pawn_Tapered_Body")) { "Pawn body missing from GLB" }
        require(parts.containsKey("LM_Pawn_Spherical_Head")) { "Pawn head missing from GLB" }
        require(parts.containsKey("LM_Die_HydroChrome_Body")) { "Die body missing from GLB" }

        return parts
    }

    private fun parseBufferViews(array: JSONArray?): List<BufferView> {
        if (array == null) return emptyList()
        return List(array.length()) { i ->
            val obj = array.getJSONObject(i)
            BufferView(
                byteOffset = obj.optInt("byteOffset", 0),
                byteLength = obj.optInt("byteLength"),
                byteStride = obj.optInt("byteStride", 0)
            )
        }
    }

    private fun parseAccessors(array: JSONArray?): List<Accessor> {
        requireNotNull(array) { "GLB accessors missing" }
        return List(array.length()) { i ->
            val obj = array.getJSONObject(i)
            Accessor(
                bufferView = obj.optInt("bufferView", -1),
                byteOffset = obj.optInt("byteOffset", 0),
                componentType = obj.optInt("componentType"),
                count = obj.optInt("count"),
                type = obj.optString("type"),
                normalized = obj.optBoolean("normalized", false)
            )
        }
    }

    private fun componentCount(type: String): Int = when (type) {
        "SCALAR" -> 1
        "VEC2" -> 2
        "VEC3" -> 3
        "VEC4" -> 4
        else -> error("Unsupported accessor type: \${type}")
    }

    private fun readFloatAccessor(
        bin: ByteArray,
        views: List<BufferView>,
        accessors: List<Accessor>,
        accessorIndex: Int,
        expectedComponents: Int
    ): FloatArray {
        val accessor = accessors[accessorIndex]
        require(accessor.componentType == 5126) {
            "Expected FLOAT accessor, got \${accessor.componentType}"
        }
        val components = componentCount(accessor.type)
        require(components == expectedComponents) {
            "Expected \${expectedComponents} components, got \${components}"
        }
        val view = views[accessor.bufferView]
        val elementBytes = components * 4
        val stride = if (view.byteStride > 0) view.byteStride else elementBytes
        val start = view.byteOffset + accessor.byteOffset
        val out = FloatArray(accessor.count * components)
        val bb = ByteBuffer.wrap(bin).order(ByteOrder.LITTLE_ENDIAN)

        for (i in 0 until accessor.count) {
            val src = start + i * stride
            for (c in 0 until components) {
                out[i * components + c] = bb.getFloat(src + c * 4)
            }
        }
        return out
    }

    private fun readIndexAccessor(
        bin: ByteArray,
        views: List<BufferView>,
        accessors: List<Accessor>,
        accessorIndex: Int
    ): ShortArray {
        val accessor = accessors[accessorIndex]
        require(accessor.type == "SCALAR") { "Index accessor must be SCALAR" }
        require(accessor.componentType == 5123) {
            "Expected UNSIGNED_SHORT indices, got \${accessor.componentType}"
        }
        val view = views[accessor.bufferView]
        val stride = if (view.byteStride > 0) view.byteStride else 2
        val start = view.byteOffset + accessor.byteOffset
        val out = ShortArray(accessor.count)
        val bb = ByteBuffer.wrap(bin).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until accessor.count) {
            out[i] = bb.getShort(start + i * stride)
        }
        return out
    }

    private fun nodeTransform(node: JSONObject, subtractTranslation: FloatArray? = null): FloatArray {
        // glTF nodes may encode transforms either as a full 4x4 matrix or as
        // TRS. Preserve the authored orientation so non-zero asset rotations
        // (notably the hydro-chrome die) are not silently discarded at runtime.
        node.optJSONArray("matrix")?.let { matrix ->
            if (matrix.length() == 16) {
                val out = FloatArray(16) { i ->
                    matrix.optDouble(i, if (i % 5 == 0) 1.0 else 0.0).toFloat()
                }
                if (subtractTranslation != null) {
                    out[12] -= subtractTranslation[0]
                    out[13] -= subtractTranslation[1]
                    out[14] -= subtractTranslation[2]
                }
                return out
            }
        }

        val out = FloatArray(16)
        Matrix.setIdentityM(out, 0)

        node.optJSONArray("translation")?.let { t ->
            if (t.length() == 3) {
                Matrix.translateM(
                    out, 0,
                    t.optDouble(0).toFloat(),
                    t.optDouble(1).toFloat(),
                    t.optDouble(2).toFloat()
                )
            }
        }

        node.optJSONArray("rotation")?.let { q ->
            if (q.length() == 4) {
                var x = q.optDouble(0).toFloat()
                var y = q.optDouble(1).toFloat()
                var z = q.optDouble(2).toFloat()
                var w = q.optDouble(3).toFloat()
                val len = kotlin.math.sqrt(x * x + y * y + z * z + w * w)
                if (len > 0.000001f) {
                    x /= len
                    y /= len
                    z /= len
                    w /= len

                    // glTF quaternion order is [x, y, z, w]. Android Matrix
                    // uses column-major storage, so construct the equivalent
                    // rotation matrix before applying node scale.
                    val r = FloatArray(16)
                    val xx = x * x
                    val yy = y * y
                    val zz = z * z
                    val xy = x * y
                    val xz = x * z
                    val yz = y * z
                    val wx = w * x
                    val wy = w * y
                    val wz = w * z
                    Matrix.setIdentityM(r, 0)
                    r[0] = 1f - 2f * (yy + zz)
                    r[1] = 2f * (xy + wz)
                    r[2] = 2f * (xz - wy)
                    r[4] = 2f * (xy - wz)
                    r[5] = 1f - 2f * (xx + zz)
                    r[6] = 2f * (yz + wx)
                    r[8] = 2f * (xz + wy)
                    r[9] = 2f * (yz - wx)
                    r[10] = 1f - 2f * (xx + yy)

                    val rotated = FloatArray(16)
                    Matrix.multiplyMM(rotated, 0, out, 0, r, 0)
                    System.arraycopy(rotated, 0, out, 0, 16)
                }
            }
        }

        node.optJSONArray("scale")?.let { s ->
            if (s.length() == 3) {
                Matrix.scaleM(
                    out, 0,
                    s.optDouble(0, 1.0).toFloat(),
                    s.optDouble(1, 1.0).toFloat(),
                    s.optDouble(2, 1.0).toFloat()
                )
            }
        }

        return out
    }
}
