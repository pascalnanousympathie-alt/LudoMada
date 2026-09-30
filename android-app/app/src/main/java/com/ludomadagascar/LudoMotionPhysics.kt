package com.ludomadagascar

import kotlin.math.PI
import kotlin.math.sin

/**
 * Deterministic mobile physics used for Ludo pawn motion.
 * A full rigid-body engine is intentionally not used: Ludo pieces only need
 * gravity-like hops, damping and smooth angular inertia, keeping the gameplay
 * renderer light enough for Android 8.1 / mid-range GPUs.
 */
object LudoMotionPhysics {
    fun ballisticArc(t: Float, height: Float): Float {
        val u = t.coerceIn(0f, 1f)
        return (4f * height * u * (1f - u)).coerceAtLeast(0f)
    }

    fun spring(t: Float, amplitude: Float): Float {
        val u = t.coerceIn(0f, 1f)
        val envelope = (1f - u) * (1f - u)
        return amplitude * envelope * sin(u * PI.toFloat() * 3.0f)
    }

    fun sway(t: Float, degrees: Float): Float {
        val u = t.coerceIn(0f, 1f)
        return degrees * sin(u * PI.toFloat() * 2.0f) * (1f - u)
    }
}
