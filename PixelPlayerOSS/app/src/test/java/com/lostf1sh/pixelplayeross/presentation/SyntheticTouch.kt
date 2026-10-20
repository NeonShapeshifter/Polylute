package com.lostf1sh.pixelplayeross.presentation

import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot

/**
 * B29: touch input as the platform delivers it, for the gesture tests (the lock surface's flick, the player's queue swipe).
 *
 * A finger is a DOWN, a run of MOVEs and an UP. The platform hands the MOVEs of one frame over as one event (the earlier samples
 * ride along as history) and the UP right behind the last batch, **in the same message**. [Delivery.FRAMED] does that, with the
 * scheduler running between frames; [Delivery.STALLED] is the main thread busy from the touch-down to the release, the whole
 * gesture in one message. Use it with the `v2` compose rule (`androidx.compose.ui.test.junit4.v2.createComposeRule`): its standard
 * dispatcher runs a coroutine launched from a pointer callback only when the test lets the scheduler run, as a phone does after
 * the message. The `v1` rule resumes it on the spot, so the lag between a callback and what it launched cannot be seen there.
 */
internal class SyntheticTouch(private val compose: ComposeContentTestRule) {

    enum class Delivery { FRAMED, STALLED }

    class Sample(val t: Long, val x: Float, val y: Float)

    private val frameMs = 16L
    private val moveEveryMs = 4L

    private fun view() = (compose.onRoot().fetchSemanticsNode().root as ViewRootForTest).view

    /** A finger that goes from [from] by ([dxPx], [dyPx]) in [millis] ms, then lifts. */
    fun swipe(from: Offset, dyPx: Float, millis: Long, delivery: Delivery, dxPx: Float = 0f, settleMs: Long = 2_000) =
        deliver(path(from, listOf(Offset(from.x + dxPx, from.y + dyPx)), millis), delivery, settleMs)

    /** A path through [via] (the last one is where the finger lifts), [millis] in all, split evenly between the legs. */
    fun path(from: Offset, via: List<Offset>, millis: Long): List<Sample> {
        val out = mutableListOf(Sample(0, from.x, from.y))
        val legMs = millis / via.size
        var last = from
        var t = 0L
        for (to in via) {
            val steps = (legMs / moveEveryMs).toInt().coerceAtLeast(1)
            for (i in 1..steps) {
                t += legMs / steps
                out += Sample(t, last.x + (to.x - last.x) * i / steps, last.y + (to.y - last.y) * i / steps)
            }
            last = to
        }
        return out
    }

    private fun motion(action: Int, downTime: Long, s: Sample, batch: List<Sample> = emptyList()): MotionEvent {
        val e = MotionEvent.obtain(downTime, downTime + s.t, action, s.x, s.y, 0)
        batch.forEach { e.addBatch(downTime + it.t, it.x, it.y, 1f, 1f, 0) }
        return e
    }

    /** [samples]: the first is the touch-down, the last is the lift (the UP takes the last sample's place). */
    fun deliver(samples: List<Sample>, delivery: Delivery, settleMs: Long = 2_000) {
        val downTime = 10_000L + SystemClock.uptimeMillis()
        val view = view()
        val down = samples.first()
        val up = samples.last()
        val moves = samples.subList(1, samples.size - 1)
        fun send(e: MotionEvent) { view.dispatchTouchEvent(e); e.recycle() }
        when (delivery) {
            Delivery.STALLED -> compose.runOnUiThread {
                send(motion(MotionEvent.ACTION_DOWN, downTime, down))
                if (moves.isNotEmpty()) send(motion(MotionEvent.ACTION_MOVE, downTime, moves.last(), moves.dropLast(1)))
                send(motion(MotionEvent.ACTION_UP, downTime, up))
            }
            Delivery.FRAMED -> {
                compose.runOnUiThread { send(motion(MotionEvent.ACTION_DOWN, downTime, down)) }
                compose.waitForIdle()
                val frames = moves.groupBy { it.t / frameMs }.toSortedMap().values.toList()
                frames.forEachIndexed { index, batch ->
                    val lastFrame = index == frames.lastIndex
                    compose.runOnUiThread {
                        send(motion(MotionEvent.ACTION_MOVE, downTime, batch.last(), batch.dropLast(1)))
                        // the UP is handed over right behind the last batch, in the same message
                        if (lastFrame) send(motion(MotionEvent.ACTION_UP, downTime, up))
                    }
                    if (!lastFrame) compose.waitForIdle()
                }
                if (frames.isEmpty()) compose.runOnUiThread { send(motion(MotionEvent.ACTION_UP, downTime, up)) }
            }
        }
        compose.waitForIdle()
        if (settleMs > 0) {
            compose.mainClock.advanceTimeBy(settleMs)
            compose.waitForIdle()
        }
    }

    /**
     * B29-fix1: the gesture as the HONOR delivered it with something heavy happening at the first frame. The frames of the gesture are
     * cut at [cutsMs] (each cut ends a frame's batch); the scheduler runs after every batch but the last (that is the frame in which
     * the surface was revealed and the covered app was measured), and the last batch is handed over together with the UP.
     */
    fun deliverInBatches(samples: List<Sample>, cutsMs: List<Long>, settleMs: Long = 2_000, lift: Boolean = true) {
        val downTime = 10_000L + SystemClock.uptimeMillis()
        val view = view()
        val down = samples.first()
        val up = samples.last()
        val moves = samples.subList(1, samples.size - 1)
        fun send(e: MotionEvent) { view.dispatchTouchEvent(e); e.recycle() }
        val batches = moves.groupBy { m -> cutsMs.count { it < m.t } }.toSortedMap().values.toList()
        compose.runOnUiThread { send(motion(MotionEvent.ACTION_DOWN, downTime, down)) }
        batches.forEachIndexed { index, batch ->
            val last = index == batches.lastIndex
            compose.runOnUiThread {
                send(motion(MotionEvent.ACTION_MOVE, downTime, batch.last(), batch.dropLast(1)))
                if (last && lift) send(motion(MotionEvent.ACTION_UP, downTime, up))
            }
            if (!last) compose.waitForIdle()
        }
        if (batches.isEmpty() && lift) compose.runOnUiThread { send(motion(MotionEvent.ACTION_UP, downTime, up)) }
        compose.waitForIdle()
        if (settleMs > 0) {
            compose.mainClock.advanceTimeBy(settleMs)
            compose.waitForIdle()
        }
    }

    /**
     * B29-fix1: every sample is its own frame, the scheduler running after each (composition, the reveal, the covered app's layout), and
     * the lift last: `adb shell input swipe` while the first frame is long. The tool injects each event and waits for the app to finish
     * it, so it cannot get ahead of a stalled app; when it wakes its clock is past the end of the swipe, so the next thing it sends is the
     * lift, at the swipe's end point. The lift is the first sample after the stall that is not near the ones before it.
     */
    fun deliverOnePerFrame(samples: List<Sample>, settleMs: Long = 2_000) {
        val downTime = 10_000L + SystemClock.uptimeMillis()
        val view = view()
        fun send(e: MotionEvent) { view.dispatchTouchEvent(e); e.recycle() }
        compose.runOnUiThread { send(motion(MotionEvent.ACTION_DOWN, downTime, samples.first())) }
        compose.waitForIdle()
        samples.drop(1).dropLast(1).forEach { m ->
            compose.runOnUiThread { send(motion(MotionEvent.ACTION_MOVE, downTime, m)) }
            compose.waitForIdle()
        }
        compose.runOnUiThread { send(motion(MotionEvent.ACTION_UP, downTime, samples.last())) }
        compose.waitForIdle()
        if (settleMs > 0) {
            compose.mainClock.advanceTimeBy(settleMs)
            compose.waitForIdle()
        }
    }

    /**
     * A finger that goes down at [from], moves by ([dxPx], [dyPx]) over [millis] ms frame by frame, **stays down** while [whileHeld] looks at
     * the screen (B33: a drag in progress), and then lifts where it was.
     */
    fun swipeAndHold(from: Offset, dyPx: Float, millis: Long, dxPx: Float = 0f, settleMs: Long = 2_000, whileHeld: () -> Unit) {
        val samples = path(from, listOf(Offset(from.x + dxPx, from.y + dyPx)), millis)
        val downTime = 10_000L + SystemClock.uptimeMillis()
        val view = view()
        fun send(e: MotionEvent) { view.dispatchTouchEvent(e); e.recycle() }
        compose.runOnUiThread { send(motion(MotionEvent.ACTION_DOWN, downTime, samples.first())) }
        compose.waitForIdle()
        samples.drop(1).groupBy { it.t / frameMs }.toSortedMap().values.forEach { batch ->
            compose.runOnUiThread { send(motion(MotionEvent.ACTION_MOVE, downTime, batch.last(), batch.dropLast(1))) }
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(frameMs)
        }
        compose.waitForIdle()
        whileHeld()
        compose.runOnUiThread { send(motion(MotionEvent.ACTION_UP, downTime, samples.last())) }
        compose.waitForIdle()
        if (settleMs > 0) {
            compose.mainClock.advanceTimeBy(settleMs)
            compose.waitForIdle()
        }
    }

    fun tap(at: Offset, settleMs: Long = 2_000) =
        deliver(listOf(Sample(0, at.x, at.y), Sample(60, at.x, at.y)), Delivery.FRAMED, settleMs)
}
