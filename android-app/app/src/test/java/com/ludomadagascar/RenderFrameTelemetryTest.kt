package com.ludomadagascar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RenderFrameTelemetryTest {
    @Test
    fun reportsFrameCadenceAndGlSubmissionPercentilesAtTheConfiguredBoundary() {
        val telemetry = RenderFrameTelemetry(
            windowSize = 4,
            reportIntervalNanos = 30_000_000L
        )

        assertNull(telemetry.record(1_000_000_000L, 5_000_000L))
        assertNull(telemetry.record(1_010_000_000L, 8_000_000L))
        val snapshot = telemetry.record(1_030_000_000L, 25_000_000L)

        assertNotNull(snapshot)
        snapshot!!
        assertEquals(2, snapshot.sampleCount)
        assertEquals(66.67f, snapshot.estimatedFps, 0.1f)
        assertEquals(10f, snapshot.frameIntervalP50Ms, 0.01f)
        assertEquals(20f, snapshot.frameIntervalP95Ms, 0.01f)
        assertEquals(20f, snapshot.frameIntervalMaxMs, 0.01f)
        assertEquals(8f, snapshot.glSubmitP50Ms, 0.01f)
        assertEquals(25f, snapshot.glSubmitP95Ms, 0.01f)
        assertEquals(25f, snapshot.glSubmitMaxMs, 0.01f)
    }

    @Test
    fun keepsOnlyTheLatestSamplesWhenTheWindowWraps() {
        val telemetry = RenderFrameTelemetry(
            windowSize = 2,
            reportIntervalNanos = 20_000_000L
        )

        telemetry.record(1_000_000_000L, 2_000_000L)
        telemetry.record(1_010_000_000L, 3_000_000L)
        val first = telemetry.record(1_020_000_000L, 4_000_000L)
        assertNotNull(first)
        assertEquals(2, first!!.sampleCount)

        telemetry.record(1_030_000_000L, 5_000_000L)
        val second = telemetry.record(1_040_000_000L, 6_000_000L)
        assertNotNull(second)
        assertEquals(2, second!!.sampleCount)
        assertEquals(10f, second.frameIntervalP50Ms, 0.01f)
        assertEquals(6f, second.glSubmitP95Ms, 0.01f)
    }

    @Test
    fun resetDropsPreviousFrameAndPendingWindowState() {
        val telemetry = RenderFrameTelemetry(
            windowSize = 3,
            reportIntervalNanos = 10_000_000L
        )
        telemetry.record(1_000_000_000L, 1_000_000L)
        telemetry.record(1_010_000_000L, 1_000_000L)
        telemetry.reset()

        assertNull(telemetry.record(2_000_000_000L, 1_000_000L))
        assertNull(telemetry.record(2_005_000_000L, 1_000_000L))
    }
}
