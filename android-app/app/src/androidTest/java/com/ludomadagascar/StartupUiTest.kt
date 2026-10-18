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
    fun premium_home_exposes_and_navigates_primary_menus_and_options() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        context.getSharedPreferences("ludomada_session", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
        val settingsPrefs = context.getSharedPreferences("ludomada_settings", android.content.Context.MODE_PRIVATE)
        settingsPrefs.edit().putString("language", "fr").putInt("defaultEntryFee", 500).commit()
        val musicPrefs = context.getSharedPreferences("music", android.content.Context.MODE_PRIVATE)
        val originalMusicState = musicPrefs.getBoolean("enabled", true)

        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        assertNotNull("Launch intent missing", intent)
        intent!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        instrumentation.startActivitySync(intent)

        val device = UiDevice.getInstance(instrumentation)
        fun captureEvidence(name: String) {
            val file = File(instrumentation.context.cacheDir, name)
            device.takeScreenshot(file)
            assertTrue("Screenshot evidence missing: $name", file.exists() && file.length() > 1000)
        }
        assertTrue("Premium Home did not render", device.wait(Until.hasObject(By.desc("LudoMada Premium Home")), 8000))
        captureEvidence("ludomada-home.png")

        val homeControls = listOf(
            "HOME_PROFILE_CHIP",
            "HOME_CREATE_ROOM",
            "HOME_JOIN_ROOM",
            "HOME_PROFILE",
            "HOME_MARKET",
            "HOME_WALLET_SHORTCUT",
            "HOME_MUSIC_TOGGLE",
            "HOME_TOURNAMENTS",
            "HOME_TUTORIALS",
            "HOME_ENTRY_FEE_500",
            "HOME_ENTRY_FEE_1000",
            "HOME_ENTRY_FEE_2000",
            "HOME_ENTRY_FEE_5000",
            "HOME_ENTRY_FEE_10000",
            "HOME_ENTRY_FEE_20000"
        )
        homeControls.forEach { description ->
            assertTrue(
                "Home control missing from accessibility tree: $description",
                device.wait(Until.hasObject(By.desc(description)), 3000)
            )
        }

        // JOUER must expose both functional modes; online opens the real stake lobby.
        device.findObject(By.desc("JOUER")).click()
        assertTrue("Game-mode screen did not open from Home", device.wait(Until.hasObject(By.desc("GAME_MODE_DIALOG")), 3000))
        assertTrue("Offline mode target missing", device.hasObject(By.desc("MODE_OFFLINE")))
        assertTrue("Online mode target missing", device.hasObject(By.desc("MODE_ONLINE")))
        captureEvidence("ludomada-game-mode.png")
        device.findObject(By.desc("MODE_ONLINE")).click()
        assertTrue("Online mode should open the stake lobby", device.wait(Until.hasObject(By.text("Choisir la mise")), 3000))
        device.findObject(By.text("← Accueil")).click()
        assertTrue("Stake lobby should return to Home", device.wait(Until.hasObject(By.desc("LudoMada Premium Home")), 5000))

        // Entry fees are real selectable controls rather than decorative Canvas text.
        assertTrue("500 Ar should be selected on first launch", device.findObject(By.desc("HOME_ENTRY_FEE_500")).isSelected)
        device.findObject(By.desc("HOME_ENTRY_FEE_1000")).click()
        instrumentation.waitForIdleSync()
        assertTrue("1000 Ar selection did not update", device.findObject(By.desc("HOME_ENTRY_FEE_1000")).isSelected)
        assertTrue("500 Ar should no longer be selected", !device.findObject(By.desc("HOME_ENTRY_FEE_500")).isSelected)
        assertTrue("Selected entry fee should persist locally", settingsPrefs.getInt("defaultEntryFee", 0) == 1000)

        // The Home music icon must control the same local music preference as the lobby.
        device.findObject(By.desc("HOME_MUSIC_TOGGLE")).click()
        instrumentation.waitForIdleSync()
        assertTrue("Home music toggle did not change the saved state", musicPrefs.getBoolean("enabled", true) != originalMusicState)
        device.findObject(By.desc("HOME_MUSIC_TOGGLE")).click()
        instrumentation.waitForIdleSync()
        assertTrue("Home music toggle did not restore the saved state", musicPrefs.getBoolean("enabled", true) == originalMusicState)

        // Profile, Market, leaderboard and Tutorials must all be reachable from Home.
        device.findObject(By.desc("HOME_PROFILE")).click()
        assertTrue("Profile menu did not open", device.wait(Until.hasObject(By.text("Langue / Language")), 3000))
        device.findObject(By.text("⚙ Paramètres")).click()
        assertTrue("Settings screen did not open", device.wait(Until.hasObject(By.text("Préférences de l'application")), 3000))
        assertTrue("Settings screen should expose persisted French locale", device.hasObject(By.text("Français • préférence enregistrée sur cet appareil")))
        device.findObject(By.text("🌐 Changer de langue")).click()
        assertTrue("Language choices did not open", device.wait(Until.hasObject(By.text("Malagasy")), 2000))
        device.findObject(By.text("Malagasy")).click()
        assertTrue("Activity should return to Home after changing language", device.wait(Until.hasObject(By.desc("LudoMada Premium Home")), 5000))
        assertTrue("Malagasy locale was not persisted", settingsPrefs.getString("language", "") == "mg")
        device.findObject(By.desc("HOME_PROFILE")).click()
        device.findObject(By.text("⚙ Paramètres")).click()
        assertTrue("Settings must remember Malagasy across recreation", device.wait(Until.hasObject(By.text("Malagasy • préférence enregistrée sur cet appareil")), 3000))
        device.findObject(By.text("🌐 Changer de langue")).click()
        assertTrue("French choice missing", device.wait(Until.hasObject(By.text("Français")), 2000))
        device.findObject(By.text("Français")).click()
        assertTrue("French reset should return Home", device.wait(Until.hasObject(By.desc("LudoMada Premium Home")), 5000))
        device.findObject(By.desc("HOME_PROFILE")).click()
        device.findObject(By.text("← Miverina")).click()
        assertTrue("Profile did not return to Home", device.wait(Until.hasObject(By.desc("LudoMada Premium Home")), 5000))

        device.findObject(By.desc("HOME_WALLET_SHORTCUT")).click()
        assertTrue("Wallet menu did not open", device.wait(Until.hasObject(By.text("SOLDE MGA")), 5000))
        assertTrue("Unauthenticated Wallet must not fabricate a balance", device.hasObject(By.text("— Ar")))
        captureEvidence("ludomada-wallet.png")
        device.findObject(By.text("⌂ Accueil")).click()
        assertTrue("Wallet did not return to Home", device.wait(Until.hasObject(By.desc("LudoMada Premium Home")), 5000))

        device.findObject(By.desc("HOME_MARKET")).click()
        assertTrue("3D Market screen did not open", device.wait(Until.hasObject(By.text("Aperçu live 3D")), 5000))
        captureEvidence("ludomada-market.png")
        device.findObject(By.text("← Accueil")).click()
        assertTrue("Market did not return to Home", device.wait(Until.hasObject(By.desc("LudoMada Premium Home")), 5000))

        device.findObject(By.desc("HOME_TOURNAMENTS")).click()
        assertTrue("Leaderboard menu did not open", device.wait(Until.hasObject(By.text("↻ Actualiser le classement")), 3000))
        device.findObject(By.text("← Accueil")).click()
        assertTrue("Leaderboard did not return to Home", device.wait(Until.hasObject(By.desc("LudoMada Premium Home")), 5000))

        device.findObject(By.desc("HOME_TUTORIALS")).click()
        assertTrue("Tutorial menu did not open", device.wait(Until.hasObject(By.text("Maîtriser le Ludo")), 3000))
        device.findObject(By.desc("Accueil")).click()
        assertTrue("Tutorials did not return to Home", device.wait(Until.hasObject(By.desc("LudoMada Premium Home")), 5000))

        // Room entry and creation must reach their intended next step without a signed-in session.
        device.findObject(By.desc("HOME_JOIN_ROOM")).click()
        assertTrue("Join-room dialog did not open", device.wait(Until.hasObject(By.text("AOKA")), 3000))
        device.findObject(By.text("AOKA")).click()
        assertTrue("Join-room dismissal should preserve Home", device.wait(Until.hasObject(By.desc("LudoMada Premium Home")), 3000))

        device.findObject(By.desc("HOME_CREATE_ROOM")).click()
        assertTrue("Create-room menu should open the entry-fee lobby", device.wait(Until.hasObject(By.text("Choisir la mise")), 3000))
        device.findObject(By.text("500 Ar")).click()
        assertTrue("Selecting a stake without a session should route to login", device.wait(Until.hasObject(By.text("CONNEXION")), 3000))
    }

    @Test
    fun app_launches_premium_home_and_reaches_offline_game_flow() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val info = context.packageManager.getApplicationInfo(context.packageName, 0)
        context.getSharedPreferences("ludomada_session", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("ludomada_settings", android.content.Context.MODE_PRIVATE)
            .edit().putString("language", "fr").putInt("defaultEntryFee", 500).commit()
        assertTrue("Launcher icon resource must exist", info.icon != 0)

        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        assertNotNull("Launch intent missing", intent)
        intent!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        instrumentation.startActivitySync(intent)

        val device = UiDevice.getInstance(instrumentation)
        fun captureEvidence(name: String) {
            val file = File(instrumentation.context.cacheDir, name)
            device.takeScreenshot(file)
            assertTrue("Screenshot evidence missing: $name", file.exists() && file.length() > 1000)
        }

        // Premium Home is the first screen; authentication is only required for online actions.
        assertTrue(device.wait(Until.hasObject(By.desc("LudoMada Premium Home")), 8000))
        assertTrue("Premium Home must expose the primary play target", device.hasObject(By.desc("JOUER")))

        device.findObject(By.desc("JOUER")).click()
        assertTrue(device.wait(Until.hasObject(By.desc("MODE_OFFLINE")), 3000))

        device.findObject(By.desc("MODE_OFFLINE")).click()
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
        captureEvidence("ludomada-gameplay-2p.png")
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

        // Read DEBUG-only, real game state from the existing 3D board accessibility node.
        // The tap uses the same normalized projected-pawn coordinates as LudoBoardView.onTouchEvent.
        fun offlineSmokeMarkers(): Map<String, String> {
            val board = device.findObject(By.descContains("OFFLINE_SMOKE_STATE=")) ?: return emptyMap()
            return board.contentDescription?.toString().orEmpty()
                .split("|")
                .mapNotNull { marker ->
                    val separator = marker.indexOf('=')
                    if (separator <= 0) null else marker.substring(0, separator) to marker.substring(separator + 1)
                }
                .toMap()
        }
        fun parseSmokeFields(raw: String?): Map<String, String> = raw.orEmpty()
            .split(",")
            .mapNotNull { field ->
                val separator = field.indexOf('=')
                if (separator <= 0) null else field.substring(0, separator) to field.substring(separator + 1)
            }
            .toMap()
        fun smokePositions(markers: Map<String, String>, player: Int): List<Int> {
            val playerRow = markers["OFFLINE_SMOKE_POSITIONS"]?.split(';')
                ?.firstOrNull { it.startsWith("$player:") } ?: return emptyList()
            return playerRow.substringAfter(':').split(',').mapNotNull { it.toIntOrNull() }
        }

        var beforeMarkers = emptyMap<String, String>()
        var beforeState = emptyMap<String, String>()
        var beforePositions = emptyList<Int>()
        var turn = -1
        for (attempt in 0 until 20) {
            beforeMarkers = offlineSmokeMarkers()
            beforeState = parseSmokeFields(beforeMarkers["OFFLINE_SMOKE_STATE"])
            turn = beforeState["turn"]?.toIntOrNull() ?: -1
            if (beforeState["players"] == "2" && beforeState["dice"] == "6" && turn >= 0) {
                beforePositions = smokePositions(beforeMarkers, turn)
                if (beforePositions.size == 4) break
            }
            Thread.sleep(250)
        }
        assertTrue("DEBUG state did not report a valid two-player six roll: $beforeState",
            beforeState["players"] == "2" && beforeState["dice"] == "6" && turn >= 0)
        assertTrue("Four token positions were not exposed: $beforeMarkers", beforePositions.size == 4)
        val tokenIndex = beforePositions.indexOfFirst { position -> position == -1 || position >= 0 && position + 6 <= 57 }
        assertTrue("No legal token found after six: $beforePositions", tokenIndex >= 0)

        val targetEntry = beforeMarkers["OFFLINE_SMOKE_TOKENS"]?.split(';')
            ?.firstOrNull { it.startsWith("$tokenIndex:") }
            ?: throw AssertionError("Projected target for legal token $tokenIndex is missing: $beforeMarkers")
        val targetParts = targetEntry.split('@')
        assertTrue("Projected target coordinates are malformed: $targetEntry", targetParts.size == 2)
        val scenePoint = targetParts[0].substringAfter(':').split(',')
        val normalizedPoint = targetParts[1].split(',')
        assertTrue("Scene point is malformed: $targetEntry", scenePoint.size == 2)
        assertTrue("Normalized input point is malformed: $targetEntry", normalizedPoint.size == 2)
        val sceneX = scenePoint[0].toFloatOrNull() ?: throw AssertionError("Invalid scene X: $targetEntry")
        val sceneY = scenePoint[1].toFloatOrNull() ?: throw AssertionError("Invalid scene Y: $targetEntry")
        val inputX = normalizedPoint[0].toFloatOrNull() ?: throw AssertionError("Invalid input X: $targetEntry")
        val inputY = normalizedPoint[1].toFloatOrNull() ?: throw AssertionError("Invalid input Y: $targetEntry")
        val viewDimensions = beforeMarkers["OFFLINE_SMOKE_VIEW"]?.split(',') ?: emptyList()
        assertTrue("View and GL viewport dimensions are missing: $viewDimensions", viewDimensions.size == 2)
        val sceneDimensions = viewDimensions[1].split('x')
        assertTrue("GL viewport dimensions are malformed: $viewDimensions", sceneDimensions.size == 2)
        val sceneWidth = sceneDimensions[0].toFloatOrNull() ?: -1f
        val sceneHeight = sceneDimensions[1].toFloatOrNull() ?: -1f
        assertTrue("Projected pawn is outside the GL viewport: $targetEntry / $viewDimensions",
            sceneWidth > 0f && sceneHeight > 0f && sceneX in 0f..sceneWidth && sceneY in 0f..sceneHeight)
        assertTrue("Pawn touch input must be normalized inside the board: $targetEntry",
            inputX >= 0f && inputX < 1f && inputY >= 0f && inputY < 1f)

        val boardView = device.findObject(By.descContains("OFFLINE_SMOKE_STATE="))
            ?: throw AssertionError("3D board accessibility target disappeared before pawn tap")
        val boardBounds = boardView.visibleBounds
        assertTrue("3D board has no visible tappable bounds: $boardBounds", boardBounds.width() > 0 && boardBounds.height() > 0)
        val tapX = boardBounds.left + (inputX * boardBounds.width()).toInt()
        val tapY = boardBounds.top + (inputY * boardBounds.height()).toInt()
        assertTrue("Ramus-equivalent tap fell outside the projected board bounds: $tapX,$tapY / $boardBounds",
            tapX in boardBounds.left until boardBounds.right && tapY in boardBounds.top until boardBounds.bottom)
        assertTrue("Touch on projected legal pawn was rejected", device.click(tapX, tapY))

        var afterMarkers = emptyMap<String, String>()
        var afterState = emptyMap<String, String>()
        var afterPositions = emptyList<Int>()
        for (attempt in 0 until 20) {
            Thread.sleep(250)
            afterMarkers = offlineSmokeMarkers()
            afterState = parseSmokeFields(afterMarkers["OFFLINE_SMOKE_STATE"])
            afterPositions = smokePositions(afterMarkers, turn)
            if (afterPositions.size == 4 && afterPositions[tokenIndex] != beforePositions[tokenIndex]) break
        }
        assertTrue("Real pawn tap did not move its token: before=$beforePositions after=$afterPositions",
            afterPositions.size == 4 && afterPositions[tokenIndex] == 0)
        assertTrue("A six-roll pawn move must clear the die and preserve the extra turn: $afterState",
            afterState["dice"] == "0" && afterState["turn"] == turn.toString())
        captureEvidence("ludomada-gameplay-pawn-moved.png")

        // Game HUD settings must be functional and return to the same offline match.
        device.findObject(By.desc("PARAMETRES")).click()
        assertTrue("Gameplay settings control did not open settings", device.wait(Until.hasObject(By.text("Préférences de l'application")), 3000))
        device.pressBack()
        assertTrue("Closing settings should restore the active 2P match", device.wait(Until.hasObject(By.descContains("GAMEPLAY_3D_OFFLINE_2P")), 8000))
        assertTrue("Dice HUD must remain reachable after settings", device.hasObject(By.desc("TOUCHEZ_LE_DE")))

        device.findObject(By.desc("MENU_JEU")).click()
        assertTrue(device.wait(Until.hasObject(By.desc("OFFLINE_3_PLAYERS")), 3000))
        device.findObject(By.desc("OFFLINE_3_PLAYERS")).click()
        assertGameplayLoaded(3)
        captureEvidence("ludomada-gameplay-3p.png")

        device.findObject(By.desc("MENU_JEU")).click()
        assertTrue(device.wait(Until.hasObject(By.desc("OFFLINE_4_PLAYERS")), 3000))
        device.findObject(By.desc("OFFLINE_4_PLAYERS")).click()
        assertGameplayLoaded(4)
        captureEvidence("ludomada-gameplay-4p.png")

        val file = File(instrumentation.context.cacheDir, "ludomada-startup-offline.png")
        device.takeScreenshot(file)
        assertTrue("Startup/offline screenshot missing", file.exists() && file.length() > 1000)
    }
}
