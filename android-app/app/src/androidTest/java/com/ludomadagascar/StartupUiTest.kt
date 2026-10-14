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
