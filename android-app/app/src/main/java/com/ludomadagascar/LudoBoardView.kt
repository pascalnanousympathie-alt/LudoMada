package com.ludomadagascar

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
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
    private val gameAudio = SpatialGameAudio()
    private var stateAnimator: ValueAnimator? = null
    private var diceRolling = false

    init {
        setEGLContextClientVersion(3)
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
        isFocusable = true
        isClickable = true
    }

    fun isDiceRolling(): Boolean = diceRolling

    fun startDiceAnimation() {
        if (diceRolling) return
        diceRolling = true
        gameAudio.play(0f, 0.85f, 760.0)
        pulseHaptic(24L, 180)
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
                        renderer.lift[pi][ti] = LudoMotionPhysics.ballisticArc(local, 0.18f)
                    } else {
                        val start = if (base < 0) homeGrid(pi, ti) else animationGrid(pi, base)
                        val end = targets[pi][ti]
                        display[pi][ti][0] = start[0] + (end[0] - start[0]) * f
                        display[pi][ti][1] = start[1] + (end[1] - start[1]) * f
                        renderer.lift[pi][ti] = if (from < 0 && to >= 0)
                            LudoMotionPhysics.ballisticArc(f, 0.24f)
                        else if (from >= 0 && to < 0)
                            LudoMotionPhysics.ballisticArc(f, 0.16f)
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
            pulseHaptic(18L, 120)
            val pan = ((projected[best][0] / max(1f, width.toFloat())) * 2f - 1f).coerceIn(-1f, 1f)
            val centerDx = (projected[best][0] - width * 0.5f) / max(1f, width * 0.5f)
            val centerDy = (projected[best][1] - height * 0.5f) / max(1f, height * 0.5f)
            val distance = kotlin.math.sqrt(centerDx * centerDx + centerDy * centerDy).coerceIn(0f, 1f)
            gameAudio.play(pan, 0.65f, 520.0, distance)
            onPawnTap?.invoke(best)
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    private fun pulseHaptic(durationMs: Long, amplitude: Int) {
        val vibrator = if (Build.VERSION.SDK_INT >= 31) context.getSystemService(Vibrator::class.java)
        else {
            @Suppress("DEPRECATION")
            context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? Vibrator
        }
        runCatching {
            if (vibrator != null && vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= 26) vibrator.vibrate(VibrationEffect.createOneShot(durationMs, amplitude.coerceIn(1, 255)))
                else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(durationMs)
                }
            }
        }
    }

    override fun onDetachedFromWindow() {
        gameAudio.release()
        super.onDetachedFromWindow()
    }


    internal class SceneRenderer(private val host: LudoBoardView) : Renderer {
        var state: JSONObject? = null
        var player: Int = -1
        var diceAnim = 1f
        val lift = Array(4) { FloatArray(4) }
        private val vp=FloatArray(16)
        internal val proj=FloatArray(16)
        internal val view=FloatArray(16)
        private val projectionSnapshot=FloatArray(16)
        private val modelScratch=FloatArray(16)
        private val pawnColors=intArrayOf(
            Color.rgb(219,59,66),Color.rgb(48,165,92),Color.rgb(239,186,46),Color.rgb(59,112,219)
        )
        private var rayTracingHardware=false
        private lateinit var shader:PbrShader
        private lateinit var shadowShader:ShadowShader
        private lateinit var postShader:PostShader
        private lateinit var particleShader:ParticleShader
        private lateinit var cube:Mesh
        private lateinit var pathMesh:Mesh
        private lateinit var coloredZones:Array<Mesh>
        private lateinit var pawnBody:Mesh
        private lateinit var sphere:Mesh
        private lateinit var disk:Mesh
        private lateinit var pips:Mesh
        private var shadowFbo=0
        private var shadowTex=0
        private var sceneFbo=0
        private var sceneColorTex=0
        private var sceneDepthTex=0
        private val shadowSize=512
        private var sceneWidth=1
        private var sceneHeight=1
        private var woodTex=0
        private var boardTex=0
        private var neutralTex=0
        private var metalTex=0
        private var normalTex=0
        private var envCube=0
        internal val lightVp=FloatArray(16)
        private val lightView=FloatArray(16)
        private val lightProj=FloatArray(16)
        private var lastFrameNanos=0L
        private val dustLife=FloatArray(64)
        private val dustPos=FloatArray(64*3)
        private val dustVel=FloatArray(64*3)
        private val dustColor=FloatArray(64*4)
        private val dustSize=FloatArray(64)
        private val dustData=FloatArray(64*8)
        private var particleVbo=0
        private val lastDust=Array(4){LongArray(4)}
        private val boardTop=0.20f
        private val pawnScale=0.62f

        override fun onSurfaceCreated(gl:javax.microedition.khronos.opengles.GL10?,cfg:javax.microedition.khronos.egl.EGLConfig?){
            GLES30.glClearColor(0.008f,0.018f,0.045f,1f)
            GLES30.glEnable(GLES30.GL_DEPTH_TEST)
            GLES30.glDepthFunc(GLES30.GL_LEQUAL)
            GLES30.glEnable(GLES30.GL_CULL_FACE)
            GLES30.glCullFace(GLES30.GL_BACK)
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA,GLES30.GL_ONE_MINUS_SRC_ALPHA)
            rayTracingHardware=RayTraceSupport.hasGlesRayTracing(GLES30.glGetString(GLES30.GL_EXTENSIONS))
            android.util.Log.i("LudoMadaRender", if (rayTracingHardware) "RayTracing=available" else "RayTracing=IBL_reflection_fallback")
            shader=PbrShader(this);shadowShader=ShadowShader();postShader=PostShader();particleShader=ParticleShader()
            cube=cubeMesh();pathMesh=pathMesh();coloredZones=Array(4){zoneMesh(it)};pawnBody=pawnBodyMesh();sphere=sphereMesh(18,12);disk=diskMesh(32);pips=sphereMesh(10,6)
            woodTex=createMaterialTexture(0);boardTex=createMaterialTexture(1);neutralTex=createMaterialTexture(2);metalTex=createMaterialTexture(3);normalTex=createNormalTexture();envCube=createEnvironmentCubemap()
            val ids=IntArray(1);GLES30.glGenBuffers(1,ids,0);particleVbo=ids[0]
            Matrix.setLookAtM(lightView,0,-7.5f,11.0f,8.5f,0f,0.2f,0f,0f,1f,0f);Matrix.orthoM(lightProj,0,-8.5f,8.5f,-8.5f,8.5f,1f,25f);Matrix.multiplyMM(lightVp,0,lightProj,0,lightView,0)
        }
        override fun onSurfaceChanged(gl:javax.microedition.khronos.opengles.GL10?,w:Int,h:Int){
            sceneWidth=max(1,w);sceneHeight=max(1,h);GLES30.glViewport(0,0,sceneWidth,sceneHeight);createSceneTargets(sceneWidth,sceneHeight)
        }
        override fun onDrawFrame(gl:javax.microedition.khronos.opengles.GL10?){
            val now=System.nanoTime();val dt=if(lastFrameNanos==0L)0.016f else ((now-lastFrameNanos)/1_000_000_000f).coerceIn(0.001f,0.050f);lastFrameNanos=now
            updateParticles(dt);maybeSpawnDust(now)
            val aspect=sceneWidth.toFloat()/sceneHeight.toFloat();val land=aspect>1.35f;val camY=if(land)6.7f else 7.9f;val camZ=if(land)6.7f else 9.2f;val fov=if(land)24f else 34f
            Matrix.perspectiveM(proj,0,fov,aspect,0.1f,50f);Matrix.setLookAtM(view,0,0f,camY,camZ,0f,0.10f,0f,0f,1f,0f);Matrix.multiplyMM(vp,0,proj,0,view,0)
            renderShadowMap();renderScene();drawParticles();renderPostProcess();System.arraycopy(vp,0,projectionSnapshot,0,16)
        }
        private fun createSceneTargets(w:Int,h:Int){
            if(sceneFbo!=0){GLES30.glDeleteFramebuffers(1,intArrayOf(sceneFbo),0);GLES30.glDeleteTextures(1,intArrayOf(sceneColorTex),0);GLES30.glDeleteTextures(1,intArrayOf(sceneDepthTex),0)}
            val tex=IntArray(2);GLES30.glGenTextures(2,tex,0);sceneColorTex=tex[0];sceneDepthTex=tex[1]
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,sceneColorTex);GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_MIN_FILTER,GLES30.GL_LINEAR);GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_MAG_FILTER,GLES30.GL_LINEAR);GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_WRAP_S,GLES30.GL_CLAMP_TO_EDGE);GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_WRAP_T,GLES30.GL_CLAMP_TO_EDGE);GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D,0,GLES30.GL_RGBA,w,h,0,GLES30.GL_RGBA,GLES30.GL_UNSIGNED_BYTE,null)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,sceneDepthTex);GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_MIN_FILTER,GLES30.GL_NEAREST);GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_MAG_FILTER,GLES30.GL_NEAREST);GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_WRAP_S,GLES30.GL_CLAMP_TO_EDGE);GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_WRAP_T,GLES30.GL_CLAMP_TO_EDGE);GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D,0,GLES30.GL_DEPTH_COMPONENT16,w,h,0,GLES30.GL_DEPTH_COMPONENT,GLES30.GL_UNSIGNED_SHORT,null)
            val fb=IntArray(1);GLES30.glGenFramebuffers(1,fb,0);sceneFbo=fb[0];GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,sceneFbo);GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER,GLES30.GL_COLOR_ATTACHMENT0,GLES30.GL_TEXTURE_2D,sceneColorTex,0);GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER,GLES30.GL_DEPTH_ATTACHMENT,GLES30.GL_TEXTURE_2D,sceneDepthTex,0);GLES30.glDrawBuffers(1,intArrayOf(GLES30.GL_COLOR_ATTACHMENT0),0);checkFramebuffer("scene");GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,0)
        }
        private fun ensureShadowTargets(){
            if(shadowFbo!=0)return
            val fb=IntArray(1);val tex=IntArray(1);GLES30.glGenFramebuffers(1,fb,0);GLES30.glGenTextures(1,tex,0);shadowFbo=fb[0];shadowTex=tex[0]
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,shadowTex);GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_MIN_FILTER,GLES30.GL_NEAREST);GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_MAG_FILTER,GLES30.GL_NEAREST);GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_WRAP_S,GLES30.GL_CLAMP_TO_EDGE);GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_WRAP_T,GLES30.GL_CLAMP_TO_EDGE);GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D,0,GLES30.GL_DEPTH_COMPONENT16,shadowSize,shadowSize,0,GLES30.GL_DEPTH_COMPONENT,GLES30.GL_UNSIGNED_SHORT,null)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,shadowFbo);GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER,GLES30.GL_DEPTH_ATTACHMENT,GLES30.GL_TEXTURE_2D,shadowTex,0);GLES30.glDrawBuffers(1,intArrayOf(GLES30.GL_NONE),0);GLES30.glReadBuffer(GLES30.GL_NONE);checkFramebuffer("shadow");GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,0)
        }
        private fun checkFramebuffer(name:String){val status=GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER);if(status!=GLES30.GL_FRAMEBUFFER_COMPLETE)throw IllegalStateException("framebuffer $name incomplete: 0x"+status.toString(16))}
        private fun renderShadowMap(){
            ensureShadowTargets();GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,shadowFbo);GLES30.glViewport(0,0,shadowSize,shadowSize);GLES30.glClear(GLES30.GL_DEPTH_BUFFER_BIT);GLES30.glColorMask(false,false,false,false);shadowShader.begin(lightVp);drawWorld(true);shadowShader.end();GLES30.glColorMask(true,true,true,true);GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,0);GLES30.glViewport(0,0,sceneWidth,sceneHeight)
        }
        private fun renderScene(){
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,sceneFbo);GLES30.glViewport(0,0,sceneWidth,sceneHeight);GLES30.glClearColor(0.008f,0.018f,0.045f,1f);GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT);shader.beginFrame(shadowTex,lightVp,woodTex,boardTex,neutralTex,metalTex,normalTex,envCube);drawWorld(false);shader.endFrame();GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,0)
        }
        fun projectedTokens(pi:Int):Array<FloatArray>{
            val out=Array(4){FloatArray(2){-1000f}};val m=projectionSnapshot;if(m[0]==0f)return out
            for(ti in 0..3){val xz=host.display[pi][ti];val x=xz[0];val z=xz[1];if(x.isNaN())continue;val y=boardTop+0.60f;val cx=m[0]*x+m[4]*y+m[8]*z+m[12];val cy=m[1]*x+m[5]*y+m[9]*z+m[13];val cw=m[3]*x+m[7]*y+m[11]*z+m[15];if(cw!=0f){out[ti][0]=(cx/cw*0.5f+0.5f)*sceneWidth;out[ti][1]=(1f-(cy/cw*0.5f+0.5f))*sceneHeight}}
            return out
        }
        private fun drawWorld(shadowPass:Boolean){
            if(shadowPass){
                drawShadowMesh(cube,identityAt(0f,-1.02f,0f,13f,0.08f,13f));drawShadowMesh(cube,identityAt(0f,-0.42f,0f,4.75f,0.35f,4.75f));drawShadowMesh(cube,identityAt(0f,-0.03f,0f,4.28f,0.12f,4.28f));drawShadowMesh(cube,identityAt(0f,0.095f,0f,4.05f,0.08f,4.05f));drawShadowMesh(cube,identityAt(0f,0.165f,0f,3.86f,0.07f,3.86f))
                drawShadowMesh(pathMesh,identityAt(0f,boardTop+0.025f,0f));for(i in 0..3)drawShadowMesh(coloredZones[i],identityAt(0f,boardTop+0.035f,0f))
                val homes=arrayOf(floatArrayOf(-2.55f,-2.55f),floatArrayOf(2.55f,-2.55f),floatArrayOf(-2.55f,2.55f),floatArrayOf(2.55f,2.55f));for(hc in homes)drawShadowMesh(disk,identityAt(hc[0],boardTop+0.16f,hc[1],1.05f,1f,1.05f))
                for(pi in 0..3)for(ti in 0..3){val xz=host.display[pi][ti];if(xz[0].isNaN())continue;drawShadowMesh(pawnBody,pawnModel(xz[0],boardTop+0.10f+lift[pi][ti],xz[1],pi,ti));drawShadowMesh(sphere,pawnHeadModel(xz[0],boardTop+0.10f+lift[pi][ti],xz[1],pi,ti))}
                drawShadowMesh(cube,dieModel());return
            }
            drawMesh(cube,identityAt(0f,-1.02f,0f,13f,0.08f,13f),rgba(Color.rgb(18,33,62)),0.90f,0.02f,1f,0f,0,true)
            drawMesh(cube,identityAt(0f,-0.42f,0f,4.75f,0.35f,4.75f),floatArrayOf(0.32f,0.10f,0.035f,1f),0.34f,0.18f,1f,0f,1,false)
            drawMesh(cube,identityAt(0f,-0.03f,0f,4.28f,0.12f,4.28f),floatArrayOf(0.47f,0.18f,0.05f,1f),0.42f,0.12f,1f,0f,1,false)
            drawMesh(cube,identityAt(0f,0.095f,0f,4.05f,0.08f,4.05f),floatArrayOf(0.78f,0.48f,0.16f,1f),0.46f,0.10f,1f,0f,1,false)
            drawMesh(cube,identityAt(0f,0.165f,0f,3.86f,0.07f,3.86f),floatArrayOf(0.88f,0.84f,0.73f,1f),0.58f,0.04f,1f,0f,2,false)
            drawMesh(pathMesh,identityAt(0f,boardTop+0.008f,0f),floatArrayOf(0.93f,0.88f,0.75f,1f),0.42f,0.06f,0.98f,0f,2,false)
            // Thin metallic center plinth gives the die a physical resting area and a contact shadow.
            drawMesh(disk,identityAt(0f,boardTop+0.20f,0f,0.95f,1f,0.95f),floatArrayOf(0.72f,0.75f,0.78f,0.90f),0.18f,0.78f,0.98f,0.02f,4,false)
            for(i in 0..3)drawMesh(coloredZones[i],identityAt(0f,boardTop+0.035f,0f),rgba(zoneColorInt(i)),0.24f,0.05f,1f,0.01f,3,false)
            val homes=arrayOf(floatArrayOf(-2.55f,-2.55f),floatArrayOf(2.55f,-2.55f),floatArrayOf(-2.55f,2.55f),floatArrayOf(2.55f,2.55f))
            for(i in 0..3){val hc=homes[i];drawMesh(disk,identityAt(hc[0],boardTop+0.16f,hc[1],1.05f,1f,1.05f),rgba(zoneColorInt(i)),0.25f,0.06f,1f,0.015f,3,false);drawMesh(disk,identityAt(hc[0],boardTop+0.17f,hc[1],0.88f,1f,0.88f),floatArrayOf(0.96f,0.96f,0.96f,0.20f),0.12f,0f,1f,0f,2,true)}
            for(pi in 0..3)for(ti in 0..3){val xz=host.display[pi][ti];if(xz[0].isNaN())continue;drawMesh(disk,identityAt(xz[0],boardTop+0.012f,xz[1],0.25f,1f,0.25f),floatArrayOf(0.015f,0.018f,0.023f,0.30f),0.90f,0f,1f,0f,0,true);drawPawn(xz[0],boardTop+0.10f+lift[pi][ti],xz[1],pawnColors[pi],pi==player,pi,ti)}
            drawDie()
        }
        private fun drawPawn(x:Float,y:Float,z:Float,color:Int,active:Boolean,pi:Int,ti:Int){
            if(active)drawMesh(disk,identityAt(x,boardTop+0.022f,z,0.42f,1f,0.42f),floatArrayOf(0.93f,0.70f,0.15f,0.82f),0.10f,0.38f,1f,0.02f,4,true)
            drawMesh(pawnBody,pawnModel(x,y,z,pi,ti),rgba(color),0.22f,0.04f,1f,0.015f,3,false);drawMesh(sphere,pawnHeadModel(x,y,z,pi,ti),rgba(color),0.18f,0.05f,1f,0.018f,3,false)
        }
        private fun pawnModel(x:Float,y:Float,z:Float,pi:Int,ti:Int):FloatArray{val m=identityAt(x,y,z,pawnScale,pawnScale,pawnScale);val phase=((System.nanoTime()/18_000_000L+pi*37L+ti*11L)%360L).toFloat()/360f;Matrix.rotateM(m,0,LudoMotionPhysics.sway(phase,3.5f),0f,0f,1f);Matrix.rotateM(m,0,LudoMotionPhysics.sway(phase,2.2f),1f,0f,0f);return m}
        private fun pawnHeadModel(x:Float,y:Float,z:Float,pi:Int,ti:Int):FloatArray{val m=identityAt(x,y+pawnScale*0.74f,z,pawnScale*0.37f,pawnScale*0.37f,pawnScale*0.37f);val phase=((System.nanoTime()/20_000_000L+pi*31L+ti*7L)%360L).toFloat()/360f;Matrix.rotateM(m,0,LudoMotionPhysics.sway(phase,2.5f),0f,0f,1f);return m}
        private fun dieModel():FloatArray{val value=max(1,state?.optInt("dice",0)?:1);val t=diceAnim;val sx=(1f-t)*720f+if(value%2==0)12f else 31f;val sy=(1f-t)*430f+17f;val m=identityAt(0f,boardTop+0.74f,0f,0.62f,0.62f,0.62f);Matrix.rotateM(m,0,sx,1f,0f,0f);Matrix.rotateM(m,0,sy,0f,1f,0f);return m}
        private fun drawDie(){
            val value=max(1,state?.optInt("dice",0)?:1);val die=dieModel();drawMesh(cube,die,floatArrayOf(0.97f,0.985f,0.995f,1f),0.10f,0.88f,1f,0.035f,4,false);val pip=0.20f
            val positions=when(value){1->arrayOf(0f to 0f);2->arrayOf(-pip to -pip,pip to pip);3->arrayOf(-pip to -pip,0f to 0f,pip to pip);4->arrayOf(-pip to -pip,pip to -pip,-pip to pip,pip to pip);5->arrayOf(-pip to -pip,pip to -pip,0f to 0f,-pip to pip,pip to pip);else->arrayOf(-pip to -pip,-pip to 0f,-pip to pip,pip to -pip,pip to 0f,pip to pip)}
            for((px,pz)in positions){val pm=identityAt(px,0.51f,pz,0.065f,0.028f,0.065f);val c=FloatArray(16);Matrix.multiplyMM(c,0,die,0,pm,0);drawMesh(pips,c,floatArrayOf(0.045f,0.052f,0.06f,1f),0.16f,0.74f,1f,0f,4,false)}
        }
        private fun drawMesh(mesh:Mesh,mat:FloatArray,color:FloatArray,rough:Float,metallic:Float,ao:Float,emission:Float,material:Int,unlit:Boolean)=shader.draw(mesh,mat,color,rough,metallic,ao,emission,material,unlit)
        private fun drawShadowMesh(mesh:Mesh,mat:FloatArray)=shadowShader.draw(mesh,mat)
        private fun maybeSpawnDust(now:Long){for(pi in 0..3)for(ti in 0..3){if(lift[pi][ti]<=0.035f||now-lastDust[pi][ti]<90_000_000L)continue;val xz=host.display[pi][ti];if(xz[0].isNaN())continue;lastDust[pi][ti]=now;spawnDust(xz[0],xz[1],pawnColors[pi])}}
        private fun spawnDust(x:Float,z:Float,color:Int){for(n in 0 until 3){val slot=(0 until 64).firstOrNull{dustLife[it]<=0f}?:continue;val a=slot*2.3999631f+x*1.7f+z*0.9f+n*0.9f;dustLife[slot]=0.45f+(slot%5)*0.03f;dustPos[slot*3]=x+cos(a)*0.10f;dustPos[slot*3+1]=boardTop+0.08f;dustPos[slot*3+2]=z+sin(a)*0.10f;dustVel[slot*3]=cos(a)*0.18f;dustVel[slot*3+1]=0.55f+(slot%3)*0.08f;dustVel[slot*3+2]=sin(a)*0.18f;dustColor[slot*4]=Color.red(color)/255f;dustColor[slot*4+1]=Color.green(color)/255f;dustColor[slot*4+2]=Color.blue(color)/255f;dustColor[slot*4+3]=0.75f;dustSize[slot]=4f+(slot%4)}}
        private fun updateParticles(dt:Float){for(i in 0 until 64){if(dustLife[i]<=0f)continue;dustLife[i]-=dt;val o=i*3;dustVel[o+1]-=1.25f*dt;dustVel[o]*=(1f-1.5f*dt).coerceAtLeast(0f);dustVel[o+2]*=(1f-1.5f*dt).coerceAtLeast(0f);dustPos[o]+=dustVel[o]*dt;dustPos[o+1]+=dustVel[o+1]*dt;dustPos[o+2]+=dustVel[o+2]*dt;if(dustPos[o+1]<boardTop+0.02f)dustLife[i]=0f}}
        private fun drawParticles(){var count=0;for(i in 0 until 64)if(dustLife[i]>0f){val p=count*8;val o=i*3;val c=i*4;dustData[p]=dustPos[o];dustData[p+1]=dustPos[o+1];dustData[p+2]=dustPos[o+2];dustData[p+3]=dustColor[c];dustData[p+4]=dustColor[c+1];dustData[p+5]=dustColor[c+2];dustData[p+6]=dustColor[c+3]*(dustLife[i]/0.5f).coerceIn(0f,1f);dustData[p+7]=dustSize[i];count++};if(count==0)return;GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,sceneFbo);particleShader.draw(dustData,count,particleVbo,vp);GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,0)}
        private fun renderPostProcess(){GLES30.glDisable(GLES30.GL_DEPTH_TEST);GLES30.glBlendFunc(GLES30.GL_ONE,GLES30.GL_ZERO);postShader.draw(sceneColorTex,sceneDepthTex,sceneWidth,sceneHeight,0.28f,0.10f);GLES30.glEnable(GLES30.GL_DEPTH_TEST);GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA,GLES30.GL_ONE_MINUS_SRC_ALPHA)}
        private fun createMaterialTexture(kind:Int):Int{val size=256;val data=ByteBuffer.allocateDirect(size*size*4).order(ByteOrder.nativeOrder());for(y in 0 until size)for(x in 0 until size){val u=x.toFloat()/size;val v=y.toFloat()/size;val grain=sin(u*34.0+sin(v*9.0)*4.0)*0.5+0.5;val band=sin(v*19.0+u*6.0)*0.5+0.5;val c=when(kind){0->intArrayOf((78+74*grain+18*band).toInt().coerceIn(0,255),(30+31*grain+8*band).toInt().coerceIn(0,255),(10+14*grain).toInt().coerceIn(0,255));1->intArrayOf((226+16*grain).toInt().coerceIn(0,255),(218+18*band).toInt().coerceIn(0,255),(190+20*grain).toInt().coerceIn(0,255));2->{val q=(132+22*grain).toInt().coerceIn(0,255);intArrayOf(q,q,(138+24*band).toInt().coerceIn(0,255))};else->{val q=(194+28*grain).toInt().coerceIn(0,255);intArrayOf(q,(202+26*band).toInt().coerceIn(0,255),(210+24*grain).toInt().coerceIn(0,255))}};data.put(c[0].toByte()).put(c[1].toByte()).put(c[2].toByte()).put(255.toByte())};data.position(0);return uploadTexture(size,data)}
        private fun createNormalTexture():Int{val size=256;val data=ByteBuffer.allocateDirect(size*size*4).order(ByteOrder.nativeOrder());for(y in 0 until size)for(x in 0 until size){val nx=(128+sin(x*0.12)*9+cos(y*0.17)*5).toInt().coerceIn(0,255);val ny=(128+cos(x*0.15)*7+sin(y*0.09)*5).toInt().coerceIn(0,255);data.put(nx.toByte()).put(ny.toByte()).put(255.toByte()).put(255.toByte())};data.position(0);return uploadTexture(size,data)}
        private fun createEnvironmentCubemap():Int{
            val size=64
            val ids=IntArray(1)
            GLES30.glGenTextures(1,ids,0)
            val tex=ids[0]
            GLES30.glBindTexture(GLES30.GL_TEXTURE_CUBE_MAP,tex)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_CUBE_MAP,GLES30.GL_TEXTURE_MIN_FILTER,GLES30.GL_LINEAR_MIPMAP_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_CUBE_MAP,GLES30.GL_TEXTURE_MAG_FILTER,GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_CUBE_MAP,GLES30.GL_TEXTURE_WRAP_S,GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_CUBE_MAP,GLES30.GL_TEXTURE_WRAP_T,GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_CUBE_MAP,GLES30.GL_TEXTURE_WRAP_R,GLES30.GL_CLAMP_TO_EDGE)
            for(face in 0 until 6){
                val data=ByteBuffer.allocateDirect(size*size*4).order(ByteOrder.nativeOrder())
                for(y in 0 until size)for(x in 0 until size){
                    val a=x.toFloat()/(size-1)*2f-1f
                    val b=y.toFloat()/(size-1)*2f-1f
                    var dx=0f;var dy=0f;var dz=0f
                    when(face){
                        0->{dx=1f;dy=-b;dz=-a}
                        1->{dx=-1f;dy=-b;dz=a}
                        2->{dx=a;dy=1f;dz=b}
                        3->{dx=a;dy=-1f;dz=-b}
                        4->{dx=a;dy=-b;dz=1f}
                        else->{dx=-a;dy=-b;dz=-1f}
                    }
                    val len=max(0.001f,kotlin.math.sqrt(dx*dx+dy*dy+dz*dz));dx/=len;dy/=len;dz/=len
                    val sky=(dy*0.5f+0.5f).coerceIn(0f,1f)
                    val horizon=kotlin.math.exp(-kotlin.math.abs(dy)*3.2f).toFloat()
                    val sun=Math.pow(max(0f,dx*0.55f+dy*0.72f+dz*0.42f).toDouble(),48.0).toFloat()
                    val warm=0.10f*horizon+0.08f*sun
                    val r=(0.018f+0.19f*sky+warm).coerceIn(0f,1f)
                    val g=(0.030f+0.27f*sky+warm*0.78f).coerceIn(0f,1f)
                    val bl=(0.060f+0.46f*sky+sun*0.36f).coerceIn(0f,1f)
                    data.put((r*255f).toInt().toByte()).put((g*255f).toInt().toByte()).put((bl*255f).toInt().toByte()).put(255.toByte())
                }
                data.position(0)
                GLES30.glTexImage2D(GLES30.GL_TEXTURE_CUBE_MAP_POSITIVE_X+face,0,GLES30.GL_RGBA,size,size,0,GLES30.GL_RGBA,GLES30.GL_UNSIGNED_BYTE,data)
            }
            GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_CUBE_MAP)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_CUBE_MAP,0)
            return tex
        }

        private fun uploadTexture(size:Int,data:ByteBuffer):Int{val ids=IntArray(1);GLES30.glGenTextures(1,ids,0);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,ids[0]);GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_MIN_FILTER,GLES30.GL_LINEAR_MIPMAP_LINEAR);GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_MAG_FILTER,GLES30.GL_LINEAR);GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_WRAP_S,GLES30.GL_REPEAT);GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_WRAP_T,GLES30.GL_REPEAT);GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D,0,GLES30.GL_RGBA,size,size,0,GLES30.GL_RGBA,GLES30.GL_UNSIGNED_BYTE,data);GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,0);return ids[0]}
        private fun identityAt(x:Float,y:Float,z:Float,sx:Float=1f,sy:Float=1f,sz:Float=1f):FloatArray{Matrix.setIdentityM(modelScratch,0);Matrix.translateM(modelScratch,0,x,y,z);Matrix.scaleM(modelScratch,0,sx,sy,sz);return modelScratch.clone()}
        private fun rgba(c:Int)=floatArrayOf(Color.red(c)/255f,Color.green(c)/255f,Color.blue(c)/255f,1f)
        private fun zoneColorInt(i:Int)=when(i){0->Color.rgb(219,59,66);1->Color.rgb(48,165,92);2->Color.rgb(239,186,46);else->Color.rgb(59,112,219)}
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
                addBox(v, n, i, base, x, boardTop + 0.018f, z, 0.245f, 0.040f, 0.245f)
                base += 24
                // Raised lacquered inset: a second, smaller tier makes each playable cell read as
                // a molded/finished surface instead of a flat sticker.
                addBox(v, n, i, base, x, boardTop + 0.052f, z, 0.218f, 0.018f, 0.218f)
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
        val positions:FloatArray,val normals:FloatArray,val indices:ShortArray
    ){
        private val uvData:FloatArray=FloatArray(positions.size/3*2).also{out->for(i in positions.indices step 3){val j=(i/3)*2;out[j]=positions[i]*0.12f-floor(positions[i]*0.12f);out[j+1]=positions[i+2]*0.12f-floor(positions[i+2]*0.12f)}}
        val p:FloatBuffer=ByteBuffer.allocateDirect(positions.size*4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply{put(positions);position(0)}
        val n:FloatBuffer=ByteBuffer.allocateDirect(normals.size*4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply{put(normals);position(0)}
        val uv:FloatBuffer=ByteBuffer.allocateDirect(uvData.size*4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply{put(uvData);position(0)}
        val i:ShortBuffer=ByteBuffer.allocateDirect(indices.size*2).order(ByteOrder.nativeOrder()).asShortBuffer().apply{put(indices);position(0)}
    }

    private class PbrShader(private val renderer:SceneRenderer){
        private val program:Int
        private val uMvp:Int;private val uMv:Int;private val uModel:Int;private val uLightVp:Int;private val uColor:Int;private val uMetallic:Int;private val uRoughness:Int;private val uAO:Int;private val uEmission:Int;private val uUnlit:Int;private val uNormalStrength:Int;private val uAlbedoTex:Int;private val uNormalTex:Int;private val uShadowMap:Int;private val uEnvCube:Int
        private val mv=FloatArray(16);private val mvp=FloatArray(16);private val light=FloatArray(16)
        private var wood=0;private var board=0;private var neutral=0;private var metal=0;private var normal=0
        init{
            val vs="""#version 300 es
                precision highp float;
                layout(location=0)in vec3 aPos;layout(location=1)in vec3 aNormal;layout(location=2)in vec2 aUv;
                uniform mat4 uMvp,uMv,uModel,uLightVp;out vec3 vN,vP,vWorld;out vec2 vUv;out vec4 vShadow;
                void main(){vec4 w=uModel*vec4(aPos,1.0);vP=(uMv*vec4(aPos,1.0)).xyz;vWorld=w.xyz;vN=normalize(mat3(uMv)*aNormal);vUv=aUv;vShadow=uLightVp*w;gl_Position=uMvp*vec4(aPos,1.0);}
            """.trimIndent()
            val fs="""#version 300 es
                precision highp float;uniform vec4 uColor;uniform float uMetallic,uRoughness,uAO,uEmission,uUnlit,uNormalStrength;uniform sampler2D uAlbedoTex,uNormalTex,uShadowMap;uniform samplerCube uEnvCube;
                in vec3 vN,vP,vWorld;in vec2 vUv;in vec4 vShadow;layout(location=0)out vec4 fragColor;const float PI=3.14159265359;
                float DGGX(vec3 N,vec3 H,float r){float a=r*r,a2=a*a,nh=max(dot(N,H),0.0),q=nh*nh,d=q*(a2-1.0)+1.0;return a2/max(PI*d*d,0.0001);}
                float G(float nv,float r){float k=((r+1.0)*(r+1.0))/8.0;return nv/max(nv*(1.0-k)+k,0.0001);}
                vec3 F(float c,vec3 f0){return f0+(1.0-f0)*pow(clamp(1.0-c,0.0,1.0),5.0);}
                float shadowV(){vec3 p=vShadow.xyz/max(vShadow.w,0.0001);p=p*0.5+0.5;if(p.z>1.0||p.x<0.0||p.x>1.0||p.y<0.0||p.y>1.0)return 1.0;float cur=p.z-0.0018,s=0.0;vec2 t=1.0/vec2(512.0);for(int x=-1;x<=1;x++)for(int y=-1;y<=1;y++){float d=texture(uShadowMap,p.xy+vec2(float(x),float(y))*t).r;s+=cur<=d?1.0:0.0;}return s/9.0;}
                void main(){
                    vec3 N0=normalize(vN),nm=texture(uNormalTex,vUv*3.5).xyz*2.0-1.0;vec3 T=normalize(vec3(1,0,0)-N0*dot(N0,vec3(1,0,0)));if(length(T)<0.10)T=normalize(vec3(0,0,1)-N0*dot(N0,vec3(0,0,1)));vec3 B=normalize(cross(N0,T));
                    vec3 N=normalize(mix(N0,T*nm.x+B*nm.y+N0*max(nm.z,0.15),uNormalStrength));vec3 tex=texture(uAlbedoTex,vUv*2.0).rgb;vec3 albedo=mix(uColor.rgb*tex,uColor.rgb,0.52);
                    float m=clamp(uMetallic,0.0,1.0),r=clamp(uRoughness,0.045,1.0);vec3 V=normalize(-vP),L=normalize(vec3(-0.42,0.92,0.36)),H=normalize(V+L);float nv=max(dot(N,V),0.0),nl=max(dot(N,L),0.0);
                    vec3 f0=mix(vec3(0.04),albedo,m),f=F(max(dot(H,V),0.0),f0);float d=DGGX(N,H,r),g=G(nv,r)*G(nl,r);vec3 spec=(d*g*f)/max(4.0*nv*nl,0.001);vec3 kd=(vec3(1)-f)*(1-m);float sh=shadowV();
                    vec3 direct=(kd*albedo/PI+spec)*vec3(1.05,0.98,0.84)*nl*sh;vec3 R=reflect(-V,N);vec3 envS=textureLod(uEnvCube,R,r*5.0).rgb;vec3 envD=textureLod(uEnvCube,N,4.0).rgb;
                    float fres=pow(clamp(1.0-max(dot(N,V),0.0),0.0,1.0),5.0);vec3 color=(envD*albedo*kd*0.72+envS*f*(0.26+0.74*fres))*uAO+direct+albedo*uEmission;if(uUnlit>0.5)color=mix(color,albedo+albedo*uEmission,0.84);fragColor=vec4(max(color,vec3(0)),uColor.a);
                }
            """.trimIndent()
            program=buildProgram(vs,fs)
            uEnvCube=GLES30.glGetUniformLocation(program,"uEnvCube");uMvp=GLES30.glGetUniformLocation(program,"uMvp");uMv=GLES30.glGetUniformLocation(program,"uMv");uModel=GLES30.glGetUniformLocation(program,"uModel");uLightVp=GLES30.glGetUniformLocation(program,"uLightVp");uColor=GLES30.glGetUniformLocation(program,"uColor");uMetallic=GLES30.glGetUniformLocation(program,"uMetallic");uRoughness=GLES30.glGetUniformLocation(program,"uRoughness");uAO=GLES30.glGetUniformLocation(program,"uAO");uEmission=GLES30.glGetUniformLocation(program,"uEmission");uUnlit=GLES30.glGetUniformLocation(program,"uUnlit");uNormalStrength=GLES30.glGetUniformLocation(program,"uNormalStrength");uAlbedoTex=GLES30.glGetUniformLocation(program,"uAlbedoTex");uNormalTex=GLES30.glGetUniformLocation(program,"uNormalTex");uShadowMap=GLES30.glGetUniformLocation(program,"uShadowMap")
        }
        fun beginFrame(shadowTex:Int,lightVp:FloatArray,woodTex:Int,boardTex:Int,neutralTex:Int,metalTex:Int,normalTex:Int,envCubeTex:Int){wood=woodTex;board=boardTex;neutral=neutralTex;metal=metalTex;normal=normalTex;System.arraycopy(lightVp,0,light,0,16);GLES30.glUseProgram(program);GLES30.glActiveTexture(GLES30.GL_TEXTURE2);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,shadowTex);GLES30.glUniform1i(uShadowMap,2);GLES30.glActiveTexture(GLES30.GL_TEXTURE3);GLES30.glBindTexture(GLES30.GL_TEXTURE_CUBE_MAP,envCubeTex);GLES30.glUniform1i(uEnvCube,3)}
        fun endFrame(){GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,0)}
        fun draw(mesh:Mesh,model:FloatArray,color:FloatArray,rough:Float,metallic:Float,ao:Float,emission:Float,material:Int,unlit:Boolean){
            val albedo=when(material){1->wood;2->board;3->neutral;4->metal;else->neutral};Matrix.multiplyMM(mv,0,renderer.view,0,model,0);Matrix.multiplyMM(mvp,0,renderer.proj,0,mv,0);GLES30.glUseProgram(program)
            GLES30.glUniformMatrix4fv(uMvp,1,false,mvp,0);GLES30.glUniformMatrix4fv(uMv,1,false,mv,0);GLES30.glUniformMatrix4fv(uModel,1,false,model,0);GLES30.glUniformMatrix4fv(uLightVp,1,false,light,0);GLES30.glUniform4fv(uColor,1,color,0);GLES30.glUniform1f(uMetallic,metallic);GLES30.glUniform1f(uRoughness,rough);GLES30.glUniform1f(uAO,ao);GLES30.glUniform1f(uEmission,emission);GLES30.glUniform1f(uUnlit,if(unlit)1f else 0f);GLES30.glUniform1f(uNormalStrength,if(material==4)0.08f else 0.20f)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,albedo);GLES30.glUniform1i(uAlbedoTex,0);GLES30.glActiveTexture(GLES30.GL_TEXTURE1);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,normal);GLES30.glUniform1i(uNormalTex,1)
            GLES30.glEnableVertexAttribArray(0);GLES30.glEnableVertexAttribArray(1);GLES30.glEnableVertexAttribArray(2);GLES30.glVertexAttribPointer(0,3,GLES30.GL_FLOAT,false,0,mesh.p);GLES30.glVertexAttribPointer(1,3,GLES30.GL_FLOAT,false,0,mesh.n);GLES30.glVertexAttribPointer(2,2,GLES30.GL_FLOAT,false,0,mesh.uv);GLES30.glDrawElements(GLES30.GL_TRIANGLES,mesh.indices.size,GLES30.GL_UNSIGNED_SHORT,mesh.i);GLES30.glDisableVertexAttribArray(0);GLES30.glDisableVertexAttribArray(1);GLES30.glDisableVertexAttribArray(2)
        }
        private fun buildProgram(vs:String,fs:String):Int{fun c(t:Int,s:String):Int{val id=GLES30.glCreateShader(t);GLES30.glShaderSource(id,s);GLES30.glCompileShader(id);val o=IntArray(1);GLES30.glGetShaderiv(id,GLES30.GL_COMPILE_STATUS,o,0);if(o[0]==0)throw IllegalStateException("GL shader: "+GLES30.glGetShaderInfoLog(id));return id};val v=c(GLES30.GL_VERTEX_SHADER,vs);val f=c(GLES30.GL_FRAGMENT_SHADER,fs);val p=GLES30.glCreateProgram();GLES30.glAttachShader(p,v);GLES30.glAttachShader(p,f);GLES30.glLinkProgram(p);val o=IntArray(1);GLES30.glGetProgramiv(p,GLES30.GL_LINK_STATUS,o,0);if(o[0]==0)throw IllegalStateException("GL link: "+GLES30.glGetProgramInfoLog(p));GLES30.glDeleteShader(v);GLES30.glDeleteShader(f);return p}
    }

    private class ShadowShader{
        private val program:Int;private val uM:Int;private val scratch=FloatArray(16);private val lightVp=FloatArray(16)
        init{val vs="""#version 300 es
            layout(location=0)in vec3 aPos;uniform mat4 uLightMvp;void main(){gl_Position=uLightMvp*vec4(aPos,1.0);}
        """.trimIndent();val fs="""#version 300 es
            precision mediump float;void main(){}
        """.trimIndent();program=buildProgram(vs,fs);uM=GLES30.glGetUniformLocation(program,"uLightMvp")}
        fun begin(v:FloatArray){System.arraycopy(v,0,lightVp,0,16);GLES30.glUseProgram(program)};fun end(){}
        fun draw(mesh:Mesh,model:FloatArray){Matrix.multiplyMM(scratch,0,lightVp,0,model,0);GLES30.glUseProgram(program);GLES30.glUniformMatrix4fv(uM,1,false,scratch,0);GLES30.glEnableVertexAttribArray(0);GLES30.glVertexAttribPointer(0,3,GLES30.GL_FLOAT,false,0,mesh.p);GLES30.glDrawElements(GLES30.GL_TRIANGLES,mesh.indices.size,GLES30.GL_UNSIGNED_SHORT,mesh.i);GLES30.glDisableVertexAttribArray(0)}
        private fun buildProgram(vs:String,fs:String):Int{fun c(t:Int,s:String):Int{val id=GLES30.glCreateShader(t);GLES30.glShaderSource(id,s);GLES30.glCompileShader(id);val o=IntArray(1);GLES30.glGetShaderiv(id,GLES30.GL_COMPILE_STATUS,o,0);if(o[0]==0)throw IllegalStateException(GLES30.glGetShaderInfoLog(id));return id};val v=c(GLES30.GL_VERTEX_SHADER,vs);val f=c(GLES30.GL_FRAGMENT_SHADER,fs);val p=GLES30.glCreateProgram();GLES30.glAttachShader(p,v);GLES30.glAttachShader(p,f);GLES30.glLinkProgram(p);val o=IntArray(1);GLES30.glGetProgramiv(p,GLES30.GL_LINK_STATUS,o,0);if(o[0]==0)throw IllegalStateException(GLES30.glGetProgramInfoLog(p));GLES30.glDeleteShader(v);GLES30.glDeleteShader(f);return p}
    }

    private class PostShader{
        private val program:Int;private val scene:Int;private val depth:Int;private val res:Int;private val ssao:Int;private val bloom:Int;private val pos:FloatBuffer;private val uv:FloatBuffer
        init{val v=floatArrayOf(-1f,-1f,0f,1f,-1f,0f,-1f,1f,0f,1f,1f,0f);val u=floatArrayOf(0f,1f,1f,1f,0f,0f,1f,0f);pos=ByteBuffer.allocateDirect(v.size*4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply{put(v);position(0)};uv=ByteBuffer.allocateDirect(u.size*4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply{put(u);position(0)}
            val vs="""#version 300 es
                layout(location=0)in vec3 aPos;layout(location=1)in vec2 aUv;out vec2 vUv;void main(){vUv=aUv;gl_Position=vec4(aPos,1.0);}
            """.trimIndent()
            val fs="""#version 300 es
                precision highp float;uniform sampler2D uScene,uDepth;uniform vec2 uInvRes;uniform float uSsao,uBloom;in vec2 vUv;layout(location=0)out vec4 fragColor;
                float luma(vec3 c){return dot(c,vec3(0.299,0.587,0.114));}vec3 aces(vec3 x){const float a=2.51;const float b=0.03;const float c=2.43;const float d=0.59;const float e=0.14;return clamp((x*(a*x+b))/(x*(c*x+d)+e),0.0,1.0);}
                void main(){vec3 base=texture(uScene,vUv).rgb,bl=vec3(0);float w[5]=float[](0.32,0.20,0.12,0.08,0.05);for(int i=1;i<=4;i++){vec2 p=uInvRes*float(i)*1.6;bl+=(texture(uScene,vUv+vec2(p.x,0)).rgb+texture(uScene,vUv-vec2(p.x,0)).rgb+texture(uScene,vUv+vec2(0,p.y)).rgb+texture(uScene,vUv-vec2(0,p.y)).rgb)*w[i];}bl*=max(luma(base)-0.68,0.0)*uBloom;float d0=texture(uDepth,vUv).r,occ=0.0;vec2 o[8]=vec2[](vec2(1,0),vec2(-1,0),vec2(0,1),vec2(0,-1),vec2(1,1),vec2(-1,1),vec2(1,-1),vec2(-1,-1));for(int i=0;i<8;i++){float dn=texture(uDepth,vUv+o[i]*uInvRes*2.0).r;occ+=smoothstep(0.0,0.025,max(0.0,dn-d0));}float ao=1.0-clamp(occ/8.0*uSsao,0.0,0.34);vec3 color=aces((base+bl)*ao);color=pow(max(color,vec3(0)),vec3(0.4545));float y=luma(color);color=mix(color,vec3(y),0.025)+vec3(0.010,0.005,0.0);fragColor=vec4(color,1.0);}
            """.trimIndent()
            program=buildProgram(vs,fs);scene=GLES30.glGetUniformLocation(program,"uScene");depth=GLES30.glGetUniformLocation(program,"uDepth");res=GLES30.glGetUniformLocation(program,"uInvRes");ssao=GLES30.glGetUniformLocation(program,"uSsao");bloom=GLES30.glGetUniformLocation(program,"uBloom")
        }
        fun draw(sceneTex:Int,depthTex:Int,w:Int,h:Int,ao:Float,bl:Float){GLES30.glUseProgram(program);GLES30.glActiveTexture(GLES30.GL_TEXTURE0);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,sceneTex);GLES30.glUniform1i(scene,0);GLES30.glActiveTexture(GLES30.GL_TEXTURE1);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,depthTex);GLES30.glUniform1i(depth,1);GLES30.glUniform2f(res,1f/w,1f/h);GLES30.glUniform1f(ssao,ao);GLES30.glUniform1f(bloom,bl);GLES30.glDisable(GLES30.GL_CULL_FACE);GLES30.glEnableVertexAttribArray(0);GLES30.glEnableVertexAttribArray(1);GLES30.glVertexAttribPointer(0,3,GLES30.GL_FLOAT,false,0,pos);GLES30.glVertexAttribPointer(1,2,GLES30.GL_FLOAT,false,0,uv);GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP,0,4);GLES30.glDisableVertexAttribArray(0);GLES30.glDisableVertexAttribArray(1);GLES30.glEnable(GLES30.GL_CULL_FACE)}
        private fun buildProgram(vs:String,fs:String):Int{fun c(t:Int,s:String):Int{val id=GLES30.glCreateShader(t);GLES30.glShaderSource(id,s);GLES30.glCompileShader(id);val o=IntArray(1);GLES30.glGetShaderiv(id,GLES30.GL_COMPILE_STATUS,o,0);if(o[0]==0)throw IllegalStateException(GLES30.glGetShaderInfoLog(id));return id};val v=c(GLES30.GL_VERTEX_SHADER,vs);val f=c(GLES30.GL_FRAGMENT_SHADER,fs);val p=GLES30.glCreateProgram();GLES30.glAttachShader(p,v);GLES30.glAttachShader(p,f);GLES30.glLinkProgram(p);val o=IntArray(1);GLES30.glGetProgramiv(p,GLES30.GL_LINK_STATUS,o,0);if(o[0]==0)throw IllegalStateException(GLES30.glGetProgramInfoLog(p));GLES30.glDeleteShader(v);GLES30.glDeleteShader(f);return p}
    }

    private class ParticleShader{
        private val program:Int;private val vp:Int;private val buffer=ByteBuffer.allocateDirect(64*8*4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        init{val vs="""#version 300 es
            layout(location=0)in vec3 aPos;layout(location=1)in vec4 aColor;layout(location=2)in float aSize;uniform mat4 uVp;out vec4 vColor;void main(){gl_Position=uVp*vec4(aPos,1.0);gl_PointSize=aSize;vColor=aColor;}
        """.trimIndent();val fs="""#version 300 es
            precision mediump float;in vec4 vColor;layout(location=0)out vec4 fragColor;void main(){vec2 p=gl_PointCoord*2.0-1.0;float r=dot(p,p);if(r>1.0)discard;fragColor=vec4(vColor.rgb*1.12,(1.0-r)*vColor.a);}
        """.trimIndent();program=buildProgram(vs,fs);vp=GLES30.glGetUniformLocation(program,"uVp")}
        fun draw(data:FloatArray,count:Int,vbo:Int,viewProj:FloatArray){buffer.position(0);buffer.put(data,0,count*8);buffer.position(0);GLES30.glUseProgram(program);GLES30.glUniformMatrix4fv(vp,1,false,viewProj,0);GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,vbo);GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER,count*8*4,buffer,GLES30.GL_DYNAMIC_DRAW);GLES30.glEnableVertexAttribArray(0);GLES30.glEnableVertexAttribArray(1);GLES30.glEnableVertexAttribArray(2);GLES30.glVertexAttribPointer(0,3,GLES30.GL_FLOAT,false,8*4,0);GLES30.glVertexAttribPointer(1,4,GLES30.GL_FLOAT,false,8*4,3*4);GLES30.glVertexAttribPointer(2,1,GLES30.GL_FLOAT,false,8*4,7*4);GLES30.glEnable(0x8642);GLES30.glDepthMask(false);GLES30.glDrawArrays(GLES30.GL_POINTS,0,count);GLES30.glDepthMask(true);GLES30.glDisableVertexAttribArray(0);GLES30.glDisableVertexAttribArray(1);GLES30.glDisableVertexAttribArray(2);GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,0)}
        private fun buildProgram(vs:String,fs:String):Int{fun c(t:Int,s:String):Int{val id=GLES30.glCreateShader(t);GLES30.glShaderSource(id,s);GLES30.glCompileShader(id);val o=IntArray(1);GLES30.glGetShaderiv(id,GLES30.GL_COMPILE_STATUS,o,0);if(o[0]==0)throw IllegalStateException(GLES30.glGetShaderInfoLog(id));return id};val v=c(GLES30.GL_VERTEX_SHADER,vs);val f=c(GLES30.GL_FRAGMENT_SHADER,fs);val p=GLES30.glCreateProgram();GLES30.glAttachShader(p,v);GLES30.glAttachShader(p,f);GLES30.glLinkProgram(p);val o=IntArray(1);GLES30.glGetProgramiv(p,GLES30.GL_LINK_STATUS,o,0);if(o[0]==0)throw IllegalStateException(GLES30.glGetProgramInfoLog(p));GLES30.glDeleteShader(v);GLES30.glDeleteShader(f);return p}
    }

}
