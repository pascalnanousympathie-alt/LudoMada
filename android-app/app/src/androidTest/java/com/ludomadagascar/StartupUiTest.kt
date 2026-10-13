package com.ludomadagascar

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class StartupUiTest {
    @Test
    fun app_launches_premium_home_and_reaches_offline_game_flow() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val info = context.packageManager.getApplicationInfo(context.packageName, 0)
        context.getSharedPreferences("ludomada_session", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        assertTrue("Launcher icon resource must exist", info.icon != 0)

        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        assertNotNull("Launch intent missing", intent)
        intent!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        instrumentation.startActivitySync(intent)

        val device = UiDevice.getInstance(instrumentation)

        // Premium Home is the first screen; authentication is only required for online actions.
        assertTrue(device.wait(Until.hasObject(By.desc("LudoMada Premium Home")), 8000))
        assertTrue("Premium Home must expose the primary play target", device.hasObject(By.desc("JOUER")))

        device.findObject(By.desc("JOUER")).click()
        assertTrue(device.wait(Until.hasObject(By.text("👥 Hors-ligne — 2 à 4 joueurs")), 3000))

        device.findObject(By.text("👥 Hors-ligne — 2 à 4 joueurs")).click()
        assertTrue(device.wait(Until.hasObject(By.text("Ludo hors-ligne")), 3000))
        assertTrue(device.hasObject(By.desc("OFFLINE_2_PLAYERS")))

        fun assertGameplayLoaded(players: Int) {
            val modeMarker = "GAMEPLAY_3D_OFFLINE_${players}P"
            assertTrue(
                "${players}-player gameplay marker missing",
                device.wait(Until.hasObject(By.descContains(modeMarker)), 8000)
            )
            assertTrue(
                "${players}-player board GLB runtime did not load",
                device.wait(Until.hasObject(By.descContains("GLB_RUNTIME_LOAD_OK")), 8000)
            )
        }

        assertTrue(device.hasObject(By.desc("OFFLINE_3_PLAYERS")))
        assertTrue(device.hasObject(By.desc("OFFLINE_4_PLAYERS")))
        device.findObject(By.desc("OFFLINE_2_PLAYERS")).click()
        assertGameplayLoaded(2)
        assertTrue("Gameplay must expose the dice HUD action", device.hasObject(By.desc("TOUCHEZ_LE_DE")))

        // Exercise the real roll callback without asserting a synthetic pawn button: pawns are
        // hit-tested against projected 3D geometry in LudoBoardView rather than separate Views.
        var gotSix = false
        for (attempt in 0 until 24) {
            val dice = device.findObject(By.desc("TOUCHEZ_LE_DE"))
            assertNotNull("Dice HUD action must remain available", dice)
            dice.click()
            if (device.wait(Until.hasObject(By.textContains("Dé: 6")), 900)) {
                gotSix = true
                break
            }
            device.wait(Until.hasObject(By.textContains("Joueur ")), 1200)
            Thread.sleep(200)
        }
        assertTrue("Dice action should reach a six within retries", gotSix)

        device.findObject(By.desc("MENU_JEU")).click()
        assertTrue(device.wait(Until.hasObject(By.desc("OFFLINE_3_PLAYERS")), 3000))
        device.findObject(By.desc("OFFLINE_3_PLAYERS")).click()
        assertGameplayLoaded(3)

        device.findObject(By.desc("MENU_JEU")).click()
        assertTrue(device.wait(Until.hasObject(By.desc("OFFLINE_4_PLAYERS")), 3000))
        device.findObject(By.desc("OFFLINE_4_PLAYERS")).click()
        assertGameplayLoaded(4)

        val file = File(instrumentation.context.cacheDir, "ludomada-startup-offline.png")
        device.takeScreenshot(file)
        assertTrue("Startup/offline screenshot missing", file.exists() && file.length() > 1000)
    }
}
