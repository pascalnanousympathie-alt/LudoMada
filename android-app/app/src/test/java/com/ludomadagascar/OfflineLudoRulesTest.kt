package com.ludomadagascar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineLudoRulesTest {
    @Test
    fun tokenMustRollSixToLeaveHome() {
        assertFalse(OfflineLudoRules.canMove(-1, 5))
        assertTrue(OfflineLudoRules.canMove(-1, 6))
        assertEquals(0, OfflineLudoRules.nextPosition(-1, 6))
        assertNull(OfflineLudoRules.nextPosition(-1, 5))
    }

    @Test
    fun finishRequiresExactDiceValue() {
        assertEquals(57, OfflineLudoRules.nextPosition(56, 1))
        assertNull(OfflineLudoRules.nextPosition(56, 2))
        assertFalse(OfflineLudoRules.canMove(57, 1))
    }

    @Test
    fun diceOutsideOneThroughSixIsNeverLegal() {
        for (dice in listOf(-1, 0, 7, 99)) {
            assertFalse("dice=$dice", OfflineLudoRules.canMove(-1, dice))
            assertFalse("dice=$dice", OfflineLudoRules.canMove(10, dice))
        }
    }

    @Test
    fun playerPathsWrapOnTheSharedFiftyTwoSquareTrack() {
        assertEquals(0, OfflineLudoRules.globalSquare(0, 0))
        assertEquals(13, OfflineLudoRules.globalSquare(1, 0))
        assertEquals(26, OfflineLudoRules.globalSquare(2, 0))
        assertEquals(39, OfflineLudoRules.globalSquare(3, 0))
        assertEquals(0, OfflineLudoRules.globalSquare(1, 39))
        assertNull(OfflineLudoRules.globalSquare(4, 0))
        assertNull(OfflineLudoRules.globalSquare(0, 52))
    }

    @Test
    fun safeSquaresAreProtectedFromCapture() {
        for (square in listOf(0, 8, 13, 21, 26, 34, 39, 47)) {
            assertTrue("square=$square", OfflineLudoRules.isSafeSquare(square))
        }
        assertFalse(OfflineLudoRules.isSafeSquare(1))
        assertFalse(OfflineLudoRules.isSafeSquare(51))
    }

    @Test
    fun detectsWhetherAnyTokenCanMove() {
        assertTrue(OfflineLudoRules.hasLegalMove(listOf(-1, 55, 57), 2))
        assertFalse(OfflineLudoRules.hasLegalMove(listOf(-1, 57), 2))
        assertFalse(OfflineLudoRules.hasLegalMove(listOf(0, 10), 7))
    }

    @Test
    fun sixOrCaptureGrantsAnExtraTurn() {
        assertTrue(OfflineLudoRules.keepsTurn(6, 0))
        assertTrue(OfflineLudoRules.keepsTurn(3, 1))
        assertFalse(OfflineLudoRules.keepsTurn(3, 0))
    }

    @Test
    fun winRequiresAllFourTokensToFinish() {
        assertTrue(OfflineLudoRules.hasWinner(intArrayOf(57, 57, 57, 57)))
        assertFalse(OfflineLudoRules.hasWinner(intArrayOf(57, 57, 57, 56)))
        assertFalse(OfflineLudoRules.hasWinner(intArrayOf(57, 57, 57)))
    }
}
