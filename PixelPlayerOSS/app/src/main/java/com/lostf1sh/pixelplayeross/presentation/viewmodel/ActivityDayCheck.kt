package com.lostf1sh.pixelplayeross.presentation.viewmodel

import com.lostf1sh.pixelplayeross.data.worker.FOREGROUND_SETTLE_MS
import com.lostf1sh.pixelplayeross.data.worker.ForegroundSettleGate
import kotlinx.coroutines.CoroutineScope

/**
 * When the activity asks "is it a new day, do the mixes need renewing?" (AUD-132, B23-P1).
 *
 * The question is asked from `onStart` and from `onResume`, and on the DAP both fire on every wake. Asking costs a
 * preference read; *answering yes* regenerates the mixes (a pass over the library on IO), and that is the part that
 * must not land in the burst of a wake, when the USB writer and the decoder need the CPU.
 *
 * - **The first start of the process is asked at once**, as before: the Home surface is waiting for the mixes and
 *   there is no playback to protect yet.
 * - **Every later start and resume waits [settleMs] of quiet** and then asks once, however many of them came in the meantime
 *   ([ForegroundSettleGate] folds a burst into one wait that each new event restarts, up to a cap). A new day's mixes are therefore a few seconds late on the
 *   first wake of the day, and nothing else about them changes.
 *
 * The midnight timer and the date/time broadcasts of `DailyMixStateHolder.startDayBoundaryWatch` are untouched: they
 * are not wakes.
 */
class ActivityDayCheck(
    scope: CoroutineScope,
    settleMs: Long = FOREGROUND_SETTLE_MS,
    now: () -> Long = { System.nanoTime() / 1_000_000L },
    private val check: () -> Unit,
) {
    private val gate = ForegroundSettleGate(scope, settleMs, now = now)
    private var firstStart = true

    fun onActivityStart() {
        if (firstStart) {
            check()
        } else {
            gate.request { check() }
        }
    }

    fun onActivityResume() {
        if (firstStart) {
            // The resume that follows the process's first start: that start already asked.
            firstStart = false
            return
        }
        gate.request { check() }
    }
}
