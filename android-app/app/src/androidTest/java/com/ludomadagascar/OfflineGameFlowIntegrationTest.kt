package com.ludomadagascar

import android.content.Intent
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

/**
 * Deterministic integration coverage for the real MainActivity offline callbacks.
 * State is seeded through reflection only in the test process; no production debug
 * route, test backdoor, or runtime gameplay shortcut is added.
 */
@RunWith(AndroidJUnit4::class)
class OfflineGameFlowIntegrationTest {
    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    private fun mainActivity(): MainActivity {
        val reference = AtomicReference<MainActivity?>()
        instrumentation.runOnMainSync {
            reference.set(
                ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MainActivity>()
                    .firstOrNull()
            )
        }
        return reference.get() ?: throw AssertionError("MainActivity must be resumed")
    }

    private fun launchTwoPlayerGame(): Pair<UiDevice, MainActivity> {
        val context = instrumentation.targetContext
        context.getSharedPreferences("ludomada_session", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()

        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        assertNotNull("Launch intent missing", intent)
        intent!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        instrumentation.startActivitySync(intent)

        val device = UiDevice.getInstance(instrumentation)
        assertTrue(
            "Offline entry action must appear",
            device.wait(Until.hasObject(By.text("👥 Jouer hors-ligne")), 10000)
        )
        assertTrue("Offline entry tap failed", device.findObject(By.text("👥 Jouer hors-ligne")).click())
        assertTrue(
            "Offline menu did not appear",
            device.wait(Until.hasObject(By.text("Ludo hors-ligne")), 5000)
        )
        assertTrue("Two-player menu action is missing", device.hasObject(By.text("👤 2 joueurs")))
        assertTrue("Two-player selection tap failed", device.findObject(By.text("👤 2 joueurs")).click())
        assertTrue(
            "Offline board did not appear",
            device.wait(Until.hasObject(By.textContains("Lancer le dé")), 8000)
        )
        return device to mainActivity()
    }

    private fun field(activity: MainActivity, name: String) =
        MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }

    private fun seedOfflineState(
        activity: MainActivity,
        positionsByPlayer: List<IntArray>,
        currentPlayer: Int,
        dice: Int
    ) {
        instrumentation.runOnMainSync {
            val state = field(activity, "offlineState").get(activity) as? JSONObject
                ?: throw AssertionError("Offline game state is missing")
            val players = state.optJSONArray("players")
                ?: throw AssertionError("Offline players are missing")

            for (playerIndex in 0 until minOf(players.length(), positionsByPlayer.size)) {
                val tokens = players.optJSONObject(playerIndex)?.optJSONArray("tokens")
                    ?: throw AssertionError("Offline tokens are missing for player $playerIndex")
                for (tokenIndex in 0 until minOf(4, tokens.length())) {
                    tokens.optJSONObject(tokenIndex)?.put(
                        "pos",
                        positionsByPlayer[playerIndex].getOrElse(tokenIndex) { -1 }
                    )
                }
            }

            state.put("phase", "PLAYING").put("dice", dice)
            field(activity, "offlineTurn").setInt(activity, currentPlayer)
            field(activity, "offlineDice").setInt(activity, dice)
            field(activity, "offlinePlayers").setInt(activity, players.length())

            val host = field(activity, "offlineHost").get(activity) as? LudoBoardView
                ?: throw AssertionError("Offline board input surface is missing")
            host.state = state
            host.player = currentPlayer
            host.invalidate()

            val status = field(activity, "status").get(activity) as? TextView
                ?: throw AssertionError("Offline status view is missing")
            status.text = "Joueur ${currentPlayer + 1}/${players.length()} • Dé: $dice"
        }
    }

    private fun invokePawnTap(activity: MainActivity, tokenIndex: Int) {
        instrumentation.runOnMainSync {
            val host = field(activity, "offlineHost").get(activity) as? LudoBoardView
                ?: throw AssertionError("Offline board input surface is missing")
            val callback = host.onPawnTap
                ?: throw AssertionError("Offline 3D pawn-tap callback is not wired")
            callback.invoke(tokenIndex)
        }
    }

    private fun readState(activity: MainActivity): JSONObject {
        val reference = AtomicReference<JSONObject?>()
        instrumentation.runOnMainSync {
            reference.set(field(activity, "offlineState").get(activity) as? JSONObject)
        }
        return reference.get() ?: throw AssertionError("Offline game state is missing")
    }

    private fun readInt(activity: MainActivity, name: String): Int {
        val reference = AtomicReference<Int>()
        instrumentation.runOnMainSync {
            reference.set(field(activity, name).getInt(activity))
        }
        return reference.get()
    }

    private fun finishActivity(activity: MainActivity) {
        instrumentation.runOnMainSync { activity.finish() }
    }

    @Test
    fun captureResetsOpponentPawnAndKeepsTheCapturingPlayersTurn() {
        val (device, activity) = launchTwoPlayerGame()
        try {
            seedOfflineState(
                activity,
                listOf(
                    intArrayOf(3, -1, -1, -1),
                    intArrayOf(44, -1, -1, -1)
                ),
                currentPlayer = 0,
                dice = 2
            )

            invokePawnTap(activity, 0)

            assertTrue(
                "A real offline capture should grant an immediate replay",
                device.wait(Until.hasObject(By.textContains("Rejouez")), 4000)
            )
            val state = readState(activity)
            val players = state.getJSONArray("players")
            assertEquals(5, players.getJSONObject(0).getJSONArray("tokens").getJSONObject(0).getInt("pos"))
            assertEquals(-1, players.getJSONObject(1).getJSONArray("tokens").getJSONObject(0).getInt("pos"))
            assertEquals("PLAYING", state.getString("phase"))
            assertEquals(0, readInt(activity, "offlineTurn"))
            assertEquals(0, readInt(activity, "offlineDice"))
        } finally {
            finishActivity(activity)
        }
    }

    @Test
    fun safeSquareLeavesOpponentPawnInPlaceAndAdvancesTurn() {
        val (device, activity) = launchTwoPlayerGame()
        try {
            seedOfflineState(
                activity,
                listOf(
                    intArrayOf(6, -1, -1, -1),
                    intArrayOf(47, -1, -1, -1)
                ),
                currentPlayer = 0,
                dice = 2
            )

            invokePawnTap(activity, 0)

            assertTrue(
                "A non-capture move off a safe square must advance to player two",
                device.wait(Until.hasObject(By.textContains("Joueur 2/2")), 4000)
            )
            val players = readState(activity).getJSONArray("players")
            assertEquals(8, players.getJSONObject(0).getJSONArray("tokens").getJSONObject(0).getInt("pos"))
            assertEquals(47, players.getJSONObject(1).getJSONArray("tokens").getJSONObject(0).getInt("pos"))
            assertEquals(1, readInt(activity, "offlineTurn"))
        } finally {
            finishActivity(activity)
        }
    }

    @Test
    fun fourthFinishedPawnEndsGameAndRejectsAnotherRoll() {
        val (device, activity) = launchTwoPlayerGame()
        try {
            seedOfflineState(
                activity,
                listOf(
                    intArrayOf(56, 57, 57, 57),
                    intArrayOf(-1, -1, -1, -1)
                ),
                currentPlayer = 0,
                dice = 1
            )

            invokePawnTap(activity, 0)

            val state = readState(activity)
            assertEquals(57, state.getJSONArray("players").getJSONObject(0)
                .getJSONArray("tokens").getJSONObject(0).getInt("pos"))
            assertEquals("FINISHED", state.getString("phase"))
            assertEquals("offline-0", state.getString("winnerId"))

            instrumentation.runOnMainSync {
                val host = field(activity, "offlineHost").get(activity) as? LudoBoardView
                    ?: throw AssertionError("Offline board input surface is missing")
                val roll = host.onRollTap
                    ?: throw AssertionError("Offline dice callback is not wired")
                roll.invoke()
            }
            assertTrue(
                "A completed game must reject a new roll",
                device.wait(Until.hasObject(By.textContains("La partie est terminée")), 2500)
            )
            assertEquals(0, readInt(activity, "offlineDice"))
            assertEquals("FINISHED", readState(activity).getString("phase"))
        } finally {
            finishActivity(activity)
        }
    }
}
