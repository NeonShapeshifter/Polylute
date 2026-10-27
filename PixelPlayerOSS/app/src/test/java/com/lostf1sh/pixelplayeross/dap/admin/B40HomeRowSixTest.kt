package com.lostf1sh.pixelplayeross.dap.admin

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/**
 * B40-F9b: Dedicated's row 6 (Polylute picked as home again after an exit) and the second MainActivity.
 *
 * On the HONOR (`device-test-2026-10-06.md`, B34-fix3 round trip): the exit left the last look at "owner, Polylute home, flag off,
 * allowlist off"; HONOR was home for a while and nobody looked; picking Polylute again produced the same facts, so the tracker said "no
 * change" and nothing put the policy back. In the run before, another instance with another last look did re-contain: it depended on
 * which instance resumed.
 */
class B40HomeRowSixTest {

    private fun state(
        owner: Boolean = true,
        home: Boolean = true,
        flag: Boolean = true,
        permitted: Boolean = true,
        exitChosen: Boolean = false,
    ) = DapDeviceOwnerState(
        isDeviceOwner = owner,
        isAdminActive = owner,
        isLockTaskPermitted = permitted,
        isPixelPlayerHome = home,
        isDedicatedModeEnabled = flag,
        lockTaskModeState = android.app.ActivityManager.LOCK_TASK_MODE_NONE,
        lockTaskFeatures = 0,
        isHomeExitChosen = exitChosen,
    )

    private val finished = state()
    private val afterExit = state(flag = false, permitted = false)

    @Test
    fun `the old rule - the same facts at two looks are no change, whatever happened between them`() {
        val tracker = DapHomeChangeTracker()
        tracker.changed(afterExit)          // the look during the exit
        // HONOR was home for a while; Polylute is picked: the facts are the ones of the last look
        assertThat(tracker.changed(afterExit)).isFalse()
    }

    @Test
    fun `row 6 - picked again after an exit, the device is reconciled although nothing moved between the two looks`() {
        val tracker = DapHomeChangeTracker()
        tracker.shouldReconcile(afterExit, holding = false)
        assertThat(tracker.shouldReconcile(afterExit, holding = false)).isTrue()
    }

    @Test
    fun `row 6 - and at every look until the policy is back, then it goes quiet`() {
        val tracker = DapHomeChangeTracker()
        tracker.shouldReconcile(afterExit, holding = false)
        assertThat(tracker.shouldReconcile(afterExit, holding = false)).isTrue()
        assertThat(tracker.shouldReconcile(afterExit, holding = false)).isTrue()
        // restored: the flag and the allowlist are back (a change, once), and then nothing to do
        assertThat(tracker.shouldReconcile(finished, holding = false)).isTrue()
        repeat(4) { assertThat(tracker.shouldReconcile(finished, holding = false)).isFalse() }
    }

    @Test
    fun `a finished device is left alone at every resume`() {
        val tracker = DapHomeChangeTracker()
        repeat(6) { assertThat(tracker.shouldReconcile(finished, holding = false)).isFalse() }
    }

    @Test
    fun `an exit that is pending is not repaired - nothing is half-provisioned while the listener is leaving`() {
        val leaving = state(flag = false, permitted = false, exitChosen = true)
        val tracker = DapHomeChangeTracker()
        tracker.shouldReconcile(leaving, holding = false)
        repeat(3) { assertThat(tracker.shouldReconcile(leaving, holding = false)).isFalse() }
    }

    @Test
    fun `another launcher as home, or no Device Owner, is a device that left or never was one - nothing is repaired`() {
        for (s in listOf(state(home = false, flag = false, permitted = false), state(owner = false, flag = false, permitted = false))) {
            val tracker = DapHomeChangeTracker()
            tracker.shouldReconcile(s, holding = false)
            repeat(3) { assertThat(tracker.shouldReconcile(s, holding = false)).isFalse() }
        }
    }

    @Test
    fun `a Maintenance session, a lifted system surface or a USB dialog flow holds the repair off, a real change still counts`() {
        val tracker = DapHomeChangeTracker()
        tracker.shouldReconcile(finished, holding = false)
        // the lift released the allowlist: that is a change the first time (as it always was), and not a state to repair while it is held
        assertThat(tracker.shouldReconcile(state(permitted = false), holding = true)).isTrue()
        repeat(3) { assertThat(tracker.shouldReconcile(state(permitted = false), holding = true)).isFalse() }
        // the hold ends and the device is still unfinished: now it is repaired
        assertThat(tracker.shouldReconcile(state(permitted = false), holding = false)).isTrue()
    }

    @Test
    fun `each missing piece makes it unfinished, each is only that when Polylute is the home of a Device Owner`() {
        assertThat(DapHomeChangeTracker.isUnfinished(state(flag = false))).isTrue()
        assertThat(DapHomeChangeTracker.isUnfinished(state(permitted = false))).isTrue()
        assertThat(DapHomeChangeTracker.isUnfinished(finished)).isFalse()
    }

    @Test
    fun `the tracker of the process is one object, so two instances of the activity look through the same last look`() {
        assertThat(DapHomeChangeTracker.process).isSameInstanceAs(DapHomeChangeTracker.process)
        DapHomeChangeTracker.process.changed(finished)
        // the second instance's first look is compared with the first instance's last one
        assertThat(DapHomeChangeTracker.process.changed(afterExit)).isTrue()
        DapHomeChangeTracker.process.changed(finished)
    }

    // -- the second MainActivity --

    private fun i(key: Int, home: Boolean, resumed: Boolean = false) = DapActivityInstancePolicy.Instance(key, home, resumed)

    @Test
    fun `another launcher is home - the icon's instance stays and the HOME one that was left behind goes`() {
        val stale = i(1, home = true)
        val icon = i(2, home = false, resumed = true)
        assertThat(DapActivityInstancePolicy.toFinish(listOf(stale, icon), polyluteIsHome = false)).containsExactly(stale)
    }

    @Test
    fun `Polylute is home - the HOME instance stays and one the icon or a notification started goes`() {
        val home = i(1, home = true)
        val icon = i(2, home = false)
        assertThat(DapActivityInstancePolicy.toFinish(listOf(home, icon), polyluteIsHome = true)).containsExactly(icon)
    }

    @Test
    fun `the instance in front of the listener is never finished, it is judged when it is left`() {
        val front = i(1, home = true, resumed = true)
        val better = i(2, home = false)
        assertThat(DapActivityInstancePolicy.toFinish(listOf(front, better), polyluteIsHome = false)).isEmpty()
        assertThat(DapActivityInstancePolicy.toFinish(listOf(front, better.copy(resumed = true)), polyluteIsHome = false)).isEmpty()
    }

    @Test
    fun `an instance of the kind the situation does not want is kept when there is none of the kind it does`() {
        // a lone HOME instance while another launcher is home: it may be what the listener is looking at; there is no second one
        assertThat(DapActivityInstancePolicy.toFinish(listOf(i(1, home = true)), polyluteIsHome = false)).isEmpty()
        assertThat(DapActivityInstancePolicy.toFinish(listOf(i(1, home = false)), polyluteIsHome = true)).isEmpty()
        assertThat(DapActivityInstancePolicy.toFinish(emptyList(), polyluteIsHome = true)).isEmpty()
    }

    @Test
    fun `two instances of the same kind are none of this fix's business`() {
        // an audio file opened from another app starts a standard instance beside the standard one in use
        val a = i(1, home = false)
        val b = i(2, home = false, resumed = true)
        assertThat(DapActivityInstancePolicy.toFinish(listOf(a, b), polyluteIsHome = false)).isEmpty()
        val h1 = i(3, home = true)
        val h2 = i(4, home = true)
        assertThat(DapActivityInstancePolicy.toFinish(listOf(h1, h2), polyluteIsHome = true)).isEmpty()
    }

    @Test
    fun `several leftovers all go`() {
        val icon = i(9, home = false)
        val s1 = i(1, home = true)
        val s2 = i(2, home = true)
        assertThat(DapActivityInstancePolicy.toFinish(listOf(s1, icon, s2), polyluteIsHome = false)).containsExactly(s1, s2)
    }

    // -- where it is wired --

    private val activity: String = listOf("src/main/java", "app/src/main/java").map(::File).first { it.isDirectory }
        .let { File(it, "com/lostf1sh/pixelplayeross/MainActivity.kt").readText() }

    @Test
    fun `MainActivity looks through the process's tracker, repairs an unfinished device, and settles its instances at the four moments`() {
        assertThat(activity).doesNotContain("DapHomeChangeTracker()")
        assertThat(activity).contains("DapHomeChangeTracker.process")
        assertThat(activity).contains("homeChangeTracker.shouldReconcile(state, holding = ownerPolicyIsHeldOff())")
        assertThat(activity).contains("DapMaintenanceSession.isActive || DapTemporarySystemFlowSession.isActive || usbDialogFlow != null || systemSurfaceOpening")
        for (moment in listOf("onCreate", "onResume", "onStop")) assertThat(activity).contains("settleInstances(\"$moment\")")
        assertThat(activity).contains("liveInstances.add(this)")
        assertThat(activity).contains("liveInstances.remove(this)")
        assertThat(activity).contains("DapActivityInstancePolicy.toFinish(")
        assertThat(activity).contains("stale.finishAndRemoveTask()")
    }
}
