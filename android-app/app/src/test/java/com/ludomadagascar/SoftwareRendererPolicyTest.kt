package com.ludomadagascar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SoftwareRendererPolicyTest {
    @Test
    fun softwareRasterizersUseReducedInternalResolutionAndShadowMap() {
        for (renderer in listOf(
            "Google SwiftShader",
            "Android Emulator OpenGL ES Translator (Google SwiftShader)",
            "llvmpipe (LLVM 18.1.2, 256 bits)",
            "softpipe",
            "lavapipe"
        )) {
            assertTrue(renderer, SoftwareRendererPolicy.isSoftwareRenderer(renderer))
            assertEquals(0.25f, SoftwareRendererPolicy.renderScale(renderer, homePreview = false))
            assertEquals(
                128,
                SoftwareRendererPolicy.shadowMapResolution(renderer, 512, homePreview = false)
            )
            assertEquals(
                96,
                SoftwareRendererPolicy.shadowMapResolution(renderer, 96, homePreview = false)
            )
        }
    }

    @Test
    fun reducedOutputSurfaceIsOnlyUsedForSoftwareGameplay() {
        for (renderer in listOf("Google SwiftShader", "llvmpipe", "softpipe", "lavapipe")) {
            assertTrue(SoftwareRendererPolicy.shouldUseReducedSurfaceBuffer(renderer, homePreview = false))
        }
        assertFalse(SoftwareRendererPolicy.shouldUseReducedSurfaceBuffer("Mali-G51", homePreview = false))
        assertFalse(SoftwareRendererPolicy.shouldUseReducedSurfaceBuffer("Google SwiftShader", homePreview = true))
        assertEquals(177, SoftwareRendererPolicy.scaledDimension(1419, SoftwareRendererPolicy.SOFTWARE_SURFACE_SCALE))
        assertEquals(53, SoftwareRendererPolicy.scaledDimension(424, SoftwareRendererPolicy.SOFTWARE_SURFACE_SCALE))
    }

    @Test
    fun softwareFramePacingAndVsyncSwapAreEnabledTogetherOnlyForDebugGameplay() {
        assertEquals(33_333_333L, SoftwareRendererPolicy.SOFTWARE_ANIMATION_FRAME_INTERVAL_NANOS)
        assertEquals(1, SoftwareRendererPolicy.SOFTWARE_EGL_SWAP_INTERVAL)
        for (renderer in listOf("Google SwiftShader", "llvmpipe", "softpipe", "lavapipe")) {
            assertTrue(
                renderer,
                SoftwareRendererPolicy.shouldUseFramePacedSoftwareRendering(
                    renderer, homePreview = false, debugBuild = true
                )
            )
            assertTrue(
                renderer,
                SoftwareRendererPolicy.shouldUseGlThreadAnimationPacing(
                    renderer, homePreview = false, debugBuild = true
                )
            )
            assertTrue(
                renderer,
                SoftwareRendererPolicy.shouldRequestVsyncSwapInterval(
                    renderer, homePreview = false, debugBuild = true
                )
            )
        }

        assertFalse(SoftwareRendererPolicy.shouldUseFramePacedSoftwareRendering(
            "Google SwiftShader", homePreview = false, debugBuild = false
        ))
        assertFalse(SoftwareRendererPolicy.shouldUseFramePacedSoftwareRendering(
            "Google SwiftShader", homePreview = true, debugBuild = true
        ))
        assertFalse(SoftwareRendererPolicy.shouldUseFramePacedSoftwareRendering(
            "Mali-G51", homePreview = false, debugBuild = true
        ))
    }

    @Test
    fun reducedSurfaceBufferAvoidsApplyingTheSceneScaleTwice() {
        assertEquals(0.25f, SoftwareRendererPolicy.renderScale("Google SwiftShader", homePreview = false))
        assertEquals(
            1f,
            SoftwareRendererPolicy.renderScale(
                "Google SwiftShader",
                homePreview = false,
                outputSurfaceAlreadyScaled = true
            )
        )
        assertEquals(
            1f,
            SoftwareRendererPolicy.renderScale(
                "Mali-G51",
                homePreview = false,
                outputSurfaceAlreadyScaled = true
            )
        )
    }

    @Test
    fun dynamicShadowPassIsDisabledOnlyForSoftwareGameplay() {
        assertFalse(SoftwareRendererPolicy.shouldRenderDynamicShadows("Google SwiftShader", homePreview = false))
        assertFalse(SoftwareRendererPolicy.shouldRenderDynamicShadows("llvmpipe", homePreview = false))
        assertTrue(SoftwareRendererPolicy.shouldRenderDynamicShadows("Mali-G51", homePreview = false))
        assertTrue(SoftwareRendererPolicy.shouldRenderDynamicShadows("Google SwiftShader", homePreview = true))
    }

    @Test
    fun fastPbrShaderIsRestrictedToSoftwareGameplayAndNeverHomePreview() {
        assertTrue(SoftwareRendererPolicy.useFastPbrShader("Google SwiftShader", homePreview = false))
        assertTrue(SoftwareRendererPolicy.useFastPbrShader("llvmpipe", homePreview = false))
        assertFalse(SoftwareRendererPolicy.useFastPbrShader("Mali-G51", homePreview = false))
        assertFalse(SoftwareRendererPolicy.useFastPbrShader("Google SwiftShader", homePreview = true))
    }

    @Test
    fun fastPostProcessIsRestrictedToSoftwareGameplayAndNeverHomePreview() {
        assertTrue(SoftwareRendererPolicy.useFastPostProcess("Google SwiftShader", homePreview = false))
        assertTrue(SoftwareRendererPolicy.useFastPostProcess("llvmpipe", homePreview = false))
        assertFalse(SoftwareRendererPolicy.useFastPostProcess("Mali-G51", homePreview = false))
        assertFalse(SoftwareRendererPolicy.useFastPostProcess("Google SwiftShader", homePreview = true))
    }

    @Test
    fun gpuFenceMeasurementIsReservedForHardwareGameplayRenderers() {
        assertFalse(SoftwareRendererPolicy.shouldMeasureGpuFence("Google SwiftShader", homePreview = false))
        assertFalse(SoftwareRendererPolicy.shouldMeasureGpuFence("llvmpipe", homePreview = false))
        assertFalse(SoftwareRendererPolicy.shouldMeasureGpuFence("Mali-G51", homePreview = true))
        assertTrue(SoftwareRendererPolicy.shouldMeasureGpuFence("Mali-G51", homePreview = false))
    }

    @Test
    fun hardwareRendererKeepsFullResolutionAndConfiguredShadows() {
        val renderer = "Mali-G51"
        assertFalse(SoftwareRendererPolicy.isSoftwareRenderer(renderer))
        assertEquals(1f, SoftwareRendererPolicy.renderScale(renderer, homePreview = false))
        assertEquals(512, SoftwareRendererPolicy.shadowMapResolution(renderer, 512, homePreview = false))
    }

    @Test
    fun homePreviewKeepsFullResolutionEvenOnSoftwareRenderer() {
        val renderer = "Google SwiftShader"
        assertEquals(1f, SoftwareRendererPolicy.renderScale(renderer, homePreview = true))
        assertEquals(512, SoftwareRendererPolicy.shadowMapResolution(renderer, 512, homePreview = true))
    }

    @Test
    fun scaledDimensionsStayPositiveAndPreserveLandscapeProportionWithinRounding() {
        val width = SoftwareRendererPolicy.scaledDimension(1419, 0.125f)
        val height = SoftwareRendererPolicy.scaledDimension(424, 0.125f)
        assertEquals(177, width)
        assertEquals(53, height)
        assertTrue(width > height)
        assertEquals(1, SoftwareRendererPolicy.scaledDimension(1, 0.125f))
    }
}
