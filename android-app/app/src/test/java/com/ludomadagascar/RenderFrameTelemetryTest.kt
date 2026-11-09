package com.ludomadagascar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RenderFrameTelemetryTest {
    @Test
    fun reportsActiveFrameCadenceAndGlSubmissionPercentilesAtTheConfiguredBoundary() {
        val telemetry = RenderFrameTelemetry(windowSize = 4, reportIntervalNanos = 30_000_000L)
        assertNull(telemetry.record(1_000_000_000L, 5_000_000L))
        assertNull(telemetry.record(1_010_000_000L, 8_000_000L))
        val snapshot = telemetry.record(1_030_000_000L, 25_000_000L)

        assertNotNull(snapshot)
        snapshot!!
        assertEquals(2, snapshot.activeFrameSampleCount)
        assertEquals(66.67f, snapshot.activeFrameFps!!, 0.1f)
        assertEquals(10f, snapshot.activeFrameIntervalP50Ms!!, 0.01f)
        assertEquals(20f, snapshot.activeFrameIntervalP95Ms!!, 0.01f)
        assertEquals(20f, snapshot.activeFrameIntervalMaxMs!!, 0.01f)
        assertEquals(3, snapshot.glSubmissionSampleCount)
        assertEquals(8f, snapshot.glSubmitP50Ms, 0.01f)
        assertEquals(25f, snapshot.glSubmitP95Ms, 0.01f)
        assertEquals(25f, snapshot.glSubmitMaxMs, 0.01f)
    }

    @Test
    fun separatesCallbackCpuWorkFromTheFollowingPresentationAndSchedulingGap() {
        val telemetry = RenderFrameTelemetry(windowSize = 4, reportIntervalNanos = 30_000_000L)
        assertNull(telemetry.record(1_000_000_000L, 5_000_000L))
        telemetry.finishCallback(1_000_000_000L, 6_000_000L)
        assertNull(telemetry.record(1_010_000_000L, 8_000_000L))
        telemetry.finishCallback(1_010_000_000L, 8_000_000L)
        val snapshot = telemetry.record(1_030_000_000L, 25_000_000L)

        assertNotNull(snapshot)
        snapshot!!
        assertEquals(2, snapshot.activeFrameSampleCount)
        assertEquals(4f, snapshot.afterCallbackGapP50Ms!!, 0.01f)
        assertEquals(12f, snapshot.afterCallbackGapP95Ms!!, 0.01f)
    }

    @Test
    fun excludesLongDirtyRenderIdleGapsFromTheActiveFpsEstimate() {
        val telemetry = RenderFrameTelemetry(windowSize = 8, reportIntervalNanos = 20_000_000L)
        telemetry.record(1_000_000_000L, 65_000_000L)
        telemetry.record(1_010_000_000L, 12_000_000L)
        val snapshot = telemetry.record(6_100_000_000L, 8_000_000L)

        assertNotNull(snapshot)
        snapshot!!
        assertEquals(1, snapshot.activeFrameSampleCount)
        assertNull(snapshot.activeFrameFps)
        assertEquals(3, snapshot.glSubmissionSampleCount)
        // The rolling three-sample submission window is [65, 12, 8] ms:
        // p50 is 12 ms, while the first-frame spike remains visible as max.
        assertEquals(12f, snapshot.glSubmitP50Ms, 0.01f)
        assertEquals(65f, snapshot.glSubmitMaxMs, 0.01f)
    }

    @Test
    fun resumesActiveFpsSamplingAfterAnIdleGap() {
        val telemetry = RenderFrameTelemetry(windowSize = 8, reportIntervalNanos = 20_000_000L)
        telemetry.record(1_000_000_000L, 5_000_000L)
        telemetry.record(1_010_000_000L, 6_000_000L)
        telemetry.record(6_100_000_000L, 7_000_000L)
        telemetry.record(6_110_000_000L, 8_000_000L)
        val snapshot = telemetry.record(6_120_000_000L, 9_000_000L)

        assertNotNull(snapshot)
        snapshot!!
        assertEquals(3, snapshot.activeFrameSampleCount)
        assertEquals(100f, snapshot.activeFrameFps!!, 0.1f)
        assertEquals(5, snapshot.glSubmissionSampleCount)
    }

    @Test
    fun keepsOnlyTheLatestSamplesWhenTheWindowWraps() {
        val telemetry = RenderFrameTelemetry(windowSize = 2, reportIntervalNanos = 20_000_000L)
        telemetry.record(1_000_000_000L, 2_000_000L)
        telemetry.record(1_010_000_000L, 3_000_000L)
        val first = telemetry.record(1_020_000_000L, 4_000_000L)

        assertNotNull(first)
        assertEquals(2, first!!.activeFrameSampleCount)
        telemetry.record(1_030_000_000L, 5_000_000L)
        val second = telemetry.record(1_040_000_000L, 6_000_000L)

        assertNotNull(second)
        assertEquals(2, second!!.activeFrameSampleCount)
        assertEquals(10f, second.activeFrameIntervalP50Ms!!, 0.01f)
        assertEquals(2, second.glSubmissionSampleCount)
        assertEquals(6f, second.glSubmitP95Ms, 0.01f)
    }

    @Test
    fun resetDropsPreviousFrameAndPendingWindowState() {
        val telemetry = RenderFrameTelemetry(windowSize = 3, reportIntervalNanos = 10_000_000L)
        telemetry.record(1_000_000_000L, 1_000_000L)
        telemetry.record(1_010_000_000L, 1_000_000L)
        telemetry.reset()

        assertNull(telemetry.record(2_000_000_000L, 1_000_000L))
        assertNull(telemetry.record(2_005_000_000L, 1_000_000L))
    }
}
