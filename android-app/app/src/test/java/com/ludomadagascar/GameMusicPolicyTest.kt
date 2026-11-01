package com.ludomadagascar

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameMusicPolicyTest {
    @Test
    fun onlineGameplayAlwaysMutesBackgroundMusic() {
        assertFalse(GameMusicPolicy.shouldPlay("board", activityResumed = true))
    }

    @Test
    fun offlineGameplayAlwaysMutesBackgroundMusic() {
        assertFalse(GameMusicPolicy.shouldPlay("offline", activityResumed = true))
    }

    @Test
    fun nonGameplayScreensAllowMusicOnlyWhileResumed() {
        listOf("splash", "login", "home", "room", "wallet", "market", "profile").forEach { screen ->
            assertTrue("$screen should allow music in foreground", GameMusicPolicy.shouldPlay(screen, true))
            assertFalse("$screen should mute music in background", GameMusicPolicy.shouldPlay(screen, false))
        }
    }
}
