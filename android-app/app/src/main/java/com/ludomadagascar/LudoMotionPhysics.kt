package com.ludomadagascar

import kotlin.math.PI
import kotlin.math.pow
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

    /** Back-style ease for a controlled overshoot around the landing point. */
    fun backOut(t: Float): Float {
        val u = t.coerceIn(0f, 1f)
        val c1 = 1.70158f
        val c3 = c1 + 1f
        val x = u - 1f
        return 1f + c3 * x * x * x + c1 * x * x
    }

    /** Elastic-out curve used only for visual squash/stretch; never feeds game state. */
    fun elasticOut(t: Float): Float {
        val u = t.coerceIn(0f, 1f)
        if (u == 0f || u == 1f) return u
        val c4 = (2f * PI) / 3f
        return (2.0.pow((-10f * u).toDouble()).toFloat() *
            sin((u * 10f - 0.75f) * c4).toFloat() + 1f)
    }
}
