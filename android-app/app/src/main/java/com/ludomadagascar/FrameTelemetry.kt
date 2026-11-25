package com.ludomadagascar

import java.util.Arrays
import kotlin.math.ceil

/**
 * Allocation-free per-frame collector. It only sorts/copies samples when a
 * five-second diagnostic window is emitted, never on the rendering hot path.
 */
internal data class FrameTelemetryReport(
    val periodMs: Long,
    val frameCount: Int,
    val averageFps: Float,
    val targetRefreshHz: Float,
    val frameBudgetMs: Float,
    val intervalP50Ms: Float,
    val intervalP95Ms: Float,
    val intervalMaxMs: Float,
    val cpuSubmitP50Ms: Float,
    val cpuSubmitP95Ms: Float,
    val cpuSubmitMaxMs: Float,
    val overBudgetFrames: Int,
    val missedVsyncFrames: Int
) {
    fun toLogLine(mode: String): String =
        "PBR_FRAME_TELEMETRY mode=$mode periodMs=$periodMs frames=$frameCount " +
            "fps=$averageFps targetHz=$targetRefreshHz frameBudgetMs=$frameBudgetMs " +
            "frameIntervalMsP50=$intervalP50Ms frameIntervalMsP95=$intervalP95Ms " +
            "frameIntervalMsMax=$intervalMaxMs cpuSubmitMsP50=$cpuSubmitP50Ms " +
            "cpuSubmitMsP95=$cpuSubmitP95Ms cpuSubmitMsMax=$cpuSubmitMaxMs " +
            "overBudgetFrames=$overBudgetFrames missedVsyncFrames=$missedVsyncFrames"
}

internal class FrameTelemetry(
    refreshRateHz: Float,
    private val reportPeriodNanos: Long = 5_000_000_000L,
    private val sampleCapacity: Int = 600,
    private val minimumFrames: Int = 12
) {
    private val targetRefreshHz =
        refreshRateHz.takeIf { it.isFinite() && it in 30f..240f } ?: 60f
    private val frameBudgetMs = 1_000f / targetRefreshHz
    private val intervalSamples = FloatArray(sampleCapacity)
    private val cpuSubmitSamples = FloatArray(sampleCapacity)

    private var windowStartedNanos = 0L
    private var writeIndex = 0
    private var sampleCount = 0
    private var frameCount = 0
    private var totalIntervalNanos = 0L
    private var intervalMaxMs = 0f
    private var cpuSubmitMaxMs = 0f
    private var overBudgetFrames = 0
    private var missedVsyncFrames = 0

    init {
        require(reportPeriodNanos > 0L) { "reportPeriodNanos must be positive" }
        require(sampleCapacity > 0) { "sampleCapacity must be positive" }
        require(minimumFrames > 0) { "minimumFrames must be positive" }
    }

    fun debugSnapshot(nowNanos: Long): String =
        "windowElapsedMs=${((nowNanos - windowStartedNanos).coerceAtLeast(0L) / 1_000_000L)} " +
            "samples=$sampleCount frames=$frameCount capacity=$sampleCapacity"

    fun reset(nowNanos: Long) {
        windowStartedNanos = nowNanos
        writeIndex = 0
        sampleCount = 0
        frameCount = 0
        totalIntervalNanos = 0L
        intervalMaxMs = 0f
        cpuSubmitMaxMs = 0f
        overBudgetFrames = 0
        missedVsyncFrames = 0
    }

    /**
     * Record one GLES callback interval and CPU-side render submission duration.
     * Intervals over one second are treated as idle/background gaps. Shorter stalls
     * remain in the sample set so severe frame drops are visible in p95/max metrics.
     */
    fun record(
        frameIntervalNanos: Long,
        cpuSubmitNanos: Long,
        nowNanos: Long
    ): FrameTelemetryReport? {
        if (frameIntervalNanos <= 0L) return null
        if (frameIntervalNanos > 1_000_000_000L) {
            // A gap over one second means the GLSurfaceView was likely idle or backgrounded.
            // Start a fresh window so resume time is not counted as a rendered frame.
            reset(nowNanos)
            return null
        }

        val intervalMs = frameIntervalNanos / 1_000_000f
        val cpuSubmitMs = cpuSubmitNanos.coerceAtLeast(0L) / 1_000_000f

        intervalSamples[writeIndex] = intervalMs
        cpuSubmitSamples[writeIndex] = cpuSubmitMs
        writeIndex = (writeIndex + 1) % sampleCapacity
        sampleCount = (sampleCount + 1).coerceAtMost(sampleCapacity)

        frameCount++
        totalIntervalNanos += frameIntervalNanos
        intervalMaxMs = maxOf(intervalMaxMs, intervalMs)
        cpuSubmitMaxMs = maxOf(cpuSubmitMaxMs, cpuSubmitMs)
        if (intervalMs > frameBudgetMs) overBudgetFrames++
        if (intervalMs > frameBudgetMs * 2f) missedVsyncFrames++

        if (windowStartedNanos == 0L) windowStartedNanos = nowNanos - frameIntervalNanos
        val elapsed = nowNanos - windowStartedNanos
        if (elapsed < reportPeriodNanos || frameCount < minimumFrames) return null

        val intervalCopy = orderedCopy(intervalSamples)
        val cpuCopy = orderedCopy(cpuSubmitSamples)
        val report = FrameTelemetryReport(
            periodMs = elapsed / 1_000_000L,
            frameCount = frameCount,
            averageFps = frameCount * 1_000_000_000f / totalIntervalNanos,
            targetRefreshHz = targetRefreshHz,
            frameBudgetMs = frameBudgetMs,
            intervalP50Ms = percentile(intervalCopy, 0.50f),
            intervalP95Ms = percentile(intervalCopy, 0.95f),
            intervalMaxMs = intervalMaxMs,
            cpuSubmitP50Ms = percentile(cpuCopy, 0.50f),
            cpuSubmitP95Ms = percentile(cpuCopy, 0.95f),
            cpuSubmitMaxMs = cpuSubmitMaxMs,
            overBudgetFrames = overBudgetFrames,
            missedVsyncFrames = missedVsyncFrames
        )
        reset(nowNanos)
        return report
    }

    private fun orderedCopy(samples: FloatArray): FloatArray {
        val copy = FloatArray(sampleCount)
        val first = (writeIndex - sampleCount + sampleCapacity) % sampleCapacity
        for (index in 0 until sampleCount) {
            copy[index] = samples[(first + index) % sampleCapacity]
        }
        Arrays.sort(copy)
        return copy
    }

    private fun percentile(sortedSamples: FloatArray, percentile: Float): Float {
        if (sortedSamples.isEmpty()) return 0f
        val index = ceil((sortedSamples.size - 1) * percentile)
            .toInt()
            .coerceIn(0, sortedSamples.lastIndex)
        return sortedSamples[index]
    }
}
