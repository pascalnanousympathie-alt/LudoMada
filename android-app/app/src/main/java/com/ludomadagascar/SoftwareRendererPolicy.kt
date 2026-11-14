package com.ludomadagascar

/**
 * Keeps the full-resolution premium renderer on hardware GPUs.
 *
 * SwiftShader/llvmpipe are software rasterizers used by cloud emulator tests.
 * They need a bounded internal render target so the same GLES3 scene can be
 * exercised without pretending software rasterization has mobile-GPU speed.
 * The post-process pass still presents into the full-size surface.
 */
internal object SoftwareRendererPolicy {
    const val SOFTWARE_RENDER_SCALE = 0.25f
    const val SOFTWARE_SURFACE_SCALE = 0.125f
    const val SOFTWARE_SHADOW_MAP_MAX = 128
    const val SOFTWARE_ANIMATION_FRAME_INTERVAL_NANOS = 33_333_333L
    const val SOFTWARE_EGL_SWAP_INTERVAL = 1

    fun isSoftwareRenderer(rendererIdentity: String): Boolean =
        listOf("swiftshader", "llvmpipe", "softpipe", "lavapipe")
            .any { rendererIdentity.contains(it, ignoreCase = true) }

    /**
     * A software driver's fence signal delay includes host-emulator scheduling,
     * so it is not a useful GPU-queue measurement and adds work to the FPS run.
     */
    fun shouldMeasureGpuFence(rendererIdentity: String, homePreview: Boolean): Boolean =
        !homePreview && !isSoftwareRenderer(rendererIdentity)

    fun shouldUseReducedSurfaceBuffer(rendererIdentity: String, homePreview: Boolean): Boolean =
        !homePreview && isSoftwareRenderer(rendererIdentity)

    /**
     * Run debug software-renderer gameplay on a 30 FPS GL-thread loop with a
     * vsync-synchronized EGL swap. Hardware and Home retain their native render cadence.
     */
    fun shouldUseFramePacedSoftwareRendering(
        rendererIdentity: String,
        homePreview: Boolean,
        debugBuild: Boolean
    ): Boolean = debugBuild && !homePreview && isSoftwareRenderer(rendererIdentity)

    fun shouldUseGlThreadAnimationPacing(
        rendererIdentity: String,
        homePreview: Boolean,
        debugBuild: Boolean
    ): Boolean = shouldUseFramePacedSoftwareRendering(rendererIdentity, homePreview, debugBuild)

    fun shouldRequestVsyncSwapInterval(
        rendererIdentity: String,
        homePreview: Boolean,
        debugBuild: Boolean
    ): Boolean = shouldUseFramePacedSoftwareRendering(rendererIdentity, homePreview, debugBuild)

    /** Software rasterizers skip per-frame dynamic depth rendering during gameplay. */
    fun shouldRenderDynamicShadows(rendererIdentity: String, homePreview: Boolean): Boolean =
        homePreview || !isSoftwareRenderer(rendererIdentity)

    fun useFastPbrShader(rendererIdentity: String, homePreview: Boolean): Boolean =
        !homePreview && isSoftwareRenderer(rendererIdentity)

    /** The cloud software rasterizer cannot afford the premium full-surface bloom/SSAO pass. */
    fun useFastPostProcess(rendererIdentity: String, homePreview: Boolean): Boolean =
        !homePreview && isSoftwareRenderer(rendererIdentity)

    fun renderScale(
        rendererIdentity: String,
        homePreview: Boolean,
        outputSurfaceAlreadyScaled: Boolean = false
    ): Float =
        if (!homePreview && isSoftwareRenderer(rendererIdentity) && !outputSurfaceAlreadyScaled) {
            SOFTWARE_RENDER_SCALE
        } else {
            1f
        }

    fun shadowMapResolution(
        rendererIdentity: String,
        configuredResolution: Int,
        homePreview: Boolean
    ): Int {
        require(configuredResolution > 0) { "Shadow map resolution must be positive" }
        return if (!homePreview && isSoftwareRenderer(rendererIdentity)) {
            minOf(configuredResolution, SOFTWARE_SHADOW_MAP_MAX)
        } else {
            configuredResolution
        }
    }

    fun scaledDimension(surfaceDimension: Int, scale: Float): Int {
        require(surfaceDimension > 0) { "Surface dimension must be positive" }
        require(scale in 0.01f..1f) { "Render scale must be in (0, 1]" }
        return ((surfaceDimension * scale) + 0.5f).toInt().coerceAtLeast(1)
    }
}
