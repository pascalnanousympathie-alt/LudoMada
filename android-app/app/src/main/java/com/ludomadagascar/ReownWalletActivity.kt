package com.ludomadagascar

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import android.view.Gravity

class ReownWalletActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        handleWalletDeepLink(intent)

        if (!BuildConfig.REOWN_ENABLED) {
            showMessage("Connect Wallet non configuré\nREOWN_PROJECT_ID manquant")
            return
        }
        showAppKitOrMessage()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleWalletDeepLink(intent)
    }

    private fun showAppKitOrMessage() {
        runCatching {
            if (!LudoMadaApplication.isReownReady()) {
                showMessage("Connexion du wallet externe…")
                handler.postDelayed({ showAppKitOrMessage() }, 500L)
                return
            }
            val cls = Class.forName("com.reown.appkit.ui.AppKitView")
            val view = cls.getConstructor(android.content.Context::class.java).newInstance(this) as android.view.View
            // AppKitView is an ordinary View; close behavior is left to the SDK.
            setContentView(view)
        }.onFailure {
            showMessage("Wallet externe indisponible\nVérifie le Project ID Reown.")
        }
    }

    private fun showMessage(message: String) {
        setContentView(TextView(this).apply {
            text = message
            textSize = 18f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(8, 24, 54))
            gravity = Gravity.CENTER
        })
    }

    private fun handleWalletDeepLink(intent: Intent?) {
        val uri = intent?.dataString.orEmpty()
        if (uri.isBlank() || !BuildConfig.REOWN_ENABLED) return
        runCatching {
            val appKit = Class.forName("com.reown.appkit.client.AppKit")
            val method = appKit.methods.firstOrNull { it.name == "handleDeepLink" }
                ?: return@runCatching
            // SDK callback signatures vary across minor versions; deep-link delivery
            // is best-effort here and does not affect launcher startup.
            val params = method.parameterTypes
            when (params.size) {
                1 -> method.invoke(null, uri)
                2 -> method.invoke(null, uri, null)
            }
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
