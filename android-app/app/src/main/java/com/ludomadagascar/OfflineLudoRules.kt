package com.ludomadagascar

/**
 * Pure rules used by the offline 2–4 player game.
 *
 * Kept independent of Android views and JSON so rule regressions can be
 * caught by fast JVM unit tests as well as the device-level smoke test.
 */
internal object OfflineLudoRules {
    const val TRACK_LAST_POSITION = 51
    const val FINISH_POSITION = 57
    const val TOKENS_PER_PLAYER = 4
    private val safeSquares = setOf(0, 8, 13, 21, 26, 34, 39, 47)

    /** A token at a negative position is in home and needs a six to exit. */
    fun canMove(position: Int, dice: Int): Boolean {
        if (dice !in 1..6) return false
        return if (position < 0) dice == 6 else position + dice <= FINISH_POSITION
    }

    /** Returns the next track/finish position, or null when this move is illegal. */
    fun nextPosition(position: Int, dice: Int): Int? {
        if (!canMove(position, dice)) return null
        return if (position < 0) 0 else position + dice
    }

    /** Converts a token's 0–51 track position to a shared 0–51 board square. */
    fun globalSquare(player: Int, position: Int): Int? {
        if (player !in 0..3 || position !in 0..TRACK_LAST_POSITION) return null
        return ((player * 13) + position) % 52
    }

    fun isSafeSquare(square: Int): Boolean = square in safeSquares

    fun hasLegalMove(positions: Iterable<Int>, dice: Int): Boolean =
        positions.any { canMove(it, dice) }

    fun keepsTurn(dice: Int, capturedTokens: Int): Boolean =
        dice == 6 || capturedTokens > 0

    fun hasWinner(positions: IntArray): Boolean =
        positions.size == TOKENS_PER_PLAYER && positions.all { it == FINISH_POSITION }

    /** A completed offline game must not accept another roll or pawn move. */
    fun canContinue(phase: String?): Boolean = phase != "FINISHED"
}
