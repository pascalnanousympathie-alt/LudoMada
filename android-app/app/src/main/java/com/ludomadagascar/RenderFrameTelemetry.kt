package com.ludomadagascar

import java.util.Arrays
import kotlin.math.ceil
import kotlin.math.min

/**
 * Fixed-size, allocation-free-per-frame renderer diagnostics.
 *
 * A GLSurfaceView using RENDERMODE_WHEN_DIRTY can sit idle between user actions.
 * Long callback gaps are therefore excluded from active-frame FPS calculations;
 * GL submission CPU time is measured independently for every callback.
 *
 * GL submission duration measures CPU time issuing GL commands, not asynchronous
 * GPU execution. Snapshot allocation/sorting occurs only at the 5-second report boundary.
 */
internal data class RenderFramePerfSnapshot(
    val activeFrameSampleCount: Int,
    val activeFrameFps: Float?,
    val activeFrameIntervalP50Ms: Float?,
    val activeFrameIntervalP95Ms: Float?,
    val activeFrameIntervalMaxMs: Float?,
    val glSubmissionSampleCount: Int,
    val glSubmitP50Ms: Float,
    val glSubmitP95Ms: Float,
    val glSubmitMaxMs: Float,
    val afterCallbackGapP50Ms: Float? = null,
    val afterCallbackGapP95Ms: Float? = null
)

internal class RenderFrameTelemetry(
    private val windowSize: Int = 120,
    private val reportIntervalNanos: Long = 5_000_000_000L,
    private val maxActiveFrameIntervalNanos: Long = 250_000_000L
) {
    init {
        require(windowSize >= 2) { "windowSize must be at least 2" }
        require(reportIntervalNanos > 0L) { "reportIntervalNanos must be positive" }
        require(maxActiveFrameIntervalNanos > 0L) { "maxActiveFrameIntervalNanos must be positive" }
    }

    private val activeIntervalsNanos = LongArray(windowSize)
    private val glSubmitNanos = LongArray(windowSize)
    private val afterCallbackGapNanos = LongArray(windowSize)
    private val percentileScratch = LongArray(windowSize)
    private var nextActiveIndex = 0
    private var nextSubmissionIndex = 0
    private var nextAfterCallbackGapIndex = 0
    private var activeFrameSampleCount = 0
    private var glSubmissionSampleCount = 0
    private var afterCallbackGapSampleCount = 0
    private var hasPreviousFrame = false
    private var previousFrameStartNanos = 0L
    private var previousCallbackDurationNanos = 0L
    private var nextReportAtNanos = 0L

    fun record(frameStartNanos: Long, glSubmissionDurationNanos: Long): RenderFramePerfSnapshot? {
        if (frameStartNanos <= 0L) return null

        if (hasPreviousFrame && frameStartNanos > previousFrameStartNanos) {
            val interval = frameStartNanos - previousFrameStartNanos
            // Long gaps in WHEN_DIRTY mode usually mean no frame was requested.
            // Keep them out of active FPS rather than reporting false sub-1 FPS.
            if (interval <= maxActiveFrameIntervalNanos) {
                activeIntervalsNanos[nextActiveIndex] = interval
                nextActiveIndex = (nextActiveIndex + 1) % windowSize
                activeFrameSampleCount = min(windowSize, activeFrameSampleCount + 1)
                // This is the wait after onDrawFrame returns until the next callback.
                // It includes EGL presentation/vsync/scheduling; it is not GPU execution time.
                afterCallbackGapNanos[nextAfterCallbackGapIndex] =
                    (interval - previousCallbackDurationNanos).coerceAtLeast(0L)
                nextAfterCallbackGapIndex = (nextAfterCallbackGapIndex + 1) % windowSize
                afterCallbackGapSampleCount = min(windowSize, afterCallbackGapSampleCount + 1)
            }
        } else if (hasPreviousFrame && frameStartNanos <= previousFrameStartNanos) {
            return null
        }

        hasPreviousFrame = true
        previousFrameStartNanos = frameStartNanos
        glSubmitNanos[nextSubmissionIndex] = glSubmissionDurationNanos.coerceAtLeast(0L)
        nextSubmissionIndex = (nextSubmissionIndex + 1) % windowSize
        glSubmissionSampleCount = min(windowSize, glSubmissionSampleCount + 1)

        if (nextReportAtNanos == 0L) nextReportAtNanos = frameStartNanos + reportIntervalNanos
        if (frameStartNanos < nextReportAtNanos) return null
        nextReportAtNanos = frameStartNanos + reportIntervalNanos
        return createSnapshot()
    }

    /** Call at the end of onDrawFrame to separate callback work from the following framework/presentation gap. */
    fun finishCallback(frameStartNanos: Long, callbackDurationNanos: Long) {
        if (hasPreviousFrame && frameStartNanos == previousFrameStartNanos) {
            previousCallbackDurationNanos = callbackDurationNanos.coerceAtLeast(0L)
        }
    }

    fun reset() {
        Arrays.fill(activeIntervalsNanos, 0L)
        Arrays.fill(glSubmitNanos, 0L)
        Arrays.fill(afterCallbackGapNanos, 0L)
        Arrays.fill(percentileScratch, 0L)
        nextActiveIndex = 0
        nextSubmissionIndex = 0
        nextAfterCallbackGapIndex = 0
        activeFrameSampleCount = 0
        glSubmissionSampleCount = 0
        afterCallbackGapSampleCount = 0
        hasPreviousFrame = false
        previousFrameStartNanos = 0L
        previousCallbackDurationNanos = 0L
        nextReportAtNanos = 0L
    }

    private fun createSnapshot(): RenderFramePerfSnapshot {
        val activeFps = if (activeFrameSampleCount >= 2) {
            var totalIntervalNanos = 0.0
            for (i in 0 until activeFrameSampleCount) {
                totalIntervalNanos += activeIntervalsNanos[i].toDouble()
            }
            if (totalIntervalNanos > 0.0) {
                (activeFrameSampleCount.toDouble() * 1_000_000_000.0 / totalIntervalNanos).toFloat()
            } else null
        } else null

        return RenderFramePerfSnapshot(
            activeFrameSampleCount = activeFrameSampleCount,
            activeFrameFps = activeFps,
            activeFrameIntervalP50Ms = percentileMillis(activeIntervalsNanos, activeFrameSampleCount, 0.50),
            activeFrameIntervalP95Ms = percentileMillis(activeIntervalsNanos, activeFrameSampleCount, 0.95),
            activeFrameIntervalMaxMs = percentileMillis(activeIntervalsNanos, activeFrameSampleCount, 1.0),
            glSubmissionSampleCount = glSubmissionSampleCount,
            glSubmitP50Ms = percentileMillis(glSubmitNanos, glSubmissionSampleCount, 0.50) ?: 0f,
            glSubmitP95Ms = percentileMillis(glSubmitNanos, glSubmissionSampleCount, 0.95) ?: 0f,
            glSubmitMaxMs = percentileMillis(glSubmitNanos, glSubmissionSampleCount, 1.0) ?: 0f,
            afterCallbackGapP50Ms = percentileMillis(afterCallbackGapNanos, afterCallbackGapSampleCount, 0.50),
            afterCallbackGapP95Ms = percentileMillis(afterCallbackGapNanos, afterCallbackGapSampleCount, 0.95)
        )
    }

    private fun percentileMillis(samples: LongArray, count: Int, percentile: Double): Float? {
        if (count == 0) return null
        for (i in 0 until count) percentileScratch[i] = samples[i]
        Arrays.sort(percentileScratch, 0, count)
        val rank = ceil(percentile * count.toDouble()).toInt().coerceIn(1, count)
        return percentileScratch[rank - 1] / 1_000_000f
    }
}
