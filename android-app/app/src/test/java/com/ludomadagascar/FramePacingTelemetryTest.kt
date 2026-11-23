package com.ludomadagascar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FramePacingTelemetryTest {
    @Test
    fun stable120HzBurstReportsFramePacingPercentilesFromAtLeast120Samples() {
        val telemetry=FramePacingTelemetry()
        var report:FramePacingReport?=null
        val interval=8_333_333L
        for(frame in 0..130){
            val start=1_000_000_000L+frame*interval
            telemetry.record(start,start+1_500_000L)?.let { report=it }
        }

        val measured=assertNotNullAndReturn(report)
        assertTrue(measured.samples>=120)
        assertTrue(measured.averageFps>119.0)
        assertTrue(measured.p95FrameMs<8.34)
        assertEquals(0,measured.over120BudgetFrames)
        assertTrue(measured.p95GlWorkMs>=1.49)
    }

    @Test
    fun sixtyHzBurstIsNotMisreportedAs120Fps() {
        val telemetry=FramePacingTelemetry()
        var report:FramePacingReport?=null
        val interval=16_666_667L
        for(frame in 0..130){
            val start=2_000_000_000L+frame*interval
            telemetry.record(start,start+2_000_000L)?.let { report=it }
        }

        val measured=assertNotNullAndReturn(report)
        assertTrue(measured.samples>=120)
        assertTrue(measured.averageFps in 59.0..61.0)
        assertEquals(measured.samples,measured.over120BudgetFrames)
        assertEquals(0,measured.over60BudgetFrames)
    }

    @Test
    fun thirtyHzBurstUsesTheFullSampleWindow() {
        val telemetry=FramePacingTelemetry()
        var report:FramePacingReport?=null
        val interval=33_333_333L
        for(frame in 0..130){
            val start=4_000_000_000L+frame*interval
            telemetry.record(start,start+2_000_000L)?.let { report=it }
        }

        val measured=assertNotNullAndReturn(report)
        assertTrue(measured.samples>=120)
        assertTrue(measured.averageFps in 29.0..31.0)
        assertTrue(measured.p95FrameMs in 33.2..33.5)
        assertEquals(measured.samples,measured.over120BudgetFrames)
        assertEquals(measured.samples,measured.over60BudgetFrames)
    }

    @Test
    fun fewerThan120IntervalsDoesNotEmitAReport() {
        val telemetry=FramePacingTelemetry()
        val interval=16_666_667L
        for(frame in 0..119){
            val start=4_500_000_000L+frame*interval
            assertNull(telemetry.record(start,start+1_000_000L))
        }
    }

    @Test
    fun emitsAReportWhenThe120thIntervalCompletesTheWindow() {
        val telemetry=FramePacingTelemetry()
        val interval=16_666_667L
        var report:FramePacingReport?=null
        for(frame in 0..120){
            val start=5_000_000_000L+frame*interval
            telemetry.record(start,start+1_000_000L)?.let { report=it }
        }

        val measured=assertNotNullAndReturn(report)
        assertEquals(120,measured.samples)
        assertTrue(measured.spanMs>=1_000.0)
    }

    @Test
    fun idleGapStartsANewAnimationBurst() {
        val telemetry=FramePacingTelemetry()
        val interval=33_333_333L
        for(frame in 0..9){
            val start=3_000_000_000L+frame*interval
            assertNull(telemetry.record(start,start+1_000_000L))
        }
        val afterIdle=4_500_000_000L
        var report:FramePacingReport?=null
        for(frame in 0..130){
            val start=afterIdle+frame*interval
            telemetry.record(start,start+1_000_000L)?.let { report=it }
        }
        val measured=assertNotNullAndReturn(report)
        assertEquals(120,measured.samples)
        assertTrue(measured.averageFps in 29.0..31.0)
    }

    @Test
    fun activeBurstSamplesAccumulateWithoutCountingLongIdleGaps() {
        val telemetry=FramePacingTelemetry()
        var report:FramePacingReport?=null
        val interval=16_666_667L
        val firstBurstStart=7_000_000_000L

        // First animation burst contributes 60 valid frame intervals.
        for(frame in 0..60){
            val start=firstBurstStart+frame*interval
            telemetry.record(start,start+1_000_000L)?.let { report=it }
        }
        assertNull(report)

        // Five seconds of idle time separates the next burst and must not be
        // counted as a long frame or force the 60 earlier samples to be discarded.
        val secondBurstStart=firstBurstStart+60*interval+5_000_000_000L
        for(frame in 0..60){
            val start=secondBurstStart+frame*interval
            telemetry.record(start,start+1_000_000L)?.let { report=it }
        }

        val measured=assertNotNullAndReturn(report)
        assertEquals(120,measured.samples)
        assertTrue(measured.averageFps in 59.0..61.0)
        assertTrue(measured.spanMs in 1_999.0..2_001.0)
        assertTrue(measured.maxFrameMs<17.0)
    }

    @Test
    fun p99ExposesARepeatedLongFrameHitch() {
        val telemetry=FramePacingTelemetry()
        val reports=mutableListOf<FramePacingReport>()
        var start=5_000_000_000L
        for(frame in 0..130){
            val interval=if(frame in 60..62)40_000_000L else 8_333_333L
            start+=interval
            telemetry.record(start,start+1_000_000L)?.let { reports.add(it) }
        }
        assertTrue(reports.any { it.samples>=120 && it.p99FrameMs>=39.9 })
    }

    @Test
    fun slowActiveBurstRetainsLongFrameIntervalsInsteadOfResettingAsIdle() {
        val telemetry=FramePacingTelemetry()
        var start=6_000_000_000L
        var report:FramePacingReport?=null
        for(frame in 0..120){
            val interval=if(frame==60)600_000_000L else 100_000_000L
            start+=interval
            telemetry.record(start,start+2_000_000L)?.let { report=it }
        }

        val measured=assertNotNullAndReturn(report)
        assertEquals(120,measured.samples)
        assertTrue(measured.maxFrameMs>=599.0)
        assertTrue(measured.averageFps<10.0)
    }

    @Test
    fun ignoresInvalidAndInsufficientFrameSamples() {
        val telemetry=FramePacingTelemetry()
        assertNull(telemetry.record(0L,1L))
        assertNull(telemetry.record(1_000L,900L))
        for(frame in 0..2){
            val start=10_000_000L+frame*8_333_333L
            assertNull(telemetry.record(start,start+500_000L))
        }
    }

    private fun assertNotNullAndReturn(report:FramePacingReport?):FramePacingReport {
        assertNotNull(report)
        return report!!
    }
}
