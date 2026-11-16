package com.ludomadagascar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FramePacingTelemetryTest {
    @Test
    fun stable120HzBurstReportsFramePacingPercentiles() {
        val telemetry=FramePacingTelemetry()
        var report:FramePacingReport?=null
        val interval=8_333_333L
        for(frame in 0..65){
            val start=1_000_000_000L+frame*interval
            telemetry.record(start,start+1_500_000L)?.let { report=it }
        }

        val measured=assertNotNullAndReturn(report)
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
        for(frame in 0..65){
            val start=2_000_000_000L+frame*interval
            telemetry.record(start,start+2_000_000L)?.let { report=it }
        }

        val measured=assertNotNullAndReturn(report)
        assertTrue(measured.averageFps in 59.0..61.0)
        assertEquals(measured.samples,measured.over120BudgetFrames)
        assertEquals(0,measured.over60BudgetFrames)
    }

    @Test
    fun shortThirtyHzAnimationBurstProducesReport() {
        val telemetry=FramePacingTelemetry()
        var report:FramePacingReport?=null
        val interval=33_333_333L
        for(frame in 0..12){
            val start=4_000_000_000L+frame*interval
            telemetry.record(start,start+2_000_000L)?.let { report=it }
        }

        val measured=assertNotNullAndReturn(report)
        assertTrue(measured.averageFps in 29.0..31.0)
        assertTrue(measured.p95FrameMs in 33.2..33.5)
        assertEquals(measured.samples,measured.over120BudgetFrames)
        assertEquals(measured.samples,measured.over60BudgetFrames)
    }

    @Test
    fun fewerThanMinimumSamplesDoesNotEmitReport() {
        val telemetry=FramePacingTelemetry()
        val interval=33_333_333L
        for(frame in 0..5){
            val start=4_500_000_000L+frame*interval
            assertNull(telemetry.record(start,start+1_000_000L))
        }
    }

    @Test
    fun idleGapStartsANewAnimationBurst() {
        val telemetry=FramePacingTelemetry()
        val interval=33_333_333L
        // A short first burst stays below the diagnostic minimum.
        for(frame in 0..5){
            val start=3_000_000_000L+frame*interval
            assertNull(telemetry.record(start,start+1_000_000L))
        }
        // A gap greater than 500 ms resets the partial window before the next burst.
        val afterIdle=4_500_000_000L
        var report:FramePacingReport?=null
        for(frame in 0..12){
            val start=afterIdle+frame*interval
            telemetry.record(start,start+1_000_000L)?.let { report=it }
        }
        val measured=assertNotNullAndReturn(report)
        assertTrue(measured.samples>=8)
        assertTrue(measured.averageFps in 29.0..31.0)
    }

    @Test
    fun p99ExposesAnIsolatedLongFrame() {
        val telemetry=FramePacingTelemetry()
        var report:FramePacingReport?=null
        val interval=8_333_333L
        var start=5_000_000_000L
        for(frame in 0..65){
            val frameInterval=if(frame==50)40_000_000L else interval
            start+=frameInterval
            telemetry.record(start,start+1_000_000L)?.let { report=it }
        }
        val measured=assertNotNullAndReturn(report)
        assertTrue(measured.p99FrameMs>=39.9)
    }

    @Test
    fun ignoresInvalidAndInsufficientFrameSamples() {
        val telemetry=FramePacingTelemetry()
        assertNull(telemetry.record(0L,1L))
        assertNull(telemetry.record(1_000L,900L))
        for(frame in 0..20){
            val start=10_000_000L+frame*8_333_333L
            assertNull(telemetry.record(start,start+500_000L))
        }
    }

    private fun assertNotNullAndReturn(report:FramePacingReport?):FramePacingReport {
        assertNotNull(report)
        return report!!
    }
}
