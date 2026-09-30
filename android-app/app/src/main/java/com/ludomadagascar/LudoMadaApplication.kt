package com.ludomadagascar

import android.app.Application
import android.util.Log
import com.reown.android.Core
import com.reown.android.CoreClient
import com.reown.appkit.client.AppKit
import com.reown.appkit.client.Modal
import com.reown.appkit.presets.AppKitChainsPresets

class LudoMadaApplication : Application() {
    companion object {
        @Volatile private var reownReady = false
        fun isReownReady(): Boolean = reownReady
    }
    override fun onCreate() {
        super.onCreate()

        val projectId = BuildConfig.REOWN_PROJECT_ID.trim()
        if (projectId.isBlank()) return

        val metadata = Core.Model.AppMetaData(
            name = "LudoMada",
            description = "Jeu de Ludo et portefeuille crypto",
            url = "https://ludomada-production.up.railway.app",
            icons = emptyList(),
            redirect = "ludomada://walletconnect",
            linkMode = true
        )

        runCatching {
            CoreClient.initialize(
                application = this,
                projectId = projectId,
                metaData = metadata
            ) { error ->
                Log.e("LudoMadaReown", "Core initialization failed", error.throwable)
            }

            AppKit.initialize(
                init = Modal.Params.Init(core = CoreClient),
                onSuccess = {
                    reownReady = true
                    Log.i("LudoMadaReown", "AppKit ready")
                },
                onError = { error ->
                    reownReady = false
                    Log.e("LudoMadaReown", "AppKit initialization failed", error.throwable)
                }
            )

            AppKit.setChains(AppKitChainsPresets.ethChains.values.toList())
        }.onFailure {
            Log.e("LudoMadaReown", "Wallet connector bootstrap failed", it)
        }
    }
}
