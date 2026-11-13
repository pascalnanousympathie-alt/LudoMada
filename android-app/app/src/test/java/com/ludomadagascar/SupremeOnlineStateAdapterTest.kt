package com.ludomadagascar

import com.ludomadagascar.supreme.model.TokenState
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SupremeOnlineStateAdapterTest {
    @Test
    fun onlineStateRevisionGuardAcceptsOnlyCurrentGameAndNonStaleVersions() {
        assertTrue(
            OnlineStateRevisionGuard.shouldApply(
                currentGameId = "game-1",
                requestedGameId = "game-1",
                responseGameId = "game-1",
                currentVersion = 7,
                incomingVersion = 8
            )
        )
        assertTrue(
            OnlineStateRevisionGuard.shouldApply(
                currentGameId = "game-1",
                requestedGameId = "game-1",
                responseGameId = "game-1",
                currentVersion = 8,
                incomingVersion = 8
            )
        )
        assertFalse(
            OnlineStateRevisionGuard.shouldApply(
                currentGameId = "game-2",
                requestedGameId = "game-1",
                responseGameId = "game-1",
                currentVersion = 7,
                incomingVersion = 9
            )
        )
        assertFalse(
            OnlineStateRevisionGuard.shouldApply(
                currentGameId = "game-1",
                requestedGameId = "game-1",
                responseGameId = "game-2",
                currentVersion = 7,
                incomingVersion = 9
            )
        )
        assertFalse(
            OnlineStateRevisionGuard.shouldApply(
                currentGameId = "game-1",
                requestedGameId = "game-1",
                responseGameId = "game-1",
                currentVersion = 9,
                incomingVersion = 8
            )
        )
        assertFalse(
            OnlineStateRevisionGuard.shouldApply(
                currentGameId = "game-1",
                requestedGameId = "game-1",
                responseGameId = "game-1",
                currentVersion = 0,
                incomingVersion = -1
            )
        )
    }

    @Test
    fun mapsServerSeatsDiceAndFinishedPawnsIntoSupremePresentation() {
        val response = JSONObject(
            """{
              "status":"PLAYING",
              "state":{
                "phase":"PLAYING",
                "stateVersion":17,
                "currentPlayerIndex":1,
                "dice":6,
                "players":[
                  {"id":"user-red","color":"RED","tokens":[{"pos":57},{"pos":-1},{"pos":52},{"pos":8}]},
                  {"id":"user-green","color":"GREEN","tokens":[{"pos":0},{"pos":-1},{"pos":-1},{"pos":-1}]}
                ]
              }
            }"""
        )

        val state = SupremeOnlineStateAdapter.fromServer(response, "user-green", "Nanou")!!

        assertEquals(17, state.stateVersion)
        assertEquals(1, state.activePlayerIndex)
        assertEquals(1, state.localPlayerIndex)
        assertTrue(state.isLocalPlayersTurn)
        assertEquals(6, state.diceValue)
        assertEquals("Nanou", state.players[1].name)
        assertEquals("RED", state.players[0].color.name)
        assertEquals(9, state.players[0].tokens[3].trackPosition)
        assertEquals(14, state.players[1].tokens[0].trackPosition)
        assertEquals(1, state.players[0].finishedTokensCount)
        assertFalse(state.players[0].hasWon)
        assertEquals(TokenState.FINISHED, state.players[0].tokens[0].state)
        assertEquals(TokenState.IN_YARD, state.players[0].tokens[1].state)
        assertEquals(TokenState.IN_HOME_STRETCH, state.players[0].tokens[2].state)
    }

    @Test
    fun finishedGameHasNoLocalTurnAndMarksWinner() {
        val response = JSONObject(
            """{
              "status":"FINISHED",
              "state":{
                "phase":"FINISHED",
                "stateVersion":44,
                "currentPlayerIndex":0,
                "dice":null,
                "winnerId":"user-red",
                "players":[
                  {"id":"user-red","color":"RED","tokens":[{"pos":57},{"pos":57},{"pos":57},{"pos":57}]},
                  {"id":"user-blue","color":"BLUE","tokens":[{"pos":-1},{"pos":-1},{"pos":-1},{"pos":-1}]}
                ]
              }
            }"""
        )

        val state = SupremeOnlineStateAdapter.fromServer(response, "user-red", "Nanou")!!

        assertEquals("FINISHED", state.phase)
        assertFalse(state.isLocalPlayersTurn)
        assertTrue(state.players[0].hasWon)
        assertEquals(4, state.players[0].finishedTokensCount)
        assertEquals(1, state.players[0].rank)
        assertNull(state.diceValue)
    }

    @Test
    fun mapsEachServerColorStartToSupremeBoardCoordinate() {
        val response = JSONObject(
            """{
              "status":"PLAYING",
              "state":{
                "phase":"PLAYING",
                "stateVersion":1,
                "currentPlayerIndex":0,
                "dice":null,
                "players":[
                  {"id":"red","color":"RED","tokens":[{"pos":0},{"pos":-1},{"pos":-1},{"pos":-1}]},
                  {"id":"green","color":"GREEN","tokens":[{"pos":0},{"pos":-1},{"pos":-1},{"pos":-1}]},
                  {"id":"yellow","color":"YELLOW","tokens":[{"pos":0},{"pos":-1},{"pos":-1},{"pos":-1}]},
                  {"id":"blue","color":"BLUE","tokens":[{"pos":0},{"pos":-1},{"pos":-1},{"pos":-1}]}
                ]
              }
            }"""
        )

        val state = SupremeOnlineStateAdapter.fromServer(response, "red", "Nanou")!!

        assertEquals(listOf(1, 14, 27, 40), state.players.map { it.tokens[0].trackPosition })
    }

    @Test
    fun missingOrEmptyServerPlayersAreRejected() {
        assertNull(SupremeOnlineStateAdapter.fromServer(null, "user-red", "Nanou"))
        assertNull(SupremeOnlineStateAdapter.fromServer(JSONObject("""{"players":[]}"""), "user-red", "Nanou"))
    }
}
