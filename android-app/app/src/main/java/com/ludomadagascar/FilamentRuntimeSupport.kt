package com.ludomadagascar

import com.google.android.filament.Engine
import com.google.android.filament.Filament

/**
 * Isolated bootstrap for the premium Filament lane.
 *
 * The current GameplayRenderer remains active until this capability is proven
 * on the target Android device and in Ramus. No game state is touched here.
 */
object FilamentRuntimeSupport {
    const val VERSION = "1.77.2"

    @Volatile
    private var initialized = false

    @Synchronized
    fun initialize(): Boolean {
        if (initialized) return true
        return runCatching {
            Filament.init()
            initialized = true
            true
        }.getOrDefault(false)
    }

    fun isAvailable(): Boolean =
        runCatching {
            Class.forName("com.google.android.filament.Filament")
            true
        }.getOrDefault(false)

    fun createEngineOrNull(): Engine? {
        if (!initialize()) return null
        return runCatching { Engine.create() }.getOrNull()
    }
}
