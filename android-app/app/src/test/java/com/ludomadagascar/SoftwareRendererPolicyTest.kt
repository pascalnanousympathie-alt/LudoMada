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
            assertEquals(0.375f, SoftwareRendererPolicy.renderScale(renderer, homePreview = false))
            assertEquals(
                256,
                SoftwareRendererPolicy.shadowMapResolution(renderer, 512, homePreview = false)
            )
        }
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
        val width = SoftwareRendererPolicy.scaledDimension(1419, 0.375f)
        val height = SoftwareRendererPolicy.scaledDimension(424, 0.375f)
        assertEquals(532, width)
        assertEquals(159, height)
        assertTrue(width > height)
        assertEquals(1, SoftwareRendererPolicy.scaledDimension(1, 0.375f))
    }
}
