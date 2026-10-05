package com.ludomadagascar

import com.google.android.filament.Engine
import com.google.android.filament.Filament

/**
 * Isolated Filament bootstrap for the premium 3D lane.
 *
 * The existing GLES2/GLES3 renderer remains authoritative until this lane
 * is integrated and verified on the target devices. No game state is touched.
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

    fun isAvailable(): Boolean {
        return runCatching {
            Class.forName("com.google.android.filament.Filament")
            true
        }.getOrDefault(false)
    }

    fun createEngineOrNull(): Engine? {
        if (!initialize()) return null
        return runCatching { Engine.create() }.getOrNull()
    }
}
