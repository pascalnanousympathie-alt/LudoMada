package com.ludomadagascar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnimationVsyncTelemetryTest {
    @Test
    fun reportsMainThreadVsyncCadenceIncludingMissedFrames() {
        val telemetry = AnimationVsyncTelemetry(windowSize = 4)
        telemetry.record(1_000_000_000L)
        telemetry.record(1_016_000_000L)
        telemetry.record(1_032_000_000L)
        telemetry.record(1_064_000_000L)

        val snapshot = telemetry.snapshot()
        assertEquals(3, snapshot.sampleCount)
        assertEquals(46.875f, snapshot.fps!!, 0.01f)
        assertEquals(16f, snapshot.intervalP50Ms!!, 0.01f)
        assertEquals(32f, snapshot.intervalP95Ms!!, 0.01f)
    }

    @Test
    fun ignoresInvalidOrNonIncreasingFrameTimestamps() {
        val telemetry = AnimationVsyncTelemetry(windowSize = 4)
        telemetry.record(0L)
        telemetry.record(1_000L)
        telemetry.record(1_000L)
        telemetry.record(500L)

        val snapshot = telemetry.snapshot()
        assertEquals(0, snapshot.sampleCount)
        assertNull(snapshot.fps)
        assertNull(snapshot.intervalP50Ms)
        assertNull(snapshot.intervalP95Ms)
    }

    @Test
    fun resetClearsThePreviousFrameAndAllSamples() {
        val telemetry = AnimationVsyncTelemetry(windowSize = 3)
        telemetry.record(1_000_000_000L)
        telemetry.record(1_016_000_000L)
        telemetry.reset()
        telemetry.record(2_000_000_000L)

        val snapshot = telemetry.snapshot()
        assertEquals(0, snapshot.sampleCount)
        assertNull(snapshot.fps)
        assertNull(snapshot.intervalP50Ms)
        assertNull(snapshot.intervalP95Ms)
    }

    @Test
    fun keepsOnlyLatestSamplesAfterTheWindowWraps() {
        val telemetry = AnimationVsyncTelemetry(windowSize = 2)
        telemetry.record(1_000_000_000L)
        telemetry.record(1_016_000_000L)
        telemetry.record(1_032_000_000L)
        telemetry.record(1_064_000_000L)

        val snapshot = telemetry.snapshot()
        assertEquals(2, snapshot.sampleCount)
        assertEquals(41.6667f, snapshot.fps!!, 0.02f)
        assertEquals(16f, snapshot.intervalP50Ms!!, 0.01f)
        assertEquals(32f, snapshot.intervalP95Ms!!, 0.01f)
    }
}
