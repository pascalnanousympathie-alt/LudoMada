package com.ludomadagascar

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PawnMoveSoundPolicyTest {
    @Test
    fun firstFrameDoesNotEmitPawnSound() {
        assertFalse(PawnMoveSoundPolicy.shouldPlay(Int.MIN_VALUE, -1))
        assertFalse(PawnMoveSoundPolicy.shouldPlay(Int.MIN_VALUE, 0))
    }

    @Test
    fun unchangedPositionDoesNotEmitPawnSound() {
        assertFalse(PawnMoveSoundPolicy.shouldPlay(-1, -1))
        assertFalse(PawnMoveSoundPolicy.shouldPlay(12, 12))
    }

    @Test
    fun exitMovementAndNormalMovementEmitPawnSound() {
        assertTrue(PawnMoveSoundPolicy.shouldPlay(-1, 0))
        assertTrue(PawnMoveSoundPolicy.shouldPlay(12, 13))
        assertTrue(PawnMoveSoundPolicy.shouldPlay(56, 57))
    }

    @Test
    fun capturedPawnReturningHomeEmitsPawnSound() {
        assertTrue(PawnMoveSoundPolicy.shouldPlay(24, -1))
    }
}
