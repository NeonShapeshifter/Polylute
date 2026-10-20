package com.lostf1sh.pixelplayeross.data.service

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.utils.OrderedTestTimeline
import org.junit.jupiter.api.Test

/**
 * B19-S4: the line the reviewer reads on the device, `SongChange: session publication N ms (session A ms, controllers B ms) items=K`.
 * The bracket is two listeners around the session's own, and a message posted behind the one the session posted for its controllers.
 */
class SessionPublicationMeterTest {

    private class Rig {
        var now = 0L
        val posted = ArrayDeque<() -> Unit>()
        val lines = ArrayList<List<Number>>()
        val meter = SessionPublicationMeter(
            clockMs = { now },
            post = { posted.addLast(it) },
            log = { session, controllers, items, reason -> lines += listOf(session, controllers, items, reason) },
        )

        fun mainLooperRuns() {
            while (posted.isNotEmpty()) posted.removeFirst()()
        }
    }

    @Test
    fun `the session's part is what lies between the two ends, and the controllers' part is what the posted message waited for`() {
        val rig = Rig()
        val timeline = OrderedTestTimeline.of(0, 1, 2, 3)

        rig.now = 1_000
        rig.meter.begin.onTimelineChanged(timeline, 1)
        rig.now = 1_004 // the session republished the timeline to the platform session
        rig.meter.end.onTimelineChanged(timeline, 1)
        assertThat(rig.lines).isEmpty() // nothing is said until the controllers have been served
        rig.now = 1_010 // the session's own message, to the controllers, ran before ours
        rig.mainLooperRuns()

        assertThat(rig.lines).containsExactly(listOf<Number>(4L, 6L, 4, 1))
    }

    @Test
    fun `every publication is its own line, with the size and the reason it had`() {
        val rig = Rig()
        val small = OrderedTestTimeline.of(0, 1)
        val big = OrderedTestTimeline.of(0, 1, 2, 3, 4)

        rig.now = 10
        rig.meter.begin.onTimelineChanged(small, 0)
        rig.now = 11
        rig.meter.end.onTimelineChanged(small, 0)
        rig.mainLooperRuns()
        rig.now = 50
        rig.meter.begin.onTimelineChanged(big, 1)
        rig.now = 52
        rig.meter.end.onTimelineChanged(big, 1)
        rig.now = 53
        rig.mainLooperRuns()

        assertThat(rig.lines).containsExactly(listOf<Number>(1L, 0L, 2, 0), listOf<Number>(2L, 1L, 5, 1)).inOrder()
    }
}
