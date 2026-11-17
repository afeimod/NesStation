package com.nesstation.app.core.engine

import java.util.concurrent.locks.LockSupport

/**
 * Precise frame pacer for the emulation threads.
 *
 * Replaces the former `Thread.sleep(remaining)` pacing block that every
 * engine duplicated. The old pattern suffered from two problems:
 *
 *  1. **Sleep overshoot**: `Thread.sleep` on Android/Linux has a scheduler
 *     granularity of roughly 1-4 ms (sometimes much worse under load). With a
 *     16.6 ms budget at 60 fps, overshooting by even 2 ms means dropping below
 *     the target rate — the visible symptom was intermittent micro-stutter,
 *     especially noticeable on NDS where each frame's CPU cost is already high.
 *  2. **No fast-forward support in some engines** (NdsEngine / PsxEngine /
 *     DosEngine): their loops hardcoded a `1_000_000_000L / 60` sleep and
 *     ignored the requested fast-forward multiplier entirely — native-side
 *     "frame skip" only skipped the render/blit step, so fast-forward appeared
 *     completely broken on those platforms.
 *
 * How it works (hybrid precision strategy):
 *  - While more than ~2 ms remain until the deadline: [Thread.sleep] — cheap,
 *    fully interruptible, frees the CPU for other cores.
 *  - In the final sub-2 ms window: [LockSupport.parkNanos] — nanosecond-
 *    resolution waiting without scheduler wake granularity; wakes early on
 *    interrupt.
 *
 * This mirrors what the official melonDS Android frontend does with its
 * FrameTimer: the emulation thread targets an exact wall-clock deadline per
 * frame instead of "sleep remaining ms", and during fast-forward it simply
 * uses a divided deadline (or none at all) so emulation runs unthrottled.
 */
object FramePacer {

    // Each emulation loop has its own clock. Starting a fresh budget at every
    // iteration accumulates every scheduler oversleep (e.g. 20 ms wakeups
    // become a permanent 50 fps cap for a 60 fps core on some TV boxes).
    private val clocks = ThreadLocal.withInitial { FrameDeadline() }

    /** Below this remainder we switch from Thread.sleep to parkNanos. */
    private const val SLEEP_THRESHOLD_NS = 2_000_000L

    // parkNanos slightly undershoots on some kernels; padding the last sleep
    // segment so we always land INSIDE the parking window keeps jitter < 0.5ms.
    private const val SLEEP_PAD_NS = 1_000_000L

    /**
     * Pace one loop iteration to [fps] frames per second relative to
     * [startNs] (the loop iteration start time).
     *
     * @return true when the deadline is reached normally, false if interrupted
     *         while sleeping — callers should treat false as "break the loop"
     *         to preserve the old InterruptedException handling semantics.
     */
    fun pace(startNs: Long, fps: Int): Boolean =
        paceTo(startNs, 1_000_000_000L / fps.coerceAtLeast(1).toLong())

    /**
     * Pace one loop iteration to exactly [targetFrameNs] nanoseconds per frame
     * relative to [startNs]. Same return contract as [pace].
     */
    fun paceTo(startNs: Long, targetFrameNs: Long): Boolean {
        val deadline = clocks.get().next(startNs, targetFrameNs)
        while (true) {
            if (Thread.currentThread().isInterrupted) return false
            val remain = deadline - System.nanoTime()
            if (remain <= 0) return true
            if (remain > SLEEP_THRESHOLD_NS) {
                val sleepNs = remain - SLEEP_PAD_NS
                try {
                    Thread.sleep(sleepNs / 1_000_000L, (sleepNs % 1_000_000L).toInt())
                } catch (_: InterruptedException) {
                    return false
                }
            } else {
                LockSupport.parkNanos(remain)
            }
        }
    }
}

/** Absolute deadlines with bounded catch-up after a late wakeup. */
internal class FrameDeadline {
    private var deadline = 0L
    private var period = 0L

    fun next(startNs: Long, targetFrameNs: Long): Long {
        val frameNs = targetFrameNs.coerceAtLeast(1L)
        // Reset after pause, loading, a speed change or a slow core. Catch up
        // small scheduling delays without a long burst of emulation frames.
        deadline = if (period != frameNs || deadline == 0L ||
            startNs < deadline - frameNs || startNs - deadline > frameNs * 3L
        ) startNs + frameNs else deadline + frameNs
        period = frameNs
        return deadline
    }
}
