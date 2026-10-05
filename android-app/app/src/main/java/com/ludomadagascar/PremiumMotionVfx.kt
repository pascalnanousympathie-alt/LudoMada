package com.ludomadagascar

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Visual-only premium VFX profile.
 *
 * It consumes already-authoritative transitions and never writes game state.
 */
data class PawnLandingProfile(
    val hopHeight: Float = 0.22f,
    val travelDurationMs: Long = 720L,
    val bounceAmplitude: Float = 0.035f,
    val settleCycles: Float = 1.65f
)

data class DieImpactProfile(
    val rollDurationMs: Long = 720L,
    val preImpactScale: Float = 1.045f,
    val impactScale: Float = 0.965f,
    val settleScale: Float = 1.0f,
    val cameraImpulse: Float = 0.010f
)

data class CaptureVfxProfile(
    val flashSeconds: Float = 0.11f,
    val ringSeconds: Float = 0.24f,
    val particleLifeSeconds: Float = 0.48f,
    val particleCount: Int = 18
)

object PremiumMotionVfx {
    val pawn = PawnLandingProfile()
    val die = DieImpactProfile()
    val capture = CaptureVfxProfile()

    fun easeOutCubic(t: Float): Float {
        val u = t.coerceIn(0f, 1f)
        val x = 1f - u
        return 1f - x * x * x
    }

    fun arc(t: Float): Float {
        val u = t.coerceIn(0f, 1f)
        return 4f * pawn.hopHeight * u * (1f - u)
    }

    fun landingBounce(t: Float): Float {
        val u = t.coerceIn(0f, 1f)
        val envelope = (1f - u) * (1f - u)
        return pawn.bounceAmplitude * envelope *
            sin(u * PI.toFloat() * pawn.settleCycles * 2f)
    }

    fun squash(t: Float): Float {
        val u = t.coerceIn(0f, 1f)
        val impact = (1f - abs(u - 0.78f) / 0.22f).coerceIn(0f, 1f)
        return 1f + 0.05f * impact
    }

    fun stretch(t: Float): Float {
        val u = t.coerceIn(0f, 1f)
        val flight = (1f - abs(u - 0.34f) / 0.34f).coerceIn(0f, 1f)
        return 1f - 0.04f * flight
    }

    fun dieSpin(t: Float): Float {
        return easeOutCubic(t)
    }

    fun dieImpactScale(t: Float): Float {
        val u = t.coerceIn(0f, 1f)
        return when {
            u < 0.58f -> die.preImpactScale
            u < 0.78f -> {
                val k = (u - 0.58f) / 0.20f
                die.preImpactScale + (die.impactScale - die.preImpactScale) * k
            }
            else -> {
                val k = (u - 0.78f) / 0.22f
                die.impactScale + (die.settleScale - die.impactScale) * k
            }
        }
    }

    fun cameraImpulse(t: Float): Float {
        val u = t.coerceIn(0f, 1f)
        return die.cameraImpulse * sin(u * PI.toFloat()) * (1f - u)
    }

    fun captureFlash(t: Float): Float {
        val u = (t / capture.flashSeconds).coerceIn(0f, 1f)
        return 1f - u
    }

    fun captureRing(t: Float): Float {
        val u = (t / capture.ringSeconds).coerceIn(0f, 1f)
        return 1f - u
    }

    fun particleRadius(t: Float): Float {
        val u = (t / capture.particleLifeSeconds).coerceIn(0f, 1f)
        return 0.18f + 0.72f * easeOutCubic(u)
    }

    fun particleAlpha(t: Float): Float {
        val u = (t / capture.particleLifeSeconds).coerceIn(0f, 1f)
        return (1f - u) * (1f - u)
    }

    fun particleOrbit(index: Int, count: Int, t: Float): FloatArray {
        val u = (t / capture.particleLifeSeconds).coerceIn(0f, 1f)
        val phase = if (count <= 0) 0f else index.toFloat() / count.toFloat() * 2f * PI.toFloat()
        val radius = particleRadius(t)
        val y = 0.10f + 0.42f * sin(u * PI.toFloat())
        return floatArrayOf(
            radius * cos(phase + u * 1.7f),
            y,
            radius * sin(phase + u * 1.7f)
        )
    }
}
