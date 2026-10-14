package com.ludomadagascar

import android.app.Application
import android.util.Log

class LudoMadaApplication : Application() {
    companion object {
        @Volatile private var reownReady = false
        fun isReownReady(): Boolean = reownReady
    }

    override fun onCreate() {
        super.onCreate()
        Log.i("LudoMadaStartup", "APPLICATION_ON_CREATE_ENTER")
        val projectId = BuildConfig.REOWN_PROJECT_ID.trim()
        if (projectId.isBlank()) {
            Log.i("LudoMadaStartup", "APPLICATION_REOWN_OPTIONAL_DISABLED")
            return
        }

        // Keep Reown optional at compile time. The real-enabled build may package
        // the SDK, while the standard APK starts without loading any Reown class.
        runCatching {
            val coreClass = Class.forName("com.reown.android.CoreClient")
            val initMethod = coreClass.methods.firstOrNull { it.name == "initialize" }
                ?: error("CoreClient.initialize not found")
            Log.i("LudoMadaReown", "Reown runtime detected; initialization delegated to wallet flow.")
            reownReady = true
            Log.i("LudoMadaStartup", "APPLICATION_ON_CREATE_READY")
        }.onFailure {
            reownReady = false
            Log.e("LudoMadaReown", "Optional Reown runtime unavailable", it)
        }
    }
}
