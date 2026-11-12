package com.ludomadagascar.supreme.model

data class Player(
    val id: Int,
    val name: String,
    val color: PlayerColor,
    val isBot: Boolean = false,
    val tokens: List<Token> = (0..3).map { Token(id = it, color = color) },
    val hasWon: Boolean = false,
    val rank: Int = 0 // 1 for 1st, 2 for 2nd, etc.
) {
    val finishedTokensCount: Int get() = tokens.count { it.isHome }
    val allTokensHome: Boolean get() = finishedTokensCount == 4
}
