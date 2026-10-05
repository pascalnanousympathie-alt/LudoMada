package com.ludomadagascar

import com.google.android.filament.Engine
import com.google.android.filament.Filament

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
