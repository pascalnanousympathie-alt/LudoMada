package com.ludomadagascar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RenderSceneBreakdownTelemetryTest {
    @Test
    fun reportsEachSceneDrawGroupIndependently() {
        val telemetry = RenderSceneBreakdownTelemetry(windowSize = 4, reportIntervalNanos = 10L)
        assertNull(telemetry.record(100L, 1_000_000L, 4_000_000L, 7_000_000L, 10_000_000L, 13_000_000L, 2_000_000L, 500_000L))
        assertNull(telemetry.record(105L, 2_000_000L, 5_000_000L, 8_000_000L, 11_000_000L, 14_000_000L, 3_000_000L, 600_000L))
        val snapshot = telemetry.record(110L, 3_000_000L, 6_000_000L, 9_000_000L, 12_000_000L, 15_000_000L, 4_000_000L, 700_000L)

        assertEquals(3, snapshot!!.sampleCount)
        assertEquals(2f, snapshot.setupCpuP50Ms, 0.001f)
        assertEquals(3f, snapshot.setupCpuP95Ms, 0.001f)
        assertEquals(5f, snapshot.backdropCpuP50Ms, 0.001f)
        assertEquals(6f, snapshot.backdropCpuP95Ms, 0.001f)
        assertEquals(8f, snapshot.boardCpuP50Ms, 0.001f)
        assertEquals(9f, snapshot.boardCpuP95Ms, 0.001f)
        assertEquals(11f, snapshot.pawnsCpuP50Ms, 0.001f)
        assertEquals(12f, snapshot.pawnsCpuP95Ms, 0.001f)
        assertEquals(14f, snapshot.diceCpuP50Ms, 0.001f)
        assertEquals(15f, snapshot.diceCpuP95Ms, 0.001f)
        assertEquals(3f, snapshot.shaderEndCpuP50Ms, 0.001f)
        assertEquals(4f, snapshot.shaderEndCpuP95Ms, 0.001f)
        assertEquals(0.6f, snapshot.particlesCpuP50Ms, 0.001f)
        assertEquals(0.7f, snapshot.particlesCpuP95Ms, 0.001f)
    }

    @Test
    fun wrapsTheFixedWindowAndClampsNegativeDurations() {
        val telemetry = RenderSceneBreakdownTelemetry(windowSize = 2, reportIntervalNanos = 1L)
        // Durations are supplied in nanoseconds and reported in milliseconds.
        telemetry.record(100L, 1_000_000L, 2_000_000L, 3_000_000L, 4_000_000L, 5_000_000L, 6_000_000L, 7_000_000L)
        telemetry.record(101L, 2_000_000L, 4_000_000L, 5_000_000L, 6_000_000L, 7_000_000L, 8_000_000L, 9_000_000L)
        val snapshot = telemetry.record(102L, -1L, 6_000_000L, 7_000_000L, 8_000_000L, 9_000_000L, 10_000_000L, 11_000_000L)

        assertEquals(2, snapshot!!.sampleCount)
        assertEquals(0f, snapshot.setupCpuP50Ms, 0.001f)
        assertEquals(2f, snapshot.setupCpuP95Ms, 0.001f)
        assertEquals(4f, snapshot.backdropCpuP50Ms, 0.001f)
        assertEquals(6f, snapshot.backdropCpuP95Ms, 0.001f)
    }

    @Test
    fun reportsParticlePackingUploadDrawAndActiveCountSeparately() {
        val telemetry = RenderSceneBreakdownTelemetry(windowSize = 4, reportIntervalNanos = 1L)
        telemetry.record(
            100L, 0L, 0L, 0L, 0L, 0L, 0L, 12_000_000L,
            1_000_000L, 2_000_000L, 3_000_000L, 4_000_000L, 5_000_000L, 6_000_000L, 8
        )
        telemetry.record(
            101L, 0L, 0L, 0L, 0L, 0L, 0L, 22_000_000L,
            2_000_000L, 3_000_000L, 4_000_000L, 5_000_000L, 6_000_000L, 7_000_000L, 16
        )
        val snapshot = telemetry.record(
            102L, 0L, 0L, 0L, 0L, 0L, 0L, 32_000_000L,
            3_000_000L, 4_000_000L, 5_000_000L, 6_000_000L, 7_000_000L, 8_000_000L, 24
        )!!

        assertEquals(3, snapshot.sampleCount)
        assertEquals(2f, snapshot.particleBuildCpuP50Ms, 0.001f)
        assertEquals(3f, snapshot.particleBuildCpuP95Ms, 0.001f)
        assertEquals(3f, snapshot.particleBufferCopyCpuP50Ms, 0.001f)
        assertEquals(4f, snapshot.particleBufferCopyCpuP95Ms, 0.001f)
        assertEquals(4f, snapshot.particleGlStateCpuP50Ms, 0.001f)
        assertEquals(5f, snapshot.particleGlStateCpuP95Ms, 0.001f)
        assertEquals(5f, snapshot.particleBufferUploadCpuP50Ms, 0.001f)
        assertEquals(6f, snapshot.particleBufferUploadCpuP95Ms, 0.001f)
        assertEquals(6f, snapshot.particleDrawSubmitCpuP50Ms, 0.001f)
        assertEquals(7f, snapshot.particleDrawSubmitCpuP95Ms, 0.001f)
        assertEquals(7f, snapshot.particleCleanupCpuP50Ms, 0.001f)
        assertEquals(8f, snapshot.particleCleanupCpuP95Ms, 0.001f)
        assertEquals(16, snapshot.activeParticlesP50)
        assertEquals(24, snapshot.activeParticlesP95)
    }

    @Test
    fun reportsBackdropBatchCpuPercentilesAndGeometrySizes() {
        val telemetry = RenderSceneBreakdownTelemetry(windowSize = 3, reportIntervalNanos = 1L)
        telemetry.record(
            100L, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
            backdropBatchCount = 2,
            backdropBatchCpuNanos = longArrayOf(1_000_000L, 9_000_000L),
            backdropBatchVertexCounts = intArrayOf(100, 900),
            backdropBatchIndexCounts = intArrayOf(300, 2_700)
        )
        telemetry.record(
            101L, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
            backdropBatchCount = 2,
            backdropBatchCpuNanos = longArrayOf(3_000_000L, 12_000_000L),
            backdropBatchVertexCounts = intArrayOf(110, 950),
            backdropBatchIndexCounts = intArrayOf(330, 2_850)
        )
        val snapshot = telemetry.record(
            102L, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
            backdropBatchCount = 2,
            backdropBatchCpuNanos = longArrayOf(2_000_000L, 10_000_000L),
            backdropBatchVertexCounts = intArrayOf(120, 1_000),
            backdropBatchIndexCounts = intArrayOf(360, 3_000)
        )!!

        assertEquals(2, snapshot.backdropBatchCount)
        assertEquals(listOf(3f, 12f), snapshot.backdropBatchCpuP95Ms)
        assertEquals(listOf(120, 1_000), snapshot.backdropBatchVertexCounts)
        assertEquals(listOf(360, 3_000), snapshot.backdropBatchIndexCounts)
    }

    @Test
    fun separatesBackdropStateCostsBatchSubmissionsAndUnattributedTime() {
        val telemetry = RenderSceneBreakdownTelemetry(windowSize = 3, reportIntervalNanos = 1L)
        val batches = longArrayOf(1_000_000L, 2_000_000L)
        val vertices = intArrayOf(100, 200)
        val indices = intArrayOf(300, 600)
        telemetry.record(
            100L, 0L, 20_000_000L, 0L, 0L, 0L, 0L, 0L,
            backdropBatchCount = 2,
            backdropBatchCpuNanos = batches,
            backdropBatchVertexCounts = vertices,
            backdropBatchIndexCounts = indices,
            backdropCullingQueryCpuNanos = 3_000_000L,
            backdropCullingTransitionCpuNanos = 1_000_000L,
            backdropCullingRestoreCpuNanos = 2_000_000L
        )
        telemetry.record(
            101L, 0L, 22_000_000L, 0L, 0L, 0L, 0L, 0L,
            backdropBatchCount = 2,
            backdropBatchCpuNanos = longArrayOf(2_000_000L, 3_000_000L),
            backdropCullingQueryCpuNanos = 4_000_000L,
            backdropCullingTransitionCpuNanos = 1_000_000L,
            backdropCullingRestoreCpuNanos = 2_000_000L
        )
        val snapshot = telemetry.record(
            102L, 0L, 24_000_000L, 0L, 0L, 0L, 0L, 0L,
            backdropBatchCount = 2,
            backdropBatchCpuNanos = longArrayOf(3_000_000L, 4_000_000L),
            backdropCullingQueryCpuNanos = 5_000_000L,
            backdropCullingTransitionCpuNanos = 2_000_000L,
            backdropCullingRestoreCpuNanos = 2_000_000L
        )!!

        assertEquals(4f, snapshot.backdropCullingQueryCpuP50Ms, 0.001f)
        assertEquals(5f, snapshot.backdropCullingQueryCpuP95Ms, 0.001f)
        assertEquals(1f, snapshot.backdropCullingTransitionCpuP50Ms, 0.001f)
        assertEquals(2f, snapshot.backdropCullingTransitionCpuP95Ms, 0.001f)
        assertEquals(2f, snapshot.backdropCullingRestoreCpuP50Ms, 0.001f)
        assertEquals(2f, snapshot.backdropCullingRestoreCpuP95Ms, 0.001f)
        assertEquals(5f, snapshot.backdropBatchTotalCpuP50Ms, 0.001f)
        assertEquals(7f, snapshot.backdropBatchTotalCpuP95Ms, 0.001f)
        assertEquals(10f, snapshot.backdropUnattributedCpuP50Ms, 0.001f)
        assertEquals(11f, snapshot.backdropUnattributedCpuP95Ms, 0.001f)
    }

    @Test
    fun resetClearsSamplesAndRestartsTheReportInterval() {
        val telemetry = RenderSceneBreakdownTelemetry(windowSize = 3, reportIntervalNanos = 10L)
        telemetry.record(100L, 1L, 1L, 1L, 1L, 1L, 1L, 1L)
        telemetry.reset()
        assertNull(telemetry.record(200L, 2L, 2L, 2L, 2L, 2L, 2L, 2L))
    }
}
