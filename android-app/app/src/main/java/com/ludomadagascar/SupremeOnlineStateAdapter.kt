package com.ludomadagascar

import com.ludomadagascar.supreme.model.Player
import com.ludomadagascar.supreme.model.PlayerColor
import com.ludomadagascar.supreme.model.Token
import com.ludomadagascar.supreme.model.TokenState
import org.json.JSONObject

/**
 * One-way adapter from LudoMada's authoritative API state to Ludo Supreme's presentation model.
 * Never run the candidate's local game engine for online matches: dice, moves, captures,
 * turns and wins remain server-authoritative under LUDO_MADA_CLASSIC.
 */
internal data class SupremeOnlineUiState(
    val players: List<Player>,
    val activePlayerIndex: Int,
    val localPlayerIndex: Int,
    val diceValue: Int?,
    val stateVersion: Int,
    val phase: String,
    val isLocalPlayersTurn: Boolean
)

internal object SupremeOnlineStateAdapter {
    fun fromServer(
        response: JSONObject?,
        currentUserId: String,
        currentUsername: String
    ): SupremeOnlineUiState? {
        if (response == null) return null
        val wire = response.optJSONObject("state") ?: response
        val wirePlayers = wire.optJSONArray("players") ?: response.optJSONArray("players") ?: return null
        if (wirePlayers.length() == 0) return null

        val winnerId = wire.optString("winnerId", response.optString("winnerId", ""))
        val players = (0 until wirePlayers.length()).map { index ->
            val row = wirePlayers.optJSONObject(index) ?: JSONObject()
            val remoteId = row.optString("id", "")
            val color = PlayerColor.values().firstOrNull {
                it.name.equals(row.optString("color", ""), ignoreCase = true)
            } ?: PlayerColor.values()[index.coerceIn(0, PlayerColor.values().lastIndex)]
            val wireTokens = row.optJSONArray("tokens")
            val tokens = (0 until 4).map { tokenIndex ->
                val position = wireTokens?.optJSONObject(tokenIndex)?.optInt("pos", -1) ?: -1
                val tokenState = when {
                    position < 0 -> TokenState.IN_YARD
                    position >= 57 -> TokenState.FINISHED
                    position >= 52 -> TokenState.IN_HOME_STRETCH
                    else -> TokenState.ON_TRACK
                }
                Token(
                    id = tokenIndex,
                    color = color,
                    state = tokenState,
                    trackPosition = if (position in 0..51) ((color.startSquareIndex - 1 + position) % 52) else -1,
                    homeStretchPosition = if (position in 52..56) position - 52 else -1,
                    stepsMoved = if (position < 0) 0 else (position + 1).coerceAtMost(57)
                )
            }
            val didWin = tokens.size == 4 && tokens.all { it.state == TokenState.FINISHED }
            Player(
                id = index,
                name = if (remoteId == currentUserId && currentUsername.isNotBlank()) currentUsername else "Joueur " + (index + 1),
                color = color,
                isBot = false,
                tokens = tokens,
                hasWon = didWin,
                rank = if (didWin && winnerId == remoteId) 1 else 0
            )
        }
        val activeIndex = wire.optInt("currentPlayerIndex", response.optInt("currentPlayerIndex", 0))
            .coerceIn(0, players.lastIndex)
        val localIndex = (0 until wirePlayers.length()).firstOrNull { index ->
            wirePlayers.optJSONObject(index)?.optString("id", "") == currentUserId
        } ?: -1
        val phase = wire.optString("phase", if (response.optString("status") == "FINISHED") "FINISHED" else "PLAYING")
        val dice = wire.optInt("dice", response.optInt("dice", 0)).takeIf { it in 1..6 }
        return SupremeOnlineUiState(
            players = players,
            activePlayerIndex = activeIndex,
            localPlayerIndex = localIndex,
            diceValue = dice,
            stateVersion = wire.optInt("stateVersion", response.optInt("stateVersion", 0)),
            phase = phase,
            isLocalPlayersTurn = localIndex >= 0 && localIndex == activeIndex && phase != "FINISHED"
        )
    }
}
