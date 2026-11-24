package com.ludomadagascar

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class StartupUiTest {
    private fun findBoardSurface(view: View): SurfaceView? {
        if (view is SurfaceView &&
            view.contentDescription?.toString()?.contains("LudoMada 3D Ludo board") == true) {
            return view
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findBoardSurface(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    /**
     * API 27 UiDevice screenshots can omit the separately-composited GLSurfaceView
     * even when GLES backbuffer readback proves that PBR rendered a complete frame.
     * PixelCopy captures the SurfaceView buffer directly, so renderer evidence is
     * kept separate from the whole-screen UI screenshot.
     */
    private fun captureBoardSurface(
        instrumentation: android.app.Instrumentation,
        context: android.content.Context,
        apiLevel: Int
    ) {
        var boardSurface: SurfaceView? = null
        instrumentation.runOnMainSync {
            val activity = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MainActivity>()
                .firstOrNull()
            assertNotNull("MainActivity must be resumed for PixelCopy", activity)
            boardSurface = findBoardSurface(activity!!.window.decorView)
        }
        val source = boardSurface
        assertNotNull("3D GLSurfaceView was not found in the resumed Activity", source)
        val surfaceView = source!!
        assertTrue("3D SurfaceView must have a valid surface", surfaceView.holder.surface.isValid)
        assertTrue("3D SurfaceView has invalid dimensions ${surfaceView.width}x${surfaceView.height}",
            surfaceView.width > 0 && surfaceView.height > 0)

        val bitmap = Bitmap.createBitmap(surfaceView.width, surfaceView.height, Bitmap.Config.ARGB_8888)
        val completed = CountDownLatch(1)
        var copyResult = PixelCopy.ERROR_UNKNOWN
        PixelCopy.request(surfaceView, bitmap, { result ->
            copyResult = result
            completed.countDown()
        }, Handler(Looper.getMainLooper()))
        assertTrue("PixelCopy did not complete within 10 seconds", completed.await(10, TimeUnit.SECONDS))
        assertEquals("PixelCopy failed for the PBR board surface", PixelCopy.SUCCESS, copyResult)

        val sampleStep = maxOf(1, minOf(bitmap.width, bitmap.height) / 128)
        val colorBuckets = HashSet<Int>()
        var sampledPixels = 0
        var chromaticPixels = 0
        for (y in 0 until bitmap.height step sampleStep) {
            for (x in 0 until bitmap.width step sampleStep) {
                val pixel = bitmap.getPixel(x, y)
                val red = Color.red(pixel)
                val green = Color.green(pixel)
                val blue = Color.blue(pixel)
                colorBuckets.add(((red shr 4) shl 8) or ((green shr 4) shl 4) or (blue shr 4))
                if (maxOf(red, green, blue) - minOf(red, green, blue) > 18) chromaticPixels++
                sampledPixels++
            }
        }
        assertTrue("PixelCopy board surface appears blank/flat: colorBuckets=${colorBuckets.size}",
            colorBuckets.size >= 24)
        assertTrue("PixelCopy board surface has insufficient rendered color detail: $chromaticPixels/$sampledPixels",
            chromaticPixels > sampledPixels / 25)

        val file = File(context.filesDir, "ludomada-board-surface-api-$apiLevel.png")
        if (file.exists()) assertTrue("Previous board surface capture could not be removed", file.delete())
        FileOutputStream(file).use { output ->
            assertTrue("Could not encode board surface PixelCopy PNG", bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            output.flush()
        }
        assertTrue("Board surface PixelCopy PNG missing or too small", file.exists() && file.length() > 1000)
        android.util.Log.i("LudoMadaTest",
            "ANDROID_BOARD_SURFACE_PIXCOPY_WRITTEN api=$apiLevel size=${bitmap.width}x${bitmap.height} " +
                "colorBuckets=${colorBuckets.size} chromaticPixels=$chromaticPixels/$sampledPixels bytes=${file.length()}")
        bitmap.recycle()
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
        val debugPrefs = context.getSharedPreferences("ludomada_debug", android.content.Context.MODE_PRIVATE)
        debugPrefs.edit()
            .remove("pbr_first_frame_complete")
            .remove("last_frame_pacing_report")
            .remove("frame_pacing_probe_started")
            .remove("frame_pacing_probe_ended")
            .remove("frame_pacing_probe_samples")
            .remove("frame_pacing_probe_progress")
            .commit()
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
        // A visible GLSurfaceView only proves layout. Wait for PBR scene rendering,
        // post-processing and backbuffer readback before capturing visual evidence.
        val firstFrameDeadline = System.currentTimeMillis() + 30_000
        while (!debugPrefs.getBoolean("pbr_first_frame_complete", false) &&
            System.currentTimeMillis() < firstFrameDeadline) {
            Thread.sleep(50)
        }
        assertTrue(
            "PBR first frame did not complete scene rendering and backbuffer readback within 30 seconds",
            debugPrefs.getBoolean("pbr_first_frame_complete", false)
        )
        Thread.sleep(250)
        // Capture the GLSurfaceView buffer directly before asking UiAutomator for
        // the whole-screen screenshot. This avoids mistaking an API 27 screenshot
        // omission of the separately-composited SurfaceView for a renderer failure.
        val apiLevel = android.os.Build.VERSION.SDK_INT
        captureBoardSurface(instrumentation, context, apiLevel)
        // Keep the independent whole-screen screenshot for UI/layout evidence.
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
        android.util.Log.i("LudoMadaTest", "ANDROID_UI_SCREENSHOT_WRITTEN bytes=${screenshotFile.length()} name=$screenshotName")

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

        // The first dice animation starts one bounded debug-only continuous render probe.
        // It records actual GL draw intervals and preserves the sample-window diagnostics.
        var framePacingReport = debugPrefs.getString("last_frame_pacing_report", null)
        val framePacingDeadline = System.currentTimeMillis() + 90_000L
        while (framePacingReport.isNullOrBlank() &&
            System.currentTimeMillis() < framePacingDeadline) {
            Thread.sleep(50)
            framePacingReport = debugPrefs.getString("last_frame_pacing_report", null)
        }
        val probeStarted = debugPrefs.getBoolean("frame_pacing_probe_started", false)
        val probeEnded = debugPrefs.getBoolean("frame_pacing_probe_ended", false)
        val probeSamples = debugPrefs.getInt("frame_pacing_probe_samples", 0)
        val probeProgress = debugPrefs.getString("frame_pacing_probe_progress", "not-recorded")
        assertTrue(
            "PBR frame-pacing report missing; started=$probeStarted ended=$probeEnded samples=$probeSamples progress=$probeProgress",
            !framePacingReport.isNullOrBlank() && framePacingReport!!.contains("samples=120")
        )
        android.util.Log.i("LudoMadaTest", "ANDROID_FRAME_PACING_REPORT=$framePacingReport")

        assertTrue(device.hasObject(By.text("Pion 1")))
        device.findObject(By.text("Pion 1")).click()
        assertTrue("A six gives the player another turn after the pawn move",
            device.wait(Until.hasObject(By.textContains("Rejouez")), 1800))

    }
}