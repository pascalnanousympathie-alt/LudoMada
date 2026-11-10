package com.ludomadagascar

import java.util.Arrays
import kotlin.math.ceil
import kotlin.math.min

internal data class AnimationVsyncSnapshot(
    val sampleCount: Int,
    val fps: Float?,
    val intervalP50Ms: Float?,
    val intervalP95Ms: Float?
)

/**
 * Samples main-thread Choreographer frame timestamps while a gameplay animation
 * owns continuous rendering. This is independent of the GLSurfaceView GL-thread
 * callback cadence and helps separate UI/vsync starvation from EGL presentation.
 */
internal class AnimationVsyncTelemetry(
    private val windowSize: Int = 120
) {
    init {
        require(windowSize >= 2) { "windowSize must be at least 2" }
    }

    private val intervalsNanos = LongArray(windowSize)
    private val percentileScratch = LongArray(windowSize)
    private var nextIndex = 0
    private var sampleCount = 0
    private var previousFrameNanos = 0L

    fun record(frameTimeNanos: Long) {
        if (frameTimeNanos <= 0L) return
        val previous = previousFrameNanos
        if (previous > 0L && frameTimeNanos > previous) {
            intervalsNanos[nextIndex] = frameTimeNanos - previous
            nextIndex = (nextIndex + 1) % windowSize
            sampleCount = min(windowSize, sampleCount + 1)
        }
        previousFrameNanos = frameTimeNanos
    }

    fun snapshot(): AnimationVsyncSnapshot {
        if (sampleCount == 0) {
            return AnimationVsyncSnapshot(0, null, null, null)
        }
        var totalNanos = 0.0
        for (i in 0 until sampleCount) totalNanos += intervalsNanos[i].toDouble()
        val fps = if (totalNanos > 0.0) {
            (sampleCount.toDouble() * 1_000_000_000.0 / totalNanos).toFloat()
        } else null
        return AnimationVsyncSnapshot(
            sampleCount = sampleCount,
            fps = fps,
            intervalP50Ms = percentileMillis(0.50),
            intervalP95Ms = percentileMillis(0.95)
        )
    }

    fun reset() {
        Arrays.fill(intervalsNanos, 0L)
        Arrays.fill(percentileScratch, 0L)
        nextIndex = 0
        sampleCount = 0
        previousFrameNanos = 0L
    }

    private fun percentileMillis(percentile: Double): Float? {
        if (sampleCount == 0) return null
        for (i in 0 until sampleCount) percentileScratch[i] = intervalsNanos[i]
        Arrays.sort(percentileScratch, 0, sampleCount)
        val rank = ceil(percentile * sampleCount.toDouble()).toInt().coerceIn(1, sampleCount)
        return percentileScratch[rank - 1] / 1_000_000f
    }
}
