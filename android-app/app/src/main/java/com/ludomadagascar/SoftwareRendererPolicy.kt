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
    const val SOFTWARE_RENDER_SCALE = 0.375f
    const val SOFTWARE_SHADOW_MAP_MAX = 256

    fun isSoftwareRenderer(rendererIdentity: String): Boolean =
        listOf("swiftshader", "llvmpipe", "softpipe", "lavapipe")
            .any { rendererIdentity.contains(it, ignoreCase = true) }

    fun useFastPbrShader(rendererIdentity: String, homePreview: Boolean): Boolean =
        !homePreview && isSoftwareRenderer(rendererIdentity)

    fun renderScale(rendererIdentity: String, homePreview: Boolean): Float =
        if (!homePreview && isSoftwareRenderer(rendererIdentity)) {
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
