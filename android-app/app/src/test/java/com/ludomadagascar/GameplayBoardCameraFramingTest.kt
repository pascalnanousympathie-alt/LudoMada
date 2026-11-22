package com.ludomadagascar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.atan
import kotlin.math.tan

class GameplayBoardCameraFramingTest {
    @Test
    fun wideLandscapeUsesTheFullBoardVerticalFieldOfView() {
        assertEquals(38f, gameplayBoardVerticalFov(1419f / 424f, homePreview = false), 0.001f)
        assertEquals(38f, gameplayBoardVerticalFov(16f / 9f, homePreview = false), 0.001f)
    }

    @Test
    fun portraitKeepsTheTargetHorizontalFieldOfView() {
        val aspect = 720f / 1616f
        val verticalFov = gameplayBoardVerticalFov(aspect, homePreview = false)
        val horizontalFov = 2.0 * atan(
            tan(Math.toRadians(verticalFov.toDouble() * 0.5)) * aspect.toDouble()
        )
        assertEquals(42.0, Math.toDegrees(horizontalFov), 0.1)
    }

    @Test
    fun homePreviewAndSquareViewportsKeepTheirExistingFraming() {
        assertEquals(38f, gameplayBoardVerticalFov(16f / 9f, homePreview = true), 0.001f)
        assertEquals(34f, gameplayBoardVerticalFov(1f, homePreview = false), 0.001f)
        assertTrue(gameplayBoardVerticalFov(720f / 1616f, homePreview = true) in 34f..104f)
    }
}
