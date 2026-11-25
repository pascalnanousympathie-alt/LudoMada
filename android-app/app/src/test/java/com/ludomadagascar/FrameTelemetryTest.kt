package com.ludomadagascar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class FrameTelemetryTest {
    @Test
    fun reportsFrameCadenceCpuSubmissionAndRefreshBudget() {
        val telemetry = FrameTelemetry(
            refreshRateHz = 60f,
            reportPeriodNanos = 80_000_000L,
            sampleCapacity = 8,
            minimumFrames = 4
        )
        var now = 1_000_000_000L
        telemetry.reset(now)

        val intervals = longArrayOf(16_000_000L, 20_000_000L, 8_000_000L, 40_000_000L)
        var report: FrameTelemetryReport? = null
        intervals.forEachIndexed { index, interval ->
            now += interval
            report = telemetry.record(interval, (index + 1) * 1_000_000L, now)
        }

        val actual = assertNotNullAndReturn(report)
        assertEquals(4, actual.frameCount)
        assertEquals(47.619f, actual.averageFps, 0.02f)
        assertEquals(60f, actual.targetRefreshHz, 0.01f)
        assertEquals(20f, actual.intervalP50Ms, 0.01f)
        assertEquals(40f, actual.intervalP95Ms, 0.01f)
        assertEquals(40f, actual.intervalMaxMs, 0.01f)
        assertEquals(3f, actual.cpuSubmitP50Ms, 0.01f)
        assertEquals(4f, actual.cpuSubmitP95Ms, 0.01f)
        assertEquals(2, actual.overBudgetFrames)
        assertEquals(1, actual.missedVsyncFrames)
    }

    @Test
    fun reportsPassCpuPercentilesAcrossTheWholeWindow() {
        val telemetry = FrameTelemetry(
            refreshRateHz = 60f,
            reportPeriodNanos = 1L,
            sampleCapacity = 4,
            minimumFrames = 4
        )
        var now = 1_000_000_000L
        telemetry.reset(now)
        var report: FrameTelemetryReport? = null
        val world = longArrayOf(2_000_000L, 4_000_000L, 6_000_000L, 8_000_000L)
        val particles = longArrayOf(100_000L, 200_000L, 300_000L, 400_000L)
        repeat(4) { index ->
            now += 16_000_000L
            report = telemetry.record(
                frameIntervalNanos = 16_000_000L,
                cpuSubmitNanos = 10_000_000L,
                nowNanos = now,
                shadowCpuNanos = (index + 1) * 500_000L,
                sceneCpuNanos = (index + 1) * 3_000_000L,
                worldCpuNanos = world[index],
                particlesCpuNanos = particles[index],
                simulationCpuNanos = (index + 1) * 100_000L,
                postCpuNanos = (index % 2) * 1_000_000L
            )
        }

        val actual = assertNotNullAndReturn(report)
        assertEquals(6f, actual.worldCpuP50Ms, 0.001f)
        assertEquals(8f, actual.worldCpuP95Ms, 0.001f)
        assertEquals(0.3f, actual.particlesCpuP50Ms, 0.001f)
        assertEquals(0.4f, actual.particlesCpuP95Ms, 0.001f)
        assertEquals(1.5f, actual.shadowCpuP50Ms, 0.001f)
        assertEquals(2f, actual.shadowCpuP95Ms, 0.001f)
        assertEquals(12f, actual.sceneCpuP95Ms, 0.001f)
        assertEquals(0.4f, actual.simulationCpuP95Ms, 0.001f)
        assertEquals(1f, actual.postCpuP95Ms, 0.001f)
    }

    @Test
    fun defaultWindowReportsAfterFiveSecondsAndRetainsFiveSecondsAt120Hz() {
        val telemetry = FrameTelemetry(refreshRateHz = 120f)
        var now = 3_000_000_000L
        telemetry.reset(now)

        var report: FrameTelemetryReport? = null
        repeat(599) {
            now += 8_333_334L
            report = telemetry.record(8_333_334L, 1_000_000L, now)
            assertNull(report)
        }
        now += 8_333_334L
        report = telemetry.record(8_333_334L, 1_000_000L, now)

        val actual = assertNotNullAndReturn(report)
        assertEquals(600, actual.frameCount)
        assertEquals(5_000L, actual.periodMs)
        assertEquals(120f, actual.averageFps, 0.02f)
        assertEquals(8.34f, actual.intervalP95Ms, 0.01f)
    }

    @Test
    fun ignoresIdleGapsAndDoesNotEmitBeforeWindowEnds() {
        val telemetry = FrameTelemetry(
            refreshRateHz = 120f,
            reportPeriodNanos = 100_000_000L,
            sampleCapacity = 4
        )
        var now = 2_000_000_000L
        telemetry.reset(now)

        now += 1_500_000_000L
        assertNull(telemetry.record(1_500_000_000L, 1_000_000L, now))

        now += 8_000_000L
        assertNull(telemetry.record(8_000_000L, 2_000_000L, now))
        now += 8_000_000L
        assertNull(telemetry.record(8_000_000L, 2_000_000L, now))
    }

    @Test
    fun retainsSevereFrameStallsInsteadOfDiscardingThem() {
        val telemetry = FrameTelemetry(
            refreshRateHz = 120f,
            reportPeriodNanos = 1L,
            sampleCapacity = 2,
            minimumFrames = 1
        )
        telemetry.reset(1_000_000_000L)
        val report = telemetry.record(300_000_000L, 80_000_000L, 1_300_000_000L)

        val actual = assertNotNullAndReturn(report)
        assertEquals(1, actual.frameCount)
        assertEquals(300f, actual.intervalMaxMs, 0.01f)
        assertEquals(1, actual.overBudgetFrames)
        assertEquals(1, actual.missedVsyncFrames)
    }

    @Test
    fun clampsInvalidRefreshRatesToSixtyHertz() {
        val telemetry = FrameTelemetry(
            refreshRateHz = 0f,
            reportPeriodNanos = 1L,
            sampleCapacity = 2,
            minimumFrames = 1
        )
        telemetry.reset(1_000L)
        val report = telemetry.record(16_000_000L, 1_000_000L, 16_001_000L)
        val actual = assertNotNullAndReturn(report)
        assertEquals(60f, actual.targetRefreshHz, 0.01f)
    }

    private fun assertNotNullAndReturn(report: FrameTelemetryReport?): FrameTelemetryReport {
        assertNotNull(report)
        return report!!
    }
}
