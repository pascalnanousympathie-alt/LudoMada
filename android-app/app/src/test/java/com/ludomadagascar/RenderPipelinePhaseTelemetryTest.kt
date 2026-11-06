package com.ludomadagascar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RenderPipelinePhaseTelemetryTest {
    @Test
    fun reportsEachRenderPhaseIndependentlyAtTheConfiguredBoundary() {
        val telemetry = RenderPipelinePhaseTelemetry(windowSize = 4, reportIntervalNanos = 10L)
        assertNull(telemetry.record(100L, 1_000_000L, 4_000_000L, 8_000_000L, 2_000_000L, 500_000L))
        assertNull(telemetry.record(105L, 2_000_000L, 5_000_000L, 9_000_000L, 3_000_000L, 600_000L))
        val snapshot = telemetry.record(110L, 3_000_000L, 6_000_000L, 10_000_000L, 4_000_000L, 700_000L)

        assertEquals(3, snapshot!!.sampleCount)
        assertEquals(2f, snapshot.updateCpuP50Ms, 0.001f)
        assertEquals(3f, snapshot.updateCpuP95Ms, 0.001f)
        assertEquals(5f, snapshot.shadowCpuP50Ms, 0.001f)
        assertEquals(6f, snapshot.shadowCpuP95Ms, 0.001f)
        assertEquals(9f, snapshot.sceneCpuP50Ms, 0.001f)
        assertEquals(10f, snapshot.sceneCpuP95Ms, 0.001f)
        assertEquals(3f, snapshot.postCpuP50Ms, 0.001f)
        assertEquals(4f, snapshot.postCpuP95Ms, 0.001f)
        assertEquals(0.6f, snapshot.pawnTargetsCpuP50Ms, 0.001f)
        assertEquals(0.7f, snapshot.pawnTargetsCpuP95Ms, 0.001f)
    }

    @Test
    fun wrapsTheFixedWindowAndClampsNegativeDurationsToZero() {
        val telemetry = RenderPipelinePhaseTelemetry(windowSize = 2, reportIntervalNanos = 1L)
        telemetry.record(100L, 2_000_000L, 3_000_000L, 4_000_000L, 5_000_000L, 6_000_000L)
        telemetry.record(101L, 4_000_000L, 5_000_000L, 6_000_000L, 7_000_000L, 8_000_000L)
        val snapshot = telemetry.record(102L, -1L, 7_000_000L, 8_000_000L, 9_000_000L, 10_000_000L)

        assertEquals(2, snapshot!!.sampleCount)
        assertEquals(0f, snapshot.updateCpuP50Ms, 0.001f)
        assertEquals(4f, snapshot.updateCpuP95Ms, 0.001f)
        assertEquals(5f, snapshot.shadowCpuP50Ms, 0.001f)
        assertEquals(7f, snapshot.shadowCpuP95Ms, 0.001f)
    }

    @Test
    fun resetClearsAllPhaseSamplesAndRestartsTheReportingWindow() {
        val telemetry = RenderPipelinePhaseTelemetry(windowSize = 3, reportIntervalNanos = 10L)
        telemetry.record(100L, 1L, 1L, 1L, 1L, 1L)
        telemetry.reset()
        assertNull(telemetry.record(200L, 2L, 2L, 2L, 2L, 2L))
    }
}
