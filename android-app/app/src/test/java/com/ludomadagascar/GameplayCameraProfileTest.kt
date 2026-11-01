package com.ludomadagascar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GameplayCameraProfileTest {
    @Test
    fun portraitProfileIsObliqueRatherThanNearTopDown() {
        val profile = GameplayCameraProfile.forAspect(720f / 1616f)

        assertEquals(10.6f, profile.eyeY, 0f)
        assertEquals(6.0f, profile.eyeZ, 0f)
        assertEquals(47f, profile.horizontalFov, 0f)
        assertTrue(
            "Portrait camera elevation should be oblique (actual=${profile.elevationDegrees})",
            profile.elevationDegrees in 58f..63f
        )
    }

    @Test
    fun landscapeProfileRetainsTheEstablishedCameraCalibration() {
        val profile = GameplayCameraProfile.forAspect(16f / 9f)

        assertEquals(11.8f, profile.eyeY, 0f)
        assertEquals(8.8f, profile.eyeZ, 0f)
        assertEquals(0.12f, profile.targetY, 0f)
        assertEquals(45f, profile.horizontalFov, 0f)
        assertTrue(profile.elevationDegrees in 51f..55f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidAspectRatio() {
        GameplayCameraProfile.forAspect(0f)
    }
}
