package com.ludomadagascar

import android.opengl.GLES30
import java.util.Arrays
import kotlin.math.ceil
import kotlin.math.min

/**
 * Non-blocking GPU execution timer for GLES implementations exposing
 * GL_EXT_disjoint_timer_query.
 *
 * Results are polled only after GL_QUERY_RESULT_AVAILABLE is true. No glFinish,
 * client wait, or synchronous result read is issued from the render loop.
 *
 * GLES30 exposes the unsigned 32-bit query-result accessor on Android. To avoid
 * ambiguity from its low 32 bits, samples >= 1 second are rejected; gameplay
 * frames should be far below that bound.
 */
internal data class GpuTimerSnapshot(
    val status: String,
    val sampleCount: Int,
    val p50Ms: Float?,
    val p95Ms: Float?,
    val disjointCount: Int,
    val skippedFrames: Int
)

internal class GlesGpuTimerQuery(
    private val queryCount: Int = 4,
    private val windowSize: Int = 60
) {
    companion object {
        private const val GL_TIME_ELAPSED_EXT = 0x88BF
        private const val GL_QUERY_RESULT_EXT = 0x8866
        private const val GL_QUERY_RESULT_AVAILABLE_EXT = 0x8867
        private const val GL_GPU_DISJOINT_EXT = 0x8FBB
        private const val MAX_UNAMBIGUOUS_RESULT_NANOS = 1_000_000_000L
        private const val MIN_REPORTED_SAMPLES = 20
    }

    init {
        require(queryCount >= 2) { "queryCount must be at least 2" }
        require(windowSize >= 2) { "windowSize must be at least 2" }
    }

    private val queryIds = IntArray(queryCount)
    private val pendingSlots = IntArray(queryCount)
    private val valueScratch = IntArray(1)
    private val samplesNanos = LongArray(windowSize)
    private val percentileScratch = LongArray(windowSize)
    private var nextSampleIndex = 0
    private var sampleCount = 0
    private var pendingHead = 0
    private var pendingCount = 0
    private var nextQuerySlot = 0
    private var activeSlot = -1
    private var supported = false
    private var initialized = false
    private var disjointCount = 0
    private var skippedFrames = 0

    /** Must be called on the GL thread after a new context is current. */
    fun initialize(extensions: String?) {
        Arrays.fill(queryIds, 0)
        Arrays.fill(pendingSlots, 0)
        resetSamples()
        pendingHead = 0
        pendingCount = 0
        nextQuerySlot = 0
        activeSlot = -1
        disjointCount = 0
        skippedFrames = 0
        initialized = true
        supported = extensions
            ?.split(' ')
            ?.any { it == "GL_EXT_disjoint_timer_query" } == true
        if (!supported) return

        GLES30.glGenQueries(queryCount, queryIds, 0)
        if (queryIds.any { it == 0 }) {
            val allocated = queryIds.filter { it != 0 }.toIntArray()
            if (allocated.isNotEmpty()) GLES30.glDeleteQueries(allocated.size, allocated, 0)
            Arrays.fill(queryIds, 0)
            supported = false
            return
        }

        // The extension requires at least 30 timer bits. Since the Android
        // GLES30 Java binding exposes a 32-bit result accessor, reject counters
        // below 30 bits and discard any elapsed result at or above one second.
        valueScratch[0] = 0
        GLES30.glGetQueryiv(GL_TIME_ELAPSED_EXT, 0x8864, valueScratch, 0)
        if (valueScratch[0] < 30) {
            GLES30.glDeleteQueries(queryIds.size, queryIds, 0)
            Arrays.fill(queryIds, 0)
            supported = false
        }
    }

    /** Poll completed work without blocking, then start a new frame query if a slot is free. */
    fun beginFrame() {
        activeSlot = -1
        if (!initialized || !supported) return

        pollCompletedQueries()
        if (pendingCount >= queryCount) {
            skippedFrames++
            return
        }
        val slot = nextQuerySlot
        nextQuerySlot = (nextQuerySlot + 1) % queryCount
        GLES30.glBeginQuery(GL_TIME_ELAPSED_EXT, queryIds[slot])
        activeSlot = slot
    }

    /** Ends the current query after all commands for renderDirectFrame have been submitted. */
    fun endFrame() {
        val slot = activeSlot
        if (slot < 0) return
        GLES30.glEndQuery(GL_TIME_ELAPSED_EXT)
        val tail = (pendingHead + pendingCount) % queryCount
        pendingSlots[tail] = slot
        pendingCount++
        activeSlot = -1
    }

    fun snapshot(): GpuTimerSnapshot {
        val p50 = percentileMillis(0.50)
        val p95 = percentileMillis(0.95)
        val status = when {
            !initialized || !supported -> "UNSUPPORTED"
            sampleCount < MIN_REPORTED_SAMPLES -> "INSUFFICIENT"
            else -> "MEASURED"
        }
        return GpuTimerSnapshot(
            status = status,
            sampleCount = sampleCount,
            p50Ms = if (status == "MEASURED") p50 else null,
            p95Ms = if (status == "MEASURED") p95 else null,
            disjointCount = disjointCount,
            skippedFrames = skippedFrames
        )
    }

    private fun pollCompletedQueries() {
        if (pendingCount == 0) return
        val slot = pendingSlots[pendingHead]
        valueScratch[0] = 0
        GLES30.glGetQueryObjectuiv(
            queryIds[slot],
            GL_QUERY_RESULT_AVAILABLE_EXT,
            valueScratch,
            0
        )
        // Availability is the only result query performed on an unfinished slot.
        // Poll one FIFO entry per frame to keep instrumentation overhead bounded.
        if (valueScratch[0] == 0) return

        valueScratch[0] = 0
        GLES30.glGetIntegerv(GL_GPU_DISJOINT_EXT, valueScratch, 0)
        if (valueScratch[0] != 0) {
            disjointCount++
            resetSamples()
            pendingHead = 0
            pendingCount = 0
            return
        }

        valueScratch[0] = 0
        GLES30.glGetQueryObjectuiv(
            queryIds[slot],
            GL_QUERY_RESULT_EXT,
            valueScratch,
            0
        )
        val elapsedNanos = valueScratch[0].toLong() and 0xffffffffL
        if (elapsedNanos in 1L until MAX_UNAMBIGUOUS_RESULT_NANOS) {
            samplesNanos[nextSampleIndex] = elapsedNanos
            nextSampleIndex = (nextSampleIndex + 1) % windowSize
            sampleCount = min(windowSize, sampleCount + 1)
        }
        pendingHead = (pendingHead + 1) % queryCount
        pendingCount--
    }

    private fun resetSamples() {
        Arrays.fill(samplesNanos, 0L)
        Arrays.fill(percentileScratch, 0L)
        nextSampleIndex = 0
        sampleCount = 0
    }

    private fun percentileMillis(percentile: Double): Float? {
        if (sampleCount == 0) return null
        for (i in 0 until sampleCount) percentileScratch[i] = samplesNanos[i]
        Arrays.sort(percentileScratch, 0, sampleCount)
        val rank = ceil(percentile * sampleCount.toDouble()).toInt().coerceIn(1, sampleCount)
        return percentileScratch[rank - 1] / 1_000_000f
    }
}
