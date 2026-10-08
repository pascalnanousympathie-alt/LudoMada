package com.ludomadagascar

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.MotionEvent
import kotlin.math.max
import kotlin.math.min

/**
 * Real-time 3D showroom for Market V6 assets.
 *
 * The geometry is loaded from the authored GLB itself. No bitmap/card/image
 * substitution is used. The selected mesh set is normalized from its real
 * vertex bounds, then rendered with a small physically-inspired metallic shader.
 */
class MarketPreviewView(context: Context) : GLSurfaceView(context) {

    private val renderer = PreviewRenderer(this)

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        holder.setFormat(android.graphics.PixelFormat.RGBA_8888)
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
        isFocusable = true
        isClickable = true
        contentDescription = "Marché 3D live"
    }

    fun show(category: String, itemId: String) {
        renderer.category = category
        renderer.itemId = itemId
        requestRender()
    }

    private class PreviewRenderer(private val host: MarketPreviewView) :
        GLSurfaceView.Renderer {

        @Volatile var category: String = "board"
        @Volatile var itemId: String = "royal"
        @Volatile var reloadRequested = true
        private var lastCategory = ""
        private var lastItemId = ""

        private var marketParts: Map<String, GlbRuntimeLoader.Part> = emptyMap()
        private var heroParts: Map<String, GlbRuntimeLoader.Part> = emptyMap()

        private val projection = FloatArray(16)
        private val view = FloatArray(16)
        private val vp = FloatArray(16)
        private val model = FloatArray(16)
        private val temp = FloatArray(16)
        private val temp2 = FloatArray(16)

        private var width = 1
        private var height = 1
        private var program = 0
        private var aPos = 0
        private var aNormal = 1
        private var uMvp = 0
        private var uModel = 0
        private var uColor = 0
        private var uMetallic = 0
        private var uRoughness = 0
        private var uCamera = 0
        private var uLight = 0
        private var uTime = 0

        private var centerX = 0f
        private var centerY = 0f
        private var centerZ = 0f
        private var fitScale = 1f
        private var rotationY = 0f
        private var dragX = 0f
        private var dragging = false

        override fun onSurfaceCreated(
            gl: javax.microedition.khronos.opengles.GL10?,
            config: javax.microedition.khronos.egl.EGLConfig?
        ) {
            GLES30.glClearColor(0.018f, 0.028f, 0.055f, 1f)
            GLES30.glEnable(GLES30.GL_DEPTH_TEST)
            GLES30.glDepthFunc(GLES30.GL_LEQUAL)
            GLES30.glEnable(GLES30.GL_CULL_FACE)
            GLES30.glCullFace(GLES30.GL_BACK)
            GLES30.glDisable(GLES30.GL_BLEND)

            val vs = """
                #version 300 es
                precision highp float;
                layout(location=0) in vec3 aPos;
                layout(location=1) in vec3 aNormal;
                uniform mat4 uMvp;
                uniform mat4 uModel;
                out vec3 vWorld;
                out vec3 vNormal;
                void main() {
                    vec4 w = uModel * vec4(aPos, 1.0);
                    vWorld = w.xyz;
                    vNormal = normalize(mat3(uModel) * aNormal);
                    gl_Position = uMvp * vec4(aPos, 1.0);
                }
            """.trimIndent()

            val fs = """
                #version 300 es
                precision highp float;
                uniform vec4 uColor;
                uniform float uMetallic;
                uniform float uRoughness;
                uniform vec3 uCamera;
                uniform vec3 uLight;
                uniform float uTime;
                in vec3 vWorld;
                in vec3 vNormal;
                out vec4 fragColor;
                void main() {
                    vec3 N = normalize(vNormal);
                    vec3 L = normalize(uLight - vWorld);
                    vec3 V = normalize(uCamera - vWorld);
                    vec3 H = normalize(L + V);
                    float ndl = max(dot(N, L), 0.0);
                    float ndv = max(dot(N, V), 0.0);
                    float ndh = max(dot(N, H), 0.0);

                    float rough = clamp(uRoughness, 0.05, 1.0);
                    float metal = clamp(uMetallic, 0.0, 1.0);
                    float shininess = mix(110.0, 18.0, rough);
                    float spec = pow(ndh, shininess) * mix(0.10, 0.92, metal);
                    float fresnel = pow(1.0 - ndv, 4.0);

                    vec3 envA = vec3(0.035, 0.055, 0.10);
                    vec3 envB = vec3(0.95, 0.97, 1.0);
                    float horizon = smoothstep(-0.5, 0.85, reflect(-V, N).y);
                    vec3 env = mix(envA, envB, horizon);
                    float sweep = 0.5 + 0.5 * sin(vWorld.y * 5.0 + uTime * 1.25);
                    env *= 0.90 + 0.10 * sweep;

                    vec3 base = uColor.rgb;
                    vec3 ambient = base * (0.34 + 0.20 * ndv);
                    vec3 diffuse = base * (0.96 * ndl);
                    vec3 chrome = env * (0.52 + 1.25 * fresnel);
                    vec3 lit = ambient + diffuse + vec3(1.0,0.98,0.95) * spec;
                    lit = mix(lit, chrome, 0.22 + 0.58 * metal);
                    lit += base * 0.025;

                    lit = lit / (lit + vec3(1.0));
                    lit = pow(max(lit, vec3(0.0)), vec3(0.92));
                    fragColor = vec4(lit, 1.0);
                }
            """.trimIndent()

            program = buildProgram(vs, fs)
            uMvp = GLES30.glGetUniformLocation(program, "uMvp")
            uModel = GLES30.glGetUniformLocation(program, "uModel")
            uColor = GLES30.glGetUniformLocation(program, "uColor")
            uMetallic = GLES30.glGetUniformLocation(program, "uMetallic")
            uRoughness = GLES30.glGetUniformLocation(program, "uRoughness")
            uCamera = GLES30.glGetUniformLocation(program, "uCamera")
            uLight = GLES30.glGetUniformLocation(program, "uLight")
            uTime = GLES30.glGetUniformLocation(program, "uTime")

            reloadAssets()
        }

        override fun onSurfaceChanged(
            gl: javax.microedition.khronos.opengles.GL10?,
            w: Int,
            h: Int
        ) {
            width = max(1, w)
            height = max(1, h)
            GLES30.glViewport(0, 0, width, height)
            rebuildFit()
        }

        override fun onDrawFrame(gl: javax.microedition.khronos.opengles.GL10?) {
            if (reloadRequested) {
                reloadAssets()
                reloadRequested = false
            }
            if (category != lastCategory || itemId != lastItemId) {
                rebuildFit()
                lastCategory = category
                lastItemId = itemId
            }

            GLES30.glViewport(0, 0, width, height)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

            val aspect = width.toFloat() / max(1, height).toFloat()
            Matrix.perspectiveM(projection, 0, 34f, aspect, 0.05f, 80f)
            Matrix.setLookAtM(
                view, 0,
                0f, 2.8f, 4.9f,
                0f, 0.0f, 0f,
                0f, 1f, 0f
            )
            Matrix.multiplyMM(vp, 0, projection, 0, view, 0)

            GLES30.glUseProgram(program)
            GLES30.glUniform3f(uCamera, 0f, 2.8f, 4.9f)
            GLES30.glUniform3f(uLight, -4.5f, 8.5f, 5.0f)
            GLES30.glUniform1f(uTime, System.nanoTime() / 1_000_000_000f)

            Matrix.setIdentityM(model, 0)
            Matrix.translateM(model, 0, -centerX * fitScale, -centerY * fitScale, -centerZ * fitScale)
            Matrix.scaleM(model, 0, fitScale, fitScale, fitScale)
            Matrix.rotateM(model, 0, rotationY, 0f, 1f, 0f)
            drawSelected(model)

            // VFX is runtime material/geometry FX around the real GLB asset.
            // The base object is still the authored Market V6 mesh.
            if (category == "vfx") drawVfxAccent()
        }

        private fun reloadAssets() {
            runCatching {
                val loader = GlbRuntimeLoader(host.context)
                marketParts = loader.loadMarketParts()
                heroParts = loader.loadHeroParts()
                rebuildFit()
                host.post {
                    host.contentDescription = "Marché 3D live • " + category + " • " + itemId
                }
            }.onFailure {
                marketParts = emptyMap()
                heroParts = emptyMap()
                rebuildFit()
                android.util.Log.e("LudoMadaMarketPreview", "MARKET_PREVIEW_LOAD_FAILED", it)
            }
        }

        private fun selectedParts(): List<GlbRuntimeLoader.Part> {
            return when (category) {
                "board" -> {
                    val root = when (itemId) {
                        "royal" -> "LM_MARKET_BOARD_ROYAL"
                        "obsidian" -> "LM_MARKET_BOARD_OBSIDIAN"
                        "sapphire" -> "LM_MARKET_BOARD_SAPPHIRE"
                        "ruby" -> "LM_MARKET_BOARD_RUBY"
                        else -> "LM_MARKET_BOARD_ROYAL"
                    }
                    marketParts.values.filter { it.name == root }
                }
                "die" -> {
                    val root = when (itemId) {
                        "chrome" -> "LM_MARKET_DIE_MERCURY"
                        "obsidian" -> "LM_MARKET_DIE_OBSIDIAN"
                        "ruby" -> "LM_MARKET_DIE_RUBY"
                        "emerald" -> "LM_MARKET_DIE_EMERALD"
                        else -> "LM_MARKET_DIE_MERCURY"
                    }
                    marketParts.values.filter { it.name == root }
                }
                "pawn" -> {
                    if (itemId == "classic") {
                        listOfNotNull(
                            heroParts["LM_Pawn_Underside_Insert"],
                            heroParts["LM_Pawn_Base_Weighted"],
                            heroParts["LM_Pawn_Collar"],
                            heroParts["LM_Pawn_Tapered_Body"],
                            heroParts["LM_Pawn_Spherical_Head"]
                        )
                    } else {
                        val root = when (itemId) {
                            "chrome" -> "LM_MARKET_PAWN_CHROME"
                            "onyx" -> "LM_MARKET_PAWN_ONYX"
                            "emerald" -> "LM_MARKET_PAWN_EMERALD"
                            "sapphire" -> "LM_MARKET_PAWN_SAPPHIRE"
                            else -> "LM_MARKET_PAWN_ROYAL_GOLD"
                        }
                        val style = root.removePrefix("LM_MARKET_PAWN_")
                        marketParts.values.filter {
                            it.name == root ||
                                it.name == "LM_MARKET_ACCENT_" + style + "_COLLAR" ||
                                it.name == "LM_MARKET_ACCENT_" + style + "_GEM"
                        }
                    }
                }
                "vfx" -> {
                    listOfNotNull(marketParts["LM_MARKET_DIE_MERCURY"])
                }
                else -> emptyList()
            }
        }

        private fun rebuildFit() {
            val parts = selectedParts()
            if (parts.isEmpty()) {
                centerX = 0f; centerY = 0f; centerZ = 0f; fitScale = 1f
                return
            }

            var minX = Float.POSITIVE_INFINITY
            var minY = Float.POSITIVE_INFINITY
            var minZ = Float.POSITIVE_INFINITY
            var maxX = Float.NEGATIVE_INFINITY
            var maxY = Float.NEGATIVE_INFINITY
            var maxZ = Float.NEGATIVE_INFINITY

            val point = FloatArray(4)
            for (part in parts) {
                val p = part.mesh.positions
                for (i in p.indices step 3) {
                    point[0] = p[i]
                    point[1] = p[i + 1]
                    point[2] = p[i + 2]
                    point[3] = 1f
                    val out = FloatArray(4)
                    Matrix.multiplyMV(out, 0, part.transform, 0, point, 0)
                    minX = min(minX, out[0]); maxX = max(maxX, out[0])
                    minY = min(minY, out[1]); maxY = max(maxY, out[1])
                    minZ = min(minZ, out[2]); maxZ = max(maxZ, out[2])
                }
            }

            centerX = (minX + maxX) * 0.5f
            centerY = (minY + maxY) * 0.5f
            centerZ = (minZ + maxZ) * 0.5f
            val maxDim = max(maxX - minX, max(maxY - minY, maxZ - minZ)).coerceAtLeast(0.001f)
            fitScale = 3.0f / maxDim
        }

        private fun drawSelected(global: FloatArray) {
            val parts = selectedParts()
            for (part in parts) {
                Matrix.multiplyMM(temp, 0, global, 0, part.transform, 0)
                Matrix.multiplyMM(temp2, 0, vp, 0, temp, 0)
                drawMesh(part.mesh, temp, temp2, part)
            }
        }

        private fun drawMesh(
            mesh: LudoBoardView.Mesh,
            modelMatrix: FloatArray,
            mvpMatrix: FloatArray,
            part: GlbRuntimeLoader.Part
        ) {
            GLES30.glUniformMatrix4fv(uModel, 1, false, modelMatrix, 0)
            GLES30.glUniformMatrix4fv(uMvp, 1, false, mvpMatrix, 0)
            val color = part.baseColor
            GLES30.glUniform4f(uColor, color[0], color[1], color[2], color[3])
            GLES30.glUniform1f(uMetallic, part.metallic)
            GLES30.glUniform1f(uRoughness, part.roughness)

            GLES30.glEnableVertexAttribArray(aPos)
            GLES30.glVertexAttribPointer(aPos, 3, GLES30.GL_FLOAT, false, 0, mesh.p)
            GLES30.glEnableVertexAttribArray(aNormal)
            GLES30.glVertexAttribPointer(aNormal, 3, GLES30.GL_FLOAT, false, 0, mesh.n)
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, mesh.indices.size, GLES30.GL_UNSIGNED_SHORT, mesh.i)
            GLES30.glDisableVertexAttribArray(aPos)
            GLES30.glDisableVertexAttribArray(aNormal)
        }

        private fun drawVfxAccent() {
            // Lightweight live FX indicator: three concentric, rotating sweeps rendered
            // from a generated ring mesh, while the visible hero asset remains the real GLB.
            val t = System.nanoTime() / 1_000_000_000f
            val pulse = 0.9f + 0.12f * kotlin.math.sin(t * 5.5f)
            val color = when (itemId) {
                "ember" -> floatArrayOf(1.0f, 0.27f, 0.05f, 0.65f)
                "frost" -> floatArrayOf(0.30f, 0.85f, 1.0f, 0.65f)
                else -> floatArrayOf(1.0f, 0.78f, 0.20f, 0.72f)
            }
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE)
            GLES30.glDisable(GLES30.GL_CULL_FACE)

            val segments = 96
            val verts = FloatArray(segments * 3)
            val norms = FloatArray(segments * 3)
            val inds = ShortArray((segments - 2) * 3)
            val radius = (1.12f * pulse)
            for (i in 0 until segments) {
                val a = (i.toFloat() / (segments - 1)) * (Math.PI * 2.0)
                verts[i*3] = kotlin.math.cos(a).toFloat() * radius
                verts[i*3+1] = -0.95f + kotlin.math.sin(t*2.4f+a.toFloat()) * 0.07f
                verts[i*3+2] = kotlin.math.sin(a).toFloat() * radius
                norms[i*3] = 0f; norms[i*3+1] = 1f; norms[i*3+2] = 0f
            }
            // Ring as line strip is lighter than a textured particle system and remains native.
            GLES30.glLineWidth(5f)
            GLES30.glUniform4f(uColor,color[0],color[1],color[2],color[3])
            GLES30.glUniform1f(uMetallic,0.05f)
            GLES30.glUniform1f(uRoughness,0.18f)
            GLES30.glUniformMatrix4fv(uModel,1,false,model,0)
            Matrix.multiplyMM(temp2,0,vp,0,model,0)
            GLES30.glUniformMatrix4fv(uMvp,1,false,temp2,0)
            GLES30.glEnableVertexAttribArray(aPos)
            GLES30.glVertexAttribPointer(aPos,3,GLES30.GL_FLOAT,false,0,java.nio.ByteBuffer.allocateDirect(verts.size*4).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer().apply{put(verts);position(0)})
            GLES30.glEnableVertexAttribArray(aNormal)
            GLES30.glVertexAttribPointer(aNormal,3,GLES30.GL_FLOAT,false,0,java.nio.ByteBuffer.allocateDirect(norms.size*4).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer().apply{put(norms);position(0)})
            GLES30.glDrawArrays(GLES30.GL_LINE_STRIP,0,segments)
            GLES30.glDisableVertexAttribArray(aPos)
            GLES30.glDisableVertexAttribArray(aNormal)
            GLES30.glEnable(GLES30.GL_CULL_FACE)
            GLES30.glDisable(GLES30.GL_BLEND)
        }

        private fun buildProgram(vs: String, fs: String): Int {
            fun compile(type: Int, source: String): Int {
                val shader = GLES30.glCreateShader(type)
                GLES30.glShaderSource(shader, source)
                GLES30.glCompileShader(shader)
                val ok = IntArray(1)
                GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, ok, 0)
                require(ok[0] == GLES30.GL_TRUE) {
                    "shader compile failed: " + GLES30.glGetShaderInfoLog(shader)
                }
                return shader
            }
            val v = compile(GLES30.GL_VERTEX_SHADER, vs)
            val f = compile(GLES30.GL_FRAGMENT_SHADER, fs)
            val p = GLES30.glCreateProgram()
            GLES30.glAttachShader(p, v)
            GLES30.glAttachShader(p, f)
            GLES30.glBindAttribLocation(p, aPos, "aPos")
            GLES30.glBindAttribLocation(p, aNormal, "aNormal")
            GLES30.glLinkProgram(p)
            val ok = IntArray(1)
            GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
            require(ok[0] == GLES30.GL_TRUE) {
                "program link failed: " + GLES30.glGetProgramInfoLog(p)
            }
            GLES30.glDeleteShader(v)
            GLES30.glDeleteShader(f)
            return p
        }

        fun onTouch(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragX = event.x
                    dragging = true
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - dragX
                    dragX = event.x
                    rotationY = (rotationY + dx * 0.55f) % 360f
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    return true
                }
            }
            return true
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = renderer.onTouch(event)
}
