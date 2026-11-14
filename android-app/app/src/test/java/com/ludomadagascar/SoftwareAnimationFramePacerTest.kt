package com.ludomadagascar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SoftwareAnimationFramePacerTest {
    @Test
    fun waitsUntilTheNextThirtyFpsDeadline() {
        var now = 1_000_000_000L
        val waits = mutableListOf<Long>()
        val pacer = SoftwareAnimationFramePacer(
            frameIntervalNanos = 33_333_333L,
            nanoTimeNanos = { now },
            parkNanos = { remaining ->
                waits += remaining
                now += remaining
            }
        )

        pacer.awaitNextFrame()
        assertTrue(waits.isEmpty())

        now += 5_000_000L
        pacer.awaitNextFrame()

        assertEquals(listOf(28_333_333L), waits)
        assertEquals(1_033_333_333L, now)
    }

    @Test
    fun lateFramesDoNotTriggerCatchUpBursts() {
        var now = 1_000L
        val waits = mutableListOf<Long>()
        val pacer = SoftwareAnimationFramePacer(
            frameIntervalNanos = 100L,
            nanoTimeNanos = { now },
            parkNanos = { remaining ->
                waits += remaining
                now += remaining
            }
        )

        pacer.awaitNextFrame()
        now = 1_160L
        pacer.awaitNextFrame()
        assertTrue(waits.isEmpty())

        now = 1_170L
        pacer.awaitNextFrame()

        assertEquals(listOf(90L), waits)
        assertEquals(1_260L, now)
    }

    @Test
    fun resetStartsANewPacingWindow() {
        var now = 2_000L
        val waits = mutableListOf<Long>()
        val pacer = SoftwareAnimationFramePacer(
            frameIntervalNanos = 100L,
            nanoTimeNanos = { now },
            parkNanos = { remaining ->
                waits += remaining
                now += remaining
            }
        )

        pacer.awaitNextFrame()
        now = 2_010L
        pacer.reset()
        pacer.awaitNextFrame()
        now = 2_020L
        pacer.awaitNextFrame()

        assertEquals(listOf(90L), waits)
        assertEquals(2_110L, now)
    }
}
