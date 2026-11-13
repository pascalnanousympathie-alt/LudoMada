package com.ludomadagascar

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PawnAnimationPolicyTest {
    @Test
    fun unchangedOrUninitializedTokensDoNotStartMovementAnimation() {
        assertFalse(PawnAnimationPolicy.shouldAnimate(Int.MIN_VALUE, 12))
        assertFalse(PawnAnimationPolicy.shouldAnimate(-1, -1))
        assertFalse(PawnAnimationPolicy.shouldAnimate(0, 0))
        assertFalse(PawnAnimationPolicy.shouldAnimate(57, 57))
    }

    @Test
    fun yardEntryMovementAndCaptureTransitionsAnimate() {
        assertTrue(PawnAnimationPolicy.shouldAnimate(-1, 0))
        assertTrue(PawnAnimationPolicy.shouldAnimate(0, 1))
        assertTrue(PawnAnimationPolicy.shouldAnimate(52, 57))
        assertTrue(PawnAnimationPolicy.shouldAnimate(12, -1))
        assertTrue(PawnAnimationPolicy.shouldAnimate(8, 5))
    }
}
