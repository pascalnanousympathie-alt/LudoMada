package com.ludomadagascar

import java.util.Arrays
import kotlin.math.ceil
import kotlin.math.min

/**
 * Fixed-size, allocation-free renderer phase sampler.
 *
 * Measures CPU wall time around GL command submission for individual passes.
 * It does not measure asynchronous GPU execution time.
 * Snapshot allocation/sorting only occurs once per reporting interval.
 */
internal data class RenderPipelinePhaseSnapshot(
    val sampleCount: Int,
    val updateCpuP50Ms: Float,
    val updateCpuP95Ms: Float,
    val shadowCpuP50Ms: Float,
    val shadowCpuP95Ms: Float,
    val sceneCpuP50Ms: Float,
    val sceneCpuP95Ms: Float,
    val postCpuP50Ms: Float,
    val postCpuP95Ms: Float,
    val pawnTargetsCpuP50Ms: Float,
    val pawnTargetsCpuP95Ms: Float
)

internal class RenderPipelinePhaseTelemetry(
    private val windowSize: Int = 60,
    private val reportIntervalNanos: Long = 5_000_000_000L
) {
    init {
        require(windowSize >= 2) { "windowSize must be at least 2" }
        require(reportIntervalNanos > 0L) { "reportIntervalNanos must be positive" }
    }

    private val updateNanos = LongArray(windowSize)
    private val shadowNanos = LongArray(windowSize)
    private val sceneNanos = LongArray(windowSize)
    private val postNanos = LongArray(windowSize)
    private val pawnTargetsNanos = LongArray(windowSize)
    private val scratch = LongArray(windowSize)
    private var nextIndex = 0
    private var sampleCount = 0
    private var nextReportAtNanos = 0L

    fun record(
        frameStartNanos: Long,
        updateCpuNanos: Long,
        shadowCpuNanos: Long,
        sceneCpuNanos: Long,
        postCpuNanos: Long,
        pawnTargetsCpuNanos: Long
    ): RenderPipelinePhaseSnapshot? {
        if (frameStartNanos <= 0L) return null
        updateNanos[nextIndex] = updateCpuNanos.coerceAtLeast(0L)
        shadowNanos[nextIndex] = shadowCpuNanos.coerceAtLeast(0L)
        sceneNanos[nextIndex] = sceneCpuNanos.coerceAtLeast(0L)
        postNanos[nextIndex] = postCpuNanos.coerceAtLeast(0L)
        pawnTargetsNanos[nextIndex] = pawnTargetsCpuNanos.coerceAtLeast(0L)
        nextIndex = (nextIndex + 1) % windowSize
        sampleCount = min(windowSize, sampleCount + 1)

        if (nextReportAtNanos == 0L) nextReportAtNanos = frameStartNanos + reportIntervalNanos
        if (frameStartNanos < nextReportAtNanos) return null
        nextReportAtNanos = frameStartNanos + reportIntervalNanos
        return snapshot()
    }

    fun reset() {
        Arrays.fill(updateNanos, 0L)
        Arrays.fill(shadowNanos, 0L)
        Arrays.fill(sceneNanos, 0L)
        Arrays.fill(postNanos, 0L)
        Arrays.fill(pawnTargetsNanos, 0L)
        Arrays.fill(scratch, 0L)
        nextIndex = 0
        sampleCount = 0
        nextReportAtNanos = 0L
    }

    private fun snapshot(): RenderPipelinePhaseSnapshot = RenderPipelinePhaseSnapshot(
        sampleCount = sampleCount,
        updateCpuP50Ms = percentileMillis(updateNanos, 0.50),
        updateCpuP95Ms = percentileMillis(updateNanos, 0.95),
        shadowCpuP50Ms = percentileMillis(shadowNanos, 0.50),
        shadowCpuP95Ms = percentileMillis(shadowNanos, 0.95),
        sceneCpuP50Ms = percentileMillis(sceneNanos, 0.50),
        sceneCpuP95Ms = percentileMillis(sceneNanos, 0.95),
        postCpuP50Ms = percentileMillis(postNanos, 0.50),
        postCpuP95Ms = percentileMillis(postNanos, 0.95),
        pawnTargetsCpuP50Ms = percentileMillis(pawnTargetsNanos, 0.50),
        pawnTargetsCpuP95Ms = percentileMillis(pawnTargetsNanos, 0.95)
    )

    private fun percentileMillis(values: LongArray, percentile: Double): Float {
        for (i in 0 until sampleCount) scratch[i] = values[i]
        Arrays.sort(scratch, 0, sampleCount)
        val rank = ceil(percentile * sampleCount.toDouble()).toInt().coerceIn(1, sampleCount)
        return scratch[rank - 1] / 1_000_000f
    }
}
