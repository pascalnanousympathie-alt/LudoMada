package com.ludomadagascar

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

data class PremiumPawnMotion(
    val arcHeight: Float = 0.22f,
    val landingOvershoot: Float = 0.055f,
    val bounceAmplitude: Float = 0.035f,
    val settleSeconds: Float = 0.22f
)

object PremiumVisualMotion {
    val pawn = PremiumPawnMotion()

    fun smoothStep(t: Float): Float {
        val u = t.coerceIn(0f, 1f)
        return u * u * (3f - 2f * u)
    }

    fun ballisticArc(t: Float, height: Float = pawn.arcHeight): Float {
        val u = t.coerceIn(0f, 1f)
        return 4f * height * u * (1f - u)
    }

    fun landingBounce(t: Float, amplitude: Float = pawn.bounceAmplitude): Float {
        val u = t.coerceIn(0f, 1f)
        return amplitude * (1f - u) * (1f - u) * sin(u * PI.toFloat() * 3.5f)
    }

    fun controlledOvershoot(t: Float, amount: Float = pawn.landingOvershoot): Float {
        val u = t.coerceIn(0f, 1f)
        val x = u - 1f
        return u + amount * (x * x * x + x)
    }

    fun squash(t: Float): Float {
        val u = t.coerceIn(0f, 1f)
        val impact = (1f - abs(u - 0.72f) / 0.28f).coerceIn(0f, 1f)
        return 1f + 0.055f * impact
    }

    fun stretch(t: Float): Float {
        val u = t.coerceIn(0f, 1f)
        val flight = (1f - abs(u - 0.35f) / 0.35f).coerceIn(0f, 1f)
        return 1f - 0.045f * flight
    }

    fun dieSpin(t: Float): Float {
        val u = t.coerceIn(0f, 1f)
        return 1f - (1f - u) * (1f - u)
    }
}
