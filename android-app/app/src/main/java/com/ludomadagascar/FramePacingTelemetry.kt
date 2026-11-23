package com.ludomadagascar

import java.util.Locale
import kotlin.math.ceil

/**
 * Bounded frame-pacing sampler for the GLES render thread.
 * Long idle periods split samples into separate animation bursts so idle time
 * is not misreported as a rendering stall. Each report requires 120 frame intervals
 * so p95/p99 are based on a useful sample rather than a handful of frames.
 */
internal class FramePacingTelemetry(
    private val minimumSamples:Int = 120,
    private val minimumWindowNanos:Long = 1_000_000_000L,
    private val maximumBurstGapNanos:Long = 1_000_000_000L,
    private val capacity:Int = 240
) {
    private val intervalsNanos=LongArray(capacity)
    private val renderWorkNanos=LongArray(capacity)
    private val sortedIntervalsScratch=LongArray(capacity)
    private val sortedRenderWorkScratch=LongArray(capacity)
    private var sampleCount=0
    private var burstStartNanos=0L
    private var lastFrameStartNanos=0L

    init {
        require(minimumSamples>0)
        require(minimumWindowNanos>0L)
        require(maximumBurstGapNanos>0L)
        require(capacity>=minimumSamples)
    }

    fun record(frameStartNanos:Long,frameEndNanos:Long):FramePacingReport? {
        if(frameStartNanos<=0L || frameEndNanos<frameStartNanos)return null

        if(lastFrameStartNanos!=0L){
            val gap=frameStartNanos-lastFrameStartNanos
            if(gap<=0L || gap>maximumBurstGapNanos)resetWindow()
        }
        if(lastFrameStartNanos==0L){
            burstStartNanos=frameStartNanos
            lastFrameStartNanos=frameStartNanos
            return null
        }
        if(sampleCount>=capacity)resetWindow()
        if(lastFrameStartNanos==0L){
            burstStartNanos=frameStartNanos
            lastFrameStartNanos=frameStartNanos
            return null
        }

        val interval=frameStartNanos-lastFrameStartNanos
        intervalsNanos[sampleCount]=interval
        renderWorkNanos[sampleCount]=(frameEndNanos-frameStartNanos).coerceAtLeast(0L)
        sampleCount+=1
        lastFrameStartNanos=frameStartNanos

        val span=frameStartNanos-burstStartNanos
        if(sampleCount<minimumSamples || span<minimumWindowNanos)return null

        return createReport(span).also { resetWindow() }
    }

    private fun createReport(spanNanos:Long):FramePacingReport {
        System.arraycopy(intervalsNanos,0,sortedIntervalsScratch,0,sampleCount)
        System.arraycopy(renderWorkNanos,0,sortedRenderWorkScratch,0,sampleCount)
        java.util.Arrays.sort(sortedIntervalsScratch,0,sampleCount)
        java.util.Arrays.sort(sortedRenderWorkScratch,0,sampleCount)
        var totalIntervalNanos=0L
        for(index in 0 until sampleCount)totalIntervalNanos+=intervalsNanos[index]
        val safeSpan=spanNanos.coerceAtLeast(1L)
        val averageFps=sampleCount.toDouble()*1_000_000_000.0/safeSpan.toDouble()
        val averageFrameMs=totalIntervalNanos.toDouble()/sampleCount/1_000_000.0
        val over120Budget=(0 until sampleCount).count { intervalsNanos[it]>8_333_333L }
        val overRefreshBudget=(0 until sampleCount).count { intervalsNanos[it]>16_666_667L }
        return FramePacingReport(
            samples=sampleCount,
            spanMs=safeSpan.toDouble()/1_000_000.0,
            averageFps=averageFps,
            averageFrameMs=averageFrameMs,
            p50FrameMs=percentile(sortedIntervalsScratch,sampleCount,0.50),
            p95FrameMs=percentile(sortedIntervalsScratch,sampleCount,0.95),
            p99FrameMs=percentile(sortedIntervalsScratch,sampleCount,0.99),
            maxFrameMs=sortedIntervalsScratch[sampleCount-1].toDouble()/1_000_000.0,
            p95GlWorkMs=percentile(sortedRenderWorkScratch,sampleCount,0.95),
            over120BudgetFrames=over120Budget,
            over60BudgetFrames=overRefreshBudget
        )
    }

    private fun percentile(sorted:LongArray,count:Int,quantile:Double):Double {
        if(count<=0)return 0.0
        val index=(ceil(quantile*count).toInt()-1).coerceIn(0,count-1)
        return sorted[index].toDouble()/1_000_000.0
    }

    private fun resetWindow() {
        sampleCount=0
        burstStartNanos=0L
        lastFrameStartNanos=0L
    }
}

internal data class FramePacingReport(
    val samples:Int,
    val spanMs:Double,
    val averageFps:Double,
    val averageFrameMs:Double,
    val p50FrameMs:Double,
    val p95FrameMs:Double,
    val p99FrameMs:Double,
    val maxFrameMs:Double,
    val p95GlWorkMs:Double,
    val over120BudgetFrames:Int,
    val over60BudgetFrames:Int
) {
    fun toLogLine(refreshRateHz:Float):String {
        fun n(value:Double)=String.format(Locale.US,"%.2f",value)
        return "PBR_FRAME_PACING samples=$samples spanMs=${n(spanMs)}" +
            " refreshHz=${n(refreshRateHz.toDouble())}" +
            " avgFps=${n(averageFps)} avgFrameMs=${n(averageFrameMs)}" +
            " p50FrameMs=${n(p50FrameMs)} p95FrameMs=${n(p95FrameMs)}" +
            " p99FrameMs=${n(p99FrameMs)} maxFrameMs=${n(maxFrameMs)}" +
            " glWorkP95Ms=${n(p95GlWorkMs)}" +
            " over120Budget=$over120BudgetFrames/$samples" +
            " over60Budget=$over60BudgetFrames/$samples"
    }
}
