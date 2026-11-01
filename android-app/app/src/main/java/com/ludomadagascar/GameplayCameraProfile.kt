package com.ludomadagascar

import kotlin.math.atan2

/**
 * Shared gameplay camera calibration for both GLES2 fallback and GLES3/PBR.
 * Portrait framing uses a visibly oblique view while preserving the complete
 * board and all four corner dice in the viewport.
 */
internal data class GameplayCameraProfile(
    val eyeY: Float,
    val eyeZ: Float,
    val targetY: Float,
    val horizontalFov: Float
) {
    val elevationDegrees: Float
        get() = Math.toDegrees(
            atan2((eyeY - targetY).toDouble(), eyeZ.toDouble())
        ).toFloat()

    companion object {
        private val PORTRAIT = GameplayCameraProfile(
            eyeY = 10.6f,
            eyeZ = 6.0f,
            targetY = 0.18f,
            horizontalFov = 47f
        )

        private val LANDSCAPE = GameplayCameraProfile(
            eyeY = 11.8f,
            eyeZ = 8.8f,
            targetY = 0.12f,
            horizontalFov = 45f
        )

        fun forAspect(aspect: Float): GameplayCameraProfile {
            require(aspect.isFinite() && aspect > 0f) {
                "Camera aspect ratio must be finite and positive"
            }
            return if (aspect > 1.35f) LANDSCAPE else PORTRAIT
        }
    }
}
