package com.ludomadagascar

import android.app.Application
import android.util.Log
import com.reown.android.Core
import com.reown.android.CoreClient
import com.reown.appkit.client.AppKit
import com.reown.appkit.client.Modal
import com.reown.appkit.presets.AppKitChainsPresets

object ReownRuntime {
    @JvmStatic
    fun initialize(application: Application, projectId: String) {
        val metadata = Core.Model.AppMetaData(
            name = "LudoMada",
            description = "Jeu de Ludo et portefeuille crypto",
            url = "https://ludomada-production.up.railway.app",
            icons = emptyList(),
            redirect = "ludomada://walletconnect",
            linkMode = true
        )
        CoreClient.initialize(
            application = application,
            projectId = projectId,
            metaData = metadata
        ) { error ->
            Log.e("LudoMadaReown", "Core initialization failed", error.throwable)
        }
        AppKit.initialize(
            init = Modal.Params.Init(core = CoreClient),
            onSuccess = {
                Log.i("LudoMadaReown", "AppKit ready")
            },
            onError = { error ->
                Log.e("LudoMadaReown", "AppKit initialization failed", error.throwable)
            }
        )
        AppKit.setChains(AppKitChainsPresets.ethChains.values.toList())
    }

    @JvmStatic
    fun accountSummary(): String {
        val account = AppKit.getAccount() ?: return ""
        return account.address.take(6) + "…" + account.address.takeLast(4) + " • " + account.chain.chainName
    }
}
