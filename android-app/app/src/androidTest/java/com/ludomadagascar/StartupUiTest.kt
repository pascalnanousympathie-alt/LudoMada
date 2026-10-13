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
    fun app_launches_login_and_offline_game_flow_is_reachable() {
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
        assertTrue(device.wait(Until.hasObject(By.text("Se connecter")), 8000))
        assertTrue(device.hasObject(By.text("Créer un compte")))
        assertTrue(device.hasObject(By.text("👥 Jouer hors-ligne")))
        device.findObject(By.text("👥 Jouer hors-ligne")).click()
        assertTrue(device.wait(Until.hasObject(By.text("Ludo hors-ligne")), 3000))
        assertTrue(device.hasObject(By.text("👤 2 joueurs")))
        device.findObject(By.text("👤 2 joueurs")).click()
        assertTrue(device.wait(Until.hasObject(By.text("Lancer le dé")), 5000))
        assertTrue(device.hasObject(By.textContains("Joueur 1/")))

        var gotSix = false
        for (attempt in 1..24) {
            assertTrue("Dice button must remain reachable", device.hasObject(By.text("Lancer le dé")))
            device.findObject(By.text("Lancer le dé")).click()
            if (device.wait(Until.hasObject(By.textContains("Dé: 6")), 800)) {
                gotSix = true
                break
            }
            device.wait(Until.hasObject(By.textContains("Joueur ")), 1200)
            Thread.sleep(250)
        }
        assertTrue("Dice should produce a reachable six within retries", gotSix)

        assertTrue(device.hasObject(By.text("Pion 1")))
        device.findObject(By.text("Pion 1")).click()
        assertTrue("A six gives the player another turn after the pawn move",
            device.wait(Until.hasObject(By.textContains("Rejouez")), 1800))

        val file = File(instrumentation.context.cacheDir, "ludomada-startup-offline.png")
        device.takeScreenshot(file)
        assertTrue("Startup/offline screenshot missing", file.exists() && file.length() > 1000)
    }

    @Test
    fun game_mode_selector_exposes_both_choices_and_back_dismisses_it() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        context.getSharedPreferences("ludomada_session", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()

        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        assertNotNull("Launch intent missing", intent)
        intent!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = instrumentation.startActivitySync(intent)
        val device = UiDevice.getInstance(instrumentation)

        instrumentation.runOnMainSync {
            val selector = activity.javaClass.getDeclaredMethod("gameModeDialog")
            selector.isAccessible = true
            selector.invoke(activity)
        }

        assertTrue("Mode selector offline choice must be accessible",
            device.wait(Until.hasObject(By.desc("MODE_OFFLINE")), 3000))
        assertTrue("Mode selector online choice must be accessible",
            device.hasObject(By.desc("MODE_ONLINE")))
        assertTrue("Mode selector title must be visible",
            device.hasObject(By.text("Choisissez le mode")))

        device.pressBack()
        assertTrue("Back must dismiss the game mode selector",
            device.wait(Until.gone(By.desc("MODE_OFFLINE")), 3000))
        instrumentation.runOnMainSync { activity.finish() }
    }

}