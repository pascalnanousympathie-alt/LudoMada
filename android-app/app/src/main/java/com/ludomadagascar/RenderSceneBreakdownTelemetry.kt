package com.ludomadagascar

import java.util.Arrays
import kotlin.math.ceil
import kotlin.math.min

/**
 * Allocation-free CPU timing sampler for the major GLES scene draw groups.
 * These values measure CPU wall time spent submitting commands, not GPU execution.
 */
internal data class RenderSceneBreakdownSnapshot(
    val sampleCount: Int,
    val setupCpuP50Ms: Float, val setupCpuP95Ms: Float,
    val backdropCpuP50Ms: Float, val backdropCpuP95Ms: Float,
    val boardCpuP50Ms: Float, val boardCpuP95Ms: Float,
    val pawnsCpuP50Ms: Float, val pawnsCpuP95Ms: Float,
    val diceCpuP50Ms: Float, val diceCpuP95Ms: Float,
    val shaderEndCpuP50Ms: Float, val shaderEndCpuP95Ms: Float,
    val particlesCpuP50Ms: Float, val particlesCpuP95Ms: Float
)

internal class RenderSceneBreakdownTelemetry(
    private val windowSize: Int = 60,
    private val reportIntervalNanos: Long = 5_000_000_000L
) {
    init {
        require(windowSize >= 2) { "windowSize must be at least 2" }
        require(reportIntervalNanos > 0L) { "reportIntervalNanos must be positive" }
    }

    private val setup = LongArray(windowSize)
    private val backdrop = LongArray(windowSize)
    private val board = LongArray(windowSize)
    private val pawns = LongArray(windowSize)
    private val dice = LongArray(windowSize)
    private val shaderEnd = LongArray(windowSize)
    private val particles = LongArray(windowSize)
    private val scratch = LongArray(windowSize)
    private var nextIndex = 0
    private var sampleCount = 0
    private var nextReportAtNanos = 0L

    fun record(
        frameStartNanos: Long,
        setupCpuNanos: Long,
        backdropCpuNanos: Long,
        boardCpuNanos: Long,
        pawnsCpuNanos: Long,
        diceCpuNanos: Long,
        shaderEndCpuNanos: Long,
        particlesCpuNanos: Long
    ): RenderSceneBreakdownSnapshot? {
        if (frameStartNanos <= 0L) return null
        setup[nextIndex] = setupCpuNanos.coerceAtLeast(0L)
        backdrop[nextIndex] = backdropCpuNanos.coerceAtLeast(0L)
        board[nextIndex] = boardCpuNanos.coerceAtLeast(0L)
        pawns[nextIndex] = pawnsCpuNanos.coerceAtLeast(0L)
        dice[nextIndex] = diceCpuNanos.coerceAtLeast(0L)
        shaderEnd[nextIndex] = shaderEndCpuNanos.coerceAtLeast(0L)
        particles[nextIndex] = particlesCpuNanos.coerceAtLeast(0L)
        nextIndex = (nextIndex + 1) % windowSize
        sampleCount = min(windowSize, sampleCount + 1)

        if (nextReportAtNanos == 0L) nextReportAtNanos = frameStartNanos + reportIntervalNanos
        if (frameStartNanos < nextReportAtNanos) return null
        nextReportAtNanos = frameStartNanos + reportIntervalNanos
        return snapshot()
    }

    fun reset() {
        Arrays.fill(setup, 0L)
        Arrays.fill(backdrop, 0L)
        Arrays.fill(board, 0L)
        Arrays.fill(pawns, 0L)
        Arrays.fill(dice, 0L)
        Arrays.fill(shaderEnd, 0L)
        Arrays.fill(particles, 0L)
        Arrays.fill(scratch, 0L)
        nextIndex = 0
        sampleCount = 0
        nextReportAtNanos = 0L
    }

    private fun snapshot() = RenderSceneBreakdownSnapshot(
        sampleCount = sampleCount,
        setupCpuP50Ms = percentileMillis(setup, 0.50),
        setupCpuP95Ms = percentileMillis(setup, 0.95),
        backdropCpuP50Ms = percentileMillis(backdrop, 0.50),
        backdropCpuP95Ms = percentileMillis(backdrop, 0.95),
        boardCpuP50Ms = percentileMillis(board, 0.50),
        boardCpuP95Ms = percentileMillis(board, 0.95),
        pawnsCpuP50Ms = percentileMillis(pawns, 0.50),
        pawnsCpuP95Ms = percentileMillis(pawns, 0.95),
        diceCpuP50Ms = percentileMillis(dice, 0.50),
        diceCpuP95Ms = percentileMillis(dice, 0.95),
        shaderEndCpuP50Ms = percentileMillis(shaderEnd, 0.50),
        shaderEndCpuP95Ms = percentileMillis(shaderEnd, 0.95),
        particlesCpuP50Ms = percentileMillis(particles, 0.50),
        particlesCpuP95Ms = percentileMillis(particles, 0.95)
    )

    private fun percentileMillis(values: LongArray, percentile: Double): Float {
        for (i in 0 until sampleCount) scratch[i] = values[i]
        Arrays.sort(scratch, 0, sampleCount)
        val rank = ceil(percentile * sampleCount.toDouble()).toInt().coerceIn(1, sampleCount)
        return scratch[rank - 1] / 1_000_000f
    }
}
