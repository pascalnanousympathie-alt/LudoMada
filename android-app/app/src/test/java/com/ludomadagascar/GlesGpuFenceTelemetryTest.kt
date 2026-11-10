package com.ludomadagascar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlesGpuFenceTelemetryTest {
    private class FakeFenceApi : GlesFenceSyncApi {
        private var nextId = 1L
        val signaled = mutableSetOf<Long>()
        val statuses = mutableMapOf<Long, Int>()
        val deleted = mutableListOf<Long>()
        var failCreation = false

        override fun fenceSync(): Long {
            if (failCreation) return 0L
            return nextId++
        }

        override fun clientWaitSync(sync: Long): Int =
            statuses.remove(sync) ?: if (sync in signaled) {
                GlesGpuFenceTelemetry.CONDITION_SATISFIED
            } else {
                GlesGpuFenceTelemetry.TIMEOUT_EXPIRED
            }

        override fun deleteSync(sync: Long) {
            deleted += sync
            signaled.remove(sync)
        }
    }

    @Test
    fun recordsFenceCompletionLatencyOnlyAfterNonBlockingSignal() {
        val api = FakeFenceApi()
        val telemetry = GlesGpuFenceTelemetry(api, ringSize = 3, windowSize = 30)
        telemetry.initialize()

        var now = 1_000_000_000L
        repeat(24) {
            telemetry.endFrame(now)
            val id = (it + 1).toLong()
            api.signaled += id
            now += 16_000_000L
            telemetry.beginFrame(now)
        }

        val snapshot = telemetry.snapshot(now)
        assertEquals("MEASURED", snapshot.status)
        assertEquals(24, snapshot.sampleCount)
        assertEquals(16f, snapshot.completionP50Ms!!, 0.01f)
        assertEquals(16f, snapshot.completionP95Ms!!, 0.01f)
        assertEquals(0, snapshot.pendingCount)
        assertEquals(24, api.deleted.size)
    }

    @Test
    fun timeoutDoesNotBlockAndRemainsPendingUntilSignaled() {
        val api = FakeFenceApi()
        val telemetry = GlesGpuFenceTelemetry(api, ringSize = 2, windowSize = 24)
        telemetry.initialize()
        telemetry.endFrame(1_000_000_000L)

        telemetry.beginFrame(1_050_000_000L)
        val waiting = telemetry.snapshot(1_050_000_000L)
        assertEquals("INSUFFICIENT", waiting.status)
        assertEquals(1, waiting.pendingCount)
        assertEquals(50f, waiting.oldestPendingMs!!, 0.01f)
        assertEquals(1, waiting.waitTimeouts)
        assertEquals(0, waiting.waitFailures)
        assertTrue(api.deleted.isEmpty())

        api.signaled += 1L
        telemetry.beginFrame(1_060_000_000L)
        val completed = telemetry.snapshot(1_060_000_000L)
        assertEquals("INSUFFICIENT", completed.status)
        assertEquals(0, completed.pendingCount)
        assertEquals(1, completed.sampleCount)
        assertNull(completed.completionP50Ms)
        assertNull(completed.completionP95Ms)
    }

    @Test
    fun fullRingSkipsNewFencesWithoutOverwritingPendingSyncs() {
        val api = FakeFenceApi()
        val telemetry = GlesGpuFenceTelemetry(api, ringSize = 2, windowSize = 24)
        telemetry.initialize()
        telemetry.endFrame(1_000_000_000L)
        telemetry.endFrame(1_010_000_000L)
        telemetry.endFrame(1_020_000_000L)

        val snapshot = telemetry.snapshot(1_020_000_000L)
        assertEquals(2, snapshot.pendingCount)
        assertEquals(1, snapshot.skippedFrames)
        assertEquals(0, snapshot.waitTimeouts)
    }

    @Test
    fun reportsUnsupportedWhenDriverCannotCreateFence() {
        val api = FakeFenceApi().apply { failCreation = true }
        val telemetry = GlesGpuFenceTelemetry(api)
        telemetry.initialize()
        telemetry.endFrame(1_000_000_000L)

        val snapshot = telemetry.snapshot(1_000_000_000L)
        assertEquals("UNSUPPORTED", snapshot.status)
        assertEquals(1, snapshot.createFailures)
        assertEquals(0, snapshot.pendingCount)
        assertNull(snapshot.completionP50Ms)
        assertNull(snapshot.completionP95Ms)
    }

    @Test
    fun dropsFailedWaitAndDeletesTheInvalidSync() {
        val api = FakeFenceApi()
        val telemetry = GlesGpuFenceTelemetry(api, ringSize = 2, windowSize = 24)
        telemetry.initialize()
        telemetry.endFrame(1_000_000_000L)
        api.statuses[1L] = GlesGpuFenceTelemetry.WAIT_FAILED

        telemetry.beginFrame(1_010_000_000L)
        val snapshot = telemetry.snapshot(1_010_000_000L)
        assertEquals(0, snapshot.pendingCount)
        assertEquals(1, snapshot.waitFailures)
        assertEquals(listOf(1L), api.deleted)
    }

    @Test
    fun reportsInsufficientUntilTwentyValidCompletionSamplesExist() {
        val api = FakeFenceApi()
        val telemetry = GlesGpuFenceTelemetry(api, ringSize = 2, windowSize = 24)
        telemetry.initialize()
        repeat(19) { index ->
            val id = (index + 1).toLong()
            val at = 1_000_000_000L + index * 16_000_000L
            telemetry.endFrame(at)
            api.signaled += id
            telemetry.beginFrame(at + 8_000_000L)
        }
        val snapshot = telemetry.snapshot(2_000_000_000L)
        assertEquals("INSUFFICIENT", snapshot.status)
        assertEquals(19, snapshot.sampleCount)
        assertNull(snapshot.completionP50Ms)
    }
}
