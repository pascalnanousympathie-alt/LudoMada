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
    private fun assertLiveHomeComposition(activity: MainActivity) {
        val content = activity.window.decorView
            .findViewById<android.view.ViewGroup>(android.R.id.content)
        assertTrue("Home content root must contain its root view", content.childCount > 0)
        val hero = content.getChildAt(0) as? PremiumHeroViewV2
        assertNotNull("Premium Home root must be a FrameLayout with a live renderer", hero)
        val heroView = hero ?: throw AssertionError("Premium Home root is missing")
        assertTrue("Home must have only the 3D surface and native UI overlay", heroView.childCount == 2)
        assertTrue("First Home layer must be the live 3D board renderer", heroView.getChildAt(0) is LudoBoardView)
        assertTrue("The exposed renderer must be the actual background layer",
            heroView.homePreviewView === heroView.getChildAt(0))
        assertTrue("The foreground layer must not draw a second board renderer",
            heroView.getChildAt(1) !is LudoBoardView)
    }

    private fun captureHomePreviewScreenshot(device: UiDevice, context: android.content.Context) {
        val screenshotFile = File(context.filesDir, "ludomada-home-preview.png")
        if (screenshotFile.exists()) {
            assertTrue("Previous Home screenshot could not be removed", screenshotFile.delete())
        }
        assertTrue("Home preview screenshot capture failed", device.takeScreenshot(screenshotFile))
        assertTrue(
            "Home preview screenshot missing or too small",
            screenshotFile.exists() && screenshotFile.length() > 1000
        )
    }

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
        instrumentation.runOnMainSync { assertLiveHomeComposition(activity!!) }
        assertTrue(
            "Home must attach the live 3D scene and complete its first frame",
            device.wait(Until.hasObject(By.descContains("HOME_3D_FRAME_COMPLETE")), 15000)
        )
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
        assertTrue(
            "Returning from the lobby must render a fresh Home 3D framebuffer",
            device.wait(Until.hasObject(By.descContains("HOME_3D_FRAME_COMPLETE")), 15000)
        )
        captureHomePreviewScreenshot(device, context)

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
        assertTrue("3D board surface missing", device.wait(Until.hasObject(By.descContains("LudoMada 3D Ludo board")), 5000))
        assertTrue(device.wait(Until.hasObject(By.textContains("Lancer le dé")), 5000))
        assertTrue(
            "3D renderer must certify a GLB-backed frame with a clean framebuffer",
            device.wait(Until.hasObject(By.descContains("RENDER_PASS=")), 15000)
        )
        assertTrue(device.hasObject(By.textContains("Joueur 1/")))

        assertTrue("Dice button must remain reachable", device.hasObject(By.textContains("Lancer le dé")))
        // A six is needed to release a pawn from home. Seed that valid state directly
        // for this movement test so the CI assertion never depends on random dice odds.
        instrumentation.runOnMainSync {
            val resumed = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MainActivity>()
                .firstOrNull()
            assertNotNull("MainActivity must remain resumed", resumed)
            val diceField = MainActivity::class.java.getDeclaredField("offlineDice").apply {
                isAccessible = true
            }
            diceField.setInt(resumed, 6)
            val statusField = MainActivity::class.java.getDeclaredField("status").apply {
                isAccessible = true
            }
            val statusView = statusField.get(resumed) as? android.widget.TextView
            assertNotNull("Offline game status must be available", statusView)
            statusView!!.text = "Joueur 1/2 • Dé: 6"
        }

        for (index in 1..4) {
            assertTrue(
                "Numbered pawn control must not be visible: Pion $index",
                !device.hasObject(By.text("Pion $index"))
            )
        }
        instrumentation.runOnMainSync {
            val resumed = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MainActivity>()
                .firstOrNull()
            assertNotNull("MainActivity must remain resumed", resumed)
            val hostField = MainActivity::class.java.getDeclaredField("offlineHost").apply {
                isAccessible = true
            }
            val board = hostField.get(resumed) as? LudoBoardView
            assertNotNull("Offline 3D board must remain the pawn input surface", board)
            val pawnTap = board!!.onPawnTap
            assertNotNull("3D board pawn-tap callback must be wired", pawnTap)
            pawnTap!!.invoke(0)
        }
        assertTrue("A six gives the player another turn after tapping the board pawn",
            device.wait(Until.hasObject(By.textContains("Rejouez")), 1800))

        // Keep the screenshot app-private. The host CI helper retrieves it with
        // adb exec-out run-as, avoiding API-specific shared-storage permissions
        // and Android 8.1 emulator restrictions on public Pictures directories.
        val apiLevel = android.os.Build.VERSION.SDK_INT
        val screenshotName = "ludomada-startup-offline-api-$apiLevel.png"
        val screenshotFile = File(context.filesDir, screenshotName)
        if (screenshotFile.exists()) {
            assertTrue("Previous startup screenshot could not be removed", screenshotFile.delete())
        }
        device.takeScreenshot(screenshotFile)
        assertTrue(
            "Startup screenshot missing or too small",
            screenshotFile.exists() && screenshotFile.length() > 1000
        )
    }
}