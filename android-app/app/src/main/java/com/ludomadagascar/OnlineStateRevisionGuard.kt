package com.ludomadagascar

/**
 * Prevents a late GET /state response from overwriting a newer game revision
 * or a different room after the user has switched games.
 */
internal object OnlineStateRevisionGuard {
    fun shouldApply(
        currentGameId: String,
        requestedGameId: String,
        responseGameId: String,
        currentVersion: Int,
        incomingVersion: Int
    ): Boolean = requestedGameId.isNotBlank() &&
        currentGameId == requestedGameId &&
        responseGameId == requestedGameId &&
        incomingVersion >= 0 &&
        incomingVersion >= currentVersion
}
