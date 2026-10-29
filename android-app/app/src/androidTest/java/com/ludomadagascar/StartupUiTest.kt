package com.ludomadagascar

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
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
    fun game_mode_overlay_is_accessible_and_routes_both_modes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        context.getSharedPreferences("ludomada_session", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()

        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        assertNotNull("Launch intent missing", intent)
        intent!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        instrumentation.startActivitySync(intent)

        val device = UiDevice.getInstance(instrumentation)
        assertTrue("Login screen did not appear", device.wait(Until.hasObject(By.text("Se connecter")), 8000))

        var activity: MainActivity? = null
        instrumentation.runOnMainSync {
            activity = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MainActivity>()
                .firstOrNull()
            assertNotNull("MainActivity must be resumed", activity)
            activity!!.javaClass.getDeclaredMethod("gameModeDialog").apply {
                isAccessible = true
            }.invoke(activity)
        }

        assertTrue("Mode overlay accessibility marker missing",
            device.wait(Until.hasObject(By.desc("GAME_MODE_DIALOG")), 3000))
        assertTrue("Offline mode accessibility marker missing", device.hasObject(By.desc("MODE_OFFLINE")))
        assertTrue("Online mode accessibility marker missing", device.hasObject(By.desc("MODE_ONLINE")))

        device.findObject(By.desc("MODE_ONLINE")).click()
        assertTrue("Online selection must navigate to the lobby",
            device.wait(Until.hasObject(By.text("Choisir la mise")), 3000))
        assertTrue("Lobby room-join action missing", device.hasObject(By.text("🔑 Hiditra amin'ny room")))

        device.findObject(By.text("← Accueil")).click()
        assertTrue("Returning from lobby must reach Home",
            device.wait(Until.hasObject(By.descContains("LudoMada Premium Home")), 3000))

        instrumentation.runOnMainSync {
            activity!!.javaClass.getDeclaredMethod("gameModeDialog").apply {
                isAccessible = true
            }.invoke(activity)
        }
        assertTrue("Mode overlay did not reopen from Home",
            device.wait(Until.hasObject(By.desc("GAME_MODE_DIALOG")), 3000))
        device.findObject(By.desc("MODE_OFFLINE")).click()
        assertTrue("Offline selection must navigate to the offline menu",
            device.wait(Until.hasObject(By.text("Ludo hors-ligne")), 3000))
        assertTrue("Offline menu must expose two-player mode", device.hasObject(By.text("👤 2 joueurs")))
        assertTrue("Offline menu must expose three-player mode", device.hasObject(By.text("👥 3 joueurs")))
        assertTrue("Offline menu must expose four-player mode", device.hasObject(By.text("👥 4 joueurs")))
    }

    @Test
    fun app_launches_premium_home_and_offline_game_flow_is_reachable() {
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
        repeat(24) {
            assertTrue("Dice button must remain reachable", device.hasObject(By.text("Lancer le dé")))
            device.findObject(By.text("Lancer le dé")).click()
            if (device.wait(Until.hasObject(By.textContains("Dé: 6")), 800)) {
                gotSix = true
                return@repeat
            }
            device.wait(Until.hasObject(By.textContains("Joueur ")), 1200)
            Thread.sleep(250)
        }
        assertTrue("Dice should produce a reachable six within retries", gotSix)

        assertTrue(device.hasObject(By.text("Pion 1")))
        device.findObject(By.text("Pion 1")).click()
        assertTrue("A six gives the player another turn after the pawn move",
            device.wait(Until.hasObject(By.textContains("Rejouez")), 1800))

        val file = File("/sdcard/Download/ludomada-startup-offline.png")
        device.takeScreenshot(file)
        assertTrue("Startup/offline screenshot missing", file.exists() && file.length() > 1000)
    }
}