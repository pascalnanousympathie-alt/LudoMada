package com.ludomadagascar

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class LudoBoardView(
    context: Context,
    var onRollTap: (() -> Unit)? = null,
    var onPawnTap: ((Int) -> Unit)? = null
) : GLSurfaceView(context) {

    var state: JSONObject? = null
        set(value) { field = value; renderer.state = value; requestRender() }
    var player: Int = -1
        set(value) { field = value; renderer.player = value; requestRender() }

    private val display = Array(4) { Array(4) { FloatArray(2) { Float.NaN } } }
    private val lastPos = Array(4) { IntArray(4) { Int.MIN_VALUE } }
    private val renderer = SceneRenderer(this)
    private var stateAnimator: ValueAnimator? = null
    private var diceRolling = false

    init {
        setEGLContextClientVersion(2)
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
        isFocusable = true
        isClickable = true
    }

    fun isDiceRolling(): Boolean = diceRolling

    fun startDiceAnimation() {
        if (diceRolling) return
        diceRolling = true
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 720L
            addUpdateListener { renderer.diceAnim = it.animatedValue as Float; requestRender() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    diceRolling = false
                    renderer.diceAnim = 1f
                    requestRender()
                }
            })
            start()
        }
    }

    fun animateState() {
        val targets = Array(4) { Array(4) { FloatArray(2) } }
        val newPos = Array(4) { IntArray(4) { -1 } }
        val oldPos = Array(4) { IntArray(4) { Int.MIN_VALUE } }

        for (pi in 0..3) for (ti in 0..3) {
            val target = tokenGrid(pi, ti)
            targets[pi][ti][0] = target[0]
            targets[pi][ti][1] = target[1]
            val ps = state?.optJSONArray("players")
            val ts = ps?.optJSONObject(pi)?.optJSONArray("tokens")
            newPos[pi][ti] = ts?.optJSONObject(ti)?.optInt("pos", -1) ?: -1
            oldPos[pi][ti] = lastPos[pi][ti]
            if (display[pi][ti][0].isNaN()) {
                display[pi][ti][0] = target[0]
                display[pi][ti][1] = target[1]
                lastPos[pi][ti] = newPos[pi][ti]
            }
        }

        stateAnimator?.cancel()
        var maxSteps = 0
        for (pi in 0..3) for (ti in 0..3) {
            val from = oldPos[pi][ti]
            val to = newPos[pi][ti]
            if (from >= 0 && to > from) maxSteps = max(maxSteps, min(6, to - from))
            if (from < 0 && to >= 0) maxSteps = max(maxSteps, 1)
            if (from >= 0 && to < 0) maxSteps = max(maxSteps, 2)
        }
        val duration = (maxSteps * 120L + 320L).coerceAtLeast(320L)

        stateAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            interpolator = android.view.animation.DecelerateInterpolator(1.45f)
            addUpdateListener {
                val f = it.animatedValue as Float
                for (pi in 0..3) for (ti in 0..3) {
                    val from = oldPos[pi][ti]
                    val to = newPos[pi][ti]
                    val base = if (from == Int.MIN_VALUE) to else from
                    if (from >= 0 && to > from) {
                        val span = max(1, to - from)
                        val u = f * span
                        val step = min(span - 1, floor(u).toInt())
                        val local = u - step
                        val a = animationGrid(pi, from + step)
                        val b = animationGrid(pi, from + step + 1)
                        display[pi][ti][0] = a[0] + (b[0] - a[0]) * local
                        display[pi][ti][1] = a[1] + (b[1] - a[1]) * local
                        renderer.lift[pi][ti] = sin(local * Math.PI).toFloat() * 0.18f
                    } else {
                        val start = if (base < 0) homeGrid(pi, ti) else animationGrid(pi, base)
                        val end = targets[pi][ti]
                        display[pi][ti][0] = start[0] + (end[0] - start[0]) * f
                        display[pi][ti][1] = start[1] + (end[1] - start[1]) * f
                        renderer.lift[pi][ti] = if (from < 0 && to >= 0)
                            sin(f * Math.PI).toFloat() * 0.24f
                        else if (from >= 0 && to < 0)
                            sin(f * Math.PI).toFloat() * 0.16f
                        else 0f
                    }
                }
                requestRender()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    for (pi in 0..3) for (ti in 0..3) {
                        display[pi][ti][0] = targets[pi][ti][0]
                        display[pi][ti][1] = targets[pi][ti][1]
                        renderer.lift[pi][ti] = 0f
                        lastPos[pi][ti] = newPos[pi][ti]
                    }
                    requestRender()
                }
            })
            start()
        }
    }

    private fun homeGrid(pi: Int, ti: Int): FloatArray {
        val xy = arrayOf(
            floatArrayOf(-2.65f, -2.65f), floatArrayOf(-1.35f, -2.65f), floatArrayOf(-2.65f, -1.35f), floatArrayOf(-1.35f, -1.35f),
            floatArrayOf(1.35f, -2.65f), floatArrayOf(2.65f, -2.65f), floatArrayOf(1.35f, -1.35f), floatArrayOf(2.65f, -1.35f),
            floatArrayOf(-2.65f, 1.35f), floatArrayOf(-1.35f, 1.35f), floatArrayOf(-2.65f, 2.65f), floatArrayOf(-1.35f, 2.65f),
            floatArrayOf(1.35f, 1.35f), floatArrayOf(2.65f, 1.35f), floatArrayOf(1.35f, 2.65f), floatArrayOf(2.65f, 2.65f)
        )
        return xy[pi * 4 + ti].clone()
    }

    private fun tokenGrid(pi: Int, ti: Int): FloatArray {
        val pos = state?.optJSONArray("players")?.optJSONObject(pi)
            ?.optJSONArray("tokens")?.optJSONObject(ti)?.optInt("pos", -1) ?: -1
        return when {
            pos in 0..51 -> {
                val q = visualPath()[(pi * 13 + pos) % 52]
                floatArrayOf((q.second - 7.0f) * 0.50f, (q.first - 7.0f) * 0.50f)
            }
            pos in 52..57 -> finishGrid(pi, pos - 52)
            else -> homeGrid(pi, ti)
        }
    }

    private fun animationGrid(pi: Int, pos: Int): FloatArray {
        return if (pos in 0..51) {
            val q = visualPath()[(pi * 13 + pos) % 52]
            floatArrayOf((q.second - 7.0f) * 0.50f, (q.first - 7.0f) * 0.50f)
        } else if (pos in 52..57) {
            finishGrid(pi, pos - 52)
        } else {
            homeGrid(pi, 0)
        }
    }

    private fun finishGrid(pi: Int, step: Int): FloatArray {
        val d = 0.30f * step
        return when (pi) {
            0 -> floatArrayOf(-d, 0f)
            1 -> floatArrayOf(0f, -d)
            2 -> floatArrayOf(d, 0f)
            else -> floatArrayOf(0f, d)
        }
    }

    private fun visualPath(): List<Pair<Int, Int>> = listOf(
        6 to 0, 6 to 1, 6 to 2, 6 to 3, 6 to 4, 6 to 5, 5 to 6, 4 to 6, 3 to 6, 2 to 6, 1 to 6, 0 to 6,
        0 to 7, 0 to 8, 1 to 8, 2 to 8, 3 to 8, 4 to 8, 5 to 8, 6 to 9, 6 to 10, 6 to 11, 6 to 12, 6 to 13, 6 to 14,
        7 to 14, 8 to 14, 8 to 13, 8 to 12, 8 to 11, 8 to 10, 8 to 9, 9 to 8, 10 to 8, 11 to 8, 12 to 8, 13 to 8, 14 to 8,
        14 to 7, 14 to 6, 13 to 6, 12 to 6, 11 to 6, 10 to 6, 9 to 6, 8 to 5, 8 to 4, 8 to 3, 8 to 2, 8 to 1, 8 to 0, 7 to 0
    )

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked != MotionEvent.ACTION_UP || player !in 0..3) return true
        val cx = width * 0.5f
        val cy = height * 0.52f
        val dieR = min(width, height) * 0.13f
        val dx = e.x - cx
        val dy = e.y - cy
        if (dx * dx + dy * dy <= dieR * dieR) {
            performClick()
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onRollTap?.invoke()
            return true
        }
        val projected = renderer.projectedTokens(player)
        var best = -1
        var bestD = Float.MAX_VALUE
        for (ti in 0..3) {
            val p = projected[ti]
            val ddx = e.x - p[0]
            val ddy = e.y - p[1]
            val d2 = ddx * ddx + ddy * ddy
            if (d2 < bestD) { bestD = d2; best = ti }
        }
        val hit = min(width, height) * 0.075f
        if (best >= 0 && bestD <= hit * hit) {
            performClick()
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onPawnTap?.invoke(best)
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    private class SceneRenderer(private val host: LudoBoardView) : Renderer {
        var state: JSONObject? = null
        var player: Int = -1
        var diceAnim = 1f
        val lift = Array(4) { FloatArray(4) }
        private val vp = FloatArray(16)
        internal val proj = FloatArray(16)
        internal val view = FloatArray(16)
        private val projectionSnapshot = FloatArray(16)
        private val modelScratch = FloatArray(16)
        private val pawnColors = intArrayOf(
            Color.rgb(219, 59, 66), Color.rgb(48, 165, 92),
            Color.rgb(239, 186, 46), Color.rgb(59, 112, 219)
        )
        private lateinit var shader: Shader
        private lateinit var cube: Mesh
        private lateinit var pathMesh: Mesh
        private lateinit var coloredZones: Array<Mesh>
        private lateinit var pawnBody: Mesh
        private lateinit var sphere: Mesh
        private lateinit var disk: Mesh
        private lateinit var pips: Mesh
        private var width = 1
        private var height = 1

        private val boardTop = 0.20f
        private val pawnScale = 0.62f

        override fun onSurfaceCreated(gl: javax.microedition.khronos.opengles.GL10?, cfg: javax.microedition.khronos.egl.EGLConfig?) {
            GLES20.glClearColor(0.012f, 0.026f, 0.060f, 1f)
            GLES20.glEnable(GLES20.GL_DEPTH_TEST)
            GLES20.glDepthFunc(GLES20.GL_LEQUAL)
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            shader = Shader(this)
            cube = cubeMesh()
            pathMesh = pathMesh()
            coloredZones = Array(4) { zoneMesh(it) }
            pawnBody = pawnBodyMesh()
            sphere = sphereMesh(18, 12)
            disk = diskMesh(32)
            pips = sphereMesh(10, 6)
        }

        override fun onSurfaceChanged(gl: javax.microedition.khronos.opengles.GL10?, w: Int, h: Int) {
            width = max(1, w)
            height = max(1, h)
            GLES20.glViewport(0, 0, width, height)
        }

        override fun onDrawFrame(gl: javax.microedition.khronos.opengles.GL10?) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            val aspect = width.toFloat() / height.toFloat()
            val landscape = aspect > 1.35f
            val camY = if (landscape) 6.7f else 7.9f
            val camZ = if (landscape) 6.7f else 9.2f
            val fov = if (landscape) 24f else 34f
            Matrix.perspectiveM(proj, 0, fov, aspect, 0.1f, 50f)
            Matrix.setLookAtM(view, 0, 0f, camY, camZ, 0f, 0.10f, 0f, 0f, 1f, 0f)
            Matrix.multiplyMM(vp, 0, proj, 0, view, 0)

            drawWorld()
            System.arraycopy(vp, 0, projectionSnapshot, 0, 16)
        }

        fun projectedTokens(pi: Int): Array<FloatArray> {
            val out = Array(4) { FloatArray(2) { -1000f } }
            val m = projectionSnapshot.clone()
            if (m[0] == 0f) return out
            for (ti in 0..3) {
                val xz = host.display[pi][ti]
                val x = xz[0]
                val z = xz[1]
                if (x.isNaN()) continue
                val y = boardTop + 0.60f
                val clipX = m[0] * x + m[4] * y + m[8] * z + m[12]
                val clipY = m[1] * x + m[5] * y + m[9] * z + m[13]
                val clipW = m[3] * x + m[7] * y + m[11] * z + m[15]
                if (clipW != 0f) {
                    out[ti][0] = (clipX / clipW * 0.5f + 0.5f) * width
                    out[ti][1] = (1f - (clipY / clipW * 0.5f + 0.5f)) * height
                }
            }
            return out
        }

        private fun drawWorld() {
            // Deep studio floor prevents the scene from floating in a black void.
            drawBox(0f, -1.02f, 0f, 13.0f, 0.08f, 13.0f, floatArrayOf(0.015f, 0.030f, 0.065f, 1f), 0.10f)
            // Physical 3D wooden table and raised board stack.
            drawBox(0f, -0.42f, 0f, 4.75f, 0.35f, 4.75f, floatArrayOf(0.24f, 0.085f, 0.035f, 1f), 0.55f)
            drawBox(0f, -0.03f, 0f, 4.28f, 0.12f, 4.28f, floatArrayOf(0.43f, 0.17f, 0.055f, 1f), 0.68f)
            drawBox(0f, 0.095f, 0f, 4.05f, 0.08f, 4.05f, floatArrayOf(0.78f, 0.48f, 0.16f, 1f), 0.78f)
            drawBox(0f, 0.165f, 0f, 3.86f, 0.07f, 3.86f, floatArrayOf(0.88f, 0.84f, 0.73f, 1f), 0.34f)

            drawMesh(pathMesh, identityAt(0f, boardTop + 0.025f, 0f), floatArrayOf(0.93f, 0.88f, 0.75f, 1f), 0.30f, false)
            for (i in 0..3) drawMesh(coloredZones[i], identityAt(0f, boardTop + 0.035f, 0f), rgba(zoneColorInt(i)), 0.68f, false)

            val homeCenters = arrayOf(
                floatArrayOf(-2.55f, -2.55f), floatArrayOf(2.55f, -2.55f),
                floatArrayOf(-2.55f, 2.55f), floatArrayOf(2.55f, 2.55f)
            )
            for (i in 0..3) {
                val hc = homeCenters[i]
                drawMesh(disk, identityAt(hc[0], boardTop + 0.16f, hc[1], 1.05f, 1f, 1.05f),
                    rgba(zoneColorInt(i)), 0.70f, false)
                drawMesh(disk, identityAt(hc[0], boardTop + 0.17f, hc[1], 0.88f, 1f, 0.88f),
                    floatArrayOf(0.96f, 0.96f, 0.96f, 0.20f), 0.20f, false)
            }

            for (pi in 0..3) for (ti in 0..3) {
                val xz = host.display[pi][ti]
                if (xz[0].isNaN()) continue
                drawMesh(disk, identityAt(xz[0], boardTop + 0.012f, xz[1], 0.25f, 1f, 0.25f),
                    floatArrayOf(0.015f, 0.018f, 0.023f, 0.32f), 0f, true)
                drawPawn(xz[0], boardTop + 0.10f + lift[pi][ti], xz[1], pawnColors[pi], pi == player)
            }
            drawDie()
        }

        private fun drawPawn(x: Float, y: Float, z: Float, color: Int, active: Boolean) {
            if (active) {
                drawMesh(disk, identityAt(x, boardTop + 0.022f, z, 0.42f, 1f, 0.42f),
                    floatArrayOf(0.93f, 0.70f, 0.15f, 0.82f), 0f, true)
            }
            val c = rgba(color)
            // Body silhouette follows the classic pawn proportions seen in Screenshot B.
            drawMesh(pawnBody, identityAt(x, y, z, pawnScale, pawnScale, pawnScale), c, 0.90f, false)
            // Head/face silhouette follows unnamed (1).webp: a smooth rounded single-color head.
            drawMesh(sphere, identityAt(x, y + pawnScale * 0.74f, z, pawnScale * 0.37f, pawnScale * 0.37f, pawnScale * 0.37f), c, 0.96f, false)
        }

        private fun drawDie() {
            val value = max(1, state?.optInt("dice", 0) ?: 1)
            val t = diceAnim
            val spinX = (1f - t) * 720f + if (value % 2 == 0) 12f else 31f
            val spinY = (1f - t) * 430f + 17f
            val dieModel = identityAt(0f, boardTop + 0.74f, 0f, 0.62f, 0.62f, 0.62f)
            Matrix.rotateM(dieModel, 0, spinX, 1f, 0f, 0f)
            Matrix.rotateM(dieModel, 0, spinY, 0f, 1f, 0f)
            drawMesh(cube, dieModel, floatArrayOf(0.97f, 0.985f, 0.995f, 1f), 1.0f, false)

            val pip = 0.20f
            val positions = when (value) {
                1 -> arrayOf(0f to 0f)
                2 -> arrayOf(-pip to -pip, pip to pip)
                3 -> arrayOf(-pip to -pip, 0f to 0f, pip to pip)
                4 -> arrayOf(-pip to -pip, pip to -pip, -pip to pip, pip to pip)
                5 -> arrayOf(-pip to -pip, pip to -pip, 0f to 0f, -pip to pip, pip to pip)
                else -> arrayOf(-pip to -pip, -pip to 0f, -pip to pip, pip to -pip, pip to 0f, pip to pip)
            }
            for ((px, pz) in positions) {
                val pipModel = identityAt(px, 0.51f, pz, 0.065f, 0.028f, 0.065f)
                val combined = FloatArray(16)
                Matrix.multiplyMM(combined, 0, dieModel, 0, pipModel, 0)
                drawMesh(pips, combined, floatArrayOf(0.055f, 0.062f, 0.07f, 1f), 0.38f, false)
            }
        }

        private fun drawBox(x: Float, y: Float, z: Float, sx: Float, sy: Float, sz: Float, color: FloatArray, spec: Float) {
            drawMesh(cube, identityAt(x, y, z, sx, sy, sz), color, spec, false)
        }

        private fun identityAt(x: Float, y: Float, z: Float, sx: Float = 1f, sy: Float = 1f, sz: Float = 1f): FloatArray {
            Matrix.setIdentityM(modelScratch, 0)
            Matrix.translateM(modelScratch, 0, x, y, z)
            Matrix.scaleM(modelScratch, 0, sx, sy, sz)
            return modelScratch.clone()
        }

        private fun drawMesh(mesh: Mesh, mat: FloatArray, color: FloatArray, spec: Float, unlit: Boolean) {
            shader.draw(mesh, mat, color, spec, unlit)
        }

        private fun zoneColorInt(i: Int): Int = when (i) {
            0 -> Color.rgb(219, 59, 66)
            1 -> Color.rgb(48, 165, 92)
            2 -> Color.rgb(239, 186, 46)
            else -> Color.rgb(59, 112, 219)
        }

        private fun rgba(c: Int): FloatArray = floatArrayOf(
            Color.red(c) / 255f, Color.green(c) / 255f, Color.blue(c) / 255f, 1f
        )

        private fun cubeMesh(): Mesh {
            val positions = floatArrayOf(
                -1f,-1f,1f, 1f,-1f,1f, 1f,1f,1f, -1f,1f,1f,
                1f,-1f,-1f, -1f,-1f,-1f, -1f,1f,-1f, 1f,1f,-1f,
                -1f,1f,1f, 1f,1f,1f, 1f,1f,-1f, -1f,1f,-1f,
                -1f,-1f,-1f, 1f,-1f,-1f, 1f,-1f,1f, -1f,-1f,1f,
                1f,-1f,1f, 1f,-1f,-1f, 1f,1f,-1f, 1f,1f,1f,
                -1f,-1f,-1f, -1f,-1f,1f, -1f,1f,1f, -1f,1f,-1f
            )
            val normals = FloatArray(24 * 3)
            for (f in 0..5) {
                val normal = when (f) {
                    0 -> floatArrayOf(0f,0f,1f); 1 -> floatArrayOf(0f,0f,-1f)
                    2 -> floatArrayOf(0f,1f,0f); 3 -> floatArrayOf(0f,-1f,0f)
                    4 -> floatArrayOf(1f,0f,0f); else -> floatArrayOf(-1f,0f,0f)
                }
                for (i in 0..3) {
                    normals[(f * 4 + i) * 3] = normal[0]
                    normals[(f * 4 + i) * 3 + 1] = normal[1]
                    normals[(f * 4 + i) * 3 + 2] = normal[2]
                }
            }
            val indices = shortArrayOf(
                0,1,2,0,2,3, 4,5,6,4,6,7, 8,9,10,8,10,11,
                12,13,14,12,14,15, 16,17,18,16,18,19, 20,21,22,20,22,23
            )
            return Mesh(positions, normals, indices)
        }

        private fun visualPath(): List<Pair<Int, Int>> = listOf(
            6 to 0,6 to 1,6 to 2,6 to 3,6 to 4,6 to 5,5 to 6,4 to 6,3 to 6,2 to 6,1 to 6,0 to 6,
            0 to 7,0 to 8,1 to 8,2 to 8,3 to 8,4 to 8,5 to 8,6 to 9,6 to 10,6 to 11,6 to 12,6 to 13,6 to 14,
            7 to 14,8 to 14,8 to 13,8 to 12,8 to 11,8 to 10,8 to 9,9 to 8,10 to 8,11 to 8,12 to 8,13 to 8,14 to 8,
            14 to 7,14 to 6,13 to 6,12 to 6,11 to 6,10 to 6,9 to 6,8 to 5,8 to 4,8 to 3,8 to 2,8 to 1,8 to 0,7 to 0
        )

        private fun pathMesh(): Mesh {
            val v = ArrayList<Float>(); val n = ArrayList<Float>(); val i = ArrayList<Short>(); var base = 0
            for ((r,c) in visualPath()) {
                val x = (c - 7.0f) * 0.50f
                val z = (r - 7.0f) * 0.50f
                addBox(v, n, i, base, x, boardTop + 0.02f, z, 0.235f, 0.035f, 0.235f)
                base += 24
            }
            return Mesh(v.toFloatArray(), n.toFloatArray(), i.toShortArray())
        }

        private fun zoneMesh(pi: Int): Mesh {
            val v = ArrayList<Float>(); val n = ArrayList<Float>(); val i = ArrayList<Short>()
            val x = if (pi == 0 || pi == 2) -2.55f else 2.55f
            val z = if (pi == 0 || pi == 1) -2.55f else 2.55f
            addBox(v, n, i, 0, x, boardTop + 0.04f, z, 1.42f, 0.055f, 1.42f)
            addBox(v, n, i, 24, x, boardTop + 0.095f, z, 1.14f, 0.04f, 1.14f)
            return Mesh(v.toFloatArray(), n.toFloatArray(), i.toShortArray())
        }

        private fun pawnBodyMesh(): Mesh {
            val profile = arrayOf(
                0.74f to 0.00f, 0.86f to 0.05f, 0.78f to 0.12f,
                0.58f to 0.20f, 0.42f to 0.30f, 0.30f to 0.43f,
                0.24f to 0.55f, 0.21f to 0.62f
            )
            return latheMesh(profile, 28)
        }

        private fun latheMesh(profile: Array<Pair<Float, Float>>, seg: Int): Mesh {
            val v = ArrayList<Float>(); val n = ArrayList<Float>(); val idx = ArrayList<Short>()
            for (pi in profile.indices) for (s in 0 until seg) {
                val a = s.toFloat() / seg * Math.PI * 2.0
                val r = profile[pi].first
                val y = profile[pi].second
                v += (cos(a) * r).toFloat(); v += y; v += (sin(a) * r).toFloat()
                val slope = when (pi) {
                    0 -> profile[1].first - r
                    profile.lastIndex -> r - profile[pi - 1].first
                    else -> (profile[pi + 1].first - profile[pi - 1].first) * 0.5f
                }
                val dy = when (pi) {
                    0 -> profile[1].second - y
                    profile.lastIndex -> y - profile[pi - 1].second
                    else -> profile[pi + 1].second - profile[pi - 1].second
                }
                val nx0 = cos(a).toFloat()
                val nz0 = sin(a).toFloat()
                val ny0 = if (slope == 0f) 1f else -slope / max(0.001f, dy)
                val len = max(0.001f, kotlin.math.sqrt(nx0*nx0 + ny0*ny0 + nz0*nz0))
                n += nx0 / len; n += ny0 / len; n += nz0 / len
            }
            for (p in 0 until profile.lastIndex) for (s in 0 until seg) {
                val a = (p * seg + s).toShort()
                val b = (p * seg + (s + 1) % seg).toShort()
                val c = ((p + 1) * seg + (s + 1) % seg).toShort()
                val d = ((p + 1) * seg + s).toShort()
                idx += a; idx += b; idx += c; idx += a; idx += c; idx += d
            }
            return Mesh(v.toFloatArray(), n.toFloatArray(), idx.toShortArray())
        }

        private fun sphereMesh(seg: Int, rings: Int): Mesh {
            val v = ArrayList<Float>(); val n = ArrayList<Float>(); val idx = ArrayList<Short>()
            for (r in 0..rings) {
                val phi = (r.toFloat() / rings - 0.5f) * Math.PI
                val cp = cos(phi); val sp = sin(phi)
                for (s in 0..seg) {
                    val th = s.toFloat() / seg * Math.PI * 2
                    val x = (cp * cos(th)).toFloat(); val y = sp.toFloat(); val z = (cp * sin(th)).toFloat()
                    v += x; v += y; v += z; n += x; n += y; n += z
                }
            }
            val row = seg + 1
            for (r in 0 until rings) for (s in 0 until seg) {
                val a = (r * row + s).toShort(); val b = (a + 1).toShort()
                val c = ((r + 1) * row + s + 1).toShort(); val d = ((r + 1) * row + s).toShort()
                idx += a; idx += b; idx += c; idx += a; idx += c; idx += d
            }
            return Mesh(v.toFloatArray(), n.toFloatArray(), idx.toShortArray())
        }

        private fun diskMesh(seg: Int): Mesh {
            val v = ArrayList<Float>(); val n = ArrayList<Float>(); val idx = ArrayList<Short>()
            v += 0f; v += 0f; v += 0f; n += 0f; n += 1f; n += 0f
            for (s in 0..seg) {
                val a = s.toFloat() / seg * Math.PI * 2
                v += cos(a).toFloat(); v += 0f; v += sin(a).toFloat()
                n += 0f; n += 1f; n += 0f
            }
            for (s in 1..seg) { idx += 0; idx += s.toShort(); idx += (s + 1).toShort() }
            return Mesh(v.toFloatArray(), n.toFloatArray(), idx.toShortArray())
        }
        private fun addBox(v: MutableList<Float>, n: MutableList<Float>, idx: MutableList<Short>, base: Int,
                           x: Float, y: Float, z: Float, sx: Float, sy: Float, sz: Float) {
            val m = cubeMesh()
            for (k in m.positions.indices step 3) {
                v += m.positions[k] * sx + x
                v += m.positions[k + 1] * sy + y
                v += m.positions[k + 2] * sz + z
                n += m.normals[k]; n += m.normals[k + 1]; n += m.normals[k + 2]
            }
            for (q in m.indices) idx += (base + q.toInt()).toShort()
        }
    }

    private class Mesh(
        val positions: FloatArray,
        val normals: FloatArray,
        val indices: ShortArray
    ) {
        val p: FloatBuffer = ByteBuffer.allocateDirect(positions.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().apply { put(positions); position(0) }
        val n: FloatBuffer = ByteBuffer.allocateDirect(normals.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().apply { put(normals); position(0) }
        val i: ShortBuffer = ByteBuffer.allocateDirect(indices.size * 2).order(ByteOrder.nativeOrder())
            .asShortBuffer().apply { put(indices); position(0) }
    }
    private class Shader(private val renderer: SceneRenderer) {
        private val program: Int
        private val uMvp: Int
        private val uMv: Int
        private val uColor: Int
        private val uSpec: Int
        private val uUnlit: Int
        private val aPos: Int
        private val aNormal: Int
        private val mv = FloatArray(16)
        private val mvp = FloatArray(16)

        init {
            val vs = """
                uniform mat4 uMvp;
                uniform mat4 uMv;
                attribute vec3 aPos;
                attribute vec3 aNormal;
                varying vec3 vN;
                varying vec3 vP;
                void main() {
                    vec4 p = uMv * vec4(aPos, 1.0);
                    vP = p.xyz;
                    vN = normalize((uMv * vec4(aNormal, 0.0)).xyz);
                    gl_Position = uMvp * vec4(aPos, 1.0);
                }
            """.trimIndent()
            val fs = """
                precision mediump float;
                uniform vec4 uColor;
                uniform float uSpec;
                uniform float uUnlit;
                varying vec3 vN;
                varying vec3 vP;
                void main() {
                    vec3 N = normalize(vN);
                    vec3 L = normalize(vec3(-0.42, 0.88, 0.38));
                    vec3 V = normalize(-vP);
                    float diff = max(dot(N, L), 0.0);
                    vec3 H = normalize(L + V);
                    float spec = pow(max(dot(N, H), 0.0), 30.0) * uSpec;
                    float light = mix(0.42 + diff * 0.60 + spec, 1.0, uUnlit);
                    gl_FragColor = vec4(uColor.rgb * light, uColor.a);
                }
            """.trimIndent()
            program = buildProgram(vs, fs)
            uMvp = GLES20.glGetUniformLocation(program, "uMvp")
            uMv = GLES20.glGetUniformLocation(program, "uMv")
            uColor = GLES20.glGetUniformLocation(program, "uColor")
            uSpec = GLES20.glGetUniformLocation(program, "uSpec")
            uUnlit = GLES20.glGetUniformLocation(program, "uUnlit")
            aPos = GLES20.glGetAttribLocation(program, "aPos")
            aNormal = GLES20.glGetAttribLocation(program, "aNormal")
        }

        fun draw(mesh: Mesh, model: FloatArray, color: FloatArray, spec: Float, unlit: Boolean) {
            Matrix.multiplyMM(mv, 0, renderer.view, 0, model, 0)
            Matrix.multiplyMM(mvp, 0, renderer.proj, 0, mv, 0)
            GLES20.glUseProgram(program)
            GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
            GLES20.glUniformMatrix4fv(uMv, 1, false, mv, 0)
            GLES20.glUniform4fv(uColor, 1, color, 0)
            GLES20.glUniform1f(uSpec, spec)
            GLES20.glUniform1f(uUnlit, if (unlit) 1f else 0f)
            GLES20.glEnableVertexAttribArray(aPos)
            GLES20.glEnableVertexAttribArray(aNormal)
            GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 0, mesh.p)
            GLES20.glVertexAttribPointer(aNormal, 3, GLES20.GL_FLOAT, false, 0, mesh.n)
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, mesh.indices.size, GLES20.GL_UNSIGNED_SHORT, mesh.i)
            GLES20.glDisableVertexAttribArray(aPos)
            GLES20.glDisableVertexAttribArray(aNormal)
        }

        private fun buildProgram(vs: String, fs: String): Int {
            fun compile(type: Int, src: String): Int {
                val s = GLES20.glCreateShader(type)
                GLES20.glShaderSource(s, src)
                GLES20.glCompileShader(s)
                val ok = IntArray(1)
                GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
                if (ok[0] == 0) {
                    val log = GLES20.glGetShaderInfoLog(s)
                    GLES20.glDeleteShader(s)
                    throw IllegalStateException("GL shader: " + log)
                }
                return s
            }
            val v = compile(GLES20.GL_VERTEX_SHADER, vs)
            val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, v)
            GLES20.glAttachShader(p, f)
            GLES20.glLinkProgram(p)
            val ok = IntArray(1)
            GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
            if (ok[0] == 0) throw IllegalStateException("GL link: " + GLES20.glGetProgramInfoLog(p))
            GLES20.glDeleteShader(v)
            GLES20.glDeleteShader(f)
            return p
        }
    }
}
