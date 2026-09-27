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
    fun app_launches_premium_home_and_offline_game_flow_is_reachable() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val info = context.packageManager.getApplicationInfo(context.packageName, 0)
        assertTrue("Launcher icon resource must exist", info.icon != 0)

        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        assertNotNull("Launch intent missing", intent)
        intent!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        instrumentation.startActivitySync(intent)

        val device = UiDevice.getInstance(instrumentation)
        assertTrue(device.wait(Until.hasObject(By.descContains("LudoMada Premium Home")), 8000))
        assertTrue(device.wait(Until.hasObject(By.descContains("Player profile")), 2000))
        assertTrue(device.wait(Until.hasObject(By.descContains("coins")), 2000))
        assertTrue(device.wait(Until.hasObject(By.descContains("gems")), 1000))
        assertTrue(device.wait(Until.hasObject(By.descContains("Joue. Gagne. Grandis")), 1000))

        val animA = File("/sdcard/Download/ludomada-home-animation-a.png")
        val animB = File("/sdcard/Download/ludomada-home-animation-b.png")
        device.takeScreenshot(animA)
        Thread.sleep(240)
        device.takeScreenshot(animB)
        assertTrue("Home animation should change rendered frame", animA.exists() && animB.exists() && !animA.readBytes().contentEquals(animB.readBytes()))

        val w = device.displayWidth
        val h = device.displayHeight
        device.click((w * 195) / 390, (h * 335) / 710)
        assertTrue(device.wait(Until.hasObject(By.text("Choisissez le mode")), 3000))
        val offlineOption = device.findObject(By.text("👥 Hors-ligne — 2 à 4 joueurs"))
        assertTrue(offlineOption.exists())
        offlineOption.click()
        assertTrue(device.wait(Until.hasObject(By.text("Lancer le dé")), 5000))
        assertTrue(device.hasObject(By.textContains("Joueur 1/")))

        var gotSix = false
        repeat(12) {
            device.findObject(By.text("Lancer le dé")).click()
            if (device.wait(Until.hasObject(By.textContains("Dé: 6")), 700)) {
                gotSix = true
                return@repeat
            }
        }
        assertTrue("Dice should produce a reachable six within retries", gotSix)

        val pawn1 = device.findObject(By.text("Pion 1"))
        assertTrue(pawn1.exists())
        pawn1.click()
        assertTrue(device.wait(Until.hasObject(By.textContains("Déplacement")), 1200))

        val file = File("/sdcard/Download/ludomada-startup-offline.png")
        device.takeScreenshot(file)
        assertTrue("Startup/offline screenshot missing", file.exists() && file.length() > 1000)
    }
}