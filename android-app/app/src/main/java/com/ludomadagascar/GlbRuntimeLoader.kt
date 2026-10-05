package com.ludomadagascar

import android.content.Context
import android.opengl.Matrix
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Lightweight glTF/GLB reader used by the GLES3 premium renderer. */
internal class GlbRuntimeLoader(private val context: Context) {
    data class Part(
        val name: String,
        val mesh: LudoBoardView.Mesh,
        val transform: FloatArray,
        val baseColor: FloatArray,
        val metallic: Float,
        val roughness: Float,
        val materialName: String
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

    private val heroNames = setOf(
        "LM_Pawn_Base_Weighted",
        "LM_Pawn_Collar",
        "LM_Pawn_Spherical_Head",
        "LM_Pawn_Tapered_Body",
        "LM_Pawn_Underside_Insert",
        "LM_Die_HydroChrome_Body"
    )

    fun loadHeroParts(): Map<String, Part> =
        loadParts("3d/ludomada-premium-3d-rev3.glb") { heroNames.contains(it) }

    fun loadEnvironmentParts(): Map<String, Part> =
        loadParts("3d/ludomada-environment-final.glb") {
            it.startsWith("ENV_") && !it.startsWith("ENV_Baobab_")
        }

    fun loadBaobabParts(): Map<String, Part> =
        loadParts("3d/environment/baobab-real.glb") { it.startsWith("baobab-tree-") }

    private fun loadParts(assetPath: String, accept: (String) -> Boolean): Map<String, Part> {
        val bytes = BufferedInputStream(context.assets.open(assetPath)).use { it.readBytes() }
        require(bytes.size >= 20) { "GLB too small: $assetPath" }

        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(header.int == 0x46546C67) { "Invalid GLB magic: $assetPath" }
        require(header.int == 2) { "Unsupported GLB version: $assetPath" }
        require(header.int == bytes.size) { "GLB length mismatch: $assetPath" }

        var cursor = 12
        var root: JSONObject? = null
        var binary: ByteArray? = null
        while (cursor + 8 <= bytes.size) {
            val chunk = ByteBuffer.wrap(bytes, cursor, 8).order(ByteOrder.LITTLE_ENDIAN)
            val length = chunk.int
            val type = chunk.int
            cursor += 8
            require(length >= 0 && cursor + length <= bytes.size) { "Invalid GLB chunk" }
            val payload = bytes.copyOfRange(cursor, cursor + length)
            cursor += length
            when (type) {
                0x4E4F534A -> root = JSONObject(
                    payload.toString(Charsets.UTF_8).trimEnd('\u0000', ' ', '\n', '\r', '\t')
                )
                0x004E4942 -> binary = payload
            }
        }
        val gltf = requireNotNull(root) { "GLB JSON missing: $assetPath" }
        val bin = requireNotNull(binary) { "GLB BIN missing: $assetPath" }

        val views = parseViews(gltf.optJSONArray("bufferViews"))
        val accessors = parseAccessors(gltf.optJSONArray("accessors"))
        val meshes = gltf.optJSONArray("meshes") ?: JSONArray()
        val materials = gltf.optJSONArray("materials") ?: JSONArray()
        val nodes = gltf.optJSONArray("nodes") ?: JSONArray()
        val out = linkedMapOf<String, Part>()

        for (i in 0 until nodes.length()) {
            val node = nodes.optJSONObject(i) ?: continue
            val name = node.optString("name", "node_$i")
            if (!accept(name)) continue
            val meshIndex = node.optInt("mesh", -1)
            if (meshIndex !in 0 until meshes.length()) continue

            val meshJson = meshes.optJSONObject(meshIndex) ?: continue
            val primitive = meshJson.optJSONArray("primitives")?.optJSONObject(0) ?: continue
            val attributes = primitive.optJSONObject("attributes") ?: continue
            val pos = attributes.optInt("POSITION", -1)
            val nor = attributes.optInt("NORMAL", -1)
            val uv = attributes.optInt("TEXCOORD_0", -1)
            val ind = primitive.optInt("indices", -1)
            if (pos < 0 || nor < 0) continue

            val positions = readFloats(bin, views, accessors, pos, 3)
            val normals = readFloats(bin, views, accessors, nor, 3)
            val uvs = if (uv >= 0) readFloats(bin, views, accessors, uv, 2) else FloatArray(positions.size / 3 * 2)
            val indices = if (ind >= 0) readIndices(bin, views, accessors, ind) else sequentialIndices(positions.size / 3)

            require(positions.size == normals.size) { "Normal mismatch: $name" }
            require(positions.size / 3 * 2 == uvs.size) { "UV mismatch: $name" }

            val materialIndex = primitive.optInt("material", -1)
            val material = if (materialIndex in 0 until materials.length()) {
                materials.optJSONObject(materialIndex)
            } else null
            val pbr = material?.optJSONObject("pbrMetallicRoughness")
            val baseColor = pbr?.optJSONArray("baseColorFactor")?.let { a ->
                FloatArray(4) { k -> a.optDouble(k, 1.0).toFloat() }
            } ?: floatArrayOf(1f, 1f, 1f, 1f)

            out[name] = Part(
                name = name,
                mesh = LudoBoardView.Mesh(positions, normals, indices, uvs),
                transform = nodeTransform(node),
                baseColor = baseColor,
                metallic = pbr?.optDouble("metallicFactor", 0.0)?.toFloat() ?: 0f,
                roughness = pbr?.optDouble("roughnessFactor", 1.0)?.toFloat() ?: 1f,
                materialName = material?.optString("name", "") ?: ""
            )
        }
        require(out.isNotEmpty()) { "No accepted GLB parts in $assetPath" }
        return out
    }

    private fun parseViews(array: JSONArray?): List<BufferView> =
        if (array == null) emptyList() else List(array.length()) { i ->
            val o = array.getJSONObject(i)
            BufferView(o.optInt("byteOffset", 0), o.optInt("byteLength"), o.optInt("byteStride", 0))
        }

    private fun parseAccessors(array: JSONArray?): List<Accessor> {
        requireNotNull(array) { "GLB accessors missing" }
        return List(array.length()) { i ->
            val o = array.getJSONObject(i)
            Accessor(
                bufferView = o.optInt("bufferView", -1),
                byteOffset = o.optInt("byteOffset", 0),
                componentType = o.optInt("componentType"),
                count = o.optInt("count"),
                type = o.optString("type"),
                normalized = o.optBoolean("normalized", false)
            )
        }
    }

    private fun components(type: String): Int = when (type) {
        "SCALAR" -> 1
        "VEC2" -> 2
        "VEC3" -> 3
        "VEC4" -> 4
        else -> error("Unsupported accessor type: $type")
    }

    private fun readFloats(
        bin: ByteArray,
        views: List<BufferView>,
        accessors: List<Accessor>,
        accessorIndex: Int,
        expected: Int
    ): FloatArray {
        val a = accessors[accessorIndex]
        val count = components(a.type)
        require(a.componentType == 5126 || (a.normalized && (a.componentType == 5122 || a.componentType == 5123))) {
            "Unsupported normalized attribute accessor"
        }
        require(count == expected) { "Expected $expected components, got $count" }
        val v = views[a.bufferView]
        val bytesPerComponent = when(a.componentType){5126->4;5122,5123->2;else->error("component")}
        val stride = if (v.byteStride > 0) v.byteStride else count * bytesPerComponent
        val start = v.byteOffset + a.byteOffset
        val out = FloatArray(a.count * count)
        val bb = ByteBuffer.wrap(bin).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until a.count) {
            val src = start + i * stride
            for (c in 0 until count) {
                val off = src + c * bytesPerComponent
                out[i * count + c] = when(a.componentType){
                    5126 -> bb.getFloat(off)
                    5122 -> (bb.getShort(off).toInt()/32767f).coerceIn(-1f,1f)
                    5123 -> ((bb.getShort(off).toInt() and 0xFFFF)/65535f).coerceIn(0f,1f)
                    else -> error("Unsupported float component")
                }
            }
        }
        return out
    }

    private fun sequentialIndices(vertexCount: Int): ShortArray {
        require(vertexCount % 3 == 0) { "Non-indexed mesh is not triangle aligned" }
        require(vertexCount <= 65535) { "Mesh too large for short indices" }
        return ShortArray(vertexCount) { it.toShort() }
    }

    private fun readIndices(
        bin: ByteArray,
        views: List<BufferView>,
        accessors: List<Accessor>,
        accessorIndex: Int
    ): ShortArray {
        val a = accessors[accessorIndex]
        require(a.type == "SCALAR") { "Index accessor must be scalar" }
        require(a.componentType == 5123) { "Environment GLB must use UNSIGNED_SHORT indices" }
        val v = views[a.bufferView]
        val stride = if (v.byteStride > 0) v.byteStride else 2
        val start = v.byteOffset + a.byteOffset
        val out = ShortArray(a.count)
        val bb = ByteBuffer.wrap(bin).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until a.count) out[i] = bb.getShort(start + i * stride)
        return out
    }

    private fun nodeTransform(node: JSONObject): FloatArray {
        node.optJSONArray("matrix")?.let { m ->
            if (m.length() == 16) return FloatArray(16) { i ->
                m.optDouble(i, if (i % 5 == 0) 1.0 else 0.0).toFloat()
            }
        }

        val out = FloatArray(16)
        Matrix.setIdentityM(out, 0)
        node.optJSONArray("translation")?.let { t ->
            if (t.length() == 3) Matrix.translateM(
                out, 0,
                t.optDouble(0).toFloat(),
                t.optDouble(1).toFloat(),
                t.optDouble(2).toFloat()
            )
        }
        node.optJSONArray("rotation")?.let { q ->
            if (q.length() == 4) {
                var x = q.optDouble(0).toFloat()
                var y = q.optDouble(1).toFloat()
                var z = q.optDouble(2).toFloat()
                var w = q.optDouble(3).toFloat()
                val len = kotlin.math.sqrt(x*x + y*y + z*z + w*w)
                if (len > 0.000001f) {
                    x /= len; y /= len; z /= len; w /= len
                    val r = FloatArray(16)
                    Matrix.setIdentityM(r, 0)
                    r[0]=1f-2f*(y*y+z*z); r[1]=2f*(x*y+z*w); r[2]=2f*(x*z-y*w)
                    r[4]=2f*(x*y-z*w); r[5]=1f-2f*(x*x+z*z); r[6]=2f*(y*z+x*w)
                    r[8]=2f*(x*z+y*w); r[9]=2f*(y*z-x*w); r[10]=1f-2f*(x*x+y*y)
                    val rotated = FloatArray(16)
                    Matrix.multiplyMM(rotated, 0, out, 0, r, 0)
                    System.arraycopy(rotated, 0, out, 0, 16)
                }
            }
        }
        node.optJSONArray("scale")?.let { s ->
            if (s.length() == 3) Matrix.scaleM(
                out, 0,
                s.optDouble(0, 1.0).toFloat(),
                s.optDouble(1, 1.0).toFloat(),
                s.optDouble(2, 1.0).toFloat()
            )
        }
        return out
    }
}
