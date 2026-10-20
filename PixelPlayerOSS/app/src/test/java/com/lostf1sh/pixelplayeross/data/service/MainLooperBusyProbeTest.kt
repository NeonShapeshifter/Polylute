package com.lostf1sh.pixelplayeross.data.service

import android.util.Printer
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.utils.OrderedTestTimeline
import org.junit.jupiter.api.Test

/**
 * B31 P2.3: the probe that names what the main thread was busy with while the session publication's posted message waited. The looper's
 * message log is simulated line for line (`>>>>> Dispatching to ...` / `<<<<< Finished to ...`), with a clock the test moves.
 */
class MainLooperBusyProbeTest {
    private class Rig {
        var now = 0L
        var printer: Printer? = null
        var attaches = 0
        val probe = MainLooperBusyProbe(clockMs = { now }, attach = { printer = it; if (it != null) attaches++ })

        /** One message the looper dispatches while the printer is attached. */
        fun dispatch(what: String, ms: Long) {
            printer?.println(">>>>> Dispatching to $what")
            now += ms
            printer?.println("<<<<< Finished to $what")
        }
    }

    @Test
    fun `it is attached only inside the window and gone after it`() {
        val rig = Rig()
        assertThat(rig.printer).isNull()
        rig.probe.begin()
        assertThat(rig.printer).isNotNull()
        rig.probe.end()
        assertThat(rig.printer).isNull()
        // Nothing dispatched outside the window is seen.
        rig.dispatch("Handler (X) {1} late: 0", 500)
        rig.probe.begin()
        assertThat(rig.probe.end()).isEmpty()
    }

    @Test
    fun `it returns the longest messages first, and the message that was already running counts from the window's start`() {
        val rig = Rig()
        rig.now = 1_000
        rig.probe.begin()
        rig.now = 1_006
        rig.printer!!.println("<<<<< Finished to Handler (player) {a} callback: 0") // the message the window opened in
        rig.dispatch("Handler (android.view.Choreographer\$FrameHandler) {b} FrameDisplayEventReceiver: 0", 380)
        rig.dispatch("Handler (androidx.media3.session) {c} controller task: 0", 12)
        rig.dispatch("Handler (misc) {d} lambda: 0", 3)
        rig.dispatch("Handler (misc) {e} another: 0", 5)

        val busiest = rig.probe.end()

        assertThat(busiest.map { it.ms }).containsExactly(380L, 12L, 6L).inOrder()
        assertThat(busiest[0].what).contains("Choreographer")
        assertThat(busiest[2].what).isEqualTo(MainLooperBusyProbe.RUNNING_AT_START)
    }

    @Test
    fun `two windows that overlap share one attachment and the second end closes it`() {
        val rig = Rig()
        rig.probe.begin()
        rig.probe.begin()
        assertThat(rig.attaches).isEqualTo(1)
        rig.dispatch("Handler (a) {1} x: 0", 50)
        assertThat(rig.probe.end().map { it.ms }).contains(50L)
        assertThat(rig.printer).isNotNull() // one user is left
        rig.dispatch("Handler (a) {1} y: 0", 60)
        assertThat(rig.probe.end().map { it.ms }).containsAtLeast(60L, 50L)
        assertThat(rig.printer).isNull()
    }

    @Test
    fun `a very long description is cut and an end with no begin is harmless`() {
        val rig = Rig()
        assertThat(rig.probe.end()).isEmpty()
        rig.probe.begin()
        rig.dispatch("Handler (" + "x".repeat(500) + ") {1} z: 0", 20)
        val busiest = rig.probe.end()
        assertThat(busiest.first { it.ms == 20L }.what.length).isAtMost(140)
    }

    @Test
    fun `the meter names what a slow publication waited behind, and says nothing extra for a quick one`() {
        val rig = Rig()
        val lines = ArrayList<String>()
        val posted = ArrayDeque<() -> Unit>()
        val meter = SessionPublicationMeter(
            clockMs = { rig.now },
            post = { posted.addLast(it) },
            log = { _, controllers, _, _ -> lines += "line controllers=$controllers" },
            busy = rig.probe,
            logBusy = { controllers, busiest -> lines += "behind controllers=$controllers ${busiest.first().ms}" },
        )
        val timeline = OrderedTestTimeline.of(0, 1, 2)

        // A quick one: nothing else ran in the window.
        meter.begin.onTimelineChanged(timeline, 1)
        meter.end.onTimelineChanged(timeline, 1)
        assertThat(rig.printer).isNotNull() // the window is open while the message is queued
        rig.now += 4
        posted.removeFirst()()
        assertThat(rig.printer).isNull()
        assertThat(lines).containsExactly("line controllers=4")

        // A slow one: a frame ran first.
        lines.clear()
        meter.begin.onTimelineChanged(timeline, 1)
        meter.end.onTimelineChanged(timeline, 1)
        rig.dispatch("Handler (android.view.Choreographer\$FrameHandler) {1} frame: 0", 300)
        posted.removeFirst()()
        assertThat(lines).containsExactly("line controllers=300", "behind controllers=300 300").inOrder()
    }
}
