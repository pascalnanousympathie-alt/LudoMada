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
        telemetry.record(100L, 1L, 2_000_000L, 3_000_000L, 4_000_000L, 5_000_000L, 6_000_000L, 7_000_000L)
        telemetry.record(101L, 2L, 4_000_000L, 5_000_000L, 6_000_000L, 7_000_000L, 8_000_000L, 9_000_000L)
        val snapshot = telemetry.record(102L, -1L, 6_000_000L, 7_000_000L, 8_000_000L, 9_000_000L, 10_000_000L, 11_000_000L)

        assertEquals(2, snapshot!!.sampleCount)
        assertEquals(0f, snapshot.setupCpuP50Ms, 0.001f)
        assertEquals(2f, snapshot.setupCpuP95Ms, 0.001f)
        assertEquals(4f, snapshot.backdropCpuP50Ms, 0.001f)
        assertEquals(6f, snapshot.backdropCpuP95Ms, 0.001f)
    }

    @Test
    fun resetClearsSamplesAndRestartsTheReportInterval() {
        val telemetry = RenderSceneBreakdownTelemetry(windowSize = 3, reportIntervalNanos = 10L)
        telemetry.record(100L, 1L, 1L, 1L, 1L, 1L, 1L, 1L)
        telemetry.reset()
        assertNull(telemetry.record(200L, 2L, 2L, 2L, 2L, 2L, 2L, 2L))
    }
}
