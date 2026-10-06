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
        val projectId = BuildConfig.REOWN_PROJECT_ID.trim()
        if (projectId.isBlank()) {
            // CI/trial APKs deliberately omit Reown runtime libraries. Never load an
            // optional connector class during launcher startup when it is disabled.
            return
        }
        runCatching {
            val bridge = Class.forName("com.ludomadagascar.ReownRuntime")
            val init = bridge.getMethod("initialize", Application::class.java, String::class.java)
            init.invoke(null, this, projectId)
            reownReady = true
        }.onFailure {
            reownReady = false
            Log.e("LudoMadaReown", "Wallet connector bootstrap failed", it)
        }
    }
}
