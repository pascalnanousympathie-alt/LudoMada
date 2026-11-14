package com.ludomadagascar

import java.util.concurrent.locks.LockSupport

/**
 * Caps software-rendered gameplay animation callbacks without involving the UI thread.
 *
 * A software rasterizer can miss Android's 60 Hz presentation deadlines. Keeping
 * the GL loop continuous and sleeping between callbacks avoids requestRender()
 * coalescing on the main thread while preventing an uncapped busy render loop.
 */
internal class SoftwareAnimationFramePacer(
    private val frameIntervalNanos: Long = 33_333_333L,
    private val nanoTimeNanos: () -> Long = System::nanoTime,
    private val parkNanos: (Long) -> Unit = { remaining -> LockSupport.parkNanos(remaining) }
) {
    init {
        require(frameIntervalNanos > 0L) { "Frame interval must be positive" }
    }

    private var nextFrameDeadlineNanos = 0L

    fun awaitNextFrame() {
        val now = nanoTimeNanos()
        if (nextFrameDeadlineNanos == 0L) {
            nextFrameDeadlineNanos = now + frameIntervalNanos
            return
        }

        val deadline = nextFrameDeadlineNanos
        var remaining = deadline - nanoTimeNanos()
        while (remaining > 0L && !Thread.currentThread().isInterrupted) {
            parkNanos(remaining)
            remaining = deadline - nanoTimeNanos()
        }

        val resumedAt = nanoTimeNanos()
        // Do not issue catch-up frames after a late callback; restart one interval
        // from the actual resume point instead of trying to catch a missed deadline.
        nextFrameDeadlineNanos = if (resumedAt >= deadline) {
            resumedAt + frameIntervalNanos
        } else {
            deadline + frameIntervalNanos
        }
    }

    fun reset() {
        nextFrameDeadlineNanos = 0L
    }
}
