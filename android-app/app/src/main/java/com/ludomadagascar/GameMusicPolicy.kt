package com.ludomadagascar

/** Central policy for background music; gameplay keeps only positional game SFX. */
internal object GameMusicPolicy {
    private const val ONLINE_GAMEPLAY_SCREEN = "board"
    private const val OFFLINE_GAMEPLAY_SCREEN = "offline"

    fun shouldPlay(screen: String, activityResumed: Boolean): Boolean =
        activityResumed &&
            screen != ONLINE_GAMEPLAY_SCREEN &&
            screen != OFFLINE_GAMEPLAY_SCREEN
}
