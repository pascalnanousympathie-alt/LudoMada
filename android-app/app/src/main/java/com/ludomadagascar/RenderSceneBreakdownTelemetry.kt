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
    val backdropCullingQueryCpuP50Ms: Float = 0f, val backdropCullingQueryCpuP95Ms: Float = 0f,
    val backdropCullingTransitionCpuP50Ms: Float = 0f, val backdropCullingTransitionCpuP95Ms: Float = 0f,
    val backdropCullingRestoreCpuP50Ms: Float = 0f, val backdropCullingRestoreCpuP95Ms: Float = 0f,
    val backdropBatchTotalCpuP50Ms: Float = 0f, val backdropBatchTotalCpuP95Ms: Float = 0f,
    val backdropUnattributedCpuP50Ms: Float = 0f, val backdropUnattributedCpuP95Ms: Float = 0f,
    val boardCpuP50Ms: Float, val boardCpuP95Ms: Float,
    val pawnsCpuP50Ms: Float, val pawnsCpuP95Ms: Float,
    val diceCpuP50Ms: Float, val diceCpuP95Ms: Float,
    val shaderEndCpuP50Ms: Float, val shaderEndCpuP95Ms: Float,
    val particlesCpuP50Ms: Float, val particlesCpuP95Ms: Float,
    val particleBuildCpuP50Ms: Float, val particleBuildCpuP95Ms: Float,
    val particleBufferCopyCpuP50Ms: Float, val particleBufferCopyCpuP95Ms: Float,
    val particleGlStateCpuP50Ms: Float, val particleGlStateCpuP95Ms: Float,
    val particleBufferUploadCpuP50Ms: Float, val particleBufferUploadCpuP95Ms: Float,
    val particleDrawSubmitCpuP50Ms: Float, val particleDrawSubmitCpuP95Ms: Float,
    val particleCleanupCpuP50Ms: Float, val particleCleanupCpuP95Ms: Float,
    val activeParticlesP50: Int, val activeParticlesP95: Int,
    val backdropBatchCount: Int = 0,
    val backdropBatchCpuP95Ms: List<Float> = emptyList(),
    val backdropBatchVertexCounts: List<Int> = emptyList(),
    val backdropBatchIndexCounts: List<Int> = emptyList()
)

internal class RenderSceneBreakdownTelemetry(
    private val windowSize: Int = 60,
    private val reportIntervalNanos: Long = 5_000_000_000L
) {
    companion object {
        const val MAX_BACKDROP_BATCHES = 7
    }

    init {
        require(windowSize >= 2) { "windowSize must be at least 2" }
        require(reportIntervalNanos > 0L) { "reportIntervalNanos must be positive" }
    }

    private val setup = LongArray(windowSize)
    private val backdrop = LongArray(windowSize)
    private val backdropCullingQuery = LongArray(windowSize)
    private val backdropCullingTransition = LongArray(windowSize)
    private val backdropCullingRestore = LongArray(windowSize)
    private val backdropBatchTotal = LongArray(windowSize)
    private val backdropUnattributed = LongArray(windowSize)
    private val board = LongArray(windowSize)
    private val pawns = LongArray(windowSize)
    private val dice = LongArray(windowSize)
    private val shaderEnd = LongArray(windowSize)
    private val particles = LongArray(windowSize)
    private val particleBuild = LongArray(windowSize)
    private val particleBufferCopy = LongArray(windowSize)
    private val particleGlState = LongArray(windowSize)
    private val particleBufferUpload = LongArray(windowSize)
    private val particleDrawSubmit = LongArray(windowSize)
    private val particleCleanup = LongArray(windowSize)
    private val activeParticles = IntArray(windowSize)
    private val backdropBatchCpu = Array(MAX_BACKDROP_BATCHES) { LongArray(windowSize) }
    private val backdropBatchVertices = Array(MAX_BACKDROP_BATCHES) { IntArray(windowSize) }
    private val backdropBatchIndices = Array(MAX_BACKDROP_BATCHES) { IntArray(windowSize) }
    private val scratch = LongArray(windowSize)
    private var nextIndex = 0
    private var sampleCount = 0
    private var backdropBatchCount = 0
    private var nextReportAtNanos = 0L

    fun record(
        frameStartNanos: Long,
        setupCpuNanos: Long,
        backdropCpuNanos: Long,
        boardCpuNanos: Long,
        pawnsCpuNanos: Long,
        diceCpuNanos: Long,
        shaderEndCpuNanos: Long,
        particlesCpuNanos: Long,
        particleBuildCpuNanos: Long = 0L,
        particleBufferCopyCpuNanos: Long = 0L,
        particleGlStateCpuNanos: Long = 0L,
        particleBufferUploadCpuNanos: Long = 0L,
        particleDrawSubmitCpuNanos: Long = 0L,
        particleCleanupCpuNanos: Long = 0L,
        activeParticleCount: Int = 0,
        backdropBatchCount: Int = 0,
        backdropBatchCpuNanos: LongArray? = null,
        backdropBatchVertexCounts: IntArray? = null,
        backdropBatchIndexCounts: IntArray? = null,
        backdropCullingQueryCpuNanos: Long = 0L,
        backdropCullingTransitionCpuNanos: Long = 0L,
        backdropCullingRestoreCpuNanos: Long = 0L
    ): RenderSceneBreakdownSnapshot? {
        if (frameStartNanos <= 0L) return null
        setup[nextIndex] = setupCpuNanos.coerceAtLeast(0L)
        backdrop[nextIndex] = backdropCpuNanos.coerceAtLeast(0L)
        backdropCullingQuery[nextIndex] = backdropCullingQueryCpuNanos.coerceAtLeast(0L)
        backdropCullingTransition[nextIndex] = backdropCullingTransitionCpuNanos.coerceAtLeast(0L)
        backdropCullingRestore[nextIndex] = backdropCullingRestoreCpuNanos.coerceAtLeast(0L)
        var batchTotalNanos = 0L
        val recordedBatchCount = if (backdropBatchCpuNanos == null) 0 else
            backdropBatchCount.coerceIn(0, MAX_BACKDROP_BATCHES).coerceAtMost(backdropBatchCpuNanos.size)
        for (batch in 0 until recordedBatchCount) {
            batchTotalNanos += backdropBatchCpuNanos!![batch].coerceAtLeast(0L)
        }
        backdropBatchTotal[nextIndex] = batchTotalNanos
        backdropUnattributed[nextIndex] = (
            backdrop[nextIndex] -
                backdropCullingQuery[nextIndex] -
                backdropCullingTransition[nextIndex] -
                backdropCullingRestore[nextIndex] -
                batchTotalNanos
            ).coerceAtLeast(0L)
        board[nextIndex] = boardCpuNanos.coerceAtLeast(0L)
        pawns[nextIndex] = pawnsCpuNanos.coerceAtLeast(0L)
        dice[nextIndex] = diceCpuNanos.coerceAtLeast(0L)
        shaderEnd[nextIndex] = shaderEndCpuNanos.coerceAtLeast(0L)
        particles[nextIndex] = particlesCpuNanos.coerceAtLeast(0L)
        particleBuild[nextIndex] = particleBuildCpuNanos.coerceAtLeast(0L)
        particleBufferCopy[nextIndex] = particleBufferCopyCpuNanos.coerceAtLeast(0L)
        particleGlState[nextIndex] = particleGlStateCpuNanos.coerceAtLeast(0L)
        particleBufferUpload[nextIndex] = particleBufferUploadCpuNanos.coerceAtLeast(0L)
        particleDrawSubmit[nextIndex] = particleDrawSubmitCpuNanos.coerceAtLeast(0L)
        particleCleanup[nextIndex] = particleCleanupCpuNanos.coerceAtLeast(0L)
        activeParticles[nextIndex] = activeParticleCount.coerceAtLeast(0)
        this.backdropBatchCount = if (backdropBatchCpuNanos == null) 0 else
            backdropBatchCount.coerceIn(0, MAX_BACKDROP_BATCHES).coerceAtMost(backdropBatchCpuNanos.size)
        for (batch in 0 until MAX_BACKDROP_BATCHES) {
            val present = batch < this.backdropBatchCount
            this.backdropBatchCpu[batch][nextIndex] =
                if (present) backdropBatchCpuNanos!![batch].coerceAtLeast(0L) else 0L
            this.backdropBatchVertices[batch][nextIndex] =
                if (present && backdropBatchVertexCounts != null && batch < backdropBatchVertexCounts.size)
                    backdropBatchVertexCounts[batch].coerceAtLeast(0) else 0
            this.backdropBatchIndices[batch][nextIndex] =
                if (present && backdropBatchIndexCounts != null && batch < backdropBatchIndexCounts.size)
                    backdropBatchIndexCounts[batch].coerceAtLeast(0) else 0
        }
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
        Arrays.fill(backdropCullingQuery, 0L)
        Arrays.fill(backdropCullingTransition, 0L)
        Arrays.fill(backdropCullingRestore, 0L)
        Arrays.fill(backdropBatchTotal, 0L)
        Arrays.fill(backdropUnattributed, 0L)
        Arrays.fill(board, 0L)
        Arrays.fill(pawns, 0L)
        Arrays.fill(dice, 0L)
        Arrays.fill(shaderEnd, 0L)
        Arrays.fill(particles, 0L)
        Arrays.fill(particleBuild, 0L)
        Arrays.fill(particleBufferCopy, 0L)
        Arrays.fill(particleGlState, 0L)
        Arrays.fill(particleBufferUpload, 0L)
        Arrays.fill(particleDrawSubmit, 0L)
        Arrays.fill(particleCleanup, 0L)
        Arrays.fill(activeParticles, 0)
        for (batch in 0 until MAX_BACKDROP_BATCHES) {
            Arrays.fill(backdropBatchCpu[batch], 0L)
            Arrays.fill(backdropBatchVertices[batch], 0)
            Arrays.fill(backdropBatchIndices[batch], 0)
        }
        Arrays.fill(scratch, 0L)
        nextIndex = 0
        sampleCount = 0
        backdropBatchCount = 0
        nextReportAtNanos = 0L
    }

    private fun snapshot() = RenderSceneBreakdownSnapshot(
        sampleCount = sampleCount,
        setupCpuP50Ms = percentileMillis(setup, 0.50),
        setupCpuP95Ms = percentileMillis(setup, 0.95),
        backdropCpuP50Ms = percentileMillis(backdrop, 0.50),
        backdropCpuP95Ms = percentileMillis(backdrop, 0.95),
        backdropCullingQueryCpuP50Ms = percentileMillis(backdropCullingQuery, 0.50),
        backdropCullingQueryCpuP95Ms = percentileMillis(backdropCullingQuery, 0.95),
        backdropCullingTransitionCpuP50Ms = percentileMillis(backdropCullingTransition, 0.50),
        backdropCullingTransitionCpuP95Ms = percentileMillis(backdropCullingTransition, 0.95),
        backdropCullingRestoreCpuP50Ms = percentileMillis(backdropCullingRestore, 0.50),
        backdropCullingRestoreCpuP95Ms = percentileMillis(backdropCullingRestore, 0.95),
        backdropBatchTotalCpuP50Ms = percentileMillis(backdropBatchTotal, 0.50),
        backdropBatchTotalCpuP95Ms = percentileMillis(backdropBatchTotal, 0.95),
        backdropUnattributedCpuP50Ms = percentileMillis(backdropUnattributed, 0.50),
        backdropUnattributedCpuP95Ms = percentileMillis(backdropUnattributed, 0.95),
        boardCpuP50Ms = percentileMillis(board, 0.50),
        boardCpuP95Ms = percentileMillis(board, 0.95),
        pawnsCpuP50Ms = percentileMillis(pawns, 0.50),
        pawnsCpuP95Ms = percentileMillis(pawns, 0.95),
        diceCpuP50Ms = percentileMillis(dice, 0.50),
        diceCpuP95Ms = percentileMillis(dice, 0.95),
        shaderEndCpuP50Ms = percentileMillis(shaderEnd, 0.50),
        shaderEndCpuP95Ms = percentileMillis(shaderEnd, 0.95),
        particlesCpuP50Ms = percentileMillis(particles, 0.50),
        particlesCpuP95Ms = percentileMillis(particles, 0.95),
        particleBuildCpuP50Ms = percentileMillis(particleBuild, 0.50),
        particleBuildCpuP95Ms = percentileMillis(particleBuild, 0.95),
        particleBufferCopyCpuP50Ms = percentileMillis(particleBufferCopy, 0.50),
        particleBufferCopyCpuP95Ms = percentileMillis(particleBufferCopy, 0.95),
        particleGlStateCpuP50Ms = percentileMillis(particleGlState, 0.50),
        particleGlStateCpuP95Ms = percentileMillis(particleGlState, 0.95),
        particleBufferUploadCpuP50Ms = percentileMillis(particleBufferUpload, 0.50),
        particleBufferUploadCpuP95Ms = percentileMillis(particleBufferUpload, 0.95),
        particleDrawSubmitCpuP50Ms = percentileMillis(particleDrawSubmit, 0.50),
        particleDrawSubmitCpuP95Ms = percentileMillis(particleDrawSubmit, 0.95),
        particleCleanupCpuP50Ms = percentileMillis(particleCleanup, 0.50),
        particleCleanupCpuP95Ms = percentileMillis(particleCleanup, 0.95),
        activeParticlesP50 = percentileCount(0.50),
        activeParticlesP95 = percentileCount(0.95),
        backdropBatchCount = backdropBatchCount,
        backdropBatchCpuP95Ms = (0 until backdropBatchCount).map {
            percentileMillis(backdropBatchCpu[it], 0.95)
        },
        backdropBatchVertexCounts = (0 until backdropBatchCount).map {
            backdropBatchVertices[it][(nextIndex - 1 + windowSize) % windowSize]
        },
        backdropBatchIndexCounts = (0 until backdropBatchCount).map {
            backdropBatchIndices[it][(nextIndex - 1 + windowSize) % windowSize]
        }
    )

    private fun percentileMillis(values: LongArray, percentile: Double): Float {
        for (i in 0 until sampleCount) scratch[i] = values[i]
        Arrays.sort(scratch, 0, sampleCount)
        val rank = ceil(percentile * sampleCount.toDouble()).toInt().coerceIn(1, sampleCount)
        return scratch[rank - 1] / 1_000_000f
    }

    private fun percentileCount(percentile: Double): Int {
        for (i in 0 until sampleCount) scratch[i] = activeParticles[i].toLong()
        Arrays.sort(scratch, 0, sampleCount)
        val rank = ceil(percentile * sampleCount.toDouble()).toInt().coerceIn(1, sampleCount)
        return scratch[rank - 1].toInt()
    }
}
