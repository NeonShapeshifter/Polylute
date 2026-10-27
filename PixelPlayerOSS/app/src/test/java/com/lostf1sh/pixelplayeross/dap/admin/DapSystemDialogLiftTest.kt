package com.lostf1sh.pixelplayeross.dap.admin

import android.app.ActivityManager
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * B40-P2: the USB permission dialog on a Dedicated device. The HONOR's SystemUI cannot show `UsbPermissionActivity` while Lock Task holds
 * the task list, so a DAC that was never allowed got no dialog and no Purity. The device lifts Lock Task the way the System page does, asks
 * for the dialog, and puts the containment back whatever the dialog does, within a bound.
 *
 * [FakeDevice] stands in for the platform with the one fact that matters: a Lock Task state that the lift and the relock change, so
 * "never left unlocked" is read off the device and not off the call order.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DapSystemDialogLiftTest {
    private val locked = ActivityManager.LOCK_TASK_MODE_LOCKED
    private val none = ActivityManager.LOCK_TASK_MODE_NONE

    private class FakeDevice(var lockState: Int, val liftWorks: Boolean = true) {
        val events = mutableListOf<String>()

        suspend fun lift(): Boolean {
            events += "lift"
            if (liftWorks) lockState = ActivityManager.LOCK_TASK_MODE_NONE
            return liftWorks
        }

        suspend fun relock() {
            events += "relock"
            lockState = ActivityManager.LOCK_TASK_MODE_LOCKED
        }

        fun show() { events += "show" }
    }

    private fun flow(device: FakeDevice, timeoutMs: Long = 30_000L) =
        DapSystemDialogLift(lift = device::lift, relock = device::relock, timeoutMs = timeoutMs)

    @Test
    fun `a new DAC on a Dedicated device - lift, request, answered, relocked`() = runTest {
        val device = FakeDevice(locked)
        val ended = CompletableDeferred<DapSystemDialogLift.Outcome>()
        val outcome = async { flow(device).run(needsLift = true, show = device::show, ended = ended) }
        runCurrent()
        // the dialog is out and Lock Task is lifted, nothing is relocked yet
        assertThat(device.events).containsExactly("lift", "show").inOrder()
        assertThat(device.lockState).isEqualTo(none)
        ended.complete(DapSystemDialogLift.Outcome.ANSWERED)
        assertThat(outcome.await()).isEqualTo(DapSystemDialogLift.Outcome.ANSWERED)
        assertThat(device.events).containsExactly("lift", "show", "relock").inOrder()
        assertThat(device.lockState).isEqualTo(locked)
    }

    @Test
    fun `a dismissed dialog relocks`() = runTest {
        val device = FakeDevice(locked)
        val ended = CompletableDeferred<DapSystemDialogLift.Outcome>()
        val outcome = async { flow(device).run(needsLift = true, show = device::show, ended = ended) }
        runCurrent()
        ended.complete(DapSystemDialogLift.Outcome.DISMISSED)
        assertThat(outcome.await()).isEqualTo(DapSystemDialogLift.Outcome.DISMISSED)
        assertThat(device.lockState).isEqualTo(locked)
        assertThat(device.events.last()).isEqualTo("relock")
    }

    @Test
    fun `no answer relocks anyway when the bound passes, and not before`() = runTest {
        val device = FakeDevice(locked)
        val ended = CompletableDeferred<DapSystemDialogLift.Outcome>()
        val outcome = async { flow(device, timeoutMs = 30_000L).run(needsLift = true, show = device::show, ended = ended) }
        runCurrent()
        advanceTimeBy(29_999L)
        runCurrent()
        assertThat(device.lockState).isEqualTo(none)   // still within the bound: the listener may be reading
        advanceTimeBy(2L)
        assertThat(outcome.await()).isEqualTo(DapSystemDialogLift.Outcome.TIMED_OUT)
        assertThat(device.lockState).isEqualTo(locked)
        assertThat(currentTime).isAtMost(30_001L)
    }

    @Test
    fun `a lift that fails never asks for the dialog and puts the containment back`() = runTest {
        val device = FakeDevice(locked, liftWorks = false)
        val ended = CompletableDeferred<DapSystemDialogLift.Outcome>()
        val outcome = flow(device).run(needsLift = true, show = device::show, ended = ended)
        assertThat(outcome).isEqualTo(DapSystemDialogLift.Outcome.NOT_LIFTED)
        assertThat(device.events).containsExactly("lift", "relock").inOrder()
        assertThat(device.lockState).isEqualTo(locked)
    }

    @Test
    fun `an activity that is cancelled in the middle of the dialog still puts the containment back`() = runTest {
        val device = FakeDevice(locked)
        val ended = CompletableDeferred<DapSystemDialogLift.Outcome>()
        val job = launch { flow(device).run(needsLift = true, show = device::show, ended = ended) }
        runCurrent()
        assertThat(device.lockState).isEqualTo(none)
        job.cancelAndJoin()
        assertThat(device.lockState).isEqualTo(locked)
        assertThat(device.events.last()).isEqualTo("relock")
    }

    @Test
    fun `an activity cancelled in the middle of the lift puts the containment back too`() = runTest {
        val device = FakeDevice(locked)
        val lift = DapSystemDialogLift(
            lift = { device.events += "lift"; awaitCancellation() },
            relock = device::relock,
            timeoutMs = 30_000L,
        )
        val job = launch { lift.run(needsLift = true, show = device::show, ended = CompletableDeferred()) }
        runCurrent()
        job.cancelAndJoin()
        assertThat(device.events).containsExactly("lift", "relock").inOrder()
        assertThat(device.lockState).isEqualTo(locked)
    }

    @Test
    fun `a Lock Task that holds nothing is not lifted and not relocked - the dialog is asked for directly`() = runTest {
        val device = FakeDevice(none)
        val outcome = flow(device).run(needsLift = false, show = device::show, ended = CompletableDeferred())
        assertThat(outcome).isEqualTo(DapSystemDialogLift.Outcome.SHOWN_WITHOUT_LIFT)
        assertThat(device.events).containsExactly("show")
    }
}
