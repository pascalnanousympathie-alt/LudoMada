package com.ludomadagascar

import java.util.Locale
import kotlin.math.ceil

/**
 * Bounded frame-pacing sampler for the GLES render thread.
 * Long idle gaps split animation bursts but preserve valid active-frame samples;
 * the idle gap itself is never included in FPS or frame-time calculations.
 * Each report requires 120 frame intervals so p95/p99 are based on a useful sample.
 */
internal class FramePacingTelemetry(
    private val minimumSamples:Int = 120,
    private val minimumWindowNanos:Long = 1_000_000_000L,
    private val maximumBurstGapNanos:Long = 1_000_000_000L,
    private val capacity:Int = 240
) {
    private val intervalsNanos=LongArray(capacity)
    private val renderWorkNanos=LongArray(capacity)
    private val outsideRenderNanos=LongArray(capacity)
    private val shadowPassNanos=LongArray(capacity)
    private val scenePassNanos=LongArray(capacity)
    private val postProcessNanos=LongArray(capacity)
    private val sortedIntervalsScratch=LongArray(capacity)
    private val sortedRenderWorkScratch=LongArray(capacity)
    private val sortedOutsideRenderScratch=LongArray(capacity)
    private val sortedShadowScratch=LongArray(capacity)
    private val sortedSceneScratch=LongArray(capacity)
    private val sortedPostScratch=LongArray(capacity)
    private var sampleCount=0
    private var activeSpanNanos=0L
    private var lastFrameStartNanos=0L

    internal val sampleCountForDebug:Int
        get()=sampleCount

    internal fun debugProgressLogLine():String {
        val spanMs=String.format(Locale.US,"%.2f",activeSpanNanos.toDouble()/1_000_000.0)
        return "PBR_FRAME_PACING_PROGRESS samples=$sampleCount activeSpanMs=$spanMs"
    }

    init {
        require(minimumSamples>0)
        require(minimumWindowNanos>0L)
        require(maximumBurstGapNanos>0L)
        require(capacity>=minimumSamples)
    }

    private var pendingRenderWorkNanos=0L
    private var pendingShadowNanos=0L
    private var pendingSceneNanos=0L
    private var pendingPostNanos=0L

    fun record(
        frameStartNanos:Long,
        frameEndNanos:Long,
        shadowNanos:Long=0L,
        sceneNanos:Long=0L,
        postNanos:Long=0L
    ):FramePacingReport? {
        if(frameStartNanos<=0L || frameEndNanos<frameStartNanos)return null
        val currentRenderWork=(frameEndNanos-frameStartNanos).coerceAtLeast(0L)

        if(lastFrameStartNanos!=0L){
            val gap=frameStartNanos-lastFrameStartNanos
            when {
                gap<=0L -> resetWindow()
                // Split bursts without discarding valid samples collected before
                // the idle interval; the idle duration is excluded from activeSpan.
                gap>maximumBurstGapNanos -> lastFrameStartNanos=0L
            }
        }
        if(lastFrameStartNanos==0L){
            beginBurst(frameStartNanos,currentRenderWork,shadowNanos,sceneNanos,postNanos)
            return null
        }
        if(sampleCount>=capacity){
            resetWindow()
            beginBurst(frameStartNanos,currentRenderWork,shadowNanos,sceneNanos,postNanos)
            return null
        }

        // Pair the interval ending at this frame start with the render work and
        // stage timings from the preceding frame. That work happened inside the
        // interval being measured, not after the current frame start.
        val interval=frameStartNanos-lastFrameStartNanos
        intervalsNanos[sampleCount]=interval
        renderWorkNanos[sampleCount]=pendingRenderWorkNanos
        outsideRenderNanos[sampleCount]=(interval-pendingRenderWorkNanos).coerceAtLeast(0L)
        shadowPassNanos[sampleCount]=pendingShadowNanos
        scenePassNanos[sampleCount]=pendingSceneNanos
        postProcessNanos[sampleCount]=pendingPostNanos
        sampleCount+=1
        activeSpanNanos+=interval
        beginBurst(frameStartNanos,currentRenderWork,shadowNanos,sceneNanos,postNanos)

        if(sampleCount<minimumSamples || activeSpanNanos<minimumWindowNanos)return null
        return createReport(activeSpanNanos).also { resetWindow() }
    }

    private fun beginBurst(
        frameStartNanos:Long,
        renderWorkNanos:Long,
        shadowNanos:Long,
        sceneNanos:Long,
        postNanos:Long
    ){
        lastFrameStartNanos=frameStartNanos
        pendingRenderWorkNanos=renderWorkNanos
        pendingShadowNanos=shadowNanos.coerceAtLeast(0L)
        pendingSceneNanos=sceneNanos.coerceAtLeast(0L)
        pendingPostNanos=postNanos.coerceAtLeast(0L)
    }

    private fun createReport(spanNanos:Long):FramePacingReport {
        System.arraycopy(intervalsNanos,0,sortedIntervalsScratch,0,sampleCount)
        System.arraycopy(renderWorkNanos,0,sortedRenderWorkScratch,0,sampleCount)
        System.arraycopy(outsideRenderNanos,0,sortedOutsideRenderScratch,0,sampleCount)
        System.arraycopy(shadowPassNanos,0,sortedShadowScratch,0,sampleCount)
        System.arraycopy(scenePassNanos,0,sortedSceneScratch,0,sampleCount)
        System.arraycopy(postProcessNanos,0,sortedPostScratch,0,sampleCount)
        java.util.Arrays.sort(sortedIntervalsScratch,0,sampleCount)
        java.util.Arrays.sort(sortedRenderWorkScratch,0,sampleCount)
        java.util.Arrays.sort(sortedOutsideRenderScratch,0,sampleCount)
        java.util.Arrays.sort(sortedShadowScratch,0,sampleCount)
        java.util.Arrays.sort(sortedSceneScratch,0,sampleCount)
        java.util.Arrays.sort(sortedPostScratch,0,sampleCount)
        var totalIntervalNanos=0L
        var totalOutsideNanos=0L
        var totalShadowNanos=0L
        var totalSceneNanos=0L
        var totalPostNanos=0L
        for(index in 0 until sampleCount){
            totalIntervalNanos+=intervalsNanos[index]
            totalOutsideNanos+=outsideRenderNanos[index]
            totalShadowNanos+=shadowPassNanos[index]
            totalSceneNanos+=scenePassNanos[index]
            totalPostNanos+=postProcessNanos[index]
        }
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
            averageOutsideRenderMs=totalOutsideNanos.toDouble()/sampleCount/1_000_000.0,
            p95OutsideRenderMs=percentile(sortedOutsideRenderScratch,sampleCount,0.95),
            averageShadowPassMs=totalShadowNanos.toDouble()/sampleCount/1_000_000.0,
            p95ShadowPassMs=percentile(sortedShadowScratch,sampleCount,0.95),
            maxShadowPassMs=percentile(sortedShadowScratch,sampleCount,1.0),
            p95ScenePassMs=percentile(sortedSceneScratch,sampleCount,0.95),
            maxScenePassMs=percentile(sortedSceneScratch,sampleCount,1.0),
            averageScenePassMs=totalSceneNanos.toDouble()/sampleCount/1_000_000.0,
            p95PostProcessMs=percentile(sortedPostScratch,sampleCount,0.95),
            averagePostProcessMs=totalPostNanos.toDouble()/sampleCount/1_000_000.0,
            maxPostProcessMs=percentile(sortedPostScratch,sampleCount,1.0),
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
        activeSpanNanos=0L
        lastFrameStartNanos=0L
        pendingRenderWorkNanos=0L
        pendingShadowNanos=0L
        pendingSceneNanos=0L
        pendingPostNanos=0L
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
    val averageOutsideRenderMs:Double,
    val p95OutsideRenderMs:Double,
    val averageShadowPassMs:Double,
    val p95ShadowPassMs:Double,
    val maxShadowPassMs:Double,
    val p95ScenePassMs:Double,
    val maxScenePassMs:Double,
    val averageScenePassMs:Double,
    val p95PostProcessMs:Double,
    val averagePostProcessMs:Double,
    val maxPostProcessMs:Double,
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
            " outsideRenderAvgMs=${n(averageOutsideRenderMs)} outsideRenderP95Ms=${n(p95OutsideRenderMs)}" +
            " shadowAvgMs=${n(averageShadowPassMs)} shadowP95Ms=${n(p95ShadowPassMs)} shadowMaxMs=${n(maxShadowPassMs)}" +
            " sceneAvgMs=${n(averageScenePassMs)} sceneP95Ms=${n(p95ScenePassMs)} sceneMaxMs=${n(maxScenePassMs)}" +
            " postAvgMs=${n(averagePostProcessMs)} postP95Ms=${n(p95PostProcessMs)} postMaxMs=${n(maxPostProcessMs)}" +
            " over120Budget=$over120BudgetFrames/$samples" +
            " over60Budget=$over60BudgetFrames/$samples"
    }
}
