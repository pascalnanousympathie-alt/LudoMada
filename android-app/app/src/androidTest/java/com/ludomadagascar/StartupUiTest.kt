package com.ludomadagascar

import android.content.Intent
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class StartupUiTest {
    private fun scrollToVisibleText(device: UiDevice, text: String): Boolean {
        val scrollable = UiScrollable(UiSelector().scrollable(true)).apply {
            setAsVerticalList()
        }
        return scrollable.scrollIntoView(UiSelector().text(text))
    }

    private fun assertStaticHomeComposition(activity: MainActivity) {
        val content = activity.window.decorView
            .findViewById<android.view.ViewGroup>(android.R.id.content)
        assertTrue("Home content root must contain its root view", content.childCount > 0)
        val hero = content.getChildAt(0) as? PremiumHeroViewV2
        assertNotNull("Home must use the static reference-image container", hero)
        val imageHost = hero ?: throw AssertionError("Premium Home image root is missing")
        assertTrue(
            "Home must contain one static image, six stake targets and one JOUER hitbox",
            imageHost.childCount == 8
        )
        for (fee in listOf(500, 1000, 2000, 5000, 10000, 20000)) {
            val target = imageHost.getStakeAccessibilityTarget(fee)
            assertNotNull("Home stake target $fee must exist", target)
            assertTrue(
                "Home stake target $fee must be visible, enabled and clickable",
                target!!.isShown && target.isEnabled && target.isClickable &&
                    target.contentDescription == "HOME_STAKE_$fee"
            )
        }
        assertTrue("Home image child must not be a live 3D renderer", imageHost.getChildAt(0) !is LudoBoardView)
        assertTrue(
            "JOUER hitbox must be a visible enabled accessibility target",
            imageHost.getJouerAccessibilityTarget().isShown &&
                imageHost.getJouerAccessibilityTarget().isClickable &&
                imageHost.getJouerAccessibilityTarget().isEnabled &&
                imageHost.getJouerAccessibilityTarget().contentDescription == "HOME_ACTION_JOUER"
        )
        assertTrue(
            "The Home image view must publish the decoded 4K reference marker",
            imageHost.getHomeImageView().contentDescription.toString().contains("HOME_IMAGE_FRAME_COMPLETE")
        )
        assertTrue(
            "Home root must expose its static image readiness marker",
            imageHost.contentDescription.toString().contains("HOME_IMAGE_FRAME_COMPLETE")
        )
    }

    private data class HomeScreenshotMetrics(
        val meanLuma: Double,
        val standardDeviation: Double,
        val brightPixelRatio: Double
    ) {
        val containsRenderedScene: Boolean
            get() = meanLuma >= 45.0 && standardDeviation >= 10.0 && brightPixelRatio >= 0.35

        override fun toString(): String =
            "meanLuma=%.1f stdDev=%.1f brightRatio=%.3f".format(
                meanLuma, standardDeviation, brightPixelRatio
            )
    }

    private fun homeScreenshotMetrics(file: File): HomeScreenshotMetrics? {
        val bitmap = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = 4 }
        ) ?: return null
        try {
            // The upper-middle Home canvas is intentionally left unobstructed for
            // the live 3D environment. A dark, nearly uniform Android window color
            // means the transparent GL surface has not been presented yet.
            val left = (bitmap.width * 0.05f).toInt().coerceAtLeast(0)
            val right = (bitmap.width * 0.95f).toInt().coerceAtMost(bitmap.width)
            val top = (bitmap.height * 0.18f).toInt().coerceAtLeast(0)
            val bottom = (bitmap.height * 0.38f).toInt().coerceAtMost(bitmap.height)
            var count = 0
            var bright = 0
            var sum = 0.0
            var sumSquares = 0.0
            for (y in top until bottom step 2) {
                for (x in left until right step 2) {
                    val pixel = bitmap.getPixel(x, y)
                    val red = android.graphics.Color.red(pixel).toDouble()
                    val green = android.graphics.Color.green(pixel).toDouble()
                    val blue = android.graphics.Color.blue(pixel).toDouble()
                    val luma = 0.2126 * red + 0.7152 * green + 0.0722 * blue
                    sum += luma
                    sumSquares += luma * luma
                    if (luma > 60.0) bright++
                    count++
                }
            }
            if (count == 0) return null
            val mean = sum / count
            val variance = (sumSquares / count - mean * mean).coerceAtLeast(0.0)
            return HomeScreenshotMetrics(
                meanLuma = mean,
                standardDeviation = kotlin.math.sqrt(variance),
                brightPixelRatio = bright.toDouble() / count
            )
        } finally {
            bitmap.recycle()
        }
    }

    private fun captureHomeImageScreenshot(
        device: UiDevice,
        context: android.content.Context,
        activity: MainActivity
    ) {
        val screenshotFile = File(context.filesDir, "ludomada-home-preview.png")
        var lastMetrics: HomeScreenshotMetrics? = null
        var captured = false
        for (attempt in 0 until 6) {
            if (screenshotFile.exists()) {
                assertTrue("Previous Home screenshot could not be removed", screenshotFile.delete())
            }
            assertTrue("Home preview screenshot capture failed", device.takeScreenshot(screenshotFile))
            assertTrue(
                "Home preview screenshot missing or too small",
                screenshotFile.exists() && screenshotFile.length() > 1000
            )
            lastMetrics = homeScreenshotMetrics(screenshotFile)
            if (lastMetrics?.containsRenderedScene == true) {
                captured = true
                break
            }

            android.util.Log.w(
                "LudoMadaTest",
                "HOME_SCREENSHOT_NOT_PRESENTED attempt=${attempt + 1} metrics=${lastMetrics ?: "unreadable"}"
            )
            // Request a fresh image draw and retry instead of accepting a
            // syntactically valid but nearly-empty screenshot.
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val content = activity.window.decorView
                    .findViewById<android.view.ViewGroup>(android.R.id.content)
                val hero = content?.getChildAt(0) as? PremiumHeroViewV2
                hero?.getHomeImageView()?.invalidate()
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            android.os.SystemClock.sleep(350L)
        }
        assertTrue(
            "Home screenshot must contain the static reference composition; metrics=${lastMetrics ?: "unreadable"}",
            captured
        )
    }

    private fun tapHomeMusic(device: UiDevice, context: android.content.Context) {
        val home = device.findObject(By.descContains("LudoMada Premium Home"))
        val bounds = home.visibleBounds
        val scale = minOf(bounds.width() / 1280f, bounds.height() / 720f)
        val xOffset = (bounds.width() - 1280f * scale) / 2f
        val yOffset = (bounds.height() - 720f * scale) / 2f
        val x = bounds.left + (xOffset + 1193f * scale).toInt()
        val y = bounds.top + (yOffset + 86f * scale).toInt()
        assertTrue("Home music icon tap should be accepted", device.click(x, y))
    }

    private fun tapHomeJouer(device: UiDevice, context: android.content.Context) {
        assertTrue(
            "Home must expose its JOUER accessibility target",
            device.wait(Until.hasObject(By.desc("HOME_ACTION_JOUER")), 3000)
        )
        val play = device.findObject(By.desc("HOME_ACTION_JOUER"))
            ?: throw AssertionError("Home JOUER accessibility node disappeared after it was found")
        assertTrue("Home JOUER accessibility target must be clickable", play.isClickable)
        play.click()
    }

    private fun selectHomeStake(device: UiDevice, fee: Int) {
        assertTrue(
            "Home stake $fee accessibility target must appear",
            device.wait(Until.hasObject(By.desc("HOME_STAKE_$fee")), 3000)
        )
        val target = device.findObject(By.desc("HOME_STAKE_$fee"))
            ?: throw AssertionError("Home stake target $fee disappeared")
        assertTrue("Home stake $fee must be clickable", target.isClickable)
        target.click()
        assertTrue(
            "Selecting $fee must update the active Home stake",
            device.wait(Until.hasObject(By.descContains("HOME_SELECTED_STAKE=$fee")), 3000)
        )
    }

    private fun verifyHomeStakeSelector(device: UiDevice) {
        for (fee in listOf(500, 1000, 2000, 5000, 10000, 20000)) {
            assertTrue(
                "Home must expose stake $fee",
                device.wait(Until.hasObject(By.desc("HOME_STAKE_$fee")), 3000)
            )
        }
        selectHomeStake(device, 2000)
        selectHomeStake(device, 500)
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

        // Request Home while still on the current screen/orientation. Production
        // code must preserve this request through its own landscape Activity recreation.
        instrumentation.runOnMainSync {
            val activity = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MainActivity>()
                .firstOrNull()
            assertNotNull("MainActivity must be resumed before requesting Home", activity)
            activity!!.javaClass.getDeclaredMethod("home").apply {
                isAccessible = true
            }.invoke(activity)
        }

        assertTrue(
            "Home must decode and present the lightweight 4K reference image after rotation",
            device.wait(Until.hasObject(By.descContains("HOME_IMAGE_FRAME_COMPLETE")), 15000)
        )
        assertTrue(
            "Premium Home accessibility node must be exposed",
            device.wait(Until.hasObject(By.descContains("LudoMada Premium Home")), 3000)
        )
        instrumentation.runOnMainSync {
            val resumed = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MainActivity>()
                .firstOrNull()
            assertNotNull("Home MainActivity must remain resumed after rotation", resumed)
            assertTrue(
                "Home must be rendered in landscape",
                resumed!!.resources.configuration.orientation ==
                    android.content.res.Configuration.ORIENTATION_LANDSCAPE
            )
            assertStaticHomeComposition(resumed)
        }
        verifyHomeStakeSelector(device)

        val musicPrefs = context.getSharedPreferences("music", android.content.Context.MODE_PRIVATE)
        val originalMusicEnabled = musicPrefs.getBoolean("enabled", true)
        tapHomeMusic(device, context)
        device.waitForIdle()
        assertTrue(
            "Home music preference must change after the icon tap",
            musicPrefs.getBoolean("enabled", true) != originalMusicEnabled
        )
        tapHomeMusic(device, context)
        device.waitForIdle()
        assertTrue(
            "Home music icon must restore the original preference",
            musicPrefs.getBoolean("enabled", true) == originalMusicEnabled
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

        assertTrue(
            "Lobby return action must be reachable by scrolling the lobby content",
            scrollToVisibleText(device, "← Accueil")
        )
        device.findObject(By.text("← Accueil")).click()
        assertTrue("Returning from lobby must reach Home",
            device.wait(Until.hasObject(By.descContains("LudoMada Premium Home")), 5000))
        assertTrue(
            "Returning from lobby must redraw the static Home image",
            device.wait(Until.hasObject(By.descContains("HOME_IMAGE_FRAME_COMPLETE")), 5000)
        )
        var homeActivity: MainActivity? = null
        instrumentation.runOnMainSync {
            homeActivity = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MainActivity>()
                .firstOrNull()
            assertNotNull("Home MainActivity must be resumed for screenshot capture", homeActivity)
        }
        captureHomeImageScreenshot(device, context, homeActivity!!)

        tapHomeJouer(device, context)
        assertTrue("Mode overlay did not reopen from Home JOUER",
            device.wait(Until.hasObject(By.descContains("GAME_MODE_DIALOG")), 3000))
        val offlineMode = device.findObject(By.desc("MODE_OFFLINE"))
        assertTrue("Offline mode button must be clickable", offlineMode.isClickable)
        assertTrue("Offline mode button must be enabled", offlineMode.isEnabled)
        val offlineBounds = offlineMode.visibleBounds
        assertTrue(
            "Offline mode button must have visible bounds",
            offlineBounds.width() > 0 && offlineBounds.height() > 0
        )
        android.util.Log.i(
            "LudoMadaTest",
            "OFFLINE_ACCESSIBILITY_CLICK_BOUNDS=${offlineBounds.flattenToString()}"
        )
        // Match the Online action above: UiObject2.click() invokes the button's
        // accessibility ACTION_CLICK. On API 27, UiDevice.click(x, y) reports
        // success but the dialog receives no ACTION_DOWN/UP for this custom view.
        offlineMode.click()
        device.waitForIdle()
        assertTrue(
            "Offline selection must dismiss the native game-mode dialog",
            device.wait(Until.gone(By.desc("MODE_OFFLINE")), 5000)
        )
        assertTrue(
            "Offline menu screen must be rendered after activating the Offline button",
            device.wait(Until.hasObject(By.descContains("OFFLINE_MENU_SCREEN")), 8000)
        )
        assertTrue("Offline menu must expose two-player mode", device.hasObject(By.text("👤 2 joueurs")))
        assertTrue("Offline menu must expose three-player mode", device.hasObject(By.text("👥 3 joueurs")))
        assertTrue("Offline menu must expose four-player mode", device.hasObject(By.text("👥 4 joueurs")))
        assertTrue("Four-player option must be brought into the viewport", scrollToVisibleText(device, "👥 4 joueurs"))
        device.findObject(By.text("👥 4 joueurs")).click()
        assertTrue(
            "Landscape offline gameplay board must appear",
            device.wait(Until.hasObject(By.descContains("LudoMada 3D Ludo board")), 5000)
        )
        assertTrue(
            "Landscape 3D renderer must publish a completed frame",
            device.wait(Until.hasObject(By.descContains("RENDER_PASS=")), 15000)
        )
        instrumentation.runOnMainSync {
            val resumed = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MainActivity>()
                .firstOrNull()
            assertNotNull("Landscape gameplay activity must remain resumed", resumed)
            assertTrue(
                "Offline gameplay must remain in landscape",
                resumed!!.resources.configuration.orientation ==
                    android.content.res.Configuration.ORIENTATION_LANDSCAPE
            )
            val hostField = MainActivity::class.java.getDeclaredField("offlineHost").apply {
                isAccessible = true
            }
            val board = hostField.get(resumed) as? LudoBoardView
                ?: throw AssertionError("Landscape gameplay must retain the real GL board view")
            val content = resumed.window.decorView.findViewById<android.view.ViewGroup>(android.R.id.content)
            val availableHeight = content.height.coerceAtLeast(1)
            assertTrue(
                "Landscape 3D board must occupy at least one third of the gameplay content height; board=${board.width}x${board.height} contentHeight=$availableHeight",
                board.height >= availableHeight / 3
            )
            assertTrue(
                "Landscape 3D board must be wider than it is tall; board=${board.width}x${board.height}",
                board.width > board.height
            )
            android.util.Log.i(
                "LudoMadaTest",
                "LANDSCAPE_OFFLINE_BOARD_BOUNDS=${board.width}x${board.height} contentHeight=$availableHeight"
            )
        }
    }

    @Test
    fun offlineMenuReturnsToStaticHomeAfterLandscapeRotationWithoutSession() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        context.getSharedPreferences("ludomada_session", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()

        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        assertNotNull("Launch intent missing", intent)
        instrumentation.startActivitySync(intent!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

        val device = UiDevice.getInstance(instrumentation)
        assertTrue("Login screen did not appear", device.wait(Until.hasObject(By.text("Se connecter")), 8000))
        assertTrue("Offline entry action is missing", device.hasObject(By.text("👥 Jouer hors-ligne")))
        device.findObject(By.text("👥 Jouer hors-ligne")).click()
        assertTrue("Offline menu did not appear", device.wait(Until.hasObject(By.descContains("OFFLINE_MENU_SCREEN")), 3000))

        // This screen has no authenticated session. The first Home request must
        // survive Activity recreation when the device changes to landscape.
        device.findObject(By.text("← Accueil")).click()
        assertTrue(
            "Home request must survive the portrait-to-landscape Activity recreation",
            device.wait(Until.hasObject(By.descContains("HOME_IMAGE_FRAME_COMPLETE")), 10000)
        )

        var resumed: MainActivity? = null
        instrumentation.runOnMainSync {
            resumed = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MainActivity>()
                .firstOrNull()
            assertNotNull("Home MainActivity must remain resumed", resumed)
            assertTrue(
                "Home must be rendered in landscape",
                resumed!!.resources.configuration.orientation ==
                    android.content.res.Configuration.ORIENTATION_LANDSCAPE
            )
        }
        instrumentation.runOnMainSync { resumed?.finish() }
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
        assertTrue(device.wait(Until.hasObject(By.descContains("OFFLINE_MENU_SCREEN")), 3000))
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
        val screenshotName = "ludomada-startup-offline.png"
        val screenshotFile = File(context.filesDir, screenshotName)
        if (screenshotFile.exists()) {
            assertTrue("Previous startup screenshot could not be removed", screenshotFile.delete())
        }
        assertTrue(
            "Startup screenshot capture failed on API $apiLevel",
            device.takeScreenshot(screenshotFile)
        )
        assertTrue(
            "Startup screenshot missing or too small on API $apiLevel",
            screenshotFile.exists() && screenshotFile.length() > 1000
        )
        android.util.Log.i(
            "LudoMadaTest",
            "STARTUP_SCREENSHOT_SAVED api=$apiLevel name=$screenshotName bytes=${screenshotFile.length()}"
        )
    }
}