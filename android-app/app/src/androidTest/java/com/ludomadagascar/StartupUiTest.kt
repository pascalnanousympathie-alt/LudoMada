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
    private fun tapHomeJouer(device: UiDevice, context: android.content.Context) {
        val home = device.findObject(By.descContains("LudoMada Premium Home"))
        val bounds = home.visibleBounds
        val density = context.resources.displayMetrics.density
        val scale = minOf(
            bounds.width() / (390f * density),
            bounds.height() / (710f * density)
        ) * density
        val xOffset = (bounds.width() - 390f * scale) / 2f
        val yOffset = (bounds.height() - 710f * scale) / 2f

        // The Home canvas defines JOUER at logical x=8..126, y=110..158.
        // Convert that real hitbox to screen pixels instead of tapping the whole View center.
        val x = bounds.left + (xOffset + 65f * scale).toInt()
        val y = bounds.top + (yOffset + 132f * scale).toInt()
        assertTrue("Home JOUER tap should be accepted", device.click(x, y))
    }

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
            activity!!.javaClass.getDeclaredMethod("home").apply {
                isAccessible = true
            }.invoke(activity)
        }

        assertTrue("Premium Home did not appear",
            device.wait(Until.hasObject(By.descContains("LudoMada Premium Home")), 3000))
        tapHomeJouer(device, context)
        assertTrue("Mode overlay accessibility marker missing",
            device.wait(Until.hasObject(By.descContains("GAME_MODE_DIALOG")), 3000))
        assertTrue("Offline mode accessibility marker missing", device.hasObject(By.desc("MODE_OFFLINE")))
        assertTrue("Online mode accessibility marker missing", device.hasObject(By.desc("MODE_ONLINE")))

        device.findObject(By.desc("MODE_ONLINE")).click()
        assertTrue("Online selection must navigate to the lobby",
            device.wait(Until.hasObject(By.text("Choisir la mise")), 3000))
        assertTrue("Lobby room-join action missing", device.hasObject(By.text("🔑 Hiditra amin'ny room")))

        device.findObject(By.text("← Accueil")).click()
        assertTrue("Returning from lobby must reach Home",
            device.wait(Until.hasObject(By.descContains("LudoMada Premium Home")), 3000))

        tapHomeJouer(device, context)
        assertTrue("Mode overlay did not reopen from Home JOUER",
            device.wait(Until.hasObject(By.descContains("GAME_MODE_DIALOG")), 3000))
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
        assertTrue("3D board surface missing", device.wait(Until.hasObject(By.desc("LudoMada 3D Ludo board")), 5000))
        assertTrue(device.wait(Until.hasObject(By.textContains("Lancer le dé")), 5000))
        assertTrue(device.hasObject(By.textContains("Joueur 1/")))

        var gotSix = false
        for (attempt in 0 until 24) {
            assertTrue("Dice button must remain reachable", device.hasObject(By.textContains("Lancer le dé")))
            device.findObject(By.textContains("Lancer le dé")).click()
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

        // Keep the canonical screenshot app-private, then stream it through
        // run-as into shared Pictures before instrumentation teardown uninstalls
        // the target package. UTP output remains a fallback for API variants.
        val apiLevel = android.os.Build.VERSION.SDK_INT
        val screenshotName = "ludomada-startup-offline-api-$apiLevel.png"
        val file = File(context.filesDir, screenshotName)
        assertTrue("App-private screenshot directory must exist", context.filesDir.exists())
        device.takeScreenshot(file)
        assertTrue("Startup/offline screenshot missing", file.exists() && file.length() > 1000)

        val sharedScreenshot = "/sdcard/Pictures/LudoMada/$screenshotName"
        val copyResult = device.executeShellCommand(
            "mkdir -p /sdcard/Pictures/LudoMada && " +
                "rm -f '$sharedScreenshot' && " +
                "run-as com.ludomadagascar cat 'files/$screenshotName' > '$sharedScreenshot' && " +
                "test -s '$sharedScreenshot' && " +
                "echo LUDOMADA_SCREENSHOT_SHARED_COPY_PASS"
        )
        assertTrue(
            "Screenshot must be copied before teardown; shell output: $copyResult",
            copyResult.contains("LUDOMADA_SCREENSHOT_SHARED_COPY_PASS")
        )

        context.externalMediaDirs.firstOrNull()?.let { mediaDir ->
            val utpDir = File(mediaDir, "additional_test_output")
            if (utpDir.exists() || utpDir.mkdirs()) {
                runCatching { file.copyTo(File(utpDir, screenshotName), overwrite = true) }
            }
        }
    }
}