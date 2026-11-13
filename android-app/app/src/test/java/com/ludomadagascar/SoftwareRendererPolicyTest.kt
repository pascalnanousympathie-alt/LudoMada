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
        assertEquals(355, SoftwareRendererPolicy.scaledDimension(1419, SoftwareRendererPolicy.SOFTWARE_SURFACE_SCALE))
        assertEquals(106, SoftwareRendererPolicy.scaledDimension(424, SoftwareRendererPolicy.SOFTWARE_SURFACE_SCALE))
    }

    @Test
    fun uncappedEglSwapIntervalIsRequestedOnlyForDebugSoftwareGameplay() {
        for (renderer in listOf("Google SwiftShader", "llvmpipe", "softpipe", "lavapipe")) {
            assertTrue(SoftwareRendererPolicy.shouldRequestUncappedSwapInterval(
                renderer, homePreview = false, debugBuild = true
            ))
        }
        assertFalse(SoftwareRendererPolicy.shouldRequestUncappedSwapInterval(
            "Google SwiftShader", homePreview = false, debugBuild = false
        ))
        assertFalse(SoftwareRendererPolicy.shouldRequestUncappedSwapInterval(
            "Google SwiftShader", homePreview = true, debugBuild = true
        ))
        assertFalse(SoftwareRendererPolicy.shouldRequestUncappedSwapInterval(
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
        val width = SoftwareRendererPolicy.scaledDimension(1419, 0.25f)
        val height = SoftwareRendererPolicy.scaledDimension(424, 0.25f)
        assertEquals(355, width)
        assertEquals(106, height)
        assertTrue(width > height)
        assertEquals(1, SoftwareRendererPolicy.scaledDimension(1, 0.25f))
    }
}
