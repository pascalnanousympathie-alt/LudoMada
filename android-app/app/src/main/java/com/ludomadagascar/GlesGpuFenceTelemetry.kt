package com.ludomadagascar

import android.opengl.GLES30
import java.util.Arrays
import kotlin.math.ceil
import kotlin.math.min

internal interface GlesFenceSyncApi {
    fun fenceSync(): Long
    fun clientWaitSync(sync: Long): Int
    fun deleteSync(sync: Long)
}

private object AndroidGlesFenceSyncApi : GlesFenceSyncApi {
    override fun fenceSync(): Long =
        GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)

    override fun clientWaitSync(sync: Long): Int =
        GLES30.glClientWaitSync(sync, 0, 0L)

    override fun deleteSync(sync: Long) {
        GLES30.glDeleteSync(sync)
    }
}

internal data class GpuFenceSnapshot(
    val status: String,
    val sampleCount: Int,
    val completionP50Ms: Float?,
    val completionP95Ms: Float?,
    val pendingCount: Int,
    val oldestPendingMs: Float?,
    val waitTimeouts: Int,
    val waitFailures: Int,
    val createFailures: Int,
    val skippedFrames: Int
)

/**
 * Non-blocking GLES fence diagnostic.
 *
 * A frame fence is inserted after renderDirectFrame and polled with a zero
 * timeout on a later GL callback. The reported duration is the upper-bound
 * time from fence insertion until the first later callback that observes its
 * completion; it is NOT a precise GPU execution timer. No glFinish or blocking
 * wait is performed. The EGL swap after onDrawFrame flushes the command stream.
 */
internal class GlesGpuFenceTelemetry(
    private val api: GlesFenceSyncApi = AndroidGlesFenceSyncApi,
    private val ringSize: Int = 4,
    private val windowSize: Int = 60
) {
    companion object {
        const val ALREADY_SIGNALED = 0x911A
        const val TIMEOUT_EXPIRED = 0x911B
        const val CONDITION_SATISFIED = 0x911C
        const val WAIT_FAILED = 0x911D
        private const val MIN_REPORTED_SAMPLES = 20
    }

    init {
        require(ringSize >= 2) { "ringSize must be at least 2" }
        require(windowSize >= 2) { "windowSize must be at least 2" }
    }

    private val pendingSyncs = LongArray(ringSize)
    private val pendingStartedAtNanos = LongArray(ringSize)
    private val samplesNanos = LongArray(windowSize)
    private val percentileScratch = LongArray(windowSize)
    private var pendingHead = 0
    private var pendingCount = 0
    private var nextSampleIndex = 0
    private var sampleCount = 0
    private var initialized = false
    private var supported = true
    private var waitTimeouts = 0
    private var waitFailures = 0
    private var createFailures = 0
    private var skippedFrames = 0

    /** Must be called on the GL thread after the GLES3 context is current. */
    fun initialize() {
        Arrays.fill(pendingSyncs, 0L)
        Arrays.fill(pendingStartedAtNanos, 0L)
        resetSamples()
        pendingHead = 0
        pendingCount = 0
        supported = true
        waitTimeouts = 0
        waitFailures = 0
        createFailures = 0
        skippedFrames = 0
        initialized = true
    }

    /** Poll at most the oldest fence once; timeout is always zero. */
    fun beginFrame(nowNanos: Long) {
        if (!initialized || pendingCount == 0) return
        val slot = pendingHead
        val sync = pendingSyncs[slot]
        when (api.clientWaitSync(sync)) {
            ALREADY_SIGNALED, CONDITION_SATISFIED -> {
                val started = pendingStartedAtNanos[slot]
                if (nowNanos > started) addSample(nowNanos - started)
                api.deleteSync(sync)
                pendingSyncs[slot] = 0L
                pendingStartedAtNanos[slot] = 0L
                pendingHead = (pendingHead + 1) % ringSize
                pendingCount--
            }
            TIMEOUT_EXPIRED -> waitTimeouts++
            WAIT_FAILED -> {
                waitFailures++
                api.deleteSync(sync)
                pendingSyncs[slot] = 0L
                pendingStartedAtNanos[slot] = 0L
                pendingHead = (pendingHead + 1) % ringSize
                pendingCount--
            }
            else -> {
                waitFailures++
                api.deleteSync(sync)
                pendingSyncs[slot] = 0L
                pendingStartedAtNanos[slot] = 0L
                pendingHead = (pendingHead + 1) % ringSize
                pendingCount--
            }
        }
    }

    /** Insert a fence after the frame's GL commands have been submitted. */
    fun endFrame(nowNanos: Long) {
        if (!initialized || !supported) return
        if (pendingCount >= ringSize) {
            skippedFrames++
            return
        }
        val tail = (pendingHead + pendingCount) % ringSize
        val sync = api.fenceSync()
        if (sync == 0L) {
            createFailures++
            supported = false
            return
        }
        pendingSyncs[tail] = sync
        pendingStartedAtNanos[tail] = nowNanos
        pendingCount++
    }

    fun snapshot(nowNanos: Long): GpuFenceSnapshot {
        val status = when {
            sampleCount >= MIN_REPORTED_SAMPLES -> "MEASURED"
            !supported && sampleCount == 0 -> "UNSUPPORTED"
            else -> "INSUFFICIENT"
        }
        val oldestAge = if (pendingCount > 0) {
            (nowNanos - pendingStartedAtNanos[pendingHead]).coerceAtLeast(0L) / 1_000_000f
        } else null
        return GpuFenceSnapshot(
            status = status,
            sampleCount = sampleCount,
            completionP50Ms = if (status == "MEASURED") percentileMillis(0.50) else null,
            completionP95Ms = if (status == "MEASURED") percentileMillis(0.95) else null,
            pendingCount = pendingCount,
            oldestPendingMs = oldestAge,
            waitTimeouts = waitTimeouts,
            waitFailures = waitFailures,
            createFailures = createFailures,
            skippedFrames = skippedFrames
        )
    }

    private fun addSample(elapsedNanos: Long) {
        if (elapsedNanos <= 0L) return
        samplesNanos[nextSampleIndex] = elapsedNanos
        nextSampleIndex = (nextSampleIndex + 1) % windowSize
        sampleCount = min(windowSize, sampleCount + 1)
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
