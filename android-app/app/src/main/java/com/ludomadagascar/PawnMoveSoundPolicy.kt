package com.ludomadagascar

/** Detects committed pawn position changes while excluding first-frame initialization. */
internal object PawnMoveSoundPolicy {
    fun shouldPlay(previousPosition: Int, currentPosition: Int): Boolean =
        previousPosition != Int.MIN_VALUE && previousPosition != currentPosition
}
