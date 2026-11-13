package com.ludomadagascar

/** Animates only tokens whose authoritative board position actually changed. */
internal object PawnAnimationPolicy {
    fun shouldAnimate(previousPosition: Int, nextPosition: Int): Boolean =
        previousPosition != Int.MIN_VALUE && previousPosition != nextPosition
}
