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
        val offline2p = device.findObject(By.text("👤 2 joueurs"))
        assertTrue(offline2p.exists())
        offline2p.click()
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