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

        device.findObject(By.desc("OFFLINE_2_PLAYERS")).click()
        assertTrue(device.wait(Until.hasObject(By.desc("GAME_DICE_ROLL")), 5000))
        assertTrue(device.hasObject(By.textContains("Joueur 1/")))

        var gotSix = false
        for (attempt in 0 until 24) {
            assertTrue("Dice button must remain reachable", device.hasObject(By.desc("GAME_DICE_ROLL")))
            device.findObject(By.desc("GAME_DICE_ROLL")).click()
            if (device.wait(Until.hasObject(By.textContains("Dé: 6")), 800)) {
                gotSix = true
                break
            }
            device.wait(Until.hasObject(By.textContains("Joueur ")), 1200)
            Thread.sleep(250)
        }
        assertTrue("Dice should produce a reachable six within retries", gotSix)

        assertTrue(device.hasObject(By.desc("GAME_PAWN_1")))
        device.findObject(By.desc("GAME_PAWN_1")).click()
        assertTrue(
            "A six gives the player another turn after the pawn move",
            device.wait(Until.hasObject(By.textContains("Rejouez")), 1800)
        )

        val file = File("/sdcard/Download/ludomada-startup-offline.png")
        device.takeScreenshot(file)
        assertTrue("Startup/offline screenshot missing", file.exists() && file.length() > 1000)
    }
}
