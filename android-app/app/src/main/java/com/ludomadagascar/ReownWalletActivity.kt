package com.ludomadagascar

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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

        if (LudoMadaApplication.isReownReady()) {
            showAppKit()
        } else {
            setContentView(TextView(this).apply {
                text = "Connexion du wallet externe…"
                textSize = 18f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.rgb(8, 24, 54))
                gravity = android.view.Gravity.CENTER
            })
            waitForReownReady(0)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleWalletDeepLink(intent)
    }

    private val handler = Handler(Looper.getMainLooper())
    private fun showAppKit() {
        val view = AppKitView(this)
        view.setOnCloseModal { finish() }
        setContentView(view)
    }
    private fun waitForReownReady(attempt: Int) {
        if (LudoMadaApplication.isReownReady()) {
            showAppKit()
        } else if (attempt >= 20) {
            setContentView(TextView(this).apply {
                text = "Wallet externe indisponible\nVérifie le Project ID Reown."
                textSize = 18f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.rgb(8, 24, 54))
                gravity = android.view.Gravity.CENTER
            })
        } else {
            handler.postDelayed({ waitForReownReady(attempt + 1) }, 500L)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
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
