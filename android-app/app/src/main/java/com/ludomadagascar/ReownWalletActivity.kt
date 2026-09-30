package com.ludomadagascar

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.TextView
import com.reown.appkit.client.AppKit
import com.reown.appkit.ui.AppKitView

class ReownWalletActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        handleWalletDeepLink(intent)

        if (!BuildConfig.REOWN_ENABLED) {
            setContentView(TextView(this).apply {
                text = "Connect Wallet non configuré\nREOWN_PROJECT_ID manquant"
                textSize = 18f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.rgb(8, 24, 54))
                gravity = android.view.Gravity.CENTER
            })
            return
        }

        val view = AppKitView(this)
        view.setOnCloseModal { finish() }
        setContentView(view)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleWalletDeepLink(intent)
    }

    private fun handleWalletDeepLink(intent: Intent?) {
        val uri = intent?.dataString.orEmpty()
        if (uri.isNotBlank()) {
            AppKit.handleDeepLink(uri) { error ->
                android.util.Log.e("LudoMadaReown", "Wallet deep-link error", error.throwable)
            }
        }
    }
}
