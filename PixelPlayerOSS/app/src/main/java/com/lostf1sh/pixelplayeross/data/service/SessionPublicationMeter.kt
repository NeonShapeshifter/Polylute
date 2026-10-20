package com.lostf1sh.pixelplayeross.data.service

import androidx.media3.common.Player
import androidx.media3.common.Timeline

/**
 * How long the media session takes, on the main thread, to publish one timeline change (B19-S4).
 *
 * ExoPlayer tells its listeners in the order they registered. The session's own listener is the one that republishes the timeline: to
 * the platform session (a queue of every song, one `QueueItem` each, unless the queue is off for it) and, in a message posted behind
 * it, to each controller. This meter is two ends of a bracket around it: [begin] is registered on the session's player **before** the
 * session registers its listener, and [end] right after. What lies between the two calls is the session's synchronous part; a message
 * posted from [end] runs after the one the session posted for its controllers, so its clock adds the controllers' part. One log line
 * per publication:
 * `SongChange: session publication N ms (session A ms, controllers B ms) items=4870 reason=1`.
 *
 * B31 P2.3: the "controllers" part is **the wait of that posted message**, not the controllers' work (a 21-item timeline has shown 148–448
 * ms on the device, the same 4,870 items 5 ms on a quiet main thread). When it is [SLOW_MS] or more and a [busy] probe is given, a second
 * line names the longest messages the main looper dispatched in the window (see [MainLooperBusyProbe]).
 *
 * Main thread only, like every listener. [post] puts a block at the end of the main looper's queue.
 */
class SessionPublicationMeter(
    private val clockMs: () -> Long,
    private val post: (() -> Unit) -> Unit,
    private val log: (sessionMs: Long, controllersMs: Long, items: Int, reason: Int) -> Unit,
    private val busy: MainLooperBusyProbe? = null,
    private val logBusy: (controllersMs: Long, busiest: List<MainLooperBusyProbe.Message>) -> Unit = { _, _ -> },
) {
    private var beganAtMs = 0L

    /** Register this on the session's player before the session is built (or before it takes the player). */
    val begin: Player.Listener = object : Player.Listener {
        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            beganAtMs = clockMs()
        }
    }

    /** Register this on the session's player right after the session took it: it runs after the session's own listener. */
    val end: Player.Listener = object : Player.Listener {
        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            finish(timeline.windowCount, reason)
        }
    }

    private fun finish(items: Int, reason: Int) {
        val sessionDoneAtMs = clockMs()
        val began = beganAtMs
        busy?.begin()
        post {
            val controllersDoneAtMs = clockMs()
            val busiest = busy?.end().orEmpty()
            val controllersMs = controllersDoneAtMs - sessionDoneAtMs
            log(sessionDoneAtMs - began, controllersMs, items, reason)
            if (controllersMs >= SLOW_MS && busiest.isNotEmpty()) logBusy(controllersMs, busiest)
        }
    }

    companion object {
        /** From this wait on, the message that waited says what it waited behind. */
        const val SLOW_MS = 40L
    }
}
