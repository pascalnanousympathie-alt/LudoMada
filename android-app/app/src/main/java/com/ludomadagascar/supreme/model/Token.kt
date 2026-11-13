package com.ludomadagascar.supreme.model

enum class TokenState {
    IN_YARD,
    ON_TRACK,
    IN_HOME_STRETCH,
    FINISHED
}

data class Token(
    val id: Int, // 0..3
    val color: PlayerColor,
    val state: TokenState = TokenState.IN_YARD,
    val trackPosition: Int = -1, // 0..51 when ON_TRACK
    val homeStretchPosition: Int = -1, // 0..4 when IN_HOME_STRETCH (5 is FINISHED)
    val stepsMoved: Int = 0 // 0 when in yard, 1 when at start, up to 57 (exact finish)
) {
    val isHome: Boolean get() = state == TokenState.FINISHED
    val isInYard: Boolean get() = state == TokenState.IN_YARD
}
