package com.ludomadagascar

import java.util.Arrays
import kotlin.math.ceil
import kotlin.math.min

/**
 * Fixed-size, allocation-free-per-frame window for renderer diagnostics.
 *
 * Frame intervals describe callback cadence. GL submission durations describe
 * CPU time spent issuing renderer commands; they do NOT measure asynchronous GPU
 * execution time. Snapshots are created only at the low-frequency report boundary.
 */
internal data class RenderFramePerfSnapshot(
    val sampleCount: Int,
    val estimatedFps: Float,
    val frameIntervalP50Ms: Float,
    val frameIntervalP95Ms: Float,
    val frameIntervalMaxMs: Float,
    val glSubmitP50Ms: Float,
    val glSubmitP95Ms: Float,
    val glSubmitMaxMs: Float
)

internal class RenderFrameTelemetry(
    private val windowSize: Int = 120,
    private val reportIntervalNanos: Long = 5_000_000_000L
) {
    init {
        require(windowSize >= 2) { "windowSize must be at least 2" }
        require(reportIntervalNanos > 0L) { "reportIntervalNanos must be positive" }
    }

    private val frameIntervalsNanos = LongArray(windowSize)
    private val glSubmitNanos = LongArray(windowSize)
    private val percentileScratch = LongArray(windowSize)
    private var nextIndex = 0
    private var sampleCount = 0
    private var hasPreviousFrame = false
    private var previousFrameStartNanos = 0L
    private var nextReportAtNanos = 0L

    /** Returns a snapshot only at the configured low-frequency reporting boundary. */
    fun record(frameStartNanos: Long, glSubmissionDurationNanos: Long): RenderFramePerfSnapshot? {
        if (!hasPreviousFrame) {
            hasPreviousFrame = true
            previousFrameStartNanos = frameStartNanos
            nextReportAtNanos = frameStartNanos + reportIntervalNanos
            return null
        }

        // Ignore duplicate/out-of-order timestamps without corrupting the cadence window.
        if (frameStartNanos <= previousFrameStartNanos) return null

        frameIntervalsNanos[nextIndex] = frameStartNanos - previousFrameStartNanos
        glSubmitNanos[nextIndex] = glSubmissionDurationNanos.coerceAtLeast(0L)
        nextIndex = (nextIndex + 1) % windowSize
        sampleCount = min(windowSize, sampleCount + 1)
        previousFrameStartNanos = frameStartNanos

        if (frameStartNanos < nextReportAtNanos || sampleCount == 0) return null
        nextReportAtNanos = frameStartNanos + reportIntervalNanos
        return createSnapshot()
    }

    fun reset() {
        Arrays.fill(frameIntervalsNanos, 0L)
        Arrays.fill(glSubmitNanos, 0L)
        Arrays.fill(percentileScratch, 0L)
        nextIndex = 0
        sampleCount = 0
        hasPreviousFrame = false
        previousFrameStartNanos = 0L
        nextReportAtNanos = 0L
    }

    private fun createSnapshot(): RenderFramePerfSnapshot {
        var totalIntervalNanos = 0.0
        for (i in 0 until sampleCount) {
            totalIntervalNanos += frameIntervalsNanos[i].toDouble()
        }
        val fps = if (totalIntervalNanos > 0.0) {
            (sampleCount.toDouble() * 1_000_000_000.0 / totalIntervalNanos).toFloat()
        } else {
            0f
        }

        return RenderFramePerfSnapshot(
            sampleCount = sampleCount,
            estimatedFps = fps,
            frameIntervalP50Ms = percentileMillis(frameIntervalsNanos, 0.50),
            frameIntervalP95Ms = percentileMillis(frameIntervalsNanos, 0.95),
            frameIntervalMaxMs = percentileMillis(frameIntervalsNanos, 1.0),
            glSubmitP50Ms = percentileMillis(glSubmitNanos, 0.50),
            glSubmitP95Ms = percentileMillis(glSubmitNanos, 0.95),
            glSubmitMaxMs = percentileMillis(glSubmitNanos, 1.0)
        )
    }

    private fun percentileMillis(samples: LongArray, percentile: Double): Float {
        for (i in 0 until sampleCount) percentileScratch[i] = samples[i]
        Arrays.sort(percentileScratch, 0, sampleCount)
        val rank = ceil(percentile * sampleCount.toDouble()).toInt().coerceIn(1, sampleCount)
        return percentileScratch[rank - 1] / 1_000_000f
    }
}
