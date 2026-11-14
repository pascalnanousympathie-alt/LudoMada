package com.ludomadagascar

import android.animation.ValueAnimator
import android.app.ActivityManager
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.opengl.EGL14
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLUtils
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.Choreographer
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.WindowManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.io.File
import java.nio.ShortBuffer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

private fun portraitAwareVerticalFov(
    aspect:Float,
    targetHorizontalFov:Float,
    landscapeVerticalFov:Float
):Float {
    if (aspect >= 1f) return landscapeVerticalFov
    val safeAspect = aspect.coerceAtLeast(0.1f).toDouble()
    val halfHorizontal = Math.toRadians(targetHorizontalFov.toDouble() * 0.5)
    val vertical = 2.0 * Math.atan(Math.tan(halfHorizontal) / safeAspect)
    return Math.toDegrees(vertical).toFloat().coerceIn(34f, 104f)
}

class LudoBoardView(
    context: Context,
    var onRollTap: (() -> Unit)? = null,
    var onPawnTap: ((Int) -> Unit)? = null,
    val homePreview: Boolean = false
) : GLSurfaceView(context) {

    var state: JSONObject? = null
        set(value) { field = value; renderer.state = value; requestRender() }
    var player: Int = -1
        set(value) { field = value; renderer.player = value; requestRender() }

    private val display = Array(4) { Array(4) { FloatArray(2) { Float.NaN } } }
    private val lastPos = Array(4) { IntArray(4) { Int.MIN_VALUE } }
    private lateinit var renderer: GameplayRenderer
    private var gameAudio: SpatialGameAudio? = null
    private fun audio(): SpatialGameAudio = gameAudio ?: SpatialGameAudio().also { gameAudio = it }
    private fun smokeMarker(value:String) {
        if (!BuildConfig.DEBUG) return
        runCatching {
            context.getSharedPreferences("ludomada_debug", Context.MODE_PRIVATE)
                .edit().putString("last_marker", value).apply()
        }
        android.util.Log.i("LudoMadaSmoke", "BOARD_MARKER=" + value)
    }

    private var stateAnimator: ValueAnimator? = null
    private var diceAnimator: ValueAnimator? = null
    private var diceRolling = false
    @Volatile private var softwareSurfaceBufferRequested = false
    @Volatile private var softwareSurfaceBufferActive = false
    @Volatile private var softwareSurfaceBufferScale = 1f
    private var appliedSoftwareSurfaceBufferWidth = 0
    private var appliedSoftwareSurfaceBufferHeight = 0
    @Volatile private var animatedRendering = false
    @Volatile private var softwareVsyncDrivenRendering = false
    @Volatile private var uiAnimationVsyncDescription = ""
    private var animationRenderOwners = 0
    private val uiAnimationVsyncTelemetry = if (BuildConfig.DEBUG) AnimationVsyncTelemetry() else null
    private val animationChoreographer by lazy(LazyThreadSafetyMode.NONE) { Choreographer.getInstance() }
    private val animationVsyncFrameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!animatedRendering) return
            uiAnimationVsyncTelemetry?.record(frameTimeNanos)
            animationChoreographer.postFrameCallback(this)
        }
    }
    private val homeFrameHandler = Handler(Looper.getMainLooper())
    private val homeFrameTick = object : Runnable {
        override fun run() {
            if (!homePreview || !isAttachedToWindow) return
            requestRender()
            homeFrameHandler.postDelayed(this, 33L)
        }
    }

    private fun deviceSupportsGles3(): Boolean {
        val info = (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.deviceConfigurationInfo
        return (info?.reqGlEsVersion ?: 0) >= 0x30000
    }

    private fun isLikelyEmulator(): Boolean {
        val f = android.os.Build.FINGERPRINT.lowercase()
        val m = android.os.Build.MODEL.lowercase()
        val h = android.os.Build.HARDWARE.lowercase()
        val p = android.os.Build.PRODUCT.lowercase()
        return f.contains("generic") || f.contains("emulator") ||
            m.contains("emulator") || m.contains("android sdk") ||
            h.contains("ranchu") || h.contains("goldfish") ||
            p.contains("sdk") || p.contains("emulator")
    }

    internal val hasSoftwareSurfaceBuffer: Boolean
        get() = softwareSurfaceBufferActive

    /**
     * The SwiftShader path renders the 3D scene to a quarter-size FBO. Request
     * a matching EGL window buffer so the final post-process pass does not
     * rasterize the same result across the full-size emulated surface.
     */
    internal fun requestSoftwareSurfaceBuffer(scale: Float) {
        if (homePreview || !scale.isFinite() || scale !in 0.01f..1f) return
        softwareSurfaceBufferScale = scale
        softwareSurfaceBufferRequested = true
        post { applySoftwareSurfaceBufferSize(width, height) }
    }

    private fun applySoftwareSurfaceBufferSize(viewWidth: Int, viewHeight: Int) {
        if (!softwareSurfaceBufferRequested || viewWidth <= 0 || viewHeight <= 0) return
        val targetWidth = SoftwareRendererPolicy.scaledDimension(viewWidth, softwareSurfaceBufferScale)
        val targetHeight = SoftwareRendererPolicy.scaledDimension(viewHeight, softwareSurfaceBufferScale)
        if (targetWidth == appliedSoftwareSurfaceBufferWidth &&
            targetHeight == appliedSoftwareSurfaceBufferHeight &&
            softwareSurfaceBufferActive
        ) return
        // Mark active before setFixedSize: SurfaceChanged may be delivered on
        // the GL thread immediately after the native surface request completes.
        softwareSurfaceBufferActive = true
        runCatching {
            holder.setFixedSize(targetWidth, targetHeight)
            appliedSoftwareSurfaceBufferWidth = targetWidth
            appliedSoftwareSurfaceBufferHeight = targetHeight
            android.util.Log.i(
                "LudoMadaRender",
                "SOFTWARE_SURFACE_BUFFER view=" + viewWidth + "x" + viewHeight +
                    " buffer=" + targetWidth + "x" + targetHeight +
                    " scale=" + softwareSurfaceBufferScale
            )
        }.onFailure {
            softwareSurfaceBufferActive = false
            android.util.Log.e("LudoMadaRender", "SOFTWARE_SURFACE_BUFFER_REQUEST_FAILED", it)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applySoftwareSurfaceBufferSize(w, h)
    }

    init {
        smokeMarker("BOARD_INIT_START:" + android.os.Build.MODEL + "|" + android.os.Build.FINGERPRINT)
        val gles3Available = deviceSupportsGles3()
        // Emulator status must never be used as a quality downgrade switch. Ramus is the
        // visual validation environment for the same GLES3/PBR path we ship to capable devices.
        // Only the actual reported GLES capability decides whether the premium renderer is selected.
        val emulator = isLikelyEmulator()
        val gles3 = gles3Available
        smokeMarker("BOARD_GLES_SELECT:" + gles3 + "|available=" + gles3Available + "|emulator=" + emulator)
        setEGLContextClientVersion(if (gles3) 3 else 2)
        if (homePreview) {
            setZOrderOnTop(false)
            setZOrderMediaOverlay(false)
            holder.setFormat(PixelFormat.RGBA_8888)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        }
        smokeMarker("BOARD_EGL_VERSION_SET:" + if (gles3) 3 else 2)
        renderer = if (gles3) SceneRenderer(this) else SafeSceneRenderer(this)
        renderer.homePreview = homePreview
        smokeMarker("BOARD_RENDERER_OBJECT:" + if (gles3) "PBR" else "SAFE_3D")
        android.util.Log.i("LudoMadaRender", "Renderer=" + if (gles3) "GLES3_PBR" else "GLES2_3D_FALLBACK")
        setRenderer(renderer)
        smokeMarker("BOARD_RENDERER_SET")
        renderMode = RENDERMODE_WHEN_DIRTY
        isFocusable = !homePreview
        isClickable = !homePreview
        contentDescription = if (homePreview) "LudoMada 3D Home Preview" else "LudoMada 3D Ludo board"
        // Keep this accessibility marker stable; renderer diagnostics are already logged and persisted separately.
        if (homePreview) post { configureHomePreview() }
    }

    @Volatile private var rendererReadyDescription: String? = null
    @Volatile private var rendererTelemetryDescription: String = ""
    @Volatile private var latestPawnTargetSignature: String = ""

    internal fun markRendererReady(
        rendererName:String,
        heroPartCount:Int,
        boardPartCount:Int,
        environmentPartCount:Int,
        revision7EnvironmentReady:Boolean,
        boardDrawSourcePartCount:Int = 0,
        boardPbrBatchCount:Int = 0,
        boardShadowBatchCount:Int = 0,
        heroDrawSourcePartCount:Int = 0,
        heroDrawBatchCount:Int = 0,
        environmentDrawSourcePartCount:Int = 0,
        environmentDrawBatchCount:Int = 0,
        dicePipFaceMeshCount:Int = 0,
        dicePipDrawCallsPerDie:Int = 0
    ) {
        if (!BuildConfig.DEBUG || homePreview) return
        val viewportWidth = width.coerceAtLeast(0)
        val viewportHeight = height.coerceAtLeast(0)
        val batchMarker = if (rendererName == "GLES3_PBR") {
            " | BOARD_DRAW_SOURCE_PARTS=$boardDrawSourcePartCount" +
                " | BOARD_PBR_BATCHES=$boardPbrBatchCount" +
                " | BOARD_SHADOW_BATCHES=$boardShadowBatchCount" +
                " | HERO_DRAW_SOURCE_PARTS=$heroDrawSourcePartCount" +
                " | HERO_DRAW_BATCHES=$heroDrawBatchCount" +
                " | ENV_DRAW_SOURCE_PARTS=$environmentDrawSourcePartCount" +
                " | ENV_PBR_BATCHES=$environmentDrawBatchCount" +
                " | DICE_PIP_FACE_MESHES=$dicePipFaceMeshCount" +
                " | DICE_PIP_DRAW_CALLS_PER_DIE=$dicePipDrawCallsPerDie"
        } else ""
        val marker = "LudoMada 3D Ludo board | RENDER_PASS=$rendererName | HERO_PARTS=$heroPartCount" +
            " | BOARD_PARTS=$boardPartCount | ENV_PARTS=$environmentPartCount" +
            " | ENV_R7_COMPLETE=" + (if (revision7EnvironmentReady) "1" else "0") +
            " | BOARD_VIEWPORT=${viewportWidth}x${viewportHeight}" + batchMarker
        rendererReadyDescription = marker
        rendererTelemetryDescription = ""
        latestPawnTargetSignature = ""
        post {
            if (rendererReadyDescription == marker && contentDescription != marker) contentDescription = marker
        }
    }

    internal fun markHomePreviewReady(rendererName:String, environmentParts:Int, baobabPartCount:Int, heroPartCount:Int, boardPartCount:Int) {
        if (!BuildConfig.DEBUG || !homePreview) return
        val marker = "LudoMada 3D Home Preview | HOME_3D_FRAME_COMPLETE | RENDERER=" + rendererName +
            " | ENV_PARTS=" + environmentParts + " | BAOBAB_PARTS=" + baobabPartCount +
            " | HERO_PARTS=" + heroPartCount + " | BOARD_PARTS=" + boardPartCount
        post {
            if (contentDescription != marker) contentDescription = marker
        }
    }

    internal fun markHomePreviewNotReady() {
        if (!BuildConfig.DEBUG || !homePreview) return
        post {
            contentDescription = "LudoMada 3D Home Preview"
        }
    }

    internal fun publishRendererTelemetry(description: String) {
        if (!BuildConfig.DEBUG || homePreview) return
        val normalized = description.trim()
        if (normalized.isEmpty() || rendererTelemetryDescription == normalized) return
        rendererTelemetryDescription = normalized
        publishPawnTapTargets()
    }

    internal fun publishPawnTapTargets() {
        if (!BuildConfig.DEBUG || homePreview) return
        // The physical touch handler projects hitboxes directly. Updating this
        // accessibility-only description for every interpolated pawn position
        // needlessly invalidates the UI tree and competes with animation vsync.
        if (!RendererTelemetryAccessibility.shouldPublishPawnTapTargets(animatedRendering)) return
        val readyMarker = rendererReadyDescription ?: return
        val telemetryMarker = rendererTelemetryDescription
        val uiVsyncMarker = uiAnimationVsyncDescription
        val marker = buildString {
            append(if (telemetryMarker.isEmpty()) readyMarker else "$readyMarker | $telemetryMarker")
            if (uiVsyncMarker.isNotEmpty()) append(" | ").append(uiVsyncMarker)
        }
        val activePlayer = player
        val dice = state?.optInt("dice", 0) ?: 0
        val players = state?.optJSONArray("players")
        val tokens = players?.optJSONObject(activePlayer)?.optJSONArray("tokens")
        val projected = if (activePlayer in 0..3) renderer.projectedTokens(activePlayer)
            else Array(4) { FloatArray(2) { -1000f } }
        val targets = ArrayList<Pair<Int, FloatArray>>(4)
        if (activePlayer in 0..3 && dice in 1..6 && tokens != null) {
            for (ti in 0..3) {
                val pos = tokens.optJSONObject(ti)?.optInt("pos", -1) ?: -1
                val legal = if (pos < 0) dice == 6 else pos + dice <= 57
                val p = projected[ti]
                if (legal && p[0].isFinite() && p[1].isFinite() &&
                    p[0] >= 0f && p[0] < width.toFloat() &&
                    p[1] >= 0f && p[1] < height.toFloat()) {
                    targets.add(ti to floatArrayOf(p[0], p[1]))
                }
            }
        }
        val targetSignature = targets.joinToString(";") { (index, p) ->
            "$index@" + String.format(java.util.Locale.US, "%.1f,%.1f", p[0], p[1])
        }
        val signature = "$targetSignature|$telemetryMarker|$uiVsyncMarker"
        if (signature == latestPawnTargetSignature) return
        latestPawnTargetSignature = signature
        post {
            if (rendererReadyDescription != readyMarker || rendererTelemetryDescription != telemetryMarker) return@post
            val origin = IntArray(2)
            getLocationOnScreen(origin)
            val metrics = resources.displayMetrics
            val screenWidth = max(1, metrics.widthPixels).toFloat()
            val screenHeight = max(1, metrics.heightPixels).toFloat()
            val normalized = targets.mapNotNull { (index, p) ->
                val x = (origin[0] + p[0]) / screenWidth
                val y = (origin[1] + p[1]) / screenHeight
                if (x in 0f..1f && y in 0f..1f) {
                    "$index@" + String.format(java.util.Locale.US, "%.5f,%.5f", x, y)
                } else null
            }
            val description = marker + " | PAWN_TAP_TARGETS=" + normalized.joinToString(";")
            if (contentDescription != description) contentDescription = description
        }
    }

    fun configureHomePreview() {
        if (!homePreview) return
        val players = JSONArray()
        for (pi in 0..3) {
            val tokens = JSONArray()
            for (ti in 0..3) tokens.put(JSONObject().put("pos", -1))
            players.put(JSONObject().put("tokens", tokens))
        }
        state = JSONObject()
            .put("players", players)
            .put("dice", 5)
            .put("phase", "PLAYING")
        player = 0
        animateState()
    }

    fun isDiceRolling(): Boolean = diceRolling

    private fun findHostActivity(ctx: Context): MainActivity? {
        var c: Context? = ctx
        repeat(8) {
            when (c) {
                is MainActivity -> return c
                is android.content.ContextWrapper -> c = c.baseContext
                else -> return null
            }
        }
        return null
    }

    override fun onResume() {
        super.onResume()
        if (homePreview) {
            homeFrameHandler.removeCallbacks(homeFrameTick)
            homeFrameHandler.post(homeFrameTick)
        }
    }

    override fun onPause() {
        homeFrameHandler.removeCallbacks(homeFrameTick)
        stateAnimator?.cancel()
        stateAnimator = null
        diceAnimator?.cancel()
        diceAnimator = null
        diceRolling = false
        super.onPause()
    }

    internal fun setSoftwareVsyncDrivenRendering(enabled: Boolean) {
        softwareVsyncDrivenRendering = enabled && !homePreview
        post {
            if (!animatedRendering) return@post
            renderMode = if (softwareVsyncDrivenRendering) RENDERMODE_WHEN_DIRTY
                else RENDERMODE_CONTINUOUSLY
            if (softwareVsyncDrivenRendering) requestRender()
        }
    }

    private fun beginAnimationRendering() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "Animation render ownership must be changed on the main thread"
        }
        val firstOwner = animationRenderOwners == 0
        animationRenderOwners += 1
        if (firstOwner) {
            if (BuildConfig.DEBUG) {
                uiAnimationVsyncTelemetry?.reset()
                uiAnimationVsyncDescription = ""
                animationChoreographer.postFrameCallback(animationVsyncFrameCallback)
            }
            animatedRendering = true
        } else {
            animatedRendering = true
        }
        renderMode = if (softwareVsyncDrivenRendering) RENDERMODE_WHEN_DIRTY
            else RENDERMODE_CONTINUOUSLY
    }

    private fun endAnimationRendering() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "Animation render ownership must be changed on the main thread"
        }
        animationRenderOwners = (animationRenderOwners - 1).coerceAtLeast(0)
        if (animationRenderOwners == 0 && animatedRendering) {
            animatedRendering = false
            if (BuildConfig.DEBUG) {
                animationChoreographer.removeFrameCallback(animationVsyncFrameCallback)
                uiAnimationVsyncDescription = formatUiAnimationVsyncDiagnostic()
            }
            renderMode = RENDERMODE_WHEN_DIRTY
            requestRender()
        }
    }

    private fun formatUiAnimationVsyncDiagnostic(): String {
        val snapshot = uiAnimationVsyncTelemetry?.snapshot() ?: return ""
        val refreshRate = runCatching {
            (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
                ?.defaultDisplay?.refreshRate
        }.getOrNull()?.takeIf { it.isFinite() && it > 0f }
        fun number(value: Float?): String =
            value?.takeIf { it.isFinite() && it > 0f }?.let {
                String.format(Locale.US, "%.2f", it)
            } ?: "NA"
        return listOf(
            "UI_VSYNC_SAMPLES=${snapshot.sampleCount}",
            "UI_VSYNC_FPS=" + number(snapshot.fps),
            "UI_VSYNC_FRAME_MS=" + number(snapshot.intervalP50Ms) + "/" + number(snapshot.intervalP95Ms),
            "DISPLAY_REFRESH_HZ=" + number(refreshRate)
        ).joinToString(" | ")
    }

    fun startDiceAnimation() {
        if (diceRolling) return
        diceRolling = true
        audio().play(0f, 0.85f, 760.0)
        pulseHaptic(24L, 180)
        diceAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 720L
            addUpdateListener {
                renderer.diceAnim = it.animatedValue as Float
                if (softwareVsyncDrivenRendering) requestRender()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (diceAnimator === animation) diceAnimator = null
                    diceRolling = false
                    renderer.diceAnim = 1f
                    endAnimationRendering()
                }
            })
            beginAnimationRendering()
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
        var pawnMoved = false
        var hasVisualPositionChange = false
        for (pi in 0..3) for (ti in 0..3) {
            val from = oldPos[pi][ti]
            val to = newPos[pi][ti]
            if (PawnAnimationPolicy.shouldAnimate(from, to)) {
                hasVisualPositionChange = true
                if (PawnMoveSoundPolicy.shouldPlay(from, to)) pawnMoved = true
                if (from >= 0 && to > from) maxSteps = max(maxSteps, min(6, to - from))
                if (from < 0 && to >= 0) maxSteps = max(maxSteps, 1)
                if (from >= 0 && to < 0) maxSteps = max(maxSteps, 2)
            }
        }
        // State refreshes often change only the die/turn. Do not run a 320 ms
        // continuous GL animation when no pawn position changed.
        if (!hasVisualPositionChange) {
            for (pi in 0..3) for (ti in 0..3) {
                display[pi][ti][0] = targets[pi][ti][0]
                display[pi][ti][1] = targets[pi][ti][1]
                renderer.lift[pi][ti] = 0f
                renderer.bounce[pi][ti] = 0f
                lastPos[pi][ti] = newPos[pi][ti]
            }
            requestRender()
            return
        }
        // A pawn sound belongs to a committed position change, not to a tap attempt.
        if (pawnMoved && !homePreview) audio().play(0f, 0.62f, 520.0)
        val duration = (maxSteps * 120L + 320L).coerceAtLeast(320L)

        stateAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            interpolator = android.view.animation.DecelerateInterpolator(1.45f)
            addUpdateListener {
                val f = it.animatedValue as Float
                for (pi in 0..3) for (ti in 0..3) {
                    val from = oldPos[pi][ti]
                    val to = newPos[pi][ti]
                    if (!PawnAnimationPolicy.shouldAnimate(from, to)) {
                        display[pi][ti][0] = targets[pi][ti][0]
                        display[pi][ti][1] = targets[pi][ti][1]
                        renderer.lift[pi][ti] = 0f
                        renderer.bounce[pi][ti] = 0f
                        continue
                    }
                    renderer.bounce[pi][ti] = f
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
                        val start = if (from < 0) homeGrid(pi, ti) else animationGrid(pi, from)
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
                if (softwareVsyncDrivenRendering) requestRender()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (stateAnimator === animation) stateAnimator = null
                    for (pi in 0..3) for (ti in 0..3) {
                        display[pi][ti][0] = targets[pi][ti][0]
                        display[pi][ti][1] = targets[pi][ti][1]
                        renderer.lift[pi][ti] = 0f
                        renderer.bounce[pi][ti] = 0f
                        lastPos[pi][ti] = newPos[pi][ti]
                    }
                    endAnimationRendering()
                }
            })
            beginAnimationRendering()
            start()
        }
    }

    private fun homeGrid(pi: Int, ti: Int): FloatArray {
        val c=2.5833335f
        val d=0.43f
        // Player index order follows the authored board zones: Red, Green, Gold, Blue.
        val xy = arrayOf(
            floatArrayOf(-c-d,c-d), floatArrayOf(-c+d,c-d), floatArrayOf(-c-d,c+d), floatArrayOf(-c+d,c+d),
            floatArrayOf(-c-d,-c-d), floatArrayOf(-c+d,-c-d), floatArrayOf(-c-d,-c+d), floatArrayOf(-c+d,-c+d),
            floatArrayOf(c-d,-c-d), floatArrayOf(c+d,-c-d), floatArrayOf(c-d,-c+d), floatArrayOf(c+d,-c+d),
            floatArrayOf(c-d,c-d), floatArrayOf(c+d,c-d), floatArrayOf(c-d,c+d), floatArrayOf(c+d,c+d)
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
        val d=0.41666667f*step
        return when (pi) {
            0 -> floatArrayOf(-1.1666667f+d,0f)
            1 -> floatArrayOf(0f,-0.9166667f+d)
            2 -> floatArrayOf(1.1666667f-d,0f)
            else -> floatArrayOf(0f,1.1666667f-d)
        }
    }

    private val cachedVisualPath: List<Pair<Int, Int>> = listOf(
        6 to 0, 6 to 1, 6 to 2, 6 to 3, 6 to 4, 6 to 5, 5 to 6, 4 to 6, 3 to 6, 2 to 6, 1 to 6, 0 to 6,
        0 to 7, 0 to 8, 1 to 8, 2 to 8, 3 to 8, 4 to 8, 5 to 8, 6 to 9, 6 to 10, 6 to 11, 6 to 12, 6 to 13, 6 to 14,
        7 to 14, 8 to 14, 8 to 13, 8 to 12, 8 to 11, 8 to 10, 8 to 9, 9 to 8, 10 to 8, 11 to 8, 12 to 8, 13 to 8, 14 to 8,
        14 to 7, 14 to 6, 13 to 6, 12 to 6, 11 to 6, 10 to 6, 9 to 6, 8 to 5, 8 to 4, 8 to 3, 8 to 2, 8 to 1, 8 to 0, 7 to 0
    )

    private fun visualPath(): List<Pair<Int, Int>> = cachedVisualPath

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (homePreview) {
            // GLSurfaceView can own the touch stream even when the parent FrameLayout
            // cannot intercept it on some Android/Ramus rotation paths. Handle Home
            // navigation directly here while keeping gameplay input untouched.
            if (e.actionMasked == MotionEvent.ACTION_UP) {
                val w = max(1f, width.toFloat())
                val h = max(1f, height.toFloat())
                val nx = e.x / w
                val ny = e.y / h
                // Accept the JOUER rectangle in every orientation mapping used
                // by Android/Ramus. The visual CTA is x≈1010..1210,y≈360..470 on
                // the 1280×720 Home canvas, while Ramus may feed normalized portrait
                // or clockwise/counter-clockwise transformed coordinates.
                val landscape = nx >= 0.79f && nx <= 0.97f && ny >= 0.48f && ny <= 0.66f
                val cw = nx >= 0.34f && nx <= 0.52f && ny >= 0.76f && ny <= 0.98f
                val ccw = nx >= 0.50f && nx <= 0.70f && ny >= 0.02f && ny <= 0.24f
                val rot180 = nx >= 0.03f && nx <= 0.25f && ny >= 0.34f && ny <= 0.52f
                val playHit = landscape || cw || ccw || rot180
                if (playHit) {
                    val activity = findHostActivity(context)
                    android.util.Log.i("LudoMadaSmoke", "HOME_PLAY_HIT activity=" + (activity?.javaClass?.name ?: "null"))
                    activity?.runOnUiThread {
                        runCatching {
                            activity.gameModeDialog()
                            android.util.Log.i("LudoMadaSmoke", "GAME_MODE_DIALOG_DISPATCHED_FROM_BOARD")
                        }.onFailure {
                            android.util.Log.e("LudoMadaSmoke", "GAME_MODE_DIALOG_FROM_BOARD_FAILED", it)
                        }
                    }
                    return true
                }
                val walletHit = if (w < h) {
                    nx >= 0.40f && nx <= 0.62f && ny >= 0.14f && ny <= 0.34f
                } else {
                    nx <= 0.25f && ny >= 0.42f && ny <= 0.62f
                }
                if (walletHit) {
                    val activity = findHostActivity(context)
                    android.util.Log.i("LudoMadaSmoke", "HOME_WALLET_HIT activity=" + (activity?.javaClass?.name ?: "null"))
                    activity?.runOnUiThread {
                        runCatching { activity.wallet() }
                            .onFailure { android.util.Log.e("LudoMadaSmoke", "WALLET_FROM_BOARD_FAILED", it) }
                    }
                    return true
                }
                // Preview dice stay in the four player corners. Tapping any visible
                // die rotates that die without mutating authoritative game state.
                val projectedDice = renderer.projectedDice()
                val dieHit = min(width, height) * 0.12f
                for (pi in 0..3) {
                    val p = projectedDice[pi]
                    val dx = e.x - p[0]
                    val dy = e.y - p[1]
                    if (p[0] >= 0f && p[1] >= 0f && dx * dx + dy * dy <= dieHit * dieHit) {
                        player = pi
                        startDiceAnimation()
                        return true
                    }
                }
            }
            return true
        }
        if (e.actionMasked != MotionEvent.ACTION_UP || player !in 0..3) return true
        val projectedDice = renderer.projectedDice()
        if (BuildConfig.DEBUG) {
            try {
                File(context.filesDir, "touch-debug.txt").appendText(
                    "touch=" + e.x + "," + e.y +
                    " size=" + width + "x" + height +
                    " die0=" + projectedDice[0][0] + "," + projectedDice[0][1] +
                    " die1=" + projectedDice[1][0] + "," + projectedDice[1][1] + "\n"
                )
            } catch (_:Throwable) {}
        }
        // MotionEvent coordinates are local to this GLSurfaceView. The renderer
        // projects dice and pawn hitboxes into the same width/height pixel space,
        // so keep the input unrotated in both portrait and landscape layouts.
        val touchX = e.x
        val touchY = e.y
        val dieHit = min(width, height) * 0.120f
        for (pi in 0..3) {
            val p = projectedDice[pi]
            if (p[0] < 0f || p[1] < 0f) continue
            val dx = touchX - p[0]
            val dy = touchY - p[1]
            if (dx * dx + dy * dy <= dieHit * dieHit) {
                if (pi == player) {
                    performClick()
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onRollTap?.invoke()
                }
                return true
            }
        }
        val projected = renderer.projectedTokens(player)
        var best = -1
        var bestD = Float.MAX_VALUE
        for (ti in 0..3) {
            val p = projected[ti]
            val ddx = touchX - p[0]
            val ddy = touchY - p[1]
            val d2 = ddx * ddx + ddy * ddy
            if (d2 < bestD) { bestD = d2; best = ti }
        }
        val hit = min(width, height) * 0.075f
        if (best >= 0 && bestD <= hit * hit) {
            performClick()
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            pulseHaptic(18L, 120)
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
        homeFrameHandler.removeCallbacks(homeFrameTick)
        stateAnimator?.cancel()
        stateAnimator = null
        diceAnimator?.cancel()
        diceAnimator = null
        diceRolling = false
        gameAudio?.release()
        gameAudio = null
        super.onDetachedFromWindow()
    }



    private interface GameplayRenderer : GLSurfaceView.Renderer {
        var state: JSONObject?
        var player: Int
        var homePreview: Boolean
        var diceAnim: Float
        val lift: Array<FloatArray>
        val bounce: Array<FloatArray>
        fun projectedTokens(pi: Int): Array<FloatArray>
        fun projectedDice(): Array<FloatArray>
    }

    /**
     * Compact GLES2 gameplay renderer used on constrained/mobile/cloud devices.
     * It remains a real perspective 3D scene: depth, normals, specular lighting,
     * geometric board cells, 3D pawns, physical die mesh and animated highlights.
     */
    internal class SafeSceneRenderer(private val host:LudoBoardView) : GameplayRenderer {
        override var state:JSONObject?=null
        override var player:Int=-1
        override var homePreview:Boolean=host.homePreview
        override var diceAnim=1f
        override val lift=Array(4){FloatArray(4)}
        override val bounce=Array(4){FloatArray(4)}

        private val proj=FloatArray(16)
        private val view=FloatArray(16)
        private val vp=FloatArray(16)
        private val snapshot=FloatArray(16)
        private val scratch=FloatArray(16)
        private val tmp=FloatArray(16)
        private var width=1
        private var height=1
        private var program=0
        private var firstFrameDiagnosticsCaptured=false
        private var uMvp=0
        private var uModel=0
        private var uCamera=0
        private var uLight=0
        private var uColor=0
        private var uMetallic=0
        private var uRoughness=0
        private var uEmission=0
        private var uChrome=0

        private lateinit var cube:Mesh
        private lateinit var die:Mesh
        private lateinit var disk:Mesh
        private lateinit var sphere:Mesh
        private lateinit var pawn:Mesh
        private var glbParts:Map<String,GlbRuntimeLoader.Part> = emptyMap()
        private var boardParts:Map<String,GlbRuntimeLoader.Part> = emptyMap()
        private val boardScaleXZ=1.6666667f

        private val pawnColors=intArrayOf(
            Color.rgb(219,59,66),Color.rgb(48,165,92),
            Color.rgb(239,186,46),Color.rgb(59,112,219)
        )

        override fun onSurfaceCreated(
            gl:javax.microedition.khronos.opengles.GL10?,
            cfg:javax.microedition.khronos.egl.EGLConfig?
        ){
            // A recreated EGL context invalidates every previous GL object name.
            firstFrameDiagnosticsCaptured = false
            program = 0
            if (host.homePreview) host.markHomePreviewNotReady()
            runCatching {
                host.context.getSharedPreferences("ludomada_debug", Context.MODE_PRIVATE)
                    .edit().putString("last_marker", "SAFE_SURFACE_CREATED").apply()
            }
            android.util.Log.i("LudoMadaRender","SAFE_SURFACE_CREATED")
            GLES20.glClearColor(0.008f,0.018f,0.045f,1f)
            GLES20.glEnable(GLES20.GL_DEPTH_TEST)
            GLES20.glDepthFunc(GLES20.GL_LEQUAL)
            GLES20.glEnable(GLES20.GL_CULL_FACE)
            GLES20.glCullFace(GLES20.GL_BACK)
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA,GLES20.GL_ONE_MINUS_SRC_ALPHA)

            val vs="""
                attribute vec3 aPos;
                attribute vec3 aNormal;
                uniform mat4 uMvp;
                uniform mat4 uModel;
                varying vec3 vWorld;
                varying vec3 vNormal;
                void main(){
                    vec4 w=uModel*vec4(aPos,1.0);
                    vWorld=w.xyz;
                    vNormal=normalize(mat3(uModel)*aNormal);
                    gl_Position=uMvp*vec4(aPos,1.0);
                }
            """.trimIndent()

            val fs="""
                precision mediump float;
                uniform vec4 uColor;
                uniform vec3 uCamera;
                uniform vec3 uLight;
                uniform float uMetallic;
                uniform float uRoughness;
                uniform float uEmission;
                uniform float uChrome;
                varying vec3 vWorld;
                varying vec3 vNormal;
                void main(){
                    vec3 N=normalize(vNormal);
                    vec3 L=normalize(uLight-vWorld);
                    vec3 V=normalize(uCamera-vWorld);
                    vec3 H=normalize(L+V);
                    float ndl=max(dot(N,L),0.0);
                    float ndv=max(dot(N,V),0.0);
                    float ndh=max(dot(N,H),0.0);
                    float shin=mix(18.0,96.0,1.0-clamp(uRoughness,0.0,1.0));
                    float spec=pow(ndh,shin)*(0.08+0.74*clamp(uMetallic,0.0,1.0));
                    float rim=pow(1.0-ndv,2.0)*0.09;
                    vec3 R=reflect(-V,N);
                    float horizon=smoothstep(-0.58,0.78,R.y);
                    float sweep=0.5+0.5*sin(R.x*8.0+R.z*13.0);
                    float band=exp(-pow((R.y-0.10)*4.2,2.0));
                    vec3 chromeEnv=mix(vec3(0.035,0.055,0.085),vec3(1.55,1.60,1.66),horizon);
                    chromeEnv*=0.84+0.16*sweep;
                    chromeEnv+=vec3(1.0,0.98,0.96)*0.55*band;
                    float fresnel=pow(1.0-ndv,4.0);
                    float chromeMask=clamp(uChrome,0.0,1.0);
                    vec3 base=uColor.rgb;
                    // Procedural tabletop wood: continuous grain, directional bands and
                    // subtle micro-variation, all generated in the shader for GLES2 safety.
                    float brownMask=smoothstep(0.55,0.78,base.r)
                                   * smoothstep(0.58,0.34,base.g)
                                   * smoothstep(0.54,0.30,base.b);
                    float grainA=0.5+0.5*sin(vWorld.x*28.0+sin(vWorld.z*5.5)*2.4);
                    float grainB=0.5+0.5*sin(vWorld.z*9.0+vWorld.x*4.0);
                    float knots=0.5+0.5*sin(vWorld.x*4.1+vWorld.z*3.7);
                    float wood=mix(0.72,1.16,grainA*0.70+grainB*0.30)
                              *(0.93+0.10*knots);
                    float fine=0.972+0.028*sin(vWorld.x*73.0+vWorld.z*61.0);
                    base=mix(base,base*wood,brownMask);
                    base*=fine;
                    // Light ceramic/painted cells keep a very small surface micro-pattern.
                    float lightMask=smoothstep(0.72,0.90,base.r)
                                   * smoothstep(0.70,0.92,base.g)
                                   * smoothstep(0.60,0.88,base.b);
                    base*=mix(1.0,0.985+0.025*sin(vWorld.x*96.0+vWorld.z*84.0),lightMask);
                    // Blue woven tabletop outside the board: subtle tile/grid texture.
                    float blueMask=smoothstep(0.18,0.03,base.r)*smoothstep(0.16,0.36,base.b);
                    vec2 tile=fract(vWorld.xz*0.78)-0.5;
                    float line=1.0-smoothstep(0.03,0.075,min(abs(tile.x),abs(tile.y)));
                    float tileShade=0.90+0.06*sin(vWorld.x*2.8+vWorld.z*1.7);
                    base=mix(base,base*tileShade,blueMask*0.42);
                    base=mix(base,base*(0.78+0.10*line),blueMask*0.36);

                    vec3 ambient=base*(0.40+0.14*ndv);
                    vec3 diffuse=base*(1.05*ndl);
                    vec3 highlight=vec3(1.0,0.97,0.91)*spec;
                    vec3 c=ambient+diffuse+highlight+base*(uEmission+rim);
                    vec3 chrome=chromeEnv*(0.68+0.92*fresnel)+vec3(1.0)*spec*3.25;
                    c=mix(c,chrome,chromeMask);
                    c=c/(c+vec3(1.0));
                    c=pow(max(c,vec3(0.0)),vec3(0.92));
                    gl_FragColor=vec4(c,uColor.a);
                }
            """.trimIndent()

            program=buildProgram(vs,fs)
            uMvp=GLES20.glGetUniformLocation(program,"uMvp")
            uModel=GLES20.glGetUniformLocation(program,"uModel")
            uCamera=GLES20.glGetUniformLocation(program,"uCamera")
            uLight=GLES20.glGetUniformLocation(program,"uLight")
            uColor=GLES20.glGetUniformLocation(program,"uColor")
            uMetallic=GLES20.glGetUniformLocation(program,"uMetallic")
            uRoughness=GLES20.glGetUniformLocation(program,"uRoughness")
            uEmission=GLES20.glGetUniformLocation(program,"uEmission")
            uChrome=GLES20.glGetUniformLocation(program,"uChrome")

            cube=cubeMesh()
            die=roundedBoxMesh(24,12)
            disk=diskMesh(40)
            sphere=sphereMesh(16,10)
            pawn=pawnMesh()
            val glbLoader=GlbRuntimeLoader(host.context)
            glbParts=runCatching{
                glbLoader.loadHeroParts().also{
                    android.util.Log.i("LudoMadaRender","GLB_RUNTIME_LOAD_OK parts="+it.keys.joinToString(","))
                    runCatching{host.context.getSharedPreferences("ludomada_debug",Context.MODE_PRIVATE).edit().putString("last_marker","GLB_RUNTIME_LOAD_OK:"+it.size).apply()}
                }
            }.getOrElse{
                android.util.Log.e("LudoMadaRender","GLB_RUNTIME_LOAD_FAILED",it)
                runCatching{host.context.getSharedPreferences("ludomada_debug",Context.MODE_PRIVATE).edit().putString("last_marker","GLB_RUNTIME_LOAD_FAILED:"+it.javaClass.simpleName).apply()}
                emptyMap()
            }
            boardParts=runCatching{
                glbLoader.loadBoardParts().also{
                    android.util.Log.i("LudoMadaRender","BOARD_GLB_RUNTIME_LOAD_OK parts="+it.size)
                    runCatching{host.context.getSharedPreferences("ludomada_debug",Context.MODE_PRIVATE).edit().putString("last_marker","BOARD_GLB_RUNTIME_LOAD_OK:"+it.size).apply()}
                }
            }.getOrElse{
                android.util.Log.e("LudoMadaRender","BOARD_GLB_RUNTIME_LOAD_FAILED",it)
                emptyMap()
            }
        }

        override fun onSurfaceChanged(
            gl:javax.microedition.khronos.opengles.GL10?,
            w:Int,h:Int
        ){
            width=max(1,w)
            height=max(1,h)
            GLES20.glViewport(0,0,width,height)
            host.requestRender()
        }

        override fun onDrawFrame(gl:javax.microedition.khronos.opengles.GL10?){
            val aspect=width.toFloat()/height.toFloat()
            // Closer oblique tabletop framing: the board must fill the landscape playfield.
            val cameraY = if (homePreview) 8.4f else 13.2f
            val cameraZ = if (homePreview) 9.4f else 4.2f
            val gameplayHorizontalFov = if (aspect < 0.80f) 42f else 45f
            val fov = portraitAwareVerticalFov(
                aspect,
                if (homePreview) 48f else gameplayHorizontalFov,
                if (homePreview) 34f else 38f
            )
            Matrix.perspectiveM(proj,0,fov,aspect,0.1f,60f)
            Matrix.setLookAtM(
                view,0,0f,cameraY,cameraZ,
                0f,0.18f,0f,
                0f,1f,0f
            )
            Matrix.multiplyMM(vp,0,proj,0,view,0)
            System.arraycopy(vp,0,snapshot,0,16)

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,0)
            GLES20.glViewport(0,0,width,height)
            GLES20.glClearColor(0.008f,0.018f,0.045f,if (homePreview) 0f else 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            GLES20.glUseProgram(program)
            GLES20.glUniform3f(uCamera,0f,cameraY,cameraZ)
            GLES20.glUniform3f(uLight,-6.0f,14f,8f)

            // Home preview is a transparent live replacement over the supplied Home screenshot;
            // do not paint a second environment/table behind it. Gameplay keeps the full tabletop.
            if (!homePreview) {
                drawMesh(cube,model(0f,-1.0f,0f,13f,0.08f,13f,0f),color(78,38,14,1f),0.54f,0.08f,0f)
                drawMesh(cube,model(0f,2.9f,-6.2f,8.0f,3.0f,0.10f,0f),color(44,30,23,1f),0.70f,0.02f,0f)
                drawMesh(sphere,model(-5.3f,3.15f,-5.8f,0.26f,0.26f,0.26f,0f),color(246,192,86,1f),0.12f,0.02f,0f,0.90f)
                drawMesh(sphere,model(5.3f,3.15f,-5.8f,0.26f,0.26f,0.26f,0f),color(246,192,86,1f),0.12f,0.02f,0f,0.90f)
                drawMesh(cube,model(0f,-0.48f,0f,5.45f,0.34f,5.45f,0f),color(66,26,8,1f),0.54f,0.10f,0f)
            }
            if (boardParts.isNotEmpty()) {
                drawAuthoredBoard()
                drawHomePawnSlots()
            } else {
                drawMesh(cube,model(0f,-0.04f,0f,5.12f,0.12f,5.12f,0f),color(112,55,18,1f),0.42f,0.16f,0f)
                drawMesh(cube,model(0f,0.095f,0f,4.82f,0.075f,4.82f,0f),color(182,112,42,1f),0.34f,0.18f,0f)
                drawMesh(cube,model(0f,0.17f,0f,4.48f,0.062f,4.48f,0f),color(154,86,30,1f),0.34f,0.14f,0.02f)
                drawPath()
                drawZones()
            }
            drawPawns()
            drawDice()
            logSafeFrameReadback()
            host.publishPawnTapTargets()
        }

        private fun logSafeFrameReadback(){
            if(!BuildConfig.DEBUG || firstFrameDiagnosticsCaptured)return
            firstFrameDiagnosticsCaptured=true
            runCatching {
                val total=max(1,width*height)
                val pixels=ByteBuffer.allocateDirect(total*4).order(ByteOrder.nativeOrder())
                GLES20.glFinish()
                GLES20.glReadPixels(0,0,width,height,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,pixels)
                val glError=GLES20.glGetError()
                var brightPixels=0
                var maxChannel=0
                for(i in 0 until total){
                    val off=i*4
                    val r=pixels.get(off).toInt() and 0xff
                    val g=pixels.get(off+1).toInt() and 0xff
                    val b=pixels.get(off+2).toInt() and 0xff
                    val peak=max(r,max(g,b))
                    if(peak>24)brightPixels++
                    maxChannel=max(maxChannel,peak)
                }
                android.util.Log.i(
                    "LudoMadaRender",
                    "SAFE_READBACK label=surface size="+width+"x"+height+
                        " brightPixels="+brightPixels+"/"+total+
                        " maxChannel="+maxChannel+
                        " glError=0x"+glError.toString(16)
                )
                val expectedHeroParts=setOf(
                    "LM_Pawn_Base_Weighted","LM_Pawn_Collar","LM_Pawn_Spherical_Head",
                    "LM_Pawn_Tapered_Body","LM_Pawn_Underside_Insert","LM_Die_HydroChrome_Body"
                )
                if(glError==GLES20.GL_NO_ERROR && brightPixels>total/200 && maxChannel>24 &&
                    glbParts.keys.containsAll(expectedHeroParts) && boardParts.isNotEmpty()){
                    android.util.Log.i("LudoMadaRender","SAFE_FIRST_FRAME_COMPLETE")
                    if (host.homePreview) {
                        android.util.Log.i("LudoMadaRender","HOME_3D_FALLBACK_FRAME_COMPLETE renderer=GLES2_GLB")
                        host.markHomePreviewReady("GLES2_GLB",0,0,glbParts.size,boardParts.size)
                    } else {
                        host.markRendererReady("GLES2_GLB",glbParts.size,boardParts.size,0,false)
                    }
                }
            }.onFailure{
                android.util.Log.e("LudoMadaRender","SAFE_READBACK_FAILED",it)
            }
        }

        private fun drawPath(){
            for((r,c) in host.visualPath()){
                val x=(c-7f)*0.50f
                val z=(r-7f)*0.50f
                // Engraved dark edge + warm ivory tile.
                drawMesh(cube,model(x,0.242f,z,0.238f,0.060f,0.238f,0f),color(83,55,31,1f),0.72f,0.02f,0f)
                drawMesh(cube,model(x,0.300f,z,0.207f,0.022f,0.207f,0f),color(241,225,185,1f),0.44f,0.03f,0f)
            }
        }

        private fun drawZones(){
            val centers=arrayOf(
                floatArrayOf(-2.58f,2.58f),
                floatArrayOf(-2.58f,-2.58f),
                floatArrayOf(2.58f,-2.58f),
                floatArrayOf(2.58f,2.58f)
            )
            for(i in 0..3){
                val c=centers[i]
                // Beveled colored plinths like the reference image.
                drawMesh(die,model(c[0],0.19f,c[1],1.34f,0.085f,1.34f,0f),color(37,30,28,1f),0.46f,0.08f,0.015f,0.10f)
                drawMesh(die,model(c[0],0.28f,c[1],1.20f,0.070f,1.20f,0f),colorFromInt(pawnColors[i],0.96f),0.30f,0.08f,0.045f)
                drawMesh(die,model(c[0],0.355f,c[1],1.03f,0.040f,1.03f,0f),colorFromInt(pawnColors[i],1f),0.22f,0.06f,0.060f)
            }

            val lanes=arrayOf(
                listOf(1 to 7,2 to 7,3 to 7,4 to 7,5 to 7,6 to 7),
                listOf(7 to 8,7 to 9,7 to 10,7 to 11,7 to 12,7 to 13),
                listOf(8 to 7,9 to 7,10 to 7,11 to 7,12 to 7,13 to 7),
                listOf(7 to 1,7 to 2,7 to 3,7 to 4,7 to 5,7 to 6)
            )
            for(pi in 0..3){
                for((r,c) in lanes[pi]){
                    val x=(c-7f)*0.50f
                    val z=(r-7f)*0.50f
                    drawMesh(cube,model(x,0.320f,z,0.232f,0.026f,0.232f,0f),color(92,66,38,1f),0.66f,0.02f,0f)
                    drawMesh(cube,model(x,0.350f,z,0.194f,0.028f,0.194f,0f),colorFromInt(pawnColors[pi],0.96f),0.30f,0.08f,0.014f)
                }
            }

            drawMesh(disk,model(0f,0.33f,0f,1.24f,0.028f,1.24f,0f),color(241,235,218,1f),0.52f,0.03f,0f)
            drawMesh(disk,model(0f,0.365f,0f,0.86f,0.022f,0.86f,0f),color(194,146,56,0.85f),0.32f,0.35f,0.01f)
        }

        private fun drawPawns(){
            for(pi in 0..3) for(ti in 0..3){
                val p=host.display[pi][ti]
                if(p[0].isNaN())continue
                if(pi==player && isLegalPawn(pi,ti)){
                    val pulse=0.45f+0.07f*sin(System.nanoTime()/180_000_000.0).toFloat()
                    drawMesh(disk,model(p[0],0.275f,p[1],pulse,0.016f,pulse,0f),color(255,204,45,0.82f),0.20f,0.20f,0.08f)
                }
                drawMesh(disk,model(p[0],0.300f,p[1],0.30f,0.020f,0.30f,0f),color(32,24,20,0.30f),0.86f,0.02f,0f)
                val ly=0.46f+lift[pi][ti]
                val body=glbParts["LM_Pawn_Tapered_Body"]
                val head=glbParts["LM_Pawn_Spherical_Head"]
                if(body!=null && head!=null){
                    val b=bounce[pi][ti].coerceIn(0f,1f)
                    val elastic=LudoMotionPhysics.elasticOut(b)
                    val overshoot=elastic-b
                    val backOvershoot=LudoMotionPhysics.backOut(b)-b
                    val sx=0.72f*(1f-0.055f*overshoot)
                    val sy=0.72f*(1f+0.115f*overshoot)
                    val phase=((System.nanoTime()/18_000_000L+pi*37L+ti*11L)%360L).toFloat()/360f
                    val rz=LudoMotionPhysics.sway(phase,3.5f)+overshoot*6.5f+backOvershoot*2.8f
                    val rx=LudoMotionPhysics.sway(phase,2.2f)-overshoot*3.0f+backOvershoot*1.4f
                    listOf("LM_Pawn_Underside_Insert","LM_Pawn_Base_Weighted","LM_Pawn_Tapered_Body","LM_Pawn_Collar","LM_Pawn_Spherical_Head").forEach{name->
                        glbParts[name]?.let{part->
                            val m=heroPawnModel(part.transform,p[0],ly+PremiumVisualMotion.landingBounce(b),p[1],sx,sy,rz,rx)
                            drawMesh(part.mesh,m,colorFromInt(pawnColors[pi],1f),0.22f,0.28f,0.04f,0.08f)
                        }
                    }
                }else{
                    drawMesh(pawn,model(p[0],ly,p[1],0.36f,0.88f,0.36f,0f),colorFromInt(pawnColors[pi],1f),0.18f,0.0f,0.16f)
                    drawMesh(sphere,model(p[0],ly+0.70f,p[1],0.19f,0.19f,0.19f,0f),colorFromInt(pawnColors[pi],1f),0.14f,0.0f,0.15f)
                    drawMesh(sphere,model(p[0]-0.05f,ly+0.84f,p[1]-0.09f,0.040f,0.030f,0.018f,0f),color(255,255,255,0.90f),0.10f,0.35f,0.12f)
                }
            }
        }

        private fun heroPawnModel(partTransform:FloatArray,x:Float,y:Float,z:Float,sx:Float,sy:Float,rotZ:Float,rotX:Float):FloatArray{
            val root=FloatArray(16)
            Matrix.setIdentityM(root,0)
            Matrix.translateM(root,0,x,y,z)
            Matrix.rotateM(root,0,rotZ,0f,0f,1f)
            Matrix.rotateM(root,0,rotX,1f,0f,0f)
            Matrix.scaleM(root,0,sx,sy,sx)
            val out=FloatArray(16)
            Matrix.multiplyMM(out,0,root,0,partTransform,0)
            return out
        }

        private fun drawAuthoredBoard(){
            val root=FloatArray(16)
            Matrix.setIdentityM(root,0)
            Matrix.translateM(root,0,-0.7f*boardScaleXZ,0.20f,0f)
            Matrix.scaleM(root,0,boardScaleXZ,1f,boardScaleXZ)
            for(part in boardParts.values.sortedWith(compareBy({it.materialName},{it.name}))){
                val m=FloatArray(16)
                Matrix.multiplyMM(m,0,root,0,part.transform,0)
                val n=part.materialName
                val c=when(n){
                    "LM_Path_Red"->color(220,54,57,1f)
                    "LM_Path_Green"->color(49,165,91,1f)
                    "LM_Path_Gold"->color(237,179,44,1f)
                    "LM_Path_Blue"->color(57,111,219,1f)
                    "LM_Path_Ivory"->color(235,219,179,1f)
                    "LM_Path_Dark"->color(17,18,21,1f)
                    "LM_Board_Felt"->color(74,46,26,1f)
                    "LM_Gold_Frame"->color(184,118,36,1f)
                    "LM_Table_Lacquer"->color(102,48,19,1f)
                    else->color(205,195,176,1f)
                }
                val rough=when(n){
                    "LM_Gold_Frame"->0.22f
                    "LM_Table_Lacquer"->0.32f
                    "LM_Path_Red","LM_Path_Green","LM_Path_Gold","LM_Path_Blue"->0.34f
                    "LM_Path_Ivory"->0.52f
                    "LM_Board_Felt"->0.70f
                    else->0.44f
                }
                val metal=if(n=="LM_Gold_Frame")0.84f else 0.06f
                val emit=if(n=="LM_Gold_Frame")0.025f else 0.005f
                drawMesh(part.mesh,m,c,rough,metal,emit,if(n=="LM_Gold_Frame")0.12f else 0f)
            }
        }

        private fun drawHomePawnSlots(){
            val c=2.5833335f
            val offsets=floatArrayOf(-0.43f,0.43f)
            val centers=arrayOf(floatArrayOf(-c,c),floatArrayOf(-c,-c),floatArrayOf(c,-c),floatArrayOf(c,c))
            for(pi in 0..3)for(sx in offsets)for(sz in offsets){
                val m=model(centers[pi][0]+sx,0.212f,centers[pi][1]+sz,0.26f,0.024f,0.26f,0f)
                drawMesh(disk,m,colorFromInt(pawnColors[pi],0.72f),0.26f,0.14f,0.01f)
            }
        }

        private fun drawDice(onlyPi:Int = -1){
            for(pi in 0..3){
                if (onlyPi >= 0 && pi != onlyPi) continue
                val d=dieCenter(pi)
                val idleRotation=if(homePreview) ((System.nanoTime()/12_000_000L)%360L).toFloat() else 0f
                val rot=idleRotation+if(pi==player) ((1f-diceAnim)*32f + if(pi%2==0) -7f else 7f) else 0f
                // Four compact chrome dice trays + hydro-plated die bodies.
                drawMesh(die,model(d[0],0.24f,d[1],0.74f,0.11f,0.74f,0f),color(58,63,70,1f),0.12f,1.0f,0.015f,0.92f)
                drawMesh(die,model(d[0],0.35f,d[1],0.60f,0.055f,0.60f,0f),color(175,181,188,1f),0.08f,1.0f,0.0f,0.96f)
                val dieModel=model(d[0],0.75f,d[1],0.52f,0.52f,0.52f,rot)
                val glbDie=glbParts["LM_Die_HydroChrome_Body"]
                if(glbDie!=null){
                    val centered=model(-1.55f,-0.58f,0f,1f,1f,1f,0f)
                    val local=FloatArray(16)
                    Matrix.multiplyMM(local,0,centered,0,glbDie.transform,0)
                    val authored=FloatArray(16)
                    Matrix.multiplyMM(authored,0,dieModel,0,local,0)
                    drawMesh(glbDie.mesh,authored,color(246,249,252,1f),0.07f,1.0f,0.018f,0.98f)
                }else{
                    drawMesh(die,dieModel,color(232,236,240,1f),0.045f,1.0f,0.018f,0.98f)
                }
                val value=if(pi==player)(state?.optInt("dice",0)?:0) else 1
                val pip=0.19f
                val positions=when(value){
                    1->arrayOf(0f to 0f)
                    2->arrayOf(-pip to -pip,pip to pip)
                    3->arrayOf(-pip to -pip,0f to 0f,pip to pip)
                    4->arrayOf(-pip to -pip,pip to -pip,-pip to pip,pip to pip)
                    5->arrayOf(-pip to -pip,pip to -pip,0f to 0f,-pip to pip,pip to pip)
                    else->arrayOf(-pip to -pip,-pip to 0f,-pip to pip,pip to -pip,pip to 0f,pip to pip)
                }
                for((px,pz) in positions){
                    // Volumetric pip: embedded dark sphere leaves a shallow physical cavity
                    // on the hydro-plated die instead of a flat painted disc.
                    val pipModel=localModel(dieModel,px,0.988f,pz,0.073f,0.038f,0.073f)
                    drawMesh(sphere,pipModel,color(10,13,18,1f),0.105f,0.50f,0.0f)
                }
            }
        }

        private fun roundedBoxMesh(seg:Int,rings:Int):Mesh{
            val v=ArrayList<Float>();val n=ArrayList<Float>();val idx=ArrayList<Short>();val p=8.0
            for(r in 0..rings){
                val ph=r.toFloat()/rings*Math.PI;val sp=sin(ph);val cp=cos(ph)
                for(q in 0..seg){
                    val th=q.toFloat()/seg*Math.PI*2.0
                    val dx=sp*cos(th);val dy=cp;val dz=sp*sin(th)
                    val norm=max(0.0001,(abs(dx).pow(p)+abs(dy).pow(p)+abs(dz).pow(p))).pow(1.0/p)
                    val x=dx/norm;val y=dy/norm;val z=dz/norm
                    v+=x.toFloat();v+=y.toFloat();v+=z.toFloat()
                    val nx=sign(x)*abs(x).pow(p-1.0);val ny=sign(y)*abs(y).pow(p-1.0);val nz=sign(z)*abs(z).pow(p-1.0)
                    val nl=max(0.0001,sqrt(nx*nx+ny*ny+nz*nz))
                    n+=(nx/nl).toFloat();n+=(ny/nl).toFloat();n+=(nz/nl).toFloat()
                }
            }
            val row=seg+1
            for(r in 0 until rings)for(q in 0 until seg){
                val a=(r*row+q).toShort();val b=(a.toInt()+1).toShort();val c=((r+1)*row+q+1).toShort();val d=((r+1)*row+q).toShort()
                idx+=a;idx+=b;idx+=c;idx+=a;idx+=c;idx+=d
            }
            return Mesh(v.toFloatArray(),n.toFloatArray(),idx.toShortArray())
        }

        private fun dieCenter(pi:Int):FloatArray = when(pi){
            0->floatArrayOf(-3.55f,3.55f)
            1->floatArrayOf(-3.55f,-3.55f)
            2->floatArrayOf(3.55f,-3.55f)
            else->floatArrayOf(3.55f,3.55f)
        }

        override fun projectedTokens(pi:Int):Array<FloatArray>{
            val out=Array(4){FloatArray(2){-1000f}}
            for(ti in 0..3){
                val p=host.display[pi][ti]
                if(p[0].isNaN())continue
                project(p[0],0.94f,p[1],out[ti])
            }
            return out
        }

        override fun projectedDice():Array<FloatArray>{
            val out=Array(4){FloatArray(2){-1000f}}
            for(pi in 0..3){
                val d=dieCenter(pi)
                project(d[0],0.70f,d[1],out[pi])
            }
            return out
        }

        private fun project(x:Float,y:Float,z:Float,out:FloatArray){
            val v=floatArrayOf(x,y,z,1f)
            Matrix.multiplyMV(tmp,0,vp,0,v,0)
            val w=tmp[3]
            if(kotlin.math.abs(w)>0.0001f){
                out[0]=(tmp[0]/w*0.5f+0.5f)*width
                out[1]=(1f-(tmp[1]/w*0.5f+0.5f))*height
            }
        }

        private fun isLegalPawn(pi:Int,ti:Int):Boolean{
            val dice=state?.optInt("dice",0)?:0
            if(pi!=player || dice !in 1..6)return false
            val pos=state?.optJSONArray("players")?.optJSONObject(pi)
                ?.optJSONArray("tokens")?.optJSONObject(ti)?.optInt("pos",-1)?:-1
            return if(pos<0)dice==6 else pos+dice<=57
        }

        private fun drawMesh(mesh:Mesh,mat:FloatArray,color:FloatArray,roughness:Float,metallic:Float,emission:Float,chrome:Float=0f){
            Matrix.multiplyMM(tmp,0,vp,0,mat,0)
            GLES20.glUseProgram(program)
            GLES20.glUniformMatrix4fv(uMvp,1,false,tmp,0)
            GLES20.glUniformMatrix4fv(uModel,1,false,mat,0)
            GLES20.glUniform4fv(uColor,1,color,0)
            GLES20.glUniform1f(uMetallic,metallic)
            GLES20.glUniform1f(uRoughness,roughness)
            GLES20.glUniform1f(uEmission,emission)
            GLES20.glUniform1f(uChrome,chrome)
            GLES20.glEnableVertexAttribArray(0)
            GLES20.glEnableVertexAttribArray(1)
            GLES20.glVertexAttribPointer(0,3,GLES20.GL_FLOAT,false,0,mesh.p)
            GLES20.glVertexAttribPointer(1,3,GLES20.GL_FLOAT,false,0,mesh.n)
            GLES20.glDrawElements(GLES20.GL_TRIANGLES,mesh.indices.size,GLES20.GL_UNSIGNED_SHORT,mesh.i)
            GLES20.glDisableVertexAttribArray(0)
            GLES20.glDisableVertexAttribArray(1)
        }

        private fun color(r:Int,g:Int,b:Int,a:Float)=floatArrayOf(r/255f,g/255f,b/255f,a)
        private fun colorFromInt(c:Int,a:Float)=color(Color.red(c),Color.green(c),Color.blue(c),a)

        private fun model(x:Float,y:Float,z:Float,sx:Float,sy:Float,sz:Float,ry:Float):FloatArray{
            Matrix.setIdentityM(scratch,0)
            Matrix.translateM(scratch,0,x,y,z)
            if(ry!=0f)Matrix.rotateM(scratch,0,ry,0f,1f,0f)
            Matrix.scaleM(scratch,0,sx,sy,sz)
            return scratch.clone()
        }

        private fun localModel(parent:FloatArray,x:Float,y:Float,z:Float,sx:Float,sy:Float,sz:Float):FloatArray{
            Matrix.setIdentityM(scratch,0)
            Matrix.translateM(scratch,0,x,y,z)
            Matrix.scaleM(scratch,0,sx,sy,sz)
            Matrix.multiplyMM(tmp,0,parent,0,scratch,0)
            return tmp.clone()
        }

        private fun cubeMesh():Mesh{
            val p=floatArrayOf(
                -1f,-1f,1f,1f,-1f,1f,1f,1f,1f,-1f,1f,1f,
                1f,-1f,-1f,-1f,-1f,-1f,-1f,1f,-1f,1f,1f,-1f,
                -1f,1f,1f,1f,1f,1f,1f,1f,-1f,-1f,1f,-1f,
                -1f,-1f,-1f,1f,-1f,-1f,1f,-1f,1f,-1f,-1f,1f,
                1f,-1f,1f,1f,-1f,-1f,1f,1f,-1f,1f,1f,1f,
                -1f,-1f,-1f,-1f,-1f,1f,-1f,1f,1f,-1f,1f,-1f
            )
            val n=FloatArray(72)
            val normals=arrayOf(
                floatArrayOf(0f,0f,1f),floatArrayOf(0f,0f,-1f),
                floatArrayOf(0f,1f,0f),floatArrayOf(0f,-1f,0f),
                floatArrayOf(1f,0f,0f),floatArrayOf(-1f,0f,0f)
            )
            for(f in 0..5)for(k in 0..3){
                val q=(f*4+k)*3
                n[q]=normals[f][0];n[q+1]=normals[f][1];n[q+2]=normals[f][2]
            }
            val idx=shortArrayOf(
                0,1,2,0,2,3,4,5,6,4,6,7,
                8,9,10,8,10,11,12,13,14,12,14,15,
                16,17,18,16,18,19,20,21,22,20,22,23
            )
            return Mesh(p,n,idx)
        }

        private fun diskMesh(seg:Int):Mesh{
            val p=ArrayList<Float>();val n=ArrayList<Float>();val idx=ArrayList<Short>()
            p+=0f;p+=0f;p+=0f;n+=0f;n+=1f;n+=0f
            for(s in 0..seg){
                val a=s.toFloat()/seg*Math.PI*2
                p+=cos(a).toFloat();p+=0f;p+=sin(a).toFloat()
                n+=0f;n+=1f;n+=0f
            }
            for(s in 1..seg){idx+=0;idx+=s.toShort();idx+=(s+1).toShort()}
            return Mesh(p.toFloatArray(),n.toFloatArray(),idx.toShortArray())
        }

        private fun sphereMesh(seg:Int,rings:Int):Mesh{
            val p=ArrayList<Float>();val n=ArrayList<Float>();val idx=ArrayList<Short>()
            for(r in 0..rings){
                val ph=(r.toFloat()/rings-0.5f)*Math.PI
                val cp=cos(ph);val sp=sin(ph)
                for(s in 0..seg){
                    val th=s.toFloat()/seg*Math.PI*2
                    val x=(cp*cos(th)).toFloat();val y=sp.toFloat();val z=(cp*sin(th)).toFloat()
                    p+=x;p+=y;p+=z;n+=x;n+=y;n+=z
                }
            }
            val row=seg+1
            for(r in 0 until rings)for(s in 0 until seg){
                val a=(r*row+s).toShort();val b=(r*row+s+1).toShort()
                val c=((r+1)*row+s+1).toShort();val d=((r+1)*row+s).toShort()
                idx+=a;idx+=b;idx+=c;idx+=a;idx+=c;idx+=d
            }
            return Mesh(p.toFloatArray(),n.toFloatArray(),idx.toShortArray())
        }

        private fun pawnMesh():Mesh{
            val profile=arrayOf(
                0.62f to 0.00f,0.74f to 0.05f,0.70f to 0.11f,
                0.52f to 0.20f,0.37f to 0.31f,0.25f to 0.44f,
                0.18f to 0.56f,0.15f to 0.67f
            )
            val seg=24;val p=ArrayList<Float>();val n=ArrayList<Float>();val idx=ArrayList<Short>()
            for(pi in profile.indices)for(s in 0 until seg){
                val a=s.toFloat()/seg*Math.PI*2
                val r=profile[pi].first;val y=profile[pi].second
                p+=(cos(a)*r).toFloat();p+=y;p+=(sin(a)*r).toFloat()
                val slope=when(pi){
                    0->profile[1].first-r
                    profile.lastIndex->r-profile[pi-1].first
                    else->(profile[pi+1].first-profile[pi-1].first)*0.5f
                }
                val dy=when(pi){
                    0->profile[1].second-y
                    profile.lastIndex->y-profile[pi-1].second
                    else->profile[pi+1].second-profile[pi-1].second
                }
                val nx=cos(a).toFloat();val nz=sin(a).toFloat()
                val ny=if(kotlin.math.abs(dy)<0.001f)1f else -slope/max(0.001f,dy)
                val len=max(0.001f,kotlin.math.sqrt(nx*nx+ny*ny+nz*nz))
                n+=nx/len;n+=ny/len;n+=nz/len
            }
            for(pi in 0 until profile.lastIndex)for(s in 0 until seg){
                val a=(pi*seg+s).toShort();val b=(pi*seg+(s+1)%seg).toShort()
                val c=((pi+1)*seg+(s+1)%seg).toShort();val d=((pi+1)*seg+s).toShort()
                idx+=a;idx+=b;idx+=c;idx+=a;idx+=c;idx+=d
            }
            return Mesh(p.toFloatArray(),n.toFloatArray(),idx.toShortArray())
        }

        private fun buildProgram(vs:String,fs:String):Int{
            fun compile(type:Int,src:String):Int{
                val id=GLES20.glCreateShader(type)
                GLES20.glShaderSource(id,src)
                GLES20.glCompileShader(id)
                val ok=IntArray(1)
                GLES20.glGetShaderiv(id,GLES20.GL_COMPILE_STATUS,ok,0)
                if(ok[0]==0)throw IllegalStateException("GL shader: "+GLES20.glGetShaderInfoLog(id))
                return id
            }
            val v=compile(GLES20.GL_VERTEX_SHADER,vs)
            val f=compile(GLES20.GL_FRAGMENT_SHADER,fs)
            val p=GLES20.glCreateProgram()
            GLES20.glAttachShader(p,v);GLES20.glAttachShader(p,f);GLES20.glLinkProgram(p)
            val ok=IntArray(1)
            GLES20.glGetProgramiv(p,GLES20.GL_LINK_STATUS,ok,0)
            if(ok[0]==0)throw IllegalStateException("GL link: "+GLES20.glGetProgramInfoLog(p))
            GLES20.glDeleteShader(v);GLES20.glDeleteShader(f)
            return p
        }
    }

    internal class SceneRenderer(private val host: LudoBoardView) : GameplayRenderer {
        override var state: JSONObject? = null
        override var player: Int = -1
        override var homePreview: Boolean = host.homePreview
        override var diceAnim = 1f
        override val lift = Array(4) { FloatArray(4) }
        override val bounce = Array(4) { FloatArray(4) }
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
        private lateinit var coloredLanes:Array<Mesh>
        private lateinit var pawnBody:Mesh
        private lateinit var sphere:Mesh
        private lateinit var roundedBox:Mesh
        private lateinit var disk:Mesh
        private lateinit var pips:Mesh
        private lateinit var diePipMeshes:Array<Mesh>
        private lateinit var heroRing:Mesh
        private var shadowFbo=0
        private var shadowTex=0
        private var sceneFbo=0
        private var sceneColorTex=0
        private var sceneDepthTex=0
        private var fallbackShadowTex=0
        internal val premiumLighting = PremiumMaterialLighting.lighting
        internal val premiumPost = PremiumMaterialLighting.post
        private var shadowSize=premiumLighting.shadowMapResolution
        internal val shadowMapResolution: Int get() = shadowSize
        // sceneWidth/Height are the EGL window-buffer dimensions. On SwiftShader
        // the buffer itself may be smaller than the full Android View bounds.
        private var sceneWidth=1
        private var sceneHeight=1
        private var renderTargetWidth=1
        private var renderTargetHeight=1
        private var renderScale=1f
        private var firstFrameDiagnosticsCaptured=false
        private var homePreviewFirstFrameCaptured=false
        private var woodTex=0
        private var boardTex=0
        private var neutralTex=0
        private var metalTex=0
        private var normalTex=0;private var woodNormalTex=0
        private var envCube=0
        private var glbParts:Map<String,GlbRuntimeLoader.Part> = emptyMap()
        private var boardParts:Map<String,GlbRuntimeLoader.Part> = emptyMap()
        private var envParts:Map<String,GlbRuntimeLoader.Part> = emptyMap()
        private var gameplayEnvironmentParts:Map<String,GlbRuntimeLoader.Part> = emptyMap()
        private var preparedGameplayEnvironmentDraws: List<GameplayEnvironmentBatcher.Batch> = emptyList()
        private var preparedGameplayEnvironmentSourcePartCount = 0
        private var gameplayEnvironmentHasDoubleSidedParts = false
        private data class PreparedBoardDraw(
            val part: GlbRuntimeLoader.Part,
            val modelMatrix: FloatArray,
            val baseColor: FloatArray,
            val roughness: Float,
            val metallic: Float,
            val emission: Float,
            val albedoTexture: Int,
            val normalTexture: Int
        )
        private data class PreparedBoardBatch(
            val mesh: Mesh,
            val baseColor: FloatArray,
            val roughness: Float,
            val metallic: Float,
            val emission: Float,
            val albedoTexture: Int,
            val normalTexture: Int
        )
        private var preparedBoardBatches: List<PreparedBoardBatch> = emptyList()
        private var preparedBoardShadowMeshes: List<Mesh> = emptyList()
        private var authoredBoardPartCount = 0
        private var heroPawnMeshes: List<Mesh> = emptyList()
        private val identityModel = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }
        private val pawnRootScratch = Array(4) { Array(4) { FloatArray(16) } }
        private val pawnModelScratch = Array(4) { Array(4) { FloatArray(16) } }
        private val frameTelemetry = if (BuildConfig.DEBUG) RenderFrameTelemetry() else null
        private val pipelinePhaseTelemetry = if (BuildConfig.DEBUG) RenderPipelinePhaseTelemetry() else null
        private val sceneBreakdownTelemetry = if (BuildConfig.DEBUG) RenderSceneBreakdownTelemetry() else null
        private var latestFramePerfSnapshot: RenderFramePerfSnapshot? = null
        private var latestPipelinePhaseSnapshot: RenderPipelinePhaseSnapshot? = null
        private var latestSceneBreakdownSnapshot: RenderSceneBreakdownSnapshot? = null
        private var preparedGles3StaticMeshCount = 0
        private var lastShadowCpuNanos = 0L
        private var lastSceneCpuNanos = 0L
        private var lastPostCpuNanos = 0L
        private var lastSceneSetupCpuNanos = 0L
        private var lastSceneBackdropCpuNanos = 0L
        private var lastSceneBoardCpuNanos = 0L
        private var lastScenePawnsCpuNanos = 0L
        private var lastSceneDiceCpuNanos = 0L
        private var lastSceneShaderEndCpuNanos = 0L
        private var lastSceneParticlesCpuNanos = 0L
        private var lastParticleBuildCpuNanos = 0L
        private var lastParticleBufferCopyCpuNanos = 0L
        private var lastParticleGlStateCpuNanos = 0L
        private var lastParticleBufferUploadCpuNanos = 0L
        private var lastParticleDrawSubmitCpuNanos = 0L
        private var lastParticleCleanupCpuNanos = 0L
        private var lastActiveParticleCount = 0
        private val particleDrawTimingScratch = LongArray(5)
        private val backdropBatchCpuScratch = LongArray(RenderSceneBreakdownTelemetry.MAX_BACKDROP_BATCHES)
        private val backdropBatchVertexScratch = IntArray(RenderSceneBreakdownTelemetry.MAX_BACKDROP_BATCHES)
        private val backdropBatchIndexScratch = IntArray(RenderSceneBreakdownTelemetry.MAX_BACKDROP_BATCHES)
        private var lastBackdropBatchCount = 0
        private var lastBackdropCullingQueryCpuNanos = 0L
        private var lastBackdropCullingTransitionCpuNanos = 0L
        private var lastBackdropCullingRestoreCpuNanos = 0L
        private var glDeviceDiagnostic = "unavailable"
        private var softwareFramePacingEnabled = false
        private var eglSwapInterval0Status = "NOT_APPLIED"
        private var eglSwapSurfaceWidth = 0
        private var eglSwapSurfaceHeight = 0
        private val gpuTimerQuery = if (BuildConfig.DEBUG && !host.homePreview) GlesGpuTimerQuery() else null
        private var gpuFenceTelemetry: GlesGpuFenceTelemetry? = null
        internal var softwareRendererActive = false
            private set
        private var firstDrawMarkerPersisted = false
        private val boardScaleXZ=1.6666667f
        private var envBarkTex=0
        private var envBarkNormalTex=0
        private var envBarkRoughTex=0
        private var envDirtTex=0
        private var envDirtNormalTex=0
        private var envDirtRoughTex=0
        private var envSkyTex=0
        private var envGrassTex=0
        private var envGrassNormalTex=0
        private var envGrassRoughTex=0
        private var baobabParts:Map<String,GlbRuntimeLoader.Part> = emptyMap()
        internal val lightVp=FloatArray(16)
        private val lightView=FloatArray(16)
        private val lightProj=FloatArray(16)
        private var lastFrameNanos=0L
        private val dustLife=FloatArray(128)
        private val dustMaxLife=FloatArray(128)
        private val dustPos=FloatArray(128*3)
        private val dustVel=FloatArray(128*3)
        private val dustColor=FloatArray(128*4)
        private val dustSize=FloatArray(128)
        private val dustData=FloatArray(128*8)
        private val particleVbos = IntArray(3)
        private var nextParticleVbo = 0
        private val lastDust=Array(4){LongArray(4)}
        private val lastDiceFx=LongArray(4)
        private val boardTop=0.20f
        private val pawnScale=0.62f

        override fun onSurfaceCreated(gl:javax.microedition.khronos.opengles.GL10?,cfg:javax.microedition.khronos.egl.EGLConfig?){
            // Context loss discards GL objects. Clear every cached name before rebuilding
            // targets, textures and buffers; never reuse IDs from the old context.
            firstFrameDiagnosticsCaptured = false
            homePreviewFirstFrameCaptured = false
            firstDrawMarkerPersisted = false
            frameTelemetry?.reset()
            pipelinePhaseTelemetry?.reset()
            sceneBreakdownTelemetry?.reset()
            latestFramePerfSnapshot = null
            latestPipelinePhaseSnapshot = null
            latestSceneBreakdownSnapshot = null
            preparedGles3StaticMeshCount = 0
            lastShadowCpuNanos = 0L
            lastSceneCpuNanos = 0L
            lastPostCpuNanos = 0L
            lastSceneSetupCpuNanos = 0L
            lastSceneBackdropCpuNanos = 0L
            lastSceneBoardCpuNanos = 0L
            lastScenePawnsCpuNanos = 0L
            lastSceneDiceCpuNanos = 0L
            lastSceneShaderEndCpuNanos = 0L
            lastSceneParticlesCpuNanos = 0L
            lastParticleBuildCpuNanos = 0L
            lastParticleBufferCopyCpuNanos = 0L
            lastParticleGlStateCpuNanos = 0L
            lastParticleBufferUploadCpuNanos = 0L
            lastParticleDrawSubmitCpuNanos = 0L
            lastParticleCleanupCpuNanos = 0L
            lastActiveParticleCount = 0
            lastBackdropBatchCount = 0
            lastBackdropCullingQueryCpuNanos = 0L
            lastBackdropCullingTransitionCpuNanos = 0L
            lastBackdropCullingRestoreCpuNanos = 0L
            java.util.Arrays.fill(particleDrawTimingScratch, 0L)
            java.util.Arrays.fill(backdropBatchCpuScratch, 0L)
            java.util.Arrays.fill(backdropBatchVertexScratch, 0)
            java.util.Arrays.fill(backdropBatchIndexScratch, 0)
            preparedGameplayEnvironmentDraws = emptyList()
            preparedGameplayEnvironmentSourcePartCount = 0
            gameplayEnvironmentHasDoubleSidedParts = false
            preparedBoardBatches = emptyList()
            preparedBoardShadowMeshes = emptyList()
            authoredBoardPartCount = 0
            heroPawnMeshes = emptyList()
            lastFrameNanos = 0L
            woodTex = 0; boardTex = 0; neutralTex = 0; metalTex = 0
            normalTex = 0; woodNormalTex = 0; envCube = 0
            fallbackShadowTex = 0
            envBarkTex = 0; envBarkNormalTex = 0; envBarkRoughTex = 0
            envDirtTex = 0; envDirtNormalTex = 0; envDirtRoughTex = 0
            envSkyTex = 0; envGrassTex = 0; envGrassNormalTex = 0; envGrassRoughTex = 0
            sceneFbo = 0; sceneColorTex = 0; sceneDepthTex = 0
            shadowFbo = 0; shadowTex = 0
            java.util.Arrays.fill(particleVbos, 0)
            nextParticleVbo = 0
            if (host.homePreview) host.markHomePreviewNotReady()
            android.util.Log.i("LudoMadaRender", "PBR_SURFACE_CREATED")
            android.util.Log.i("LudoMadaRender", "PBR_CONTRACT_BOUND key=" + premiumLighting.keyIntensity +
                " fill=" + premiumLighting.fillIntensity +
                " rim=" + premiumLighting.rimIntensity +
                " ibl=" + premiumLighting.iblIntensity +
                " shadow=" + premiumLighting.shadowMapResolution +
                " ssao=" + premiumPost.ssao +
                " bloom=" + premiumPost.bloom)
            runCatching {
                host.context.getSharedPreferences("ludomada_debug", Context.MODE_PRIVATE)
                    .edit().putString("last_marker", "PBR_CONTRACT_BOUND:" +
                        premiumLighting.keyIntensity + ":" +
                        premiumLighting.fillIntensity + ":" +
                        premiumLighting.rimIntensity + ":" +
                        premiumLighting.iblIntensity + ":" +
                        premiumLighting.shadowMapResolution + ":" +
                        premiumPost.ssao + ":" +
                        premiumPost.bloom).apply()
            }
            val glVendor = GLES30.glGetString(GLES30.GL_VENDOR) ?: "unknown"
            val glRenderer = GLES30.glGetString(GLES30.GL_RENDERER) ?: "unknown"
            val glVersion = GLES30.glGetString(GLES30.GL_VERSION) ?: "unknown"
            glDeviceDiagnostic = "vendor=" + glVendor + " renderer=" + glRenderer + " version=" + glVersion
            softwareFramePacingEnabled = SoftwareRendererPolicy.shouldUseFramePacedSoftwareRendering(
                glRenderer,
                host.homePreview,
                BuildConfig.DEBUG
            )
            host.setSoftwareVsyncDrivenRendering(softwareFramePacingEnabled)
            android.util.Log.i(
                "LudoMadaRender",
                "SOFTWARE_FRAME_PACING enabled=" + softwareFramePacingEnabled +
                    " eglSwapInterval0=APPLY_ON_SURFACE_CHANGED"
            )
            val glExtensions = GLES30.glGetString(GLES30.GL_EXTENSIONS) ?: ""
            gpuTimerQuery?.initialize(glExtensions)
            softwareRendererActive = SoftwareRendererPolicy.useFastPbrShader(glRenderer, host.homePreview)
            if (SoftwareRendererPolicy.shouldUseReducedSurfaceBuffer(glRenderer, host.homePreview)) {
                host.requestSoftwareSurfaceBuffer(SoftwareRendererPolicy.SOFTWARE_SURFACE_SCALE)
            }
            gpuFenceTelemetry = if (
                BuildConfig.DEBUG &&
                SoftwareRendererPolicy.shouldMeasureGpuFence(glRenderer, host.homePreview)
            ) {
                GlesGpuFenceTelemetry().also { it.initialize() }
            } else {
                null
            }
            android.util.Log.i(
                "LudoMadaRender",
                "GPU_TIMER_QUERY status=" + (gpuTimerQuery?.snapshot()?.status ?: "DISABLED")
            )
            val softwarePostProcessActive = SoftwareRendererPolicy.useFastPostProcess(glRenderer, host.homePreview)
            shadowSize = SoftwareRendererPolicy.shadowMapResolution(
                glRenderer,
                premiumLighting.shadowMapResolution,
                host.homePreview
            )
            android.util.Log.i("LudoMadaRender", "GLES_DEVICE_INFO " + glDeviceDiagnostic)
            android.util.Log.i(
                "LudoMadaRender",
                "PBR_SOFTWARE_PROFILE renderer=" + glRenderer +
                    " shadowMap=" + shadowSize +
                    " softwarePbrShader=" + softwareRendererActive +
                    " softwarePostProcess=" + softwarePostProcessActive +
                    " dynamicShadowPass=" + SoftwareRendererPolicy.shouldRenderDynamicShadows(glRenderer, host.homePreview) +
                    " configuredShadowMap=" + premiumLighting.shadowMapResolution
            )
            GLES30.glClearColor(0.008f,0.018f,0.045f,1f)
            GLES30.glEnable(GLES30.GL_DEPTH_TEST)
            GLES30.glDepthFunc(GLES30.GL_LEQUAL)
            GLES30.glEnable(GLES30.GL_CULL_FACE)
            GLES30.glCullFace(GLES30.GL_BACK)
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA,GLES30.GL_ONE_MINUS_SRC_ALPHA)
            rayTracingHardware=RayTraceSupport.hasGlesRayTracing(glExtensions)
            android.util.Log.i("LudoMadaRender", if (rayTracingHardware) "RayTracing=available" else "RayTracing=IBL_reflection_fallback")
            shader=PbrShader(this);shadowShader=ShadowShader();postShader=PostShader(softwarePostProcessActive);particleShader=ParticleShader()
            cube=cubeMesh();pathMesh=pathMesh();coloredZones=Array(4){zoneMesh(it)};coloredLanes=Array(4){laneMesh(it)};pawnBody=pawnBodyMesh();sphere=sphereMesh(18,12);roundedBox=roundedBoxMesh(32,20);disk=diskMesh(32);pips=sphereMesh(10,6);diePipMeshes=DicePipBatcher.build(pips);heroRing=ringMesh(64,1.02f,0.72f)

            woodTex=createMaterialTexture(0);boardTex=createMaterialTexture(1);neutralTex=createMaterialTexture(2);metalTex=createMaterialTexture(3);normalTex=createFlatNormalTexture();woodNormalTex=createNormalTexture();envCube=createEnvironmentCubemap();fallbackShadowTex=createFallbackShadowTexture()
            // The authored outdoor environment is drawn only by the Home-preview path.
            // Avoid loading its 2K maps into ordinary gameplay surfaces.
            if (host.homePreview) {
                envBarkTex=loadAssetTexture("environment/textures/bark_diff_2k.jpg")
                envBarkNormalTex=loadAssetTexture("environment/textures/bark_nor_gl_2k.jpg")
                envBarkRoughTex=loadAssetTexture("environment/textures/bark_rough_2k.jpg")
                envDirtTex=loadAssetTexture("environment/textures/dirt_diff_2k.jpg")
                envDirtNormalTex=loadAssetTexture("environment/textures/dirt_nor_gl_2k.jpg")
                envDirtRoughTex=loadAssetTexture("environment/textures/dirt_rough_2k.jpg")
                envSkyTex=loadAssetTexture("environment/textures/safari_sunset_2k.jpg")
                envGrassTex=loadAssetTexture("environment/textures/grass_diff_2k.jpg")
                envGrassNormalTex=loadAssetTexture("environment/textures/grass_nor_gl_2k.jpg")
                envGrassRoughTex=loadAssetTexture("environment/textures/grass_rough_2k.jpg")
            }
            glbParts=runCatching{
                GlbRuntimeLoader(host.context).loadHeroParts().also{
                    android.util.Log.i("LudoMadaRender","GLB_RUNTIME_LOAD_OK parts="+it.keys.joinToString(","))
                    runCatching{host.context.getSharedPreferences("ludomada_debug",Context.MODE_PRIVATE).edit().putString("last_marker","GLB_RUNTIME_LOAD_OK:"+it.size).apply()}
                }
            }.getOrElse{
                android.util.Log.e("LudoMadaRender","GLB_RUNTIME_LOAD_FAILED",it)
                runCatching{host.context.getSharedPreferences("ludomada_debug",Context.MODE_PRIVATE).edit().putString("last_marker","GLB_RUNTIME_LOAD_FAILED:"+it.javaClass.simpleName).apply()}
                emptyMap()
            }
            prepareHeroPawnMeshes()
            boardParts=runCatching{
                GlbRuntimeLoader(host.context).loadBoardParts().also{
                    android.util.Log.i("LudoMadaRender","BOARD_GLB_RUNTIME_LOAD_OK parts="+it.size)
                    runCatching{host.context.getSharedPreferences("ludomada_debug",Context.MODE_PRIVATE).edit().putString("last_marker","BOARD_GLB_RUNTIME_LOAD_OK:"+it.size).apply()}
                }
            }.getOrElse{
                android.util.Log.e("LudoMadaRender","BOARD_GLB_RUNTIME_LOAD_FAILED",it)
                runCatching{host.context.getSharedPreferences("ludomada_debug",Context.MODE_PRIVATE).edit().putString("last_marker","BOARD_GLB_RUNTIME_LOAD_FAILED:"+it.javaClass.simpleName).apply()}
                emptyMap()
            }
            prepareAuthoredBoardDraws()
            if (host.homePreview) {
                envParts=runCatching{
                    GlbRuntimeLoader(host.context).loadEnvironmentParts().also{
                        android.util.Log.i("LudoMadaRender","ENV_GLB_RUNTIME_LOAD_OK parts="+it.size)
                    }
                }.getOrElse{
                    android.util.Log.e("LudoMadaRender","ENV_GLB_RUNTIME_LOAD_FAILED",it)
                    emptyMap()
                }
                baobabParts=runCatching{
                    GlbRuntimeLoader(host.context).loadBaobabParts().also{
                        android.util.Log.i("LudoMadaRender","BAOBAB_REAL_GLB_LOAD_OK parts="+it.size)
                    }
                }.getOrElse{
                    android.util.Log.e("LudoMadaRender","BAOBAB_REAL_GLB_LOAD_FAILED",it)
                    emptyMap()
                }
            } else {
                android.util.Log.i("LudoMadaRender","HOME_ENV_ASSETS_SKIPPED_FOR_GAMEPLAY")
                gameplayEnvironmentParts=runCatching{
                    GlbRuntimeLoader(host.context).loadGameplayEnvironmentParts().also{
                        android.util.Log.i("LudoMadaRender","GAMEPLAY_ENVIRONMENT_GLB_LOAD_OK parts="+it.size)
                    }
                }.getOrElse{
                    android.util.Log.e("LudoMadaRender","GAMEPLAY_ENVIRONMENT_GLB_LOAD_FAILED",it)
                    emptyMap()
                }
            }
            if (!host.homePreview) prepareGameplayEnvironmentDraws()
            GLES30.glGenBuffers(particleVbos.size, particleVbos, 0)
            particleVbos.forEach { bufferId ->
                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, bufferId)
                GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, 128 * 8 * 4, null, GLES30.GL_STREAM_DRAW)
            }
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
            prepareGles3StaticMeshes()
            Matrix.setLookAtM(lightView,0,-7.5f,11.0f,8.5f,0f,0.2f,0f,0f,1f,0f);Matrix.orthoM(lightProj,0,-8.5f,8.5f,-8.5f,8.5f,1f,25f);Matrix.multiplyMM(lightVp,0,lightProj,0,lightView,0)
        }

        /** Upload immutable draw meshes during surface setup, never inside the first visible frame. */
        private fun prepareGles3StaticMeshes() {
            val meshes = java.util.LinkedHashSet<Mesh>()
            meshes.addAll(listOf(cube, pathMesh, pawnBody, sphere, roundedBox, disk, pips, heroRing))
            meshes.addAll(coloredZones)
            meshes.addAll(coloredLanes)
            meshes.addAll(diePipMeshes)
            glbParts.values.forEach { meshes.add(it.mesh) }
            envParts.values.forEach { meshes.add(it.mesh) }
            baobabParts.values.forEach { meshes.add(it.mesh) }
            preparedGameplayEnvironmentDraws.forEach { meshes.add(it.mesh) }
            preparedBoardBatches.forEach { meshes.add(it.mesh) }
            preparedBoardShadowMeshes.forEach { meshes.add(it) }
            heroPawnMeshes.forEach { meshes.add(it) }

            val startedNanos = System.nanoTime()
            meshes.forEach { it.prepareGles3Static() }
            preparedGles3StaticMeshCount = meshes.size
            val elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000f
            android.util.Log.i(
                "LudoMadaRender",
                "GLES3_STATIC_MESHES_PREPARED count=" + preparedGles3StaticMeshCount +
                    " uploadMs=" + String.format(java.util.Locale.US, "%.2f", elapsedMs)
            )
        }

        private fun requestSoftwareSwapIntervalZero(rendererIdentity: String): String {
            if (!SoftwareRendererPolicy.shouldRequestUncappedSwapInterval(
                    rendererIdentity,
                    host.homePreview,
                    BuildConfig.DEBUG
                )
            ) return "NOT_REQUESTED"

            return runCatching {
                val display = EGL14.eglGetCurrentDisplay()
                if (display == EGL14.EGL_NO_DISPLAY) return@runCatching "NO_CURRENT_DISPLAY"
                val drawSurface = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
                if (drawSurface == EGL14.EGL_NO_SURFACE) return@runCatching "NO_CURRENT_DRAW_SURFACE"
                val dimension = IntArray(1)
                eglSwapSurfaceWidth = if (
                    EGL14.eglQuerySurface(display, drawSurface, EGL14.EGL_WIDTH, dimension, 0)
                ) dimension[0] else 0
                eglSwapSurfaceHeight = if (
                    EGL14.eglQuerySurface(display, drawSurface, EGL14.EGL_HEIGHT, dimension, 0)
                ) dimension[0] else 0
                if (EGL14.eglSwapInterval(display, 0)) {
                    "REQUEST_ACCEPTED"
                } else {
                    "REQUEST_REJECTED_0x" + EGL14.eglGetError().toString(16)
                }
            }.getOrElse { error ->
                "REQUEST_ERROR_" + error.javaClass.simpleName
            }
        }

        override fun onSurfaceChanged(gl:javax.microedition.khronos.opengles.GL10?,w:Int,h:Int){
            runCatching {
                host.context.getSharedPreferences("ludomada_debug", Context.MODE_PRIVATE)
                    .edit().putString("last_marker", "PBR_SURFACE_CHANGED:" + w + "x" + h).apply()
            }
            sceneWidth=max(1,w);sceneHeight=max(1,h)
            eglSwapInterval0Status = requestSoftwareSwapIntervalZero(glDeviceDiagnostic)
            android.util.Log.i(
                "LudoMadaRender",
                "SOFTWARE_FRAME_PACING_APPLIED callbackSurface=" + sceneWidth + "x" + sceneHeight +
                    " eglSurface=" + (if (eglSwapSurfaceWidth > 0 && eglSwapSurfaceHeight > 0) {
                        eglSwapSurfaceWidth.toString() + "x" + eglSwapSurfaceHeight
                    } else "UNAVAILABLE") +
                    " enabled=" + softwareFramePacingEnabled +
                    " eglSwapInterval0=" + eglSwapInterval0Status
            )
            renderScale = SoftwareRendererPolicy.renderScale(
                glDeviceDiagnostic,
                host.homePreview,
                outputSurfaceAlreadyScaled = host.hasSoftwareSurfaceBuffer
            )
            renderTargetWidth = SoftwareRendererPolicy.scaledDimension(sceneWidth, renderScale)
            renderTargetHeight = SoftwareRendererPolicy.scaledDimension(sceneHeight, renderScale)
            GLES30.glViewport(0,0,renderTargetWidth,renderTargetHeight)
            createSceneTargets(renderTargetWidth,renderTargetHeight)
            android.util.Log.i(
                "LudoMadaRender",
                "PBR_RENDER_TARGET surface=" + sceneWidth + "x" + sceneHeight +
                    " target=" + renderTargetWidth + "x" + renderTargetHeight +
                    " scale=" + renderScale +
                    " softwareRenderer=" + SoftwareRendererPolicy.isSoftwareRenderer(glDeviceDiagnostic)
            )
            host.requestRender()
        }
        override fun onDrawFrame(gl:javax.microedition.khronos.opengles.GL10?){
            if (BuildConfig.DEBUG && !firstDrawMarkerPersisted) {
                val markerSaved = runCatching {
                    host.context.getSharedPreferences("ludomada_debug", Context.MODE_PRIVATE)
                        .edit().putString("last_marker", "PBR_FIRST_DRAW").apply()
                }.isSuccess
                if (markerSaved) firstDrawMarkerPersisted = true
            }
            val glThreadCpuStartNanos = if (BuildConfig.DEBUG) {
                android.os.Debug.threadCpuTimeNanos().takeIf { it >= 0L }
            } else null
            val now=System.nanoTime()
            val updateStartedNanos = now
            val dt=if(lastFrameNanos==0L)0.016f else ((now-lastFrameNanos)/1_000_000_000f).coerceIn(0.001f,0.050f);lastFrameNanos=now
            updateParticles(dt);maybeSpawnDust(now);maybeSpawnDiceFx(now);maybeSpawnVictory()
            val aspect=sceneWidth.toFloat()/sceneHeight.toFloat();val land=aspect>1.35f
            // Match the supplied premium reference with an oblique tabletop camera.
            val camY=if (host.homePreview) 9.6f else if(land)11.8f else 13.2f
            val baseCamZ=if (host.homePreview) 13.2f else if(land)8.8f else 4.2f
            val rollEnergy=if(!host.homePreview && player in 0..3)(1f-diceAnim).coerceIn(0f,1f) else 0f
            val shakeX=sin(now/6_000_000.0).toFloat()*rollEnergy*0.055f
            val shakeZ=cos(now/7_000_000.0).toFloat()*rollEnergy*0.040f
            val camZ=baseCamZ+shakeZ
            val gameplayHorizontalFov=if(aspect<0.80f)42f else 45f
            val fov=portraitAwareVerticalFov(
                aspect,
                if (host.homePreview) 48f else gameplayHorizontalFov,
                if (host.homePreview) 38f else if(land)31f else 34f
            )
            Matrix.perspectiveM(proj,0,fov,aspect,0.1f,60f);Matrix.setLookAtM(view,0,shakeX,camY,camZ,shakeX*0.24f,0.12f,shakeZ*0.18f,0f,1f,0f);Matrix.multiplyMM(vp,0,proj,0,view,0)
            val updateCpuNanos = System.nanoTime() - updateStartedNanos
            gpuFenceTelemetry?.beginFrame(System.nanoTime())
            gpuTimerQuery?.beginFrame()
            val glSubmissionDurationNanos = try {
                renderDirectFrame()
            } finally {
                gpuTimerQuery?.endFrame()
                gpuFenceTelemetry?.endFrame(System.nanoTime())
            }
            var rendererTelemetryUpdated = false
            sceneBreakdownTelemetry?.record(
                now,
                lastSceneSetupCpuNanos,
                lastSceneBackdropCpuNanos,
                lastSceneBoardCpuNanos,
                lastScenePawnsCpuNanos,
                lastSceneDiceCpuNanos,
                lastSceneShaderEndCpuNanos,
                lastSceneParticlesCpuNanos,
                lastParticleBuildCpuNanos,
                lastParticleBufferCopyCpuNanos,
                lastParticleGlStateCpuNanos,
                lastParticleBufferUploadCpuNanos,
                lastParticleDrawSubmitCpuNanos,
                lastParticleCleanupCpuNanos,
                activeParticleCount = lastActiveParticleCount,
                backdropBatchCount = lastBackdropBatchCount,
                backdropBatchCpuNanos = backdropBatchCpuScratch,
                backdropBatchVertexCounts = backdropBatchVertexScratch,
                backdropBatchIndexCounts = backdropBatchIndexScratch,
                backdropCullingQueryCpuNanos = lastBackdropCullingQueryCpuNanos,
                backdropCullingTransitionCpuNanos = lastBackdropCullingTransitionCpuNanos,
                backdropCullingRestoreCpuNanos = lastBackdropCullingRestoreCpuNanos
            )?.let { phases ->
                latestSceneBreakdownSnapshot = phases
                rendererTelemetryUpdated = true
                android.util.Log.i(
                    "LudoMadaRender",
                    "PERF_SCENE_BREAKDOWN samples=" + phases.sampleCount +
                        " setupCpuMsP50=" + phases.setupCpuP50Ms +
                        " setupCpuMsP95=" + phases.setupCpuP95Ms +
                        " backdropCpuMsP50=" + phases.backdropCpuP50Ms +
                        " backdropCpuMsP95=" + phases.backdropCpuP95Ms +
                        " backdropCullingQueryCpuMsP95=" + phases.backdropCullingQueryCpuP95Ms +
                        " backdropCullingTransitionCpuMsP95=" + phases.backdropCullingTransitionCpuP95Ms +
                        " backdropCullingRestoreCpuMsP95=" + phases.backdropCullingRestoreCpuP95Ms +
                        " backdropBatchTotalCpuMsP95=" + phases.backdropBatchTotalCpuP95Ms +
                        " backdropUnattributedCpuMsP95=" + phases.backdropUnattributedCpuP95Ms +
                        " boardCpuMsP50=" + phases.boardCpuP50Ms +
                        " boardCpuMsP95=" + phases.boardCpuP95Ms +
                        " pawnsCpuMsP50=" + phases.pawnsCpuP50Ms +
                        " pawnsCpuMsP95=" + phases.pawnsCpuP95Ms +
                        " diceCpuMsP50=" + phases.diceCpuP50Ms +
                        " diceCpuMsP95=" + phases.diceCpuP95Ms +
                        " shaderEndCpuMsP50=" + phases.shaderEndCpuP50Ms +
                        " shaderEndCpuMsP95=" + phases.shaderEndCpuP95Ms +
                        " particlesCpuMsP50=" + phases.particlesCpuP50Ms +
                        " particlesCpuMsP95=" + phases.particlesCpuP95Ms +
                        " particleBuildCpuMsP50=" + phases.particleBuildCpuP50Ms +
                        " particleBuildCpuMsP95=" + phases.particleBuildCpuP95Ms +
                        " particleBufferCopyCpuMsP50=" + phases.particleBufferCopyCpuP50Ms +
                        " particleBufferCopyCpuMsP95=" + phases.particleBufferCopyCpuP95Ms +
                        " particleGlStateCpuMsP50=" + phases.particleGlStateCpuP50Ms +
                        " particleGlStateCpuMsP95=" + phases.particleGlStateCpuP95Ms +
                        " particleBufferUploadCpuMsP50=" + phases.particleBufferUploadCpuP50Ms +
                        " particleBufferUploadCpuMsP95=" + phases.particleBufferUploadCpuP95Ms +
                        " particleDrawSubmitCpuMsP50=" + phases.particleDrawSubmitCpuP50Ms +
                        " particleDrawSubmitCpuMsP95=" + phases.particleDrawSubmitCpuP95Ms +
                        " particleCleanupCpuMsP50=" + phases.particleCleanupCpuP50Ms +
                        " particleCleanupCpuMsP95=" + phases.particleCleanupCpuP95Ms +
                        " activeParticlesP50=" + phases.activeParticlesP50 +
                        " activeParticlesP95=" + phases.activeParticlesP95 +
                        " gpuExecutionTime=not_measured"
                )
                android.util.Log.i("LudoMadaRender", "GLES_DEVICE_INFO " + glDeviceDiagnostic)
            }
            System.arraycopy(vp,0,projectionSnapshot,0,16)
            val pawnTargetsStartedNanos = System.nanoTime()
            host.publishPawnTapTargets()
            val pawnTargetsCpuNanos = System.nanoTime() - pawnTargetsStartedNanos
            if (!host.homePreview) {
                pipelinePhaseTelemetry?.record(
                    now,
                    updateCpuNanos,
                    lastShadowCpuNanos,
                    lastSceneCpuNanos,
                    lastPostCpuNanos,
                    pawnTargetsCpuNanos
                )?.let { phases ->
                    latestPipelinePhaseSnapshot = phases
                    rendererTelemetryUpdated = true
                    android.util.Log.i(
                        "LudoMadaRender",
                        "PERF_PIPELINE_PHASES samples=" + phases.sampleCount +
                            " updateCpuMsP50=" + phases.updateCpuP50Ms +
                            " updateCpuMsP95=" + phases.updateCpuP95Ms +
                            " shadowCpuMsP50=" + phases.shadowCpuP50Ms +
                            " shadowCpuMsP95=" + phases.shadowCpuP95Ms +
                            " sceneCpuMsP50=" + phases.sceneCpuP50Ms +
                            " sceneCpuMsP95=" + phases.sceneCpuP95Ms +
                            " postCpuMsP50=" + phases.postCpuP50Ms +
                            " postCpuMsP95=" + phases.postCpuP95Ms +
                            " pawnTargetsCpuMsP50=" + phases.pawnTargetsCpuP50Ms +
                            " pawnTargetsCpuMsP95=" + phases.pawnTargetsCpuP95Ms +
                            " gpuExecutionTime=not_measured"
                    )
                }
                frameTelemetry?.record(
                    now,
                    glSubmissionDurationNanos,
                    host.animatedRendering,
                    glThreadCpuTimeNanos = glThreadCpuStartNanos
                )?.let { stats ->
                    latestFramePerfSnapshot = stats
                    rendererTelemetryUpdated = true
                    android.util.Log.i(
                        "LudoMadaRender",
                        "PERF_FRAME_STATS renderer=GLES3_PBR surface=" + sceneWidth + "x" + sceneHeight +
                            " renderTarget=" + renderTargetWidth + "x" + renderTargetHeight +
                            " renderScale=" + renderScale +
                            " shadowMap=" + shadowSize +
                            " activeFrameSamples=" + stats.activeFrameSampleCount +
                            " activeFrameFps=" + (stats.activeFrameFps?.toString() ?: "insufficient_samples") +
                            " activeFrameIntervalMsP50=" + (stats.activeFrameIntervalP50Ms?.toString() ?: "unavailable") +
                            " activeFrameIntervalMsP95=" + (stats.activeFrameIntervalP95Ms?.toString() ?: "unavailable") +
                            " activeFrameIntervalMsMax=" + (stats.activeFrameIntervalMaxMs?.toString() ?: "unavailable") +
                            " afterCallbackGapMsP50P95=" + stats.afterCallbackGapP50Ms + "/" + stats.afterCallbackGapP95Ms +
                            " animationSamples=" + stats.animationFrameSampleCount +
                            " animationFps=" + (stats.animationFrameFps?.toString() ?: "insufficient_samples") +
                            " animationFrameMsP50P95=" + stats.animationFrameIntervalP50Ms + "/" + stats.animationFrameIntervalP95Ms +
                            " animationAfterCallbackGapMsP50P95=" + stats.animationAfterCallbackGapP50Ms + "/" + stats.animationAfterCallbackGapP95Ms +
                            " animationGlThreadPostCallbackCpuMsP50P95=" + stats.animationGlThreadPostCallbackCpuP50Ms + "/" + stats.animationGlThreadPostCallbackCpuP95Ms +
                            " glSubmissionSamples=" + stats.glSubmissionSampleCount +
                            " glSubmitCpuMsP50=" + stats.glSubmitP50Ms +
                            " glSubmitCpuMsP95=" + stats.glSubmitP95Ms +
                            " glSubmitCpuMsMax=" + stats.glSubmitMaxMs +
                            " gpuExecutionTime=not_measured"
                    )
                }
            }
            if (rendererTelemetryUpdated && !host.homePreview) {
                RendererTelemetryAccessibility.format(
                    deviceDiagnostic = glDeviceDiagnostic +
                        " renderTarget=" + renderTargetWidth + "x" + renderTargetHeight +
                        " renderScale=" + renderScale +
                        " shadowMap=" + shadowSize,
                    surfaceWidth = sceneWidth,
                    surfaceHeight = sceneHeight,
                    frame = latestFramePerfSnapshot,
                    pipeline = latestPipelinePhaseSnapshot,
                    scene = latestSceneBreakdownSnapshot,
                    preparedStaticMeshCount = preparedGles3StaticMeshCount,
                    gpu = gpuTimerQuery?.snapshot(),
                    gpuFence = gpuFenceTelemetry?.snapshot(System.nanoTime()),
                    softwareFramePacingEnabled = softwareFramePacingEnabled,
                    eglSwapInterval0Status = eglSwapInterval0Status,
                    eglSwapSurfaceWidth = eglSwapSurfaceWidth,
                    eglSwapSurfaceHeight = eglSwapSurfaceHeight
                )?.let(host::publishRendererTelemetry)
            }
            // Includes the whole GL callback, but deliberately excludes the following
            // GLSurfaceView EGL swap/vsync wait measured by the next callback interval.
            val callbackGlThreadCpuNanos = glThreadCpuStartNanos?.let { started ->
                android.os.Debug.threadCpuTimeNanos().takeIf { it >= started }?.minus(started)
            }
            frameTelemetry?.finishCallback(now, System.nanoTime() - now, callbackGlThreadCpuNanos)
        }
        private fun logFramebufferPixels(label:String,w:Int,h:Int):Boolean{
            val safeW=max(1,w);val safeH=max(1,h)
            val pixels=ByteBuffer.allocateDirect(safeW*safeH*4).order(ByteOrder.nativeOrder())
            GLES30.glFinish()
            GLES30.glReadPixels(0,0,safeW,safeH,GLES30.GL_RGBA,GLES30.GL_UNSIGNED_BYTE,pixels)
            val glError=GLES30.glGetError()
            var brightPixels=0
            var maxChannel=0
            var sumR=0L;var sumG=0L;var sumB=0L
            for(i in 0 until safeW*safeH){
                val o=i*4
                val r=pixels.get(o).toInt() and 0xff
                val g=pixels.get(o+1).toInt() and 0xff
                val b=pixels.get(o+2).toInt() and 0xff
                if(max(r,max(g,b))>24)brightPixels++
                maxChannel=max(maxChannel,max(r,max(g,b)))
                sumR+=r;sumG+=g;sumB+=b
            }
            val center=((safeH/2)*safeW+safeW/2)*4
            val cr=pixels.get(center).toInt() and 0xff
            val cg=pixels.get(center+1).toInt() and 0xff
            val cb=pixels.get(center+2).toInt() and 0xff
            val total=max(1,safeW*safeH)
            android.util.Log.i("LudoMadaRender",
                "PBR_READBACK label="+label+
                " size="+safeW+"x"+safeH+
                " brightPixels="+brightPixels+"/"+total+
                " maxChannel="+maxChannel+
                " meanRGB="+(sumR/total)+","+(sumG/total)+","+(sumB/total)+
                " centerRGB="+cr+","+cg+","+cb+
                " glError=0x"+glError.toString(16))
            val minimumBright=max(32,(total*0.005f).toInt())
            return glError==GLES30.GL_NO_ERROR && brightPixels>=minimumBright && maxChannel>24
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
            ensureShadowTargets()
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,shadowFbo)
            GLES30.glViewport(0,0,shadowSize,shadowSize)
            GLES30.glClear(GLES30.GL_DEPTH_BUFFER_BIT)
            // Small caster-side offset complements receiver normal bias and prevents
            // depth quantization from self-shadowing the broad tabletop surfaces.
            GLES30.glEnable(GLES30.GL_POLYGON_OFFSET_FILL)
            GLES30.glPolygonOffset(1.25f,2.0f)
            GLES30.glColorMask(false,false,false,false)
            try {
                shadowShader.begin(lightVp)
                drawWorld(true)
                shadowShader.end()
            } finally {
                GLES30.glColorMask(true,true,true,true)
                GLES30.glDisable(GLES30.GL_POLYGON_OFFSET_FILL)
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,0)
                GLES30.glViewport(0,0,renderTargetWidth,renderTargetHeight)
            }
        }
        private fun renderScene(){
            val setupStarted = if (BuildConfig.DEBUG) System.nanoTime() else 0L
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,sceneFbo)
            GLES30.glViewport(0,0,renderTargetWidth,renderTargetHeight)
            GLES30.glClearColor(0.008f,0.018f,0.045f,if(host.homePreview)0f else 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
            val frameShadowTexture = if (softwareRendererActive) fallbackShadowTex else shadowTex
            shader.beginFrame(frameShadowTexture,lightVp,woodTex,boardTex,neutralTex,metalTex,normalTex,envCube)
            if (setupStarted != 0L) lastSceneSetupCpuNanos = System.nanoTime() - setupStarted

            drawWorld(false)

            val shaderEndStarted = if (BuildConfig.DEBUG) System.nanoTime() else 0L
            shader.endFrame()
            if (shaderEndStarted != 0L) lastSceneShaderEndCpuNanos = System.nanoTime() - shaderEndStarted

            val particlesStarted = if (BuildConfig.DEBUG) System.nanoTime() else 0L
            drawParticles()
            if (particlesStarted != 0L) lastSceneParticlesCpuNanos = System.nanoTime() - particlesStarted
            // The final backbuffer readback in renderDirectFrame validates the completed
            // scene after post-processing. A second glFinish/readback here only stalls the
            // GPU on the first frame and must not contaminate render CPU-time telemetry.
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,0)
        }

        private fun renderDirectFrame(): Long {
            if (sceneFbo == 0) createSceneTargets(renderTargetWidth, renderTargetHeight)
            val pipelineStartedNanos = System.nanoTime()
            // Render CPU timing intentionally ends after scene + post process and
            // before any blocking framebuffer readback (glFinish/glReadPixels).
            if (SoftwareRendererPolicy.shouldRenderDynamicShadows(glDeviceDiagnostic, host.homePreview)) {
                val shadowStartedNanos = System.nanoTime()
                renderShadowMap()
                lastShadowCpuNanos = System.nanoTime() - shadowStartedNanos
            } else {
                // SwiftShader's CPU rasterizer otherwise drains the per-frame
                // board/pawn depth-caster sequence during EGL presentation.
                // Keep authored contact-shadow disks; omit only this dynamic pass.
                lastShadowCpuNanos = 0L
            }
            val sceneStartedNanos = System.nanoTime()
            renderScene()
            lastSceneCpuNanos = System.nanoTime() - sceneStartedNanos
            val postStartedNanos = System.nanoTime()
            renderPostProcess()
            lastPostCpuNanos = System.nanoTime() - postStartedNanos
            val renderPipelineCpuNanos = System.nanoTime() - pipelineStartedNanos

            if (host.homePreview) {
                if (BuildConfig.DEBUG && !homePreviewFirstFrameCaptured) {
                    val validSurface = logFramebufferPixels("home_surface", sceneWidth, sceneHeight)
                    val expectedHeroParts = setOf(
                        "LM_Pawn_Base_Weighted", "LM_Pawn_Collar", "LM_Pawn_Spherical_Head",
                        "LM_Pawn_Tapered_Body", "LM_Pawn_Underside_Insert", "LM_Die_HydroChrome_Body"
                    )
                    val visibleEnvironmentPartCount = envParts.keys.count {
                        !HomeEnvironmentComposition.isEmbeddedBaobabPart(it)
                    }
                    val complete = validSurface &&
                        glbParts.keys.containsAll(expectedHeroParts) &&
                        authoredBoardPartCount > 0 && preparedBoardBatches.isNotEmpty() &&
                        visibleEnvironmentPartCount > 0 && baobabParts.isNotEmpty()
                    if (complete) {
                        android.util.Log.i(
                            "LudoMadaRender",
                            "HOME_PBR_FIRST_FRAME_COMPLETE envParts=" + visibleEnvironmentPartCount +
                                " embeddedBaobabParts=" + (envParts.size - visibleEnvironmentPartCount) +
                                " baobabParts=" + baobabParts.size +
                                " heroParts=" + glbParts.size + " boardParts=" + authoredBoardPartCount
                        )
                        host.markHomePreviewReady(
                            "GLES3_PBR", visibleEnvironmentPartCount, baobabParts.size,
                            glbParts.size, authoredBoardPartCount
                        )
                    } else {
                        android.util.Log.e(
                            "LudoMadaRender",
                            "HOME_PBR_FIRST_FRAME_REJECTED validSurface=" + validSurface +
                                " envParts=" + envParts.size + " baobabParts=" + baobabParts.size +
                                " heroParts=" + glbParts.size + " boardParts=" + authoredBoardPartCount
                        )
                    }
                    homePreviewFirstFrameCaptured = true
                }
                return renderPipelineCpuNanos
            }

            if (BuildConfig.DEBUG && !firstFrameDiagnosticsCaptured) {
                val validSurface = logFramebufferPixels("surface_backbuffer", sceneWidth, sceneHeight)
                val expectedHeroParts = setOf(
                    "LM_Pawn_Base_Weighted", "LM_Pawn_Collar", "LM_Pawn_Spherical_Head",
                    "LM_Pawn_Tapered_Body", "LM_Pawn_Underside_Insert", "LM_Die_HydroChrome_Body"
                )
                if (validSurface && glbParts.keys.containsAll(expectedHeroParts) &&
                    authoredBoardPartCount > 0 && preparedBoardBatches.isNotEmpty()) {
                    android.util.Log.i("LudoMadaRender", "PBR_FIRST_FRAME_COMPLETE")
                    val environmentPartCount =
                        if (GameplayEnvironmentComposition.hasRequiredBlenderGameplayParts(gameplayEnvironmentParts.keys)) {
                            preparedGameplayEnvironmentSourcePartCount
                        } else {
                            0
                        }
                    val revision7EnvironmentReady =
                        GameplayEnvironmentComposition.hasRevision7BatchedGameplayEnvironment(
                            gameplayEnvironmentParts.keys
                        )
                    host.markRendererReady(
                        "GLES3_PBR",
                        glbParts.size,
                        authoredBoardPartCount,
                        environmentPartCount,
                        revision7EnvironmentReady,
                        authoredBoardPartCount,
                        preparedBoardBatches.size,
                        preparedBoardShadowMeshes.size,
                        5,
                        heroPawnMeshes.size,
                        preparedGameplayEnvironmentSourcePartCount,
                        preparedGameplayEnvironmentDraws.size,
                        diePipMeshes.size,
                        1
                    )
                } else {
                    android.util.Log.e(
                        "LudoMadaRender",
                        "PBR_FIRST_FRAME_REJECTED validSurface=" + validSurface +
                            " heroParts=" + glbParts.size + " boardParts=" + authoredBoardPartCount
                    )
                }
                firstFrameDiagnosticsCaptured = true
            }
            return renderPipelineCpuNanos
        }
        override fun projectedTokens(pi:Int):Array<FloatArray>{
            val out=Array(4){FloatArray(2){-1000f}};val m=projectionSnapshot;if(m[0]==0f)return out
            val viewWidth=host.width.coerceAtLeast(1)
            val viewHeight=host.height.coerceAtLeast(1)
            for(ti in 0..3){val xz=host.display[pi][ti];val x=xz[0];val z=xz[1];if(x.isNaN())continue;val y=boardTop+0.60f;val cx=m[0]*x+m[4]*y+m[8]*z+m[12];val cy=m[1]*x+m[5]*y+m[9]*z+m[13];val cw=m[3]*x+m[7]*y+m[11]*z+m[15];if(cw!=0f){out[ti][0]=(cx/cw*0.5f+0.5f)*viewWidth;out[ti][1]=(1f-(cy/cw*0.5f+0.5f))*viewHeight}}
            return out
        }
        override fun projectedDice():Array<FloatArray>{
            val out=Array(4){FloatArray(2){-1000f}};val m=projectionSnapshot;if(m[0]==0f)return out
            val viewWidth=host.width.coerceAtLeast(1)
            val viewHeight=host.height.coerceAtLeast(1)
            for(pi in 0..3){val p=dieCenterWorld(pi);val x=p[0];val y=p[1]+0.38f;val z=p[2];val cx=m[0]*x+m[4]*y+m[8]*z+m[12];val cy=m[1]*x+m[5]*y+m[9]*z+m[13];val cw=m[3]*x+m[7]*y+m[11]*z+m[15];if(cw!=0f){out[pi][0]=(cx/cw*0.5f+0.5f)*viewWidth;out[pi][1]=(1f-(cy/cw*0.5f+0.5f))*viewHeight}}
            return out
        }
        private fun dieCenterWorld(pi:Int):FloatArray = when(pi){
            0 -> floatArrayOf(-3.55f,boardTop+0.10f,3.55f)
            1 -> floatArrayOf(-3.55f,boardTop+0.10f,-3.55f)
            2 -> floatArrayOf(3.55f,boardTop+0.10f,-3.55f)
            else -> floatArrayOf(3.55f,boardTop+0.10f,3.55f)
        }
        private fun drawWorld(shadowPass:Boolean){
            if(shadowPass){
                if(homePreview) drawHomeEnvironmentShadows()
                /* Broad gameplay backdrop is intentionally excluded from shadow casting. */
                if(preparedBoardBatches.isNotEmpty()){
                    drawAuthoredBoard(true)
                    drawHomePawnSlots(true)
                    for(pi in 0..3)for(ti in 0..3){
                        val xz=host.display[pi][ti]
                        if(xz[0].isNaN())continue
                        drawShadowMesh(disk,identityAt(xz[0],boardTop+0.012f,xz[1],0.25f,0.025f,0.25f))
                        drawShadowMeshPawn(xz[0],boardTop+0.10f+lift[pi][ti],xz[1],pi,ti)
                    }
                    for(pi in 0..3){
                        val dp=dieCenterWorld(pi)
                        drawShadowMesh(roundedBox,identityAt(dp[0],boardTop+0.045f,dp[2],0.76f,0.075f,0.76f))
                        drawShadowMesh(roundedBox,identityAt(dp[0],boardTop+0.125f,dp[2],0.64f,0.045f,0.64f))
                        drawShadowMesh(roundedBox,dieModel(pi))
                    }
                    return
                }
                /* Broad gameplay backdrop is intentionally excluded from shadow casting. */
                drawShadowMesh(cube,identityAt(0f,-0.42f,0f,4.75f,0.35f,4.75f))
                drawShadowMesh(cube,identityAt(0f,-0.03f,0f,4.28f,0.12f,4.28f))
                drawShadowMesh(cube,identityAt(0f,0.095f,0f,4.05f,0.08f,4.05f))
                drawShadowMesh(cube,identityAt(0f,0.165f,0f,3.86f,0.07f,3.86f))
                drawShadowMesh(pathMesh,identityAt(0f,boardTop+0.025f,0f))
                for(i in 0..3)drawShadowMesh(coloredZones[i],identityAt(0f,boardTop+0.035f,0f))
                for(i in 0..3)drawShadowMesh(coloredLanes[i],identityAt(0f,boardTop+0.035f,0f))
                val homes=arrayOf(floatArrayOf(0f,-2.58f),floatArrayOf(2.58f,0f),floatArrayOf(0f,2.58f),floatArrayOf(-2.58f,0f))
                for(hc in homes)drawShadowMesh(roundedBox,identityAt(hc[0],boardTop+0.13f,hc[1],1.34f,0.10f,1.34f))
                for(pi in 0..3)for(ti in 0..3){
                    val xz=host.display[pi][ti]
                    if(xz[0].isNaN())continue
                    drawShadowMesh(pawnBody,pawnModel(xz[0],boardTop+0.10f+lift[pi][ti],xz[1],pi,ti))
                    drawShadowMesh(sphere,pawnHeadModel(xz[0],boardTop+0.10f+lift[pi][ti],xz[1],pi,ti))
                }
                for(pi in 0..3){
                    val dp=dieCenterWorld(pi)
                    drawShadowMesh(roundedBox,dieModel(pi))
                    drawShadowMesh(cube,identityAt(dp[0],boardTop+0.035f,dp[2],0.92f,0.06f,0.92f))
                }
                return
            }
            lastBackdropBatchCount = 0
            lastBackdropCullingQueryCpuNanos = 0L
            lastBackdropCullingTransitionCpuNanos = 0L
            lastBackdropCullingRestoreCpuNanos = 0L
            java.util.Arrays.fill(backdropBatchCpuScratch, 0L)
            java.util.Arrays.fill(backdropBatchVertexScratch, 0)
            java.util.Arrays.fill(backdropBatchIndexScratch, 0)
            val backdropStarted = if (BuildConfig.DEBUG) System.nanoTime() else 0L
            if (homePreview) {
                drawHomeEnvironment()
            } else {
                drawGameplayBackdrop()
            }
            if (backdropStarted != 0L) lastSceneBackdropCpuNanos = System.nanoTime() - backdropStarted

            val boardStarted = if (BuildConfig.DEBUG) System.nanoTime() else 0L
            if(preparedBoardBatches.isNotEmpty()){
                drawAuthoredBoard(false)
                drawHomePawnSlots(false)
            }else{
                drawMesh(cube,identityAt(0f,-0.42f,0f,4.75f,0.35f,4.75f),floatArrayOf(0.32f,0.10f,0.035f,1f),0.34f,0.18f,1f,0f,1,false)
                drawMesh(cube,identityAt(0f,-0.03f,0f,4.28f,0.12f,4.28f),floatArrayOf(0.47f,0.18f,0.05f,1f),0.42f,0.12f,1f,0f,1,false)
                drawMesh(cube,identityAt(0f,0.095f,0f,4.05f,0.08f,4.05f),floatArrayOf(0.78f,0.48f,0.16f,1f),0.46f,0.10f,1f,0f,1,false)
                drawMesh(cube,identityAt(0f,0.165f,0f,3.86f,0.07f,3.86f),floatArrayOf(0.88f,0.84f,0.73f,1f),0.58f,0.04f,1f,0f,2,false)
                drawMesh(pathMesh,identityAt(0f,boardTop+0.008f,0f),floatArrayOf(0.93f,0.88f,0.75f,1f),0.42f,0.06f,0.98f,0f,2,false)
                for(i in 0..3)drawMesh(coloredZones[i],identityAt(0f,boardTop+0.035f,0f),rgba(zoneColorInt(i)),0.24f,0.05f,1f,0.01f,3,false)
                for(i in 0..3)drawMesh(coloredLanes[i],identityAt(0f,boardTop+0.035f,0f),rgba(zoneColorInt(i)),0.28f,0.05f,1f,0.012f,3,false)
                val homes=arrayOf(floatArrayOf(0f,-2.58f),floatArrayOf(2.58f,0f),floatArrayOf(0f,2.58f),floatArrayOf(-2.58f,0f))
                for(i in 0..3){
                    val hc=homes[i]
                    drawMesh(roundedBox,identityAt(hc[0],boardTop+0.13f,hc[1],1.34f,0.10f,1.34f),rgba(zoneColorInt(i)),0.34f,0.06f,1f,0.01f,3,false)
                    drawMesh(roundedBox,identityAt(hc[0],boardTop+0.22f,hc[1],1.16f,0.075f,1.16f),colorFromInt(zoneColorInt(i),0.96f),0.22f,0.10f,1f,0.012f,3,false)
                    for(sx in arrayOf(-0.43f,0.43f)) for(sz in arrayOf(-0.43f,0.43f))
                        drawMesh(disk,identityAt(hc[0]+sx,boardTop+0.30f,hc[1]+sz,0.27f,0.025f,0.27f),colorFromInt(zoneColorInt(i),0.56f),0.20f,0.18f,1f,0f,3,false)
                }
            }
            if (boardStarted != 0L) lastSceneBoardCpuNanos = System.nanoTime() - boardStarted

            val pawnsStarted = if (BuildConfig.DEBUG) System.nanoTime() else 0L
            for(pi in 0..3)for(ti in 0..3){val xz=host.display[pi][ti];if(xz[0].isNaN())continue;drawMesh(disk,identityAt(xz[0],boardTop+0.012f,xz[1],0.25f,1f,0.25f),floatArrayOf(0.015f,0.018f,0.023f,0.30f),0.90f,0f,1f,0f,0,true);drawPawn(xz[0],boardTop+0.10f+lift[pi][ti],xz[1],pawnColors[pi],pi==player,pi,ti)}
            if (pawnsStarted != 0L) lastScenePawnsCpuNanos = System.nanoTime() - pawnsStarted

            val diceStarted = if (BuildConfig.DEBUG) System.nanoTime() else 0L
            for(pi in 0..3) drawDie(pi)
            if (diceStarted != 0L) lastSceneDiceCpuNanos = System.nanoTime() - diceStarted
        }
        private fun drawGameplayBackdrop(){
            // Prefer the authored Blender table when its tabletop and floor are both
            // present. Keep the compact procedural slab as a safe GLES3 fallback.
            if (GameplayEnvironmentComposition.hasRequiredBlenderGameplayParts(
                    gameplayEnvironmentParts.keys
                )
            ) {
                drawBlenderGameplayEnvironment()
                return
            }

            val tabletop = GameplayEnvironmentComposition.tabletop()
            drawMesh(
                cube,
                identityAt(0f,-1.02f,0f,13f,0.08f,13f),
                tabletop.baseColor,
                tabletop.roughness,
                tabletop.metallic,
                tabletop.ao,
                tabletop.emission,
                GameplayEnvironmentComposition.WOOD_MATERIAL_KIND,
                false,
                woodTex,
                woodNormalTex,
                0,
                receivesShadow = GameplayEnvironmentComposition.RECEIVES_SHADOW,
                preserveAlbedo = GameplayEnvironmentComposition.PRESERVE_ALBEDO
            )
        }

        private fun prepareGameplayEnvironmentDraws() {
            val root = GameplayEnvironmentComposition.blenderGameplayRootTransform()
            val prepared = gameplayEnvironmentParts.values
                .sortedBy { part -> GameplayEnvironmentComposition.blenderPartDrawOrder(part.name) }
                .map { part ->
                    val modelMatrix = GameplayEnvironmentComposition.blenderGameplayModelMatrix(root, part.transform)
                    val textureKind = GameplayEnvironmentComposition.blenderTextureKind(part.materialName)
                    val albedoTexture = when (textureKind) {
                        GameplayEnvironmentComposition.WOOD_MATERIAL_KIND -> woodTex
                        4 -> metalTex
                        else -> neutralTex
                    }
                    val normalTexture = if (
                        textureKind == GameplayEnvironmentComposition.WOOD_MATERIAL_KIND
                    ) woodNormalTex else normalTex
                    GameplayEnvironmentBatcher.Input(
                        name = part.name,
                        drawOrder = GameplayEnvironmentComposition.blenderPartDrawOrder(part.name),
                        mesh = part.mesh,
                        transform = modelMatrix,
                        baseColor = GameplayEnvironmentComposition.blenderMaterialColor(
                            part.materialName,
                            part.baseColor
                        ),
                        roughness = part.roughness,
                        metallic = part.metallic,
                        materialName = part.materialName,
                        materialKind = GameplayEnvironmentComposition.blenderMaterialKind(part.materialName),
                        albedoTexture = albedoTexture,
                        normalTexture = normalTexture,
                        emission = if (textureKind == 4) 0.006f else 0f,
                        receivesShadow = GameplayEnvironmentComposition.blenderPartReceivesGameplayShadow(
                            part.name
                        ),
                        doubleSided = part.doubleSided
                    )
                }
            preparedGameplayEnvironmentSourcePartCount = prepared.size
            preparedGameplayEnvironmentDraws = GameplayEnvironmentBatcher.merge(prepared)
            gameplayEnvironmentHasDoubleSidedParts = prepared.any { it.doubleSided }
            val revision7EnvironmentReady =
                GameplayEnvironmentComposition.hasRevision7BatchedGameplayEnvironment(
                    gameplayEnvironmentParts.keys
                )
            android.util.Log.i(
                "LudoMadaRender",
                "GAMEPLAY_ENVIRONMENT_PREPARED drawParts=" + prepared.size +
                    " sourceParts=" + prepared.size +
                    " drawBatches=" + preparedGameplayEnvironmentDraws.size +
                    " singleSidedBatches=" + preparedGameplayEnvironmentDraws.count { !it.doubleSided } +
                    " doubleSidedBatches=" + preparedGameplayEnvironmentDraws.count { it.doubleSided } +
                    " revision7Complete=" + revision7EnvironmentReady +
                    " doubleSided=" + gameplayEnvironmentHasDoubleSidedParts +
                    " cachedTransformsMaterialsAndBatches=true"
            )
        }

        private fun drawBlenderGameplayEnvironment(){
            val batches = preparedGameplayEnvironmentDraws
            if (batches.isEmpty()) return

            // The gameplay renderer enables face culling in onSurfaceCreated and
            // this method restores that known state before returning. Do not query
            // GL state here: glIsEnabled stalls SwiftShader for tens of milliseconds.
            // glTF doubleSided metadata alone determines the per-batch override.
            var cullingEnabled = true
            var batchIndex = 0
            try {
                for (batch in batches) {
                    val batchCullingEnabled = !batch.doubleSided
                    if (batchCullingEnabled != cullingEnabled) {
                        val transitionStarted = if (BuildConfig.DEBUG) System.nanoTime() else 0L
                        if (batchCullingEnabled) GLES30.glEnable(GLES30.GL_CULL_FACE)
                        else GLES30.glDisable(GLES30.GL_CULL_FACE)
                        if (transitionStarted != 0L) {
                            lastBackdropCullingTransitionCpuNanos += System.nanoTime() - transitionStarted
                        }
                        cullingEnabled = batchCullingEnabled
                    }
                    val batchStarted = if (BuildConfig.DEBUG) System.nanoTime() else 0L
                    drawMesh(
                        batch.mesh,
                        identityModel,
                        batch.baseColor,
                        batch.roughness,
                        batch.metallic,
                        0.98f,
                        batch.emission,
                        batch.materialKind,
                        false,
                        batch.albedoTexture,
                        batch.normalTexture,
                        0,
                        // SwiftShader is CPU-bound: keep environment shadow reception on hardware, not its software fallback.
                        receivesShadow = batch.receivesShadow && !softwareRendererActive,
                        preserveAlbedo = true
                    )
                    if (batchStarted != 0L && batchIndex < backdropBatchCpuScratch.size) {
                        backdropBatchCpuScratch[batchIndex] = System.nanoTime() - batchStarted
                        backdropBatchVertexScratch[batchIndex] = batch.mesh.positions.size / 3
                        backdropBatchIndexScratch[batchIndex] = batch.mesh.indices.size
                    }
                    batchIndex++
                }
            } finally {
                lastBackdropBatchCount = batchIndex.coerceIn(
                    0,
                    RenderSceneBreakdownTelemetry.MAX_BACKDROP_BATCHES
                )
                // Restore only when a final double-sided batch left culling disabled.
                if (!cullingEnabled) {
                    val restoreStarted = if (BuildConfig.DEBUG) System.nanoTime() else 0L
                    GLES30.glEnable(GLES30.GL_CULL_FACE)
                    if (restoreStarted != 0L) {
                        lastBackdropCullingRestoreCpuNanos = System.nanoTime() - restoreStarted
                    }
                }
            }
        }

        private fun authoredBoardRoot():FloatArray{
            Matrix.setIdentityM(modelScratch,0)
            Matrix.translateM(modelScratch,0,-0.7f*boardScaleXZ,0.20f,0f)
            Matrix.scaleM(modelScratch,0,boardScaleXZ,1f,boardScaleXZ)
            return modelScratch.clone()
        }

        private fun boardMaterialColor(name:String):FloatArray=when(name){
            "LM_Path_Red"->rgba(Color.rgb(220,54,57))
            "LM_Path_Green"->rgba(Color.rgb(49,165,91))
            "LM_Path_Gold"->rgba(Color.rgb(237,179,44))
            "LM_Path_Blue"->rgba(Color.rgb(57,111,219))
            "LM_Path_Ivory"->floatArrayOf(0.93f,0.86f,0.69f,1f)
            "LM_Path_Dark"->floatArrayOf(0.065f,0.070f,0.075f,1f)
            "LM_Board_Felt"->floatArrayOf(0.29f,0.18f,0.10f,1f)
            "LM_Gold_Frame"->floatArrayOf(0.72f,0.46f,0.14f,1f)
            "LM_Table_Lacquer"->floatArrayOf(0.34f,0.13f,0.035f,1f)
            else->floatArrayOf(0.82f,0.78f,0.70f,1f)
        }

        private fun prepareHeroPawnMeshes() {
            val names = listOf(
                "LM_Pawn_Underside_Insert",
                "LM_Pawn_Base_Weighted",
                "LM_Pawn_Tapered_Body",
                "LM_Pawn_Collar",
                "LM_Pawn_Spherical_Head"
            )
            val parts = names.mapNotNull { glbParts[it] }
            if (parts.size != names.size) {
                heroPawnMeshes = emptyList()
                android.util.Log.w(
                    "LudoMadaRender",
                    "HERO_PAWN_BATCH_SKIPPED expectedParts=" + names.size + " actualParts=" + parts.size
                )
                return
            }
            val merged = MeshBatcher.merge(parts.map { part ->
                MeshBatchInput(
                    positions = part.mesh.positions,
                    normals = part.mesh.normals,
                    uvs = part.mesh.uvsCopy(),
                    indices = part.mesh.indices,
                    transform = part.transform
                )
            })
            heroPawnMeshes = merged.map { geometry ->
                Mesh(geometry.positions, geometry.normals, geometry.indices, geometry.uvs)
            }
            android.util.Log.i(
                "LudoMadaRender",
                "HERO_PAWN_BATCH_READY sourceParts=" + parts.size + " drawBatches=" + heroPawnMeshes.size
            )
        }

        private fun prepareAuthoredBoardDraws() {
            val root = authoredBoardRoot()
            val prepared = boardParts.values
                .sortedWith(compareBy({ it.materialName }, { it.name }))
                .map { part ->
                    val material = part.materialName
                    val roughness = when {
                        material == "LM_Gold_Frame" -> 0.20f
                        material == "LM_Table_Lacquer" -> 0.28f
                        material == "LM_Path_Red" || material == "LM_Path_Green" ||
                            material == "LM_Path_Gold" || material == "LM_Path_Blue" -> 0.30f
                        material == "LM_Path_Ivory" -> 0.46f
                        material == "LM_Board_Felt" -> 0.55f
                        else -> 0.34f
                    }
                    val modelMatrix = FloatArray(16)
                    Matrix.multiplyMM(modelMatrix, 0, root, 0, part.transform, 0)
                    PreparedBoardDraw(
                        part = part,
                        modelMatrix = modelMatrix,
                        baseColor = boardMaterialColor(material),
                        roughness = roughness,
                        metallic = if (material == "LM_Gold_Frame") 0.88f else 0.06f,
                        emission = if (material == "LM_Gold_Frame") 0.015f else 0f,
                        albedoTexture = when (material) {
                            "LM_Table_Lacquer" -> woodTex
                            "LM_Board_Felt" -> neutralTex
                            else -> boardTex
                        },
                        normalTexture = if (material == "LM_Table_Lacquer") woodNormalTex else 0
                    )
                }
            authoredBoardPartCount = prepared.size
            val byMaterial = prepared.groupBy { it.part.materialName }
            preparedBoardBatches = byMaterial.values.flatMap { group ->
                val first = group.first()
                val merged = MeshBatcher.merge(group.map { draw ->
                    MeshBatchInput(
                        positions = draw.part.mesh.positions,
                        normals = draw.part.mesh.normals,
                        uvs = draw.part.mesh.uvsCopy(),
                        indices = draw.part.mesh.indices,
                        transform = draw.modelMatrix
                    )
                })
                merged.map { geometry ->
                    PreparedBoardBatch(
                        mesh = Mesh(geometry.positions, geometry.normals, geometry.indices, geometry.uvs),
                        baseColor = first.baseColor,
                        roughness = first.roughness,
                        metallic = first.metallic,
                        emission = first.emission,
                        albedoTexture = first.albedoTexture,
                        normalTexture = first.normalTexture
                    )
                }
            }
            val shadowGeometry = MeshBatcher.merge(prepared.map { draw ->
                MeshBatchInput(
                    positions = draw.part.mesh.positions,
                    normals = draw.part.mesh.normals,
                    uvs = draw.part.mesh.uvsCopy(),
                    indices = draw.part.mesh.indices,
                    transform = draw.modelMatrix
                )
            })
            preparedBoardShadowMeshes = shadowGeometry.map { geometry ->
                Mesh(geometry.positions, geometry.normals, geometry.indices, geometry.uvs)
            }
            android.util.Log.i(
                "LudoMadaRender",
                "BOARD_DRAW_BATCH_READY sourceParts=" + prepared.size +
                    " pbrBatches=" + preparedBoardBatches.size +
                    " shadowBatches=" + preparedBoardShadowMeshes.size +
                    " cachedWorldGeometry=true"
            )
            // Batches now own the cached world-space geometry. Releasing the
            // source map prevents a second copy of all vertex/index buffers
            // from remaining resident on memory-constrained Android devices.
            boardParts = emptyMap()
        }

        private fun drawAuthoredBoard(shadowPass:Boolean){
            if (shadowPass) {
                for (mesh in preparedBoardShadowMeshes) drawShadowMesh(mesh, identityModel)
                return
            }
            for (batch in preparedBoardBatches) {
                drawMesh(
                    batch.mesh,
                    identityModel,
                    batch.baseColor,
                    batch.roughness,
                    batch.metallic,
                    1f,
                    batch.emission,
                    5,
                    false,
                    batch.albedoTexture,
                    batch.normalTexture,
                    0
                )
            }
        }

        private fun drawHomePawnSlots(shadowPass:Boolean){
            val centers=arrayOf(floatArrayOf(-2.5833335f,2.5833335f),floatArrayOf(-2.5833335f,-2.5833335f),floatArrayOf(2.5833335f,-2.5833335f),floatArrayOf(2.5833335f,2.5833335f))
            val offsets=arrayOf(-0.43f,0.43f)
            for(pi in 0..3)for(sx in offsets)for(sz in offsets){
                val m=identityAt(centers[pi][0]+sx,boardTop+0.012f,centers[pi][1]+sz,0.26f,0.024f,0.26f)
                if(shadowPass)drawShadowMesh(disk,m)
                else drawMesh(disk,m,colorFromInt(zoneColorInt(pi),0.30f),0.28f,0.10f,1f,0f,3,false)
            }
        }

        private fun drawShadowMeshPawn(x:Float,y:Float,z:Float,pi:Int,ti:Int){
            if (heroPawnMeshes.isNotEmpty()) {
                val model = heroPawnModel(identityModel,x,y,z,0.72f,0.72f,0f,0f)
                for (mesh in heroPawnMeshes) drawShadowMesh(mesh,model)
                return
            }
            val body=glbParts["LM_Pawn_Tapered_Body"]
            val head=glbParts["LM_Pawn_Spherical_Head"]
            if(body!=null&&head!=null){
                drawShadowMesh(body.mesh,heroPawnModel(body.transform,x,y,z,0.72f,0.72f,0f,0f))
                listOf("LM_Pawn_Underside_Insert","LM_Pawn_Base_Weighted","LM_Pawn_Collar","LM_Pawn_Spherical_Head").forEach{glbParts[it]?.let{part->drawShadowMesh(part.mesh,heroPawnModel(part.transform,x,y,z,0.72f,0.72f,0f,0f))}}
            }else{
                drawShadowMesh(pawnBody,pawnModel(x,y,z,pi,ti))
                drawShadowMesh(sphere,pawnHeadModel(x,y,z,pi,ti))
            }
        }

        private fun drawHomeEnvironmentShadows(){
            drawEnvironmentGlb(true)
        }

        private fun drawEnvironmentGlb(shadowPass:Boolean){
            if(envParts.isEmpty())return
            // The environment GLB has authored baobab source nodes and a single central
            // ENV_Rock_04 on x≈0/y≈8. Filter those occluders from both colour and shadow
            // passes; retain the four perimeter rocks, terrain, grass and sky.
            val visibleEnvironmentParts=envParts.filterKeys {
                !HomeEnvironmentComposition.isEmbeddedBaobabPart(it) &&
                    !HomeEnvironmentComposition.isHomeCentrelineOccluderPart(it)
            }
            val parts=visibleEnvironmentParts.values.sortedBy { if(it.materialName=="ENV_Sky_HDRI") 0 else 1 }
            // The authored environment is Z-up while the gameplay board is Y-up.
            // Respect glTF doubleSided metadata for the inside-facing sky dome and
            // all other environment materials that were authored double-sided.
            val cullingWasEnabled=GLES30.glIsEnabled(GLES30.GL_CULL_FACE)
            if(parts.any{it.doubleSided})GLES30.glDisable(GLES30.GL_CULL_FACE)
            try {
                if(shadowPass){
                    for(part in parts){
                        if (!HomeEnvironmentComposition.castsHomeEnvironmentShadow(part.name)) continue
                        val model=HomeEnvironmentCoordinateSpace.toYUpTransform(part.transform)
                        drawShadowMesh(part.mesh,model)
                    }
                }else{
                    for(part in parts){
                        val mn=part.materialName
                        val skyPart=mn=="ENV_Sky_HDRI"
                        val barkPart=mn=="ENV_Bark_PBR"
                        val groundPart=mn=="ENV_Ground_PBR"
                        val dirtPart=groundPart || mn=="ENV_Rock_PBR"
                        val grassPart=mn=="ENV_Grass_PBR"
                        val albedo=when{
                            skyPart->envSkyTex
                            barkPart->envBarkTex
                            dirtPart->envDirtTex
                            grassPart->envGrassTex
                            else->neutralTex
                        }
                        val ntex=when{
                            barkPart->envBarkNormalTex
                            dirtPart->envDirtNormalTex
                            grassPart->envGrassNormalTex
                            else->normalTex
                        }
                        val rtex=when{
                            barkPart->envBarkRoughTex
                            dirtPart->envDirtRoughTex
                            grassPart->envGrassRoughTex
                            else->0
                        }
                        val rough=when{
                            skyPart->1.0f
                            barkPart->0.74f
                            dirtPart->0.86f
                            grassPart->0.92f
                            else->0.82f
                        }
                        val metallic=if(skyPart)0f else part.metallic
                        val unlit=skyPart
                        val model=HomeEnvironmentCoordinateSpace.toYUpTransform(part.transform)
                        val environmentColor=when {
                            groundPart -> HomeEnvironmentComposition.liftHomeGroundAlbedo(part.baseColor)
                            barkPart -> HomeEnvironmentComposition.liftEnvironmentColor(part.baseColor,1.50f)
                            dirtPart -> HomeEnvironmentComposition.liftEnvironmentColor(part.baseColor,1.25f)
                            grassPart -> HomeEnvironmentComposition.liftEnvironmentColor(part.baseColor,1.18f)
                            else -> part.baseColor
                        }
                        val environmentEmission=when {
                            skyPart -> 0.10f
                            barkPart -> 0.042f
                            dirtPart -> 0.018f
                            grassPart -> 0.014f
                            else -> 0f
                        }
                        // Bark keeps casting a soft shadow but does not receive its own
                        // low-resolution shadow-map acne; this prevents near-black trunks.
                        drawMesh(
                            part.mesh,model,environmentColor,rough,metallic,
                            if(barkPart)0.99f else part.baseColor[3].coerceIn(.70f,1f),
                            environmentEmission,6,unlit,albedo,ntex,rtex,
                            receivesShadow = !skyPart && !barkPart,
                            preserveAlbedo = HomeEnvironmentComposition.usesSourceAlbedo(mn)
                        )
                    }
                }
            } finally {
                if(cullingWasEnabled)GLES30.glEnable(GLES30.GL_CULL_FACE) else GLES30.glDisable(GLES30.GL_CULL_FACE)
            }
            drawRealBaobabs(shadowPass)
        }

        private fun drawRealBaobabs(shadowPass:Boolean){
            if(baobabParts.isEmpty())return
            val ordered=baobabParts.values.sortedBy{if(it.materialName=="scrub")1 else 0}
            for(inst in HomeEnvironmentComposition.baobabPlacements()){
                val im=identityAt(inst.x,inst.y,inst.z,inst.scale,inst.scale,inst.scale)
                for(part in ordered){
                    val m=FloatArray(16)
                    Matrix.multiplyMM(m,0,im,0,part.transform,0)
                    if(shadowPass){
                        // A/B isolate: test whether side-framed baobab shadows darken the Home ground.
                        if(HomeEnvironmentComposition.castsHomeBaobabShadows()) {
                            drawShadowMesh(part.mesh,m)
                        }
                    }else{
                        val barkPart=part.materialName=="timber"
                        val scrubPart=part.materialName=="scrub"
                        val albedo=when{barkPart->envBarkTex;scrubPart->envGrassTex;else->neutralTex}
                        val ntex=when{barkPart->envBarkNormalTex;scrubPart->envGrassNormalTex;else->normalTex}
                        val rtex=when{barkPart->envBarkRoughTex;scrubPart->envGrassRoughTex;else->0}
                        val rough=when{barkPart->0.76f;scrubPart->0.92f;else->0.82f}
                        val base=HomeEnvironmentComposition.liftEnvironmentColor(
                            part.baseColor,if(barkPart)1.50f else 1.16f
                        )
                        // Keep the bark warm and readable against the sunset HDRI.
                        // The tree still casts a shadow, but avoids self-shadowing into a silhouette.
                        drawMesh(
                            part.mesh,m,base,rough,part.metallic,
                            if(barkPart)0.99f else 0.96f,
                            if(barkPart)0.045f else 0.014f,
                            6,false,albedo,ntex,rtex,receivesShadow = !barkPart
                        )
                    }
                }
            }
        }

        private fun drawHomeEnvironment(){
            drawEnvironmentGlb(false)
        }

        private fun drawPawn(x:Float,y:Float,z:Float,color:Int,active:Boolean,pi:Int,ti:Int){
            if(active && isLegalPawn(pi,ti)){
                val pulse=(0.42f+0.06f*sin(System.nanoTime()/180_000_000.0).toFloat()).coerceIn(0.34f,0.50f)
                drawMesh(disk,identityAt(x,boardTop+0.022f,z,pulse,1f,pulse),floatArrayOf(0.96f,0.74f,0.16f,0.88f),0.08f,0.52f,1f,0.035f,4,true)
            }
            val body=glbParts["LM_Pawn_Tapered_Body"]
            val head=glbParts["LM_Pawn_Spherical_Head"]
            if(body!=null && head!=null && heroPawnMeshes.isNotEmpty()){
                val b=bounce[pi][ti].coerceIn(0f,1f)
                val isAnimating=b>0.001f
                val elastic=if(isAnimating)LudoMotionPhysics.elasticOut(b) else b
                val overshoot=elastic-b
                val backOvershoot=if(isAnimating)LudoMotionPhysics.backOut(b)-b else 0f
                val sx=0.72f*(1f-0.055f*overshoot)
                val sy=0.72f*(1f+0.115f*overshoot)
                val phase=if(isAnimating)((System.nanoTime()/18_000_000L+pi*37L+ti*11L)%360L).toFloat()/360f else 0f
                val swayZ=if(isAnimating)LudoMotionPhysics.sway(phase,3.5f)+overshoot*6.5f+backOvershoot*2.8f else 0f
                val swayX=if(isAnimating)LudoMotionPhysics.sway(phase,2.2f)-overshoot*3.0f+backOvershoot*1.4f else 0f
                val model=heroPawnModelForToken(identityModel,x,y+PremiumVisualMotion.landingBounce(b),z,sx,sy,swayZ,swayX,pi,ti)
                val pm=PremiumMaterialLighting.pawn(color)
                for (mesh in heroPawnMeshes) {
                    drawMesh(mesh,model,rgba(color),pm.roughness,pm.metallic,pm.ao,pm.emission,5,false)
                }
            }else if(body!=null && head!=null){
                val b=bounce[pi][ti].coerceIn(0f,1f)
                val isAnimating=b>0.001f
                val elastic=if(isAnimating)LudoMotionPhysics.elasticOut(b) else b
                val overshoot=elastic-b
                val backOvershoot=if(isAnimating)LudoMotionPhysics.backOut(b)-b else 0f
                val sx=0.72f*(1f-0.055f*overshoot)
                val sy=0.72f*(1f+0.115f*overshoot)
                val phase=if(isAnimating)((System.nanoTime()/18_000_000L+pi*37L+ti*11L)%360L).toFloat()/360f else 0f
                val swayZ=if(isAnimating)LudoMotionPhysics.sway(phase,3.5f)+overshoot*6.5f+backOvershoot*2.8f else 0f
                val swayX=if(isAnimating)LudoMotionPhysics.sway(phase,2.2f)-overshoot*3.0f+backOvershoot*1.4f else 0f
                listOf(
                    "LM_Pawn_Underside_Insert",
                    "LM_Pawn_Base_Weighted",
                    "LM_Pawn_Tapered_Body",
                    "LM_Pawn_Collar",
                    "LM_Pawn_Spherical_Head"
                ).forEach { name ->
                    glbParts[name]?.let { part ->
                        val model=heroPawnModelForToken(part.transform,x,y+PremiumVisualMotion.landingBounce(b),z,sx,sy,swayZ,swayX,pi,ti)
                        val pm=PremiumMaterialLighting.pawn(color)
                        drawMesh(part.mesh,model,rgba(color),pm.roughness,pm.metallic,pm.ao,pm.emission,5,false)
                    }
                }
            }else{
                drawMesh(pawnBody,pawnModel(x,y,z,pi,ti),rgba(color),0.22f,0.04f,1f,0.015f,3,false)
                drawMesh(sphere,pawnHeadModel(x,y,z,pi,ti),rgba(color),0.18f,0.05f,1f,0.018f,3,false)
            }
        }

        private fun heroPawnModel(partTransform:FloatArray,x:Float,y:Float,z:Float,sx:Float,sy:Float,rotZ:Float,rotX:Float):FloatArray{
            val root=identityAt(x,y,z,1f,1f,1f)
            if(rotZ!=0f)Matrix.rotateM(root,0,rotZ,0f,0f,1f)
            if(rotX!=0f)Matrix.rotateM(root,0,rotX,1f,0f,0f)
            Matrix.scaleM(root,0,sx,sy,sx)
            val out=FloatArray(16)
            Matrix.multiplyMM(out,0,root,0,partTransform,0)
            return out
        }

        private fun heroPawnModelForToken(
            partTransform:FloatArray,x:Float,y:Float,z:Float,
            sx:Float,sy:Float,rotZ:Float,rotX:Float,pi:Int,ti:Int
        ):FloatArray{
            val root=pawnRootScratch[pi][ti]
            Matrix.setIdentityM(root,0)
            Matrix.translateM(root,0,x,y,z)
            if(rotZ!=0f)Matrix.rotateM(root,0,rotZ,0f,0f,1f)
            if(rotX!=0f)Matrix.rotateM(root,0,rotX,1f,0f,0f)
            Matrix.scaleM(root,0,sx,sy,sx)
            val out=pawnModelScratch[pi][ti]
            Matrix.multiplyMM(out,0,root,0,partTransform,0)
            return out
        }
        private fun isLegalPawn(pi:Int,ti:Int):Boolean{
            if(pi!=player)return false
            val dice=state?.optInt("dice",0)?:0
            if(dice !in 1..6)return false
            val pos=state?.optJSONArray("players")?.optJSONObject(pi)?.optJSONArray("tokens")?.optJSONObject(ti)?.optInt("pos",-1)?:-1
            return if(pos<0) dice==6 else pos+dice<=57
        }
        private fun pawnModel(x:Float,y:Float,z:Float,pi:Int,ti:Int):FloatArray{
            val b=bounce[pi][ti].coerceIn(0f,1f)
            val isAnimating=b>0.001f
            val elastic=if(isAnimating)LudoMotionPhysics.elasticOut(b) else b
            val overshoot=elastic-b
            val backOvershoot=if(isAnimating)LudoMotionPhysics.backOut(b)-b else 0f
            val sx=pawnScale*(1f-0.055f*overshoot)
            val sy=pawnScale*(1f+0.115f*overshoot)
            val m=identityAt(x,y+PremiumVisualMotion.landingBounce(b),z,sx,sy,sx)
            if(isAnimating){
                val phase=((System.nanoTime()/18_000_000L+pi*37L+ti*11L)%360L).toFloat()/360f
                Matrix.rotateM(m,0,LudoMotionPhysics.sway(phase,3.5f)+overshoot*6.5f+backOvershoot*2.8f,0f,0f,1f)
                Matrix.rotateM(m,0,LudoMotionPhysics.sway(phase,2.2f)-overshoot*3.0f+backOvershoot*1.4f,1f,0f,0f)
            }
            return m
        }
        private fun pawnHeadModel(x:Float,y:Float,z:Float,pi:Int,ti:Int):FloatArray{
            val b=bounce[pi][ti].coerceIn(0f,1f)
            val isAnimating=b>0.001f
            val elastic=if(isAnimating)LudoMotionPhysics.elasticOut(b) else b
            val overshoot=elastic-b
            val backOvershoot=if(isAnimating)LudoMotionPhysics.backOut(b)-b else 0f
            val s=pawnScale*0.37f*(1f-0.035f*overshoot)
            val sy=pawnScale*0.37f*(1f+0.065f*overshoot)
            val m=identityAt(x,y+pawnScale*0.74f+PremiumVisualMotion.landingBounce(b)*0.65f,z,s,sy,s)
            if(isAnimating){
                val phase=((System.nanoTime()/20_000_000L+pi*31L+ti*7L)%360L).toFloat()/360f
                Matrix.rotateM(m,0,LudoMotionPhysics.sway(phase,2.5f)+overshoot*4.0f+backOvershoot*1.8f,0f,0f,1f)
            }
            return m
        }
        private fun dieValue(pi:Int):Int{
            val live=max(0,state?.optInt("dice",0)?:0)
            return if(pi==player && live in 1..6) live else intArrayOf(4,3,5,5)[pi]
        }
        private fun dieModel(pi:Int):FloatArray{
            val p=dieCenterWorld(pi)
            val value=dieValue(pi)
            val t=if(pi==player)diceAnim else 1f
            val spin=PremiumVisualMotion.dieSpin(t)
            val idleRotation=if(homePreview) ((System.nanoTime()/12_000_000L)%360L).toFloat() else 0f
            val sx=(1f-spin)*(720f+pi*55f)+idleRotation+if(value%2==0)12f+pi*7f else 31f+pi*5f
            val sy=(1f-spin)*(430f+pi*37f)+idleRotation*0.62f+17f+pi*11f
            val m=identityAt(p[0],p[1]+0.38f,p[2],0.52f,0.52f,0.52f)
            Matrix.rotateM(m,0,sx,1f,0f,0f);Matrix.rotateM(m,0,sy,0f,1f,0f)
            return m
        }
        private fun drawDie(pi:Int){
            val p=dieCenterWorld(pi)
            val active=pi==player
            val die=dieModel(pi)
            // Layered chrome tray: dark outer lip, bright inner plate, then a compact die.
            drawMesh(roundedBox,identityAt(p[0],boardTop+0.045f,p[2],0.76f,0.075f,0.76f),floatArrayOf(0.08f,0.09f,0.10f,1f),0.12f,1.0f,1f,0.01f,4,false)
            drawMesh(roundedBox,identityAt(p[0],boardTop+0.125f,p[2],0.64f,0.045f,0.64f),floatArrayOf(0.68f,0.72f,0.76f,1f),0.09f,1.0f,1f,0.02f,4,false)
            if(active){
                val now=System.nanoTime()
                val pulse=(0.77f+0.05f*sin(now/170_000_000.0).toFloat()).coerceIn(0.70f,0.84f)
                drawMesh(disk,identityAt(p[0],boardTop+0.11f,p[2],pulse,1f,pulse),floatArrayOf(0.96f,0.74f,0.15f,0.46f),0.10f,0.66f,1f,0.025f,4,true)
                val energy=(1f-diceAnim).coerceIn(0f,1f)
                val ringScale=0.92f+0.28f*(0.5f+0.5f*sin(now/105_000_000.0).toFloat())
                drawMesh(heroRing,identityAt(p[0],boardTop+0.105f,p[2],ringScale,1f,ringScale),floatArrayOf(1f,0.72f,0.14f,0.30f+0.45f*energy),0.08f,0.35f,1f,0.24f*energy,4,true)
                val ring2=0.76f+0.22f*energy
                drawMesh(heroRing,identityAt(p[0],boardTop+0.11f,p[2],ring2,1f,ring2),floatArrayOf(0.95f,0.96f,1f,0.14f+0.20f*energy),0.06f,0.25f,1f,0.12f*energy,4,true)
            }
            val glbDie=glbParts["LM_Die_HydroChrome_Body"]
            if(glbDie!=null){
                drawMesh(
                    glbDie.mesh,
                    heroDieModel(glbDie.transform,pi,p[0],p[1]+0.38f,p[2],0.52f),
                    floatArrayOf(0.98f,0.99f,1.0f,1f),
                    PremiumMaterialLighting.Calibration.CHROME_ROUGHNESS,
                    PremiumMaterialLighting.Calibration.CHROME_METALLIC,
                    0.99f,
                    0.012f,
                    4,
                    false
                )
            }else{
                drawMesh(roundedBox,die,floatArrayOf(0.98f,0.99f,1.0f,1f),0.055f,1.0f,1f,0.045f,4,false)
            }
            val pipFaceValue = when (val value = dieValue(pi)) {
                1, 2, 3, 4, 5 -> value
                else -> 6 // Preserve the former six-pip fallback for zero/invalid values.
            }
            drawMesh(
                diePipMeshes[pipFaceValue - 1],
                die,
                floatArrayOf(0.045f, 0.052f, 0.06f, 1f),
                0.16f,
                0.74f,
                1f,
                0f,
                4,
                false
            )
        }
        private fun heroDieModel(partTransform:FloatArray,pi:Int,x:Float,y:Float,z:Float,scale:Float):FloatArray{
            val t=if(pi==player)diceAnim else 1f
            val spin=PremiumVisualMotion.dieSpin(t)
            val value=dieValue(pi)
            val idleRotation=if(homePreview) ((System.nanoTime()/12_000_000L)%360L).toFloat() else 0f
            val sx=(1f-spin)*(720f+pi*55f)+idleRotation+if(value%2==0)12f+pi*7f else 31f+pi*5f
            val sy=(1f-spin)*(430f+pi*37f)+idleRotation*0.62f+17f+pi*11f
            val anchor=identityAt(x,y,z,1f,1f,1f)
            Matrix.rotateM(anchor,0,sx,1f,0f,0f)
            Matrix.rotateM(anchor,0,sy,0f,1f,0f)
            Matrix.scaleM(anchor,0,scale,scale,scale)
            val centered=identityAt(-1.55f,-0.58f,0f,1f,1f,1f)
            val local=FloatArray(16)
            Matrix.multiplyMM(local,0,centered,0,partTransform,0)
            val out=FloatArray(16)
            Matrix.multiplyMM(out,0,anchor,0,local,0)
            return out
        }
        private fun drawMesh(mesh:Mesh,mat:FloatArray,color:FloatArray,rough:Float,metallic:Float,ao:Float,emission:Float,material:Int,unlit:Boolean,albedoOverride:Int=0,normalOverride:Int=0,roughOverride:Int=0,receivesShadow:Boolean=true,preserveAlbedo:Boolean=false)=shader.draw(mesh,mat,color,rough,metallic,ao,emission,material,unlit,albedoOverride,normalOverride,roughOverride,receivesShadow,preserveAlbedo)
        private fun drawShadowMesh(mesh:Mesh,mat:FloatArray)=shadowShader.draw(mesh,mat)
        private fun maybeSpawnDiceFx(now:Long){
            val pi=player
            if(host.homePreview||pi !in 0..3||diceAnim>=0.97f||now-lastDiceFx[pi]<48_000_000L)return
            lastDiceFx[pi]=now
            val p=dieCenterWorld(pi)
            val energy=(1f-diceAnim).coerceIn(0f,1f)
            for(n in 0 until 4){
                val a=(now/20_000_000L+n*37L+pi*11L)*0.37f
                val r=0.52f+0.28f*(0.5f+0.5f*sin(now/38_000_000.0+n).toFloat())
                spawnDiceSpark(p[0]+cos(a).toFloat()*r,p[2]+sin(a).toFloat()*r,energy,n)
            }
        }

        private fun spawnDiceSpark(x:Float,z:Float,energy:Float,n:Int){
            val slot=(0 until 128).firstOrNull{dustLife[it]<=0f}?:return
            val a=slot*2.3999631f+n*1.3f
            val life=0.22f+(slot%4)*0.035f
            dustLife[slot]=life;dustMaxLife[slot]=life
            dustPos[slot*3]=x;dustPos[slot*3+1]=boardTop+0.20f+(slot%3)*0.04f;dustPos[slot*3+2]=z
            dustVel[slot*3]=cos(a)*(0.25f+0.22f*energy)
            dustVel[slot*3+1]=0.55f+0.55f*energy
            dustVel[slot*3+2]=sin(a)*(0.25f+0.22f*energy)
            val c=if(n%2==0)Color.rgb(255,206,72)else Color.rgb(235,241,255)
            dustColor[slot*4]=Color.red(c)/255f;dustColor[slot*4+1]=Color.green(c)/255f;dustColor[slot*4+2]=Color.blue(c)/255f;dustColor[slot*4+3]=0.92f
            dustSize[slot]=5.5f+(slot%3)*1.8f
        }

        private fun maybeSpawnDust(now:Long){for(pi in 0..3)for(ti in 0..3){if(lift[pi][ti]<=0.035f||now-lastDust[pi][ti]<90_000_000L)continue;val xz=host.display[pi][ti];if(xz[0].isNaN())continue;lastDust[pi][ti]=now;spawnDust(xz[0],xz[1],pawnColors[pi])}}
        private var victoryKey=""
        private fun maybeSpawnVictory(){
            val s=state?:return
            val phase=s.optString("phase","")
            if(phase!="FINISHED"){victoryKey="";return}
            val winner=s.optString("winnerId","")
            val key=phase+"|"+winner
            if(key==victoryKey)return
            victoryKey=key
            spawnVictoryBurst()
        }
        private fun spawnDust(x:Float,z:Float,color:Int){for(n in 0 until 3){val slot=(0 until 128).firstOrNull{dustLife[it]<=0f}?:continue;val a=slot*2.3999631f+x*1.7f+z*0.9f+n*0.9f;val life=0.45f+(slot%5)*0.03f;dustLife[slot]=life;dustMaxLife[slot]=life;dustPos[slot*3]=x+cos(a)*0.10f;dustPos[slot*3+1]=boardTop+0.08f;dustPos[slot*3+2]=z+sin(a)*0.10f;dustVel[slot*3]=cos(a)*0.18f;dustVel[slot*3+1]=0.55f+(slot%3)*0.08f;dustVel[slot*3+2]=sin(a)*0.18f;dustColor[slot*4]=Color.red(color)/255f;dustColor[slot*4+1]=Color.green(color)/255f;dustColor[slot*4+2]=Color.blue(color)/255f;dustColor[slot*4+3]=0.75f;dustSize[slot]=4f+(slot%4)}}
        private fun spawnVictoryBurst(){
            val colors=intArrayOf(Color.rgb(255,203,58),Color.rgb(255,239,176),pawnColors[0],pawnColors[1],pawnColors[2],pawnColors[3])
            for(n in 0 until 56){
                val slot=(0 until 128).firstOrNull{dustLife[it]<=0f}?:continue
                val a=n*2.3999631f
                val radial=0.75f+(n%7)*0.14f
                val life=0.78f+(n%6)*0.07f
                dustLife[slot]=life;dustMaxLife[slot]=life
                dustPos[slot*3]=cos(a)*0.16f;dustPos[slot*3+1]=boardTop+0.58f+(n%5)*0.025f;dustPos[slot*3+2]=sin(a)*0.16f
                dustVel[slot*3]=cos(a)*radial;dustVel[slot*3+1]=1.35f+(n%5)*0.12f;dustVel[slot*3+2]=sin(a)*radial
                val col=colors[n%colors.size]
                dustColor[slot*4]=Color.red(col)/255f;dustColor[slot*4+1]=Color.green(col)/255f;dustColor[slot*4+2]=Color.blue(col)/255f;dustColor[slot*4+3]=0.92f
                dustSize[slot]=7f+(n%5)*2.2f
            }
        }
        private fun updateParticles(dt:Float){for(i in 0 until 128){if(dustLife[i]<=0f)continue;dustLife[i]-=dt;val o=i*3;dustVel[o+1]-=1.25f*dt;dustVel[o]*=(1f-1.5f*dt).coerceAtLeast(0f);dustVel[o+2]*=(1f-1.5f*dt).coerceAtLeast(0f);dustPos[o]+=dustVel[o]*dt;dustPos[o+1]+=dustVel[o+1]*dt;dustPos[o+2]+=dustVel[o+2]*dt;if(dustPos[o+1]<boardTop+0.02f)dustLife[i]=0f}}
        private fun drawParticles(){
            val buildStarted = if (BuildConfig.DEBUG) System.nanoTime() else 0L
            var count = 0
            for (i in 0 until 128) if (dustLife[i] > 0f) {
                val p = count * 8
                val o = i * 3
                val c = i * 4
                dustData[p] = dustPos[o]
                dustData[p + 1] = dustPos[o + 1]
                dustData[p + 2] = dustPos[o + 2]
                dustData[p + 3] = dustColor[c]
                dustData[p + 4] = dustColor[c + 1]
                dustData[p + 5] = dustColor[c + 2]
                dustData[p + 6] = dustColor[c + 3] *
                    (dustLife[i] / max(0.01f, dustMaxLife[i])).coerceIn(0f, 1f)
                dustData[p + 7] = dustSize[i]
                count++
            }
            lastActiveParticleCount = count
            if (buildStarted != 0L) lastParticleBuildCpuNanos = System.nanoTime() - buildStarted
            if (count == 0) {
                lastParticleBufferCopyCpuNanos = 0L
                lastParticleGlStateCpuNanos = 0L
                lastParticleBufferUploadCpuNanos = 0L
                lastParticleDrawSubmitCpuNanos = 0L
                lastParticleCleanupCpuNanos = 0L
                return
            }
            val timings = if (BuildConfig.DEBUG) particleDrawTimingScratch else null
            val particleVbo = particleVbos[nextParticleVbo]
            nextParticleVbo = (nextParticleVbo + 1) % particleVbos.size
            particleShader.draw(dustData, count, particleVbo, vp, timings)
            lastParticleBufferCopyCpuNanos = timings?.let { it[0] } ?: 0L
            lastParticleGlStateCpuNanos = timings?.let { it[1] } ?: 0L
            lastParticleBufferUploadCpuNanos = timings?.let { it[2] } ?: 0L
            lastParticleDrawSubmitCpuNanos = timings?.let { it[3] } ?: 0L
            lastParticleCleanupCpuNanos = timings?.let { it[4] } ?: 0L
        }
        private fun renderPostProcess(){
            // On SwiftShader the EGL buffer is quarter-size too, so this pass
            // fills only that buffer; SurfaceFlinger scales it to View bounds.
            GLES30.glViewport(0,0,sceneWidth,sceneHeight)
            GLES30.glDisable(GLES30.GL_DEPTH_TEST)
            GLES30.glBlendFunc(GLES30.GL_ONE,GLES30.GL_ZERO)
            postShader.draw(
                sceneColorTex,
                sceneDepthTex,
                renderTargetWidth,
                renderTargetHeight,
                premiumPost.ssao,
                premiumPost.bloom
            )
            GLES30.glEnable(GLES30.GL_DEPTH_TEST)
            GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA,GLES30.GL_ONE_MINUS_SRC_ALPHA)
        }
        private fun createMaterialTexture(kind:Int):Int{val size=512;val data=ByteBuffer.allocateDirect(size*size*4).order(ByteOrder.nativeOrder());for(y in 0 until size)for(x in 0 until size){val rgb=PbrMaterialTextures.colorRgb(kind,x,y,size);data.put((rgb shr 16).toByte()).put((rgb shr 8).toByte()).put(rgb.toByte()).put(255.toByte())};data.position(0);return uploadTexture(size,data)}
        private fun createFlatNormalTexture():Int{val data=ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder());data.put(128.toByte()).put(128.toByte()).put(255.toByte()).put(255.toByte());data.position(0);return uploadTexture(1,data)}
        private fun createNormalTexture():Int{val size=256;val data=ByteBuffer.allocateDirect(size*size*4).order(ByteOrder.nativeOrder());for(y in 0 until size)for(x in 0 until size){val wave=sin(x*0.055+sin(y*0.025)*0.45);val nx=(128+wave*2.5+cos(y*0.065)*1.0).toInt().coerceIn(0,255);val ny=(128+cos(x*0.045+sin(y*0.03))*2.2+sin(y*0.07)*0.8).toInt().coerceIn(0,255);data.put(nx.toByte()).put(ny.toByte()).put(255.toByte()).put(255.toByte())};data.position(0);return uploadTexture(size,data)}
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

        private fun createFallbackShadowTexture():Int{
            val data=ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
            data.put(255.toByte()).put(255.toByte()).put(255.toByte()).put(255.toByte()).position(0)
            return uploadTexture(1,data)
        }

        private fun loadAssetTexture(assetPath:String):Int{
            val opts=BitmapFactory.Options().apply{inScaled=false;inPreferredConfig=android.graphics.Bitmap.Config.ARGB_8888;inSampleSize=2}
            val bitmap=host.context.assets.open(assetPath).use{BitmapFactory.decodeStream(it,null,opts)}
                ?:error("Unable to decode asset texture: $assetPath")
            val ids=IntArray(1)
            GLES30.glGenTextures(1,ids,0)
            val tex=ids[0]
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,tex)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_MIN_FILTER,GLES30.GL_LINEAR_MIPMAP_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_MAG_FILTER,GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_WRAP_S,GLES30.GL_REPEAT)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_WRAP_T,GLES30.GL_REPEAT)
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D,0,bitmap,0)
            GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,0)
            bitmap.recycle()
            return tex
        }
        private fun uploadTexture(size:Int,data:ByteBuffer):Int{val ids=IntArray(1);GLES30.glGenTextures(1,ids,0);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,ids[0]);GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_MIN_FILTER,GLES30.GL_LINEAR_MIPMAP_LINEAR);GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_MAG_FILTER,GLES30.GL_LINEAR);GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_WRAP_S,GLES30.GL_REPEAT);GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_WRAP_T,GLES30.GL_REPEAT);GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D,0,GLES30.GL_RGBA,size,size,0,GLES30.GL_RGBA,GLES30.GL_UNSIGNED_BYTE,data);GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,0);return ids[0]}
        private fun identityAt(x:Float,y:Float,z:Float,sx:Float=1f,sy:Float=1f,sz:Float=1f):FloatArray{Matrix.setIdentityM(modelScratch,0);Matrix.translateM(modelScratch,0,x,y,z);Matrix.scaleM(modelScratch,0,sx,sy,sz);return modelScratch.clone()}
        private fun envModel(x:Float,y:Float,z:Float,sx:Float,sy:Float,sz:Float,rx:Float=0f,ry:Float=0f,rz:Float=0f):FloatArray{
            Matrix.setIdentityM(modelScratch,0);Matrix.translateM(modelScratch,0,x,y,z)
            if(rx!=0f)Matrix.rotateM(modelScratch,0,rx,1f,0f,0f)
            if(ry!=0f)Matrix.rotateM(modelScratch,0,ry,0f,1f,0f)
            if(rz!=0f)Matrix.rotateM(modelScratch,0,rz,0f,0f,1f)
            Matrix.scaleM(modelScratch,0,sx,sy,sz);return modelScratch.clone()
        }
        private fun rgba(c:Int)=floatArrayOf(Color.red(c)/255f,Color.green(c)/255f,Color.blue(c)/255f,1f)
        private fun colorFromInt(c:Int,a:Float)=floatArrayOf(Color.red(c)/255f,Color.green(c)/255f,Color.blue(c)/255f,a.coerceIn(0f,1f))
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
            val x = when (pi) { 0, 1 -> -2.58f; else -> 2.58f }
            val z = when (pi) { 0, 3 -> 2.58f; else -> -2.58f }
            addBox(v, n, i, 0, x, boardTop + 0.04f, z, 1.42f, 0.055f, 1.42f)
            addBox(v, n, i, 24, x, boardTop + 0.095f, z, 1.14f, 0.04f, 1.14f)
            return Mesh(v.toFloatArray(), n.toFloatArray(), i.toShortArray())
        }

        private fun laneMesh(pi:Int):Mesh{
            val v=ArrayList<Float>();val n=ArrayList<Float>();val i=ArrayList<Short>();var base=0
            val cells=when(pi){
                0 -> (1..6).map{it to 7}
                1 -> listOf(7 to 8,7 to 9,7 to 10,7 to 11,7 to 12,7 to 13)
                2 -> (8..13).map{it to 7}
                else -> listOf(7 to 1,7 to 2,7 to 3,7 to 4,7 to 5,7 to 6)
            }
            for((r,c) in cells){
                val x=(c-7.0f)*0.50f;val z=(r-7.0f)*0.50f
                addBox(v,n,i,base,x,boardTop+0.083f,z,0.218f,0.020f,0.218f);base+=24
            }
            return Mesh(v.toFloatArray(),n.toFloatArray(),i.toShortArray())
        }

        private fun roundedBoxMesh(seg:Int,rings:Int): Mesh {
            val v=ArrayList<Float>();val n=ArrayList<Float>();val idx=ArrayList<Short>();val p=8.0
            for(r in 0..rings){
                val ph=r.toFloat()/rings*Math.PI;val sp=sin(ph);val cp=cos(ph)
                for(q in 0..seg){
                    val th=q.toFloat()/seg*Math.PI*2.0
                    val dx=sp*cos(th);val dy=cp;val dz=sp*sin(th)
                    val norm=max(0.0001,(abs(dx).pow(p)+abs(dy).pow(p)+abs(dz).pow(p))).pow(1.0/p)
                    val x=dx/norm;val y=dy/norm;val z=dz/norm
                    v+=x.toFloat();v+=y.toFloat();v+=z.toFloat()
                    val nx=sign(x)*abs(x).pow(p-1.0);val ny=sign(y)*abs(y).pow(p-1.0);val nz=sign(z)*abs(z).pow(p-1.0)
                    val nl=max(0.0001,sqrt(nx*nx+ny*ny+nz*nz))
                    n+=(nx/nl).toFloat();n+=(ny/nl).toFloat();n+=(nz/nl).toFloat()
                }
            }
            val row=seg+1
            for(r in 0 until rings)for(q in 0 until seg){
                val a=(r*row+q).toShort();val b=(a.toInt()+1).toShort();val c=((r+1)*row+q+1).toShort();val d=((r+1)*row+q).toShort()
                idx+=a;idx+=b;idx+=c;idx+=a;idx+=c;idx+=d
            }
            return Mesh(v.toFloatArray(),n.toFloatArray(),idx.toShortArray())
        }

        private fun pawnBodyMesh(): Mesh {
            val profile = arrayOf(
                0.62f to 0.00f, 0.74f to 0.05f, 0.70f to 0.11f,
                0.52f to 0.20f, 0.37f to 0.31f, 0.25f to 0.44f,
                0.18f to 0.56f, 0.15f to 0.67f
            )
            return latheMesh(profile, 32)
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

        private fun ringMesh(seg:Int,outer:Float,inner:Float):Mesh{
            val v=ArrayList<Float>();val n=ArrayList<Float>();val idx=ArrayList<Short>()
            for(s in 0..seg){
                val a=s.toFloat()/seg*Math.PI*2.0
                val co=cos(a).toFloat();val si=sin(a).toFloat()
                v+=co*outer;v+=0f;v+=si*outer;n+=0f;n+=1f;n+=0f
                v+=co*inner;v+=0f;v+=si*inner;n+=0f;n+=1f;n+=0f
            }
            for(s in 0 until seg){
                val a=(s*2).toShort();val b=(s*2+1).toShort();val c=((s+1)*2).toShort();val d=((s+1)*2+1).toShort()
                idx+=a;idx+=c;idx+=d;idx+=a;idx+=d;idx+=b
            }
            return Mesh(v.toFloatArray(),n.toFloatArray(),idx.toShortArray())
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

    internal class Mesh(
        val positions:FloatArray,
        val normals:FloatArray,
        val indices:ShortArray,
        uvsOverride:FloatArray?=null
    ){
        private val uvData:FloatArray=uvsOverride ?: FloatArray(positions.size/3*2).also{out->
            for(i in positions.indices step 3){
                val j=(i/3)*2
                out[j]=positions[i]*0.12f-floor(positions[i]*0.12f)
                out[j+1]=positions[i+2]*0.12f-floor(positions[i+2]*0.12f)
            }
        }
        init{
            require(normals.size==positions.size){"Mesh normal count mismatch"}
            require(uvData.size==positions.size/3*2){"Mesh UV count mismatch"}
        }
        val p:FloatBuffer=ByteBuffer.allocateDirect(positions.size*4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply{put(positions);position(0)}
        val n:FloatBuffer=ByteBuffer.allocateDirect(normals.size*4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply{put(normals);position(0)}
        val uv:FloatBuffer=ByteBuffer.allocateDirect(uvData.size*4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply{put(uvData);position(0)}
        val i:ShortBuffer=ByteBuffer.allocateDirect(indices.size*2).order(ByteOrder.nativeOrder()).asShortBuffer().apply{put(indices);position(0)}
        private var gles3Vao = 0
        private var gles3Buffers = IntArray(0)

        /**
         * Upload immutable mesh streams once per GL context and capture their layout in a VAO.
         * The GLES2 CPU buffers above remain available to the fallback renderer.
         */
        /** Upload all immutable streams once while the GL context is active. */
        internal fun prepareGles3Static() {
            if (gles3Vao != 0) return
            val vao = IntArray(1)
            val buffers = IntArray(4)
            GLES30.glGenVertexArrays(1, vao, 0)
            GLES30.glGenBuffers(buffers.size, buffers, 0)
            if (vao[0] == 0 || buffers.any { it == 0 }) {
                if (vao[0] != 0) GLES30.glDeleteVertexArrays(1, vao, 0)
                if (buffers.any { it != 0 }) GLES30.glDeleteBuffers(buffers.size, buffers, 0)
                throw IllegalStateException("Unable to allocate GLES3 static mesh objects")
            }
            gles3Vao = vao[0]
            gles3Buffers = buffers
            try {
                GLES30.glBindVertexArray(gles3Vao)

                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffers[0])
                GLES30.glBufferData(
                    GLES30.GL_ARRAY_BUFFER,
                    positions.size * Float.SIZE_BYTES,
                    p,
                    GLES30.GL_STATIC_DRAW
                )
                GLES30.glEnableVertexAttribArray(0)
                GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 0, 0)

                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffers[1])
                GLES30.glBufferData(
                    GLES30.GL_ARRAY_BUFFER,
                    normals.size * Float.SIZE_BYTES,
                    n,
                    GLES30.GL_STATIC_DRAW
                )
                GLES30.glEnableVertexAttribArray(1)
                GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, 0, 0)

                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffers[2])
                GLES30.glBufferData(
                    GLES30.GL_ARRAY_BUFFER,
                    uvData.size * Float.SIZE_BYTES,
                    uv,
                    GLES30.GL_STATIC_DRAW
                )
                GLES30.glEnableVertexAttribArray(2)
                GLES30.glVertexAttribPointer(2, 2, GLES30.GL_FLOAT, false, 0, 0)

                GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, buffers[3])
                GLES30.glBufferData(
                    GLES30.GL_ELEMENT_ARRAY_BUFFER,
                    indices.size * 2,
                    i,
                    GLES30.GL_STATIC_DRAW
                )
            } catch (error: Throwable) {
                GLES30.glBindVertexArray(0)
                GLES30.glDeleteVertexArrays(1, vao, 0)
                GLES30.glDeleteBuffers(buffers.size, buffers, 0)
                gles3Vao = 0
                gles3Buffers = IntArray(0)
                throw error
            }
            GLES30.glBindVertexArray(0)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        }

        /** Binding is draw-only; every mesh must have been uploaded during surface setup. */
        internal fun bindGles3Static() {
            check(gles3Vao != 0) { "GLES3 static mesh was not prepared before the render loop" }
            GLES30.glBindVertexArray(gles3Vao)
        }

        internal fun uvsCopy(): FloatArray = uvData.copyOf()
    }

    private class PbrShader(private val renderer:SceneRenderer){
        private val program:Int
        private val uMvp:Int;private val uMv:Int;private val uModel:Int;private val uNormalModel:Int;private val uLightVp:Int;private val uColor:Int;private val uMetallic:Int;private val uRoughness:Int;private val uAO:Int;private val uEmission:Int;private val uUnlit:Int;private val uNormalStrength:Int;private val uClearCoat:Int;private val uClearCoatRoughness:Int;private val uAlbedoTex:Int;private val uNormalTex:Int;private val uRoughTex:Int;private val uRoughTexEnabled:Int;private val uShadowMap:Int;private val uEnvCube:Int;private val uKeyDirection:Int;private val uKeyColor:Int;private val uKeyIntensity:Int;private val uFillDirection:Int;private val uFillColor:Int;private val uFillIntensity:Int;private val uRimDirection:Int;private val uRimColor:Int;private val uRimIntensity:Int;private val uIBLIntensity:Int;private val uShadowBias:Int;private val uShadowNormalBias:Int;private val uReceivesShadow:Int;private val uShadowInvSize:Int;private val uPreserveAlbedo:Int
        private val mv=FloatArray(16);private val mvp=FloatArray(16);private val light=FloatArray(16);private val normalModel=FloatArray(9)
        private var wood=0;private var board=0;private var neutral=0;private var metal=0;private var normal=0
        // Texture bindings are stable across the PBR draw sequence. Invalidate at
        // beginFrame because post-processing uses texture units 0 and 1 afterwards.
        private var boundAlbedoTexture = Int.MIN_VALUE
        private var boundNormalTexture = Int.MIN_VALUE
        private var boundRoughTexture = Int.MIN_VALUE
        // Batches that share an already world-space transform do not need
        // repeated MV/MVP multiplication or uniform submission within a frame.
        private val modelMatrixCache = PbrModelMatrixCache()
        init{
            val premiumVs="""#version 300 es
                precision highp float;
                layout(location=0)in vec3 aPos;layout(location=1)in vec3 aNormal;layout(location=2)in vec2 aUv;
                uniform mat4 uMvp,uMv,uModel,uLightVp;uniform mat3 uNormalModel;uniform float uShadowNormalBias;out vec3 vN,vP,vWorld;out vec2 vUv;out vec4 vShadow;
                void main(){vec4 w=uModel*vec4(aPos,1.0);vec3 worldNormal=normalize(uNormalModel*aNormal);vec4 shadowWorld=vec4(w.xyz+worldNormal*uShadowNormalBias,1.0);vP=(uMv*vec4(aPos,1.0)).xyz;vWorld=w.xyz;vN=normalize(mat3(uMv)*aNormal);vUv=aUv;vShadow=uLightVp*shadowWorld;gl_Position=uMvp*vec4(aPos,1.0);}
            """.trimIndent()
            // CPU-backed renderers interpolate cheap lighting terms. Keep texture
            // and shadow sampling per-fragment; the premium hardware shader is unchanged.
            val softwareVs="""#version 300 es
                precision highp float;
                layout(location=0)in vec3 aPos;layout(location=1)in vec3 aNormal;layout(location=2)in vec2 aUv;
                uniform mat4 uMvp,uMv,uModel,uLightVp;uniform mat3 uNormalModel;
                uniform float uShadowNormalBias,uAO,uIBLIntensity,uKeyIntensity,uFillIntensity,uRimIntensity,uMetallic,uRoughness,uReceivesShadow;
                uniform vec3 uKeyDirection,uKeyColor,uFillDirection,uFillColor,uRimDirection,uRimColor;
                out vec2 vUv;out vec4 vShadow;out vec3 vAmbientFillRim,vKeyDiffuse,vKeySpecular;
                void main(){
                    vec4 local=vec4(aPos,1.0);
                    vec4 world=uModel*local;
                    if(uReceivesShadow>0.5){
                        vec3 worldNormal=normalize(uNormalModel*aNormal);
                        vShadow=uLightVp*vec4(world.xyz+worldNormal*uShadowNormalBias,1.0);
                    }else{
                        vShadow=vec4(0.0);
                    }
                    vec3 N=normalize(mat3(uMv)*aNormal);
                    vec3 V=normalize(-(uMv*local).xyz);
                    vec3 L=normalize(uKeyDirection);
                    vec3 fillL=normalize(uFillDirection);
                    vec3 rimL=normalize(uRimDirection);
                    float keyLambert=max(dot(N,L),0.0);
                    float fillLambert=max(dot(N,fillL),0.0);
                    float rimLambert=max(dot(N,rimL),0.0);
                    vAmbientFillRim=vec3(0.17)*uAO*(0.55+0.45*uIBLIntensity)
                        +uFillColor*(fillLambert*uFillIntensity)
                        +uRimColor*(rimLambert*uRimIntensity*0.32);
                    float specularPower=mix(52.0,5.0,clamp(uRoughness,0.05,1.0));
                    vec3 halfVector=normalize(V+L);
                    float specular=pow(max(dot(N,halfVector),0.0),specularPower)
                        *keyLambert*(0.025+0.22*clamp(uMetallic,0.0,1.0))*uKeyIntensity;
                    vKeyDiffuse=uKeyColor*(keyLambert*uKeyIntensity);
                    vKeySpecular=uKeyColor*specular;
                    vUv=aUv;
                    gl_Position=uMvp*local;
                }
            """.trimIndent()
            val vs=if(renderer.softwareRendererActive)softwareVs else premiumVs
            val premiumFs="""#version 300 es
                precision highp float;uniform vec4 uColor;uniform float uMetallic,uRoughness,uAO,uEmission,uUnlit,uNormalStrength,uClearCoat,uClearCoatRoughness,uKeyIntensity,uFillIntensity,uRimIntensity,uIBLIntensity,uShadowBias,uReceivesShadow;uniform vec3 uKeyDirection,uKeyColor,uFillDirection,uFillColor,uRimDirection,uRimColor;uniform vec2 uShadowInvSize;uniform sampler2D uAlbedoTex,uNormalTex,uRoughTex,uShadowMap;uniform float uRoughTexEnabled,uPreserveAlbedo;uniform samplerCube uEnvCube;
                in vec3 vN,vP,vWorld;in vec2 vUv;in vec4 vShadow;layout(location=0)out vec4 fragColor;const float PI=3.14159265359;
                float DGGX(vec3 N,vec3 H,float r){float a=r*r,a2=a*a,nh=max(dot(N,H),0.0),q=nh*nh,d=q*(a2-1.0)+1.0;return a2/max(PI*d*d,0.0001);}
                float G(float nv,float r){float k=((r+1.0)*(r+1.0))/8.0;return nv/max(nv*(1.0-k)+k,0.0001);}
                vec3 F(float c,vec3 f0){return f0+(1.0-f0)*pow(clamp(1.0-c,0.0,1.0),5.0);}
                float shadowV(){vec3 p=vShadow.xyz/max(vShadow.w,0.0001);p=p*0.5+0.5;if(p.z>1.0||p.x<0.0||p.x>1.0||p.y<0.0||p.y>1.0)return 1.0;float cur=p.z-uShadowBias,s=0.0;vec2 t=uShadowInvSize;for(int x=-1;x<=1;x++)for(int y=-1;y<=1;y++){float d=texture(uShadowMap,p.xy+vec2(float(x),float(y))*t).r;s+=cur<=d?1.0:0.0;}return s/9.0;}
                void main(){
                    vec3 N0=normalize(vN),nm=texture(uNormalTex,vUv*3.5).xyz*2.0-1.0;vec3 T=normalize(vec3(1,0,0)-N0*dot(N0,vec3(1,0,0)));if(length(T)<0.10)T=normalize(vec3(0,0,1)-N0*dot(N0,vec3(0,0,1)));vec3 B=normalize(cross(N0,T));
                    vec3 N=normalize(mix(N0,T*nm.x+B*nm.y+N0*max(nm.z,0.15),uNormalStrength));vec3 tex=texture(uAlbedoTex,vUv*2.0).rgb;vec3 albedo=uPreserveAlbedo>0.5?uColor.rgb*tex:mix(uColor.rgb*tex,uColor.rgb,0.52);
                    float m=clamp(uMetallic,0.0,1.0),r0=clamp(uRoughness,0.045,1.0);float mapR=texture(uRoughTex,vUv*2.0).r;r0=mix(r0,mapR,uRoughTexEnabled);float r=r0;vec3 V=normalize(-vP),L=normalize(uKeyDirection),H=normalize(V+L);float nv=max(dot(N,V),0.0),nl=max(dot(N,L),0.0);
                    vec3 f0=mix(vec3(0.04),albedo,m),f=F(max(dot(H,V),0.0),f0);float d=DGGX(N,H,r),g=G(nv,r)*G(nl,r);vec3 spec=(d*g*f)/max(4.0*nv*nl,0.001);vec3 kd=(vec3(1.0)-f)*(1.0-m);float sh=uReceivesShadow>0.5?shadowV():1.0;
                    vec3 direct=(kd*albedo/PI+spec)*uKeyColor*nl*sh*uKeyIntensity;vec3 L2=normalize(uFillDirection);vec3 H2=normalize(V+L2);float nl2=max(dot(N,L2),0.0);float r2=max(0.06,uRoughness*0.82);float d2=DGGX(N,H2,r2);float g2=G(nv,r2)*G(nl2,r2);vec3 f2=F(max(dot(H2,V),0.0),f0);vec3 spec2=(d2*g2*f2)/max(4.0*nv*nl2,0.001);direct+=(kd*albedo/PI+spec2)*uFillColor*nl2*uFillIntensity;vec3 L3=normalize(uRimDirection);vec3 H3=normalize(V+L3);float nl3=max(dot(N,L3),0.0);float r3=max(0.06,uRoughness*0.78);float d3=DGGX(N,H3,r3);float g3=G(nv,r3)*G(nl3,r3);vec3 f3=F(max(dot(H3,V),0.0),f0);vec3 spec3=(d3*g3*f3)/max(4.0*nv*nl3,0.001);direct+=spec3*uRimColor*nl3*uRimIntensity;float coat=clamp(uClearCoat,0.0,1.0);float cr=max(0.035,uClearCoatRoughness);float cd=DGGX(N,H,cr);float cg=G(nv,cr)*G(nl,cr);vec3 cf=F(max(dot(H,V),0.0),vec3(0.04));vec3 cs=(cd*cg*cf)/max(4.0*nv*nl,0.001);direct+=cs*vec3(1.0,0.96,0.90)*coat*nl*0.85;vec3 R=reflect(-V,N);vec3 envS=textureLod(uEnvCube,R,r*5.0).rgb;vec3 envD=textureLod(uEnvCube,N,4.0).rgb;
                    float fres=pow(clamp(1.0-max(dot(N,V),0.0),0.0,1.0),5.0);vec3 color=(envD*albedo*kd*0.72+envS*f*(0.26+0.74*fres))*uAO*uIBLIntensity+direct+albedo*uEmission;if(uUnlit>0.5)color=mix(color,albedo+albedo*uEmission,0.84);fragColor=vec4(max(color,vec3(0.0)),uColor.a);
                }
            """.trimIndent()
            val softwareFs="""#version 300 es
                precision highp float;
                uniform vec4 uColor;
                uniform float uEmission,uUnlit,uPreserveAlbedo;
                uniform sampler2D uAlbedoTex;
                in vec2 vUv;in vec3 vAmbientFillRim,vKeyDiffuse,vKeySpecular;
                layout(location=0)out vec4 fragColor;
                void main(){
                    vec3 tex=texture(uAlbedoTex,vUv*2.0).rgb;
                    vec3 texturedAlbedo=uColor.rgb*tex;
                    vec3 albedo=uPreserveAlbedo>0.5?texturedAlbedo:mix(texturedAlbedo,uColor.rgb,0.52);
                    vec3 color=albedo*(vAmbientFillRim+vKeyDiffuse)
                        +vKeySpecular+albedo*uEmission;
                    if(uUnlit>0.5)color=mix(color,albedo+albedo*uEmission,0.84);
                    fragColor=vec4(max(color,vec3(0.0)),uColor.a);
                }
            """.trimIndent()
            val fs=if(renderer.softwareRendererActive)softwareFs else premiumFs
            program=buildProgram(vs,fs)
            uEnvCube=GLES30.glGetUniformLocation(program,"uEnvCube");uMvp=GLES30.glGetUniformLocation(program,"uMvp");uMv=GLES30.glGetUniformLocation(program,"uMv");uModel=GLES30.glGetUniformLocation(program,"uModel");uNormalModel=GLES30.glGetUniformLocation(program,"uNormalModel");uLightVp=GLES30.glGetUniformLocation(program,"uLightVp");uColor=GLES30.glGetUniformLocation(program,"uColor");uMetallic=GLES30.glGetUniformLocation(program,"uMetallic");uRoughness=GLES30.glGetUniformLocation(program,"uRoughness");uAO=GLES30.glGetUniformLocation(program,"uAO");uEmission=GLES30.glGetUniformLocation(program,"uEmission");uUnlit=GLES30.glGetUniformLocation(program,"uUnlit");uNormalStrength=GLES30.glGetUniformLocation(program,"uNormalStrength");uClearCoat=GLES30.glGetUniformLocation(program,"uClearCoat");uClearCoatRoughness=GLES30.glGetUniformLocation(program,"uClearCoatRoughness");uAlbedoTex=GLES30.glGetUniformLocation(program,"uAlbedoTex");uNormalTex=GLES30.glGetUniformLocation(program,"uNormalTex");uRoughTex=GLES30.glGetUniformLocation(program,"uRoughTex");uRoughTexEnabled=GLES30.glGetUniformLocation(program,"uRoughTexEnabled");uShadowMap=GLES30.glGetUniformLocation(program,"uShadowMap");uKeyDirection=GLES30.glGetUniformLocation(program,"uKeyDirection");uKeyColor=GLES30.glGetUniformLocation(program,"uKeyColor");uKeyIntensity=GLES30.glGetUniformLocation(program,"uKeyIntensity");uFillDirection=GLES30.glGetUniformLocation(program,"uFillDirection");uFillColor=GLES30.glGetUniformLocation(program,"uFillColor");uFillIntensity=GLES30.glGetUniformLocation(program,"uFillIntensity");uRimDirection=GLES30.glGetUniformLocation(program,"uRimDirection");uRimColor=GLES30.glGetUniformLocation(program,"uRimColor");uRimIntensity=GLES30.glGetUniformLocation(program,"uRimIntensity");uIBLIntensity=GLES30.glGetUniformLocation(program,"uIBLIntensity");uShadowBias=GLES30.glGetUniformLocation(program,"uShadowBias");uShadowNormalBias=GLES30.glGetUniformLocation(program,"uShadowNormalBias");uReceivesShadow=GLES30.glGetUniformLocation(program,"uReceivesShadow");uShadowInvSize=GLES30.glGetUniformLocation(program,"uShadowInvSize");uPreserveAlbedo=GLES30.glGetUniformLocation(program,"uPreserveAlbedo")
        }
        fun beginFrame(shadowTex:Int,lightVp:FloatArray,woodTex:Int,boardTex:Int,neutralTex:Int,metalTex:Int,normalTex:Int,envCubeTex:Int){
            wood=woodTex;board=boardTex;neutral=neutralTex;metal=metalTex;normal=normalTex
            modelMatrixCache.reset()
            System.arraycopy(lightVp,0,light,0,16)
            // One program bind and frame-invariant uniforms per scene, not per mesh.
            GLES30.glUseProgram(program)
            GLES30.glUniformMatrix4fv(uLightVp,1,false,light,0)
            GLES30.glUniform1f(uShadowNormalBias,renderer.premiumLighting.shadowNormalBias)
            GLES30.glUniform1i(uAlbedoTex,0)
            if (!renderer.softwareRendererActive) {
                GLES30.glUniform1i(uNormalTex,1)
                GLES30.glUniform1i(uRoughTex,4)
            }
            GLES30.glUniform3fv(uKeyDirection,1,renderer.premiumLighting.keyDirection,0)
            GLES30.glUniform3fv(uKeyColor,1,renderer.premiumLighting.keyColor,0)
            GLES30.glUniform1f(uKeyIntensity,PremiumMaterialLighting.Calibration.KEY_INTENSITY)
            GLES30.glUniform3fv(uFillDirection,1,renderer.premiumLighting.fillDirection,0)
            GLES30.glUniform3fv(uFillColor,1,renderer.premiumLighting.fillColor,0)
            GLES30.glUniform1f(uFillIntensity,PremiumMaterialLighting.Calibration.FILL_INTENSITY)
            GLES30.glUniform3fv(uRimDirection,1,renderer.premiumLighting.rimDirection,0)
            GLES30.glUniform3fv(uRimColor,1,renderer.premiumLighting.rimColor,0)
            GLES30.glUniform1f(uRimIntensity,PremiumMaterialLighting.Calibration.RIM_INTENSITY)
            GLES30.glUniform1f(uIBLIntensity,PremiumMaterialLighting.Calibration.AMBIENT_IBL_INTENSITY)
            GLES30.glUniform1f(uShadowBias,PremiumMaterialLighting.Calibration.SHADOW_BIAS)
            GLES30.glUniform2f(uShadowInvSize,1f/renderer.shadowMapResolution,1f/renderer.shadowMapResolution)
            boundAlbedoTexture=Int.MIN_VALUE
            boundNormalTexture=Int.MIN_VALUE
            boundRoughTexture=Int.MIN_VALUE
            GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,shadowTex)
            GLES30.glUniform1i(uShadowMap,2)
            if (!renderer.softwareRendererActive) {
                GLES30.glActiveTexture(GLES30.GL_TEXTURE3)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_CUBE_MAP,envCubeTex)
                GLES30.glUniform1i(uEnvCube,3)
            }
        }
        fun endFrame(){
            GLES30.glBindVertexArray(0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,0)
        }
        fun draw(mesh:Mesh,model:FloatArray,color:FloatArray,rough:Float,metallic:Float,ao:Float,emission:Float,material:Int,unlit:Boolean,albedoOverride:Int=0,normalOverride:Int=0,roughOverride:Int=0,receivesShadow:Boolean=true,preserveAlbedo:Boolean=false){
            val albedo=if(albedoOverride!=0)albedoOverride else when(material){1->wood;2->board;3->neutral;4->metal;else->neutral}
            val normalId=if(normalOverride!=0)normalOverride else normal
            val roughId=if(roughOverride!=0)roughOverride else neutral
            if (modelMatrixCache.shouldUpload(model)) {
                Matrix.multiplyMM(mv,0,renderer.view,0,model,0)
                Matrix.multiplyMM(mvp,0,renderer.proj,0,mv,0)
                GLES30.glUniformMatrix4fv(uMvp,1,false,mvp,0)
                GLES30.glUniformMatrix4fv(uMv,1,false,mv,0)
                GLES30.glUniformMatrix4fv(uModel,1,false,model,0)
                MeshBatcher.inverseTranspose3x3ColumnMajor(model,normalModel)
                GLES30.glUniformMatrix3fv(uNormalModel,1,false,normalModel,0)
            }
            GLES30.glUniform4fv(uColor,1,color,0)
            GLES30.glUniform1f(uMetallic,if(material==4)PremiumMaterialLighting.Calibration.CHROME_METALLIC else metallic)
            GLES30.glUniform1f(uRoughness,if(material==4)PremiumMaterialLighting.Calibration.CHROME_ROUGHNESS else rough)
            GLES30.glUniform1f(
                uReceivesShadow,
                if (renderer.softwareRendererActive) 0f else PbrShadowReception.uniformValue(receivesShadow)
            )
            GLES30.glUniform1f(uPreserveAlbedo,if(preserveAlbedo)1f else 0f)
            GLES30.glUniform1f(uAO,ao)
            GLES30.glUniform1f(uEmission,emission)
            GLES30.glUniform1f(uUnlit,if(unlit)1f else 0f)
            if (!renderer.softwareRendererActive) {
                GLES30.glUniform1f(uNormalStrength,if(material==4)PremiumMaterialLighting.die().normalStrength else if(material==6)0.40f else 0.20f)
                GLES30.glUniform1f(uClearCoat,when(material){4->PremiumMaterialLighting.Calibration.CHROME_CLEAR_COAT;3->0.34f;2->0.16f;else->0.10f})
                GLES30.glUniform1f(uClearCoatRoughness,when(material){4->PremiumMaterialLighting.Calibration.CHROME_CLEAR_COAT_ROUGHNESS;3->0.12f;else->0.18f})
            }
            if (boundAlbedoTexture != albedo) {
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,albedo)
                boundAlbedoTexture=albedo
            }
            if (!renderer.softwareRendererActive) {
                if (boundNormalTexture != normalId) {
                    GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
                    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,normalId)
                    boundNormalTexture=normalId
                }
                if (boundRoughTexture != roughId) {
                    GLES30.glActiveTexture(GLES30.GL_TEXTURE4)
                    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,roughId)
                    boundRoughTexture=roughId
                }
                GLES30.glUniform1f(uRoughTexEnabled,if(roughOverride!=0)1f else 0f)
            }
            mesh.bindGles3Static()
            GLES30.glDrawElements(GLES30.GL_TRIANGLES,mesh.indices.size,GLES30.GL_UNSIGNED_SHORT,0)
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
        fun begin(v:FloatArray){System.arraycopy(v,0,lightVp,0,16);GLES30.glUseProgram(program)}
        fun end(){GLES30.glBindVertexArray(0)}
        fun draw(mesh:Mesh,model:FloatArray){
            // begin() binds the shadow program once for the complete depth pass.
            Matrix.multiplyMM(scratch,0,lightVp,0,model,0)
            GLES30.glUniformMatrix4fv(uM,1,false,scratch,0)
            mesh.bindGles3Static()
            GLES30.glDrawElements(GLES30.GL_TRIANGLES,mesh.indices.size,GLES30.GL_UNSIGNED_SHORT,0)
        }
        private fun buildProgram(vs:String,fs:String):Int{fun c(t:Int,s:String):Int{val id=GLES30.glCreateShader(t);GLES30.glShaderSource(id,s);GLES30.glCompileShader(id);val o=IntArray(1);GLES30.glGetShaderiv(id,GLES30.GL_COMPILE_STATUS,o,0);if(o[0]==0)throw IllegalStateException(GLES30.glGetShaderInfoLog(id));return id};val v=c(GLES30.GL_VERTEX_SHADER,vs);val f=c(GLES30.GL_FRAGMENT_SHADER,fs);val p=GLES30.glCreateProgram();GLES30.glAttachShader(p,v);GLES30.glAttachShader(p,f);GLES30.glLinkProgram(p);val o=IntArray(1);GLES30.glGetProgramiv(p,GLES30.GL_LINK_STATUS,o,0);if(o[0]==0)throw IllegalStateException(GLES30.glGetProgramInfoLog(p));GLES30.glDeleteShader(v);GLES30.glDeleteShader(f);return p}
    }

    private class PostShader(private val softwareFastPath: Boolean){
        private val program:Int;private val scene:Int;private val depth:Int;private val res:Int;private val ssao:Int;private val bloom:Int;private val pos:FloatBuffer;private val uv:FloatBuffer
        init{val v=floatArrayOf(-1f,-1f,0f,1f,-1f,0f,-1f,1f,0f,1f,1f,0f);val u=floatArrayOf(0f,1f,1f,1f,0f,0f,1f,0f);pos=ByteBuffer.allocateDirect(v.size*4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply{put(v);position(0)};uv=ByteBuffer.allocateDirect(u.size*4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply{put(u);position(0)}
            val vs="""#version 300 es
                layout(location=0)in vec3 aPos;layout(location=1)in vec2 aUv;out vec2 vUv;void main(){vUv=aUv;gl_Position=vec4(aPos,1.0);}
            """.trimIndent()
            val premiumFs="""#version 300 es
                precision highp float;uniform sampler2D uScene,uDepth;uniform vec2 uInvRes;uniform float uSsao,uBloom;in vec2 vUv;layout(location=0)out vec4 fragColor;
                float luma(vec3 c){return dot(c,vec3(0.299,0.587,0.114));}vec3 aces(vec3 x){const float a=2.51;const float b=0.03;const float c=2.43;const float d=0.59;const float e=0.14;return clamp((x*(a*x+b))/(x*(c*x+d)+e),0.0,1.0);}
                void main(){vec4 sceneSample=texture(uScene,vUv);if(sceneSample.a<=0.001){fragColor=vec4(0.0);return;}vec3 base=sceneSample.rgb,bl=vec3(0);float w[5]=float[](0.32,0.20,0.12,0.08,0.05);for(int i=1;i<=4;i++){vec2 p=uInvRes*float(i)*1.6;bl+=(texture(uScene,vUv+vec2(p.x,0)).rgb+texture(uScene,vUv-vec2(p.x,0)).rgb+texture(uScene,vUv+vec2(0,p.y)).rgb+texture(uScene,vUv-vec2(0,p.y)).rgb)*w[i];}bl*=max(luma(base)-0.68,0.0)*uBloom;float d0=texture(uDepth,vUv).r,occ=0.0;vec2 o[8]=vec2[](vec2(1,0),vec2(-1,0),vec2(0,1),vec2(0,-1),vec2(1,1),vec2(-1,1),vec2(1,-1),vec2(-1,-1));for(int i=0;i<8;i++){float dn=texture(uDepth,vUv+o[i]*uInvRes*2.0).r;occ+=smoothstep(0.0,0.025,max(0.0,dn-d0));}float ao=1.0-clamp(occ/8.0*uSsao,0.0,0.34);vec3 color=aces((base+bl)*ao);color=pow(max(color,vec3(0)),vec3(0.4545));float y=luma(color);color=mix(color,vec3(y),0.025)+vec3(0.010,0.005,0.0);fragColor=vec4(color,sceneSample.a);}
            """.trimIndent()
            val softwareFs="""#version 300 es
                precision mediump float;
                uniform sampler2D uScene;
                in vec2 vUv;
                layout(location=0)out vec4 fragColor;
                vec3 aces(vec3 x){
                    const float a=2.51;const float b=0.03;const float c=2.43;const float d=0.59;const float e=0.14;
                    return clamp((x*(a*x+b))/(x*(c*x+d)+e),0.0,1.0);
                }
                void main(){
                    vec4 sceneSample=texture(uScene,vUv);
                    if(sceneSample.a<=0.001){fragColor=vec4(0.0);return;}
                    vec3 color=aces(sceneSample.rgb);
                    color=pow(max(color,vec3(0.0)),vec3(0.4545));
                    float y=dot(color,vec3(0.299,0.587,0.114));
                    color=mix(color,vec3(y),0.025)+vec3(0.010,0.005,0.0);
                    fragColor=vec4(color,sceneSample.a);
                }
            """.trimIndent()
            val fs=if(softwareFastPath)softwareFs else premiumFs
            program=buildProgram(vs,fs);scene=GLES30.glGetUniformLocation(program,"uScene");depth=GLES30.glGetUniformLocation(program,"uDepth");res=GLES30.glGetUniformLocation(program,"uInvRes");ssao=GLES30.glGetUniformLocation(program,"uSsao");bloom=GLES30.glGetUniformLocation(program,"uBloom")
        }
        fun draw(sceneTex:Int,depthTex:Int,w:Int,h:Int,ao:Float,bl:Float){
            GLES30.glUseProgram(program)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,sceneTex)
            GLES30.glUniform1i(scene,0)
            if(!softwareFastPath){
                GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,depthTex)
                GLES30.glUniform1i(depth,1)
                GLES30.glUniform2f(res,1f/w,1f/h)
                GLES30.glUniform1f(ssao,ao)
                GLES30.glUniform1f(bloom,bl)
            }
            GLES30.glDisable(GLES30.GL_CULL_FACE)
            GLES30.glEnableVertexAttribArray(0)
            GLES30.glEnableVertexAttribArray(1)
            GLES30.glVertexAttribPointer(0,3,GLES30.GL_FLOAT,false,0,pos)
            GLES30.glVertexAttribPointer(1,2,GLES30.GL_FLOAT,false,0,uv)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP,0,4)
            GLES30.glDisableVertexAttribArray(0)
            GLES30.glDisableVertexAttribArray(1)
            GLES30.glEnable(GLES30.GL_CULL_FACE)
        }

        private fun buildProgram(vs:String,fs:String):Int{fun c(t:Int,s:String):Int{val id=GLES30.glCreateShader(t);GLES30.glShaderSource(id,s);GLES30.glCompileShader(id);val o=IntArray(1);GLES30.glGetShaderiv(id,GLES30.GL_COMPILE_STATUS,o,0);if(o[0]==0)throw IllegalStateException(GLES30.glGetShaderInfoLog(id));return id};val v=c(GLES30.GL_VERTEX_SHADER,vs);val f=c(GLES30.GL_FRAGMENT_SHADER,fs);val p=GLES30.glCreateProgram();GLES30.glAttachShader(p,v);GLES30.glAttachShader(p,f);GLES30.glLinkProgram(p);val o=IntArray(1);GLES30.glGetProgramiv(p,GLES30.GL_LINK_STATUS,o,0);if(o[0]==0)throw IllegalStateException(GLES30.glGetProgramInfoLog(p));GLES30.glDeleteShader(v);GLES30.glDeleteShader(f);return p}
    }

    private class ParticleShader{
        private val program:Int;private val vp:Int;private val buffer=ByteBuffer.allocateDirect(128*8*4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        init{val vs="""#version 300 es
            layout(location=0)in vec3 aPos;layout(location=1)in vec4 aColor;layout(location=2)in float aSize;uniform mat4 uVp;out vec4 vColor;void main(){gl_Position=uVp*vec4(aPos,1.0);gl_PointSize=aSize;vColor=aColor;}
        """.trimIndent();val fs="""#version 300 es
            precision mediump float;in vec4 vColor;layout(location=0)out vec4 fragColor;void main(){vec2 p=gl_PointCoord*2.0-1.0;float r=dot(p,p);if(r>1.0)discard;fragColor=vec4(vColor.rgb*1.12,(1.0-r)*vColor.a);}
        """.trimIndent();program=buildProgram(vs,fs);vp=GLES30.glGetUniformLocation(program,"uVp")}
        fun draw(data:FloatArray,count:Int,vbo:Int,viewProj:FloatArray,timings:LongArray?=null){
            var started = if (timings != null) System.nanoTime() else 0L
            buffer.position(0);buffer.put(data,0,count*8);buffer.position(0)
            if (timings != null) { timings[0] = System.nanoTime() - started; started = System.nanoTime() }
            GLES30.glUseProgram(program)
            GLES30.glUniformMatrix4fv(vp,1,false,viewProj,0)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,vbo)
            GLES30.glEnableVertexAttribArray(0);GLES30.glEnableVertexAttribArray(1);GLES30.glEnableVertexAttribArray(2)
            GLES30.glVertexAttribPointer(0,3,GLES30.GL_FLOAT,false,8*4,0)
            GLES30.glVertexAttribPointer(1,4,GLES30.GL_FLOAT,false,8*4,3*4)
            GLES30.glVertexAttribPointer(2,1,GLES30.GL_FLOAT,false,8*4,7*4)
            // GLES 3 uses gl_PointSize from the vertex shader directly; no enable capability is required.
            GLES30.glDepthMask(false)
            if (timings != null) { timings[1] = System.nanoTime() - started; started = System.nanoTime() }
            GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, count * 8 * 4, buffer)
            if (timings != null) { timings[2] = System.nanoTime() - started; started = System.nanoTime() }
            GLES30.glDrawArrays(GLES30.GL_POINTS,0,count)
            if (timings != null) { timings[3] = System.nanoTime() - started; started = System.nanoTime() }
            GLES30.glDepthMask(true)
            GLES30.glDisableVertexAttribArray(0);GLES30.glDisableVertexAttribArray(1);GLES30.glDisableVertexAttribArray(2)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,0)
            if (timings != null) timings[4] = System.nanoTime() - started
        }
        private fun buildProgram(vs:String,fs:String):Int{fun c(t:Int,s:String):Int{val id=GLES30.glCreateShader(t);GLES30.glShaderSource(id,s);GLES30.glCompileShader(id);val o=IntArray(1);GLES30.glGetShaderiv(id,GLES30.GL_COMPILE_STATUS,o,0);if(o[0]==0)throw IllegalStateException(GLES30.glGetShaderInfoLog(id));return id};val v=c(GLES30.GL_VERTEX_SHADER,vs);val f=c(GLES30.GL_FRAGMENT_SHADER,fs);val p=GLES30.glCreateProgram();GLES30.glAttachShader(p,v);GLES30.glAttachShader(p,f);GLES30.glLinkProgram(p);val o=IntArray(1);GLES30.glGetProgramiv(p,GLES30.GL_LINK_STATUS,o,0);if(o[0]==0)throw IllegalStateException(GLES30.glGetProgramInfoLog(p));GLES30.glDeleteShader(v);GLES30.glDeleteShader(f);return p}
    }

}
