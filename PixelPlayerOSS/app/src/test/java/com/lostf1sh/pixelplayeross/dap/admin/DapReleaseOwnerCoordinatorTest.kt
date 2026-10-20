package com.lostf1sh.pixelplayeross.dap.admin

import android.app.ActivityManager
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.dap.mode.DedicatedProvisioningEvidence
import com.lostf1sh.pixelplayeross.dap.mode.EffectiveProductMode
import com.lostf1sh.pixelplayeross.dap.mode.ProductModeResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * B34 and B34-fix1: "Dejar de ser propietario del dispositivo" is one ordered transaction, and the result says what is true.
 *
 * [Phone] is a stateful Device Owner phone: the signals the product mode is inferred from (the owner, HOME, the dedicated flag, the Lock
 * Task allowlist, the Lock Task state) are fields that the operation's calls change, as Android's do. That is what the auditor's R34-01
 * needed: a fake that only records calls cannot show that the evidence a rollback consults has been erased by the operation itself.
 * [Phone.mode] derives the mode with the real [ProductModeResolver], exactly as `MainActivity` does. Every call is recorded in
 * [Phone.events]. Nothing here touches a device: the real calls are the controller's, and the batch never runs `dpm` or
 * `clearDeviceOwnerApp` against one.
 *
 * R1 (a failure restores what was removed), R2 (never "released" without proof) and R3 (a cancel leaves no orphan window) are the three
 * groups below.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DapReleaseOwnerCoordinatorTest {

    private val locked = ActivityManager.LOCK_TASK_MODE_LOCKED
    private val none = ActivityManager.LOCK_TASK_MODE_NONE

    private class Phone(
        var owner: Boolean = true,
        /** Polylute is the HOME Android resolves (the persistent preferred activity). */
        var home: Boolean = true,
        var flag: Boolean = true,
        var permitted: Boolean = true,
        var lock: Int = ActivityManager.LOCK_TASK_MODE_LOCKED,
        var persistentHomePreference: Boolean = true,
        // what the platform does
        val lockStops: Boolean = true,
        /** `clearAllOwnerPolicies` clears the flag, then fails before the allowlist (a partial removal). */
        val policiesFailMidway: Boolean = false,
        /** `clearAllOwnerPolicies` removes everything and then reports false (a read-back or the keyguard release disagreed). */
        val policiesFailAfterRemoval: Boolean = false,
        /** Polylute is still the HOME Android resolves after the persistent policy is cleared (it is the listener's own default). */
        val homeSticky: Boolean = false,
        val homeClearThrows: Boolean = false,
        /** `clearDeviceOwnerApp` is refused: it throws, or it returns and changes nothing. */
        val ownerClearThrows: Boolean = false,
        val ownerClearIgnored: Boolean = false,
        val adminRemoves: Boolean = true,
        val resetRequestThrows: Boolean = false,
        val homeStillPolylute: Boolean = false,
        // what the reads and the rollback do
        /** The first N answers of the ownership query throw (a failed binder read); [Int.MAX_VALUE] is "always". */
        var ownerReadFailures: Int = 0,
        /** The ownership query only starts failing once the release has asked `clearDeviceOwnerApp`. */
        val ownerReadFailsAfterClear: Boolean = false,
        val startUnreadable: Boolean = false,
        val restoreWorks: Boolean = true,
        val relockWorks: Boolean = true,
    ) {
        val events = mutableListOf<String>()
        val window = MaintenanceSessionController()
        var ownerReads = 0
        private var clearAsked = false

        /** The mode the activity infers, with the real resolver: this is what the old rollback asked, after the operation erased it. */
        fun mode(): EffectiveProductMode = ProductModeResolver.resolve(
            requestedMode = null,
            evidence = DedicatedProvisioningEvidence(
                isDeviceOwner = owner,
                isPixelPlayerHome = home,
                isDedicatedModeEnabled = flag,
                isLockTaskPermitted = permitted,
                lockTaskModeState = lock,
            ),
        ).effectiveMode

        val windowOpen: Boolean get() = window.snapshot.value.isAuthenticated

        /** The hook the cancel tests use to hold the Lock Task lift somewhere in the middle. */
        var stopHook: (suspend (Phone) -> Boolean)? = null

        fun coordinator(retryDelayMs: Long = 200L) = DapReleaseOwnerCoordinator(
            ownerPresence = {
                events += "readOwner"
                ownerReads++
                if (ownerReadFailsAfterClear && clearAsked) throw IllegalStateException("binder died")
                if (ownerReadFailures > 0) {
                    ownerReadFailures--
                    throw IllegalStateException("binder died")
                }
                if (owner) OwnerPresence.PRESENT else OwnerPresence.ABSENT
            },
            readStart = {
                events += "readStart"
                if (startUnreadable) throw IllegalStateException("cannot read")
                DapOwnerStart(
                    dedicatedFlag = flag,
                    persistentHomePreference = persistentHomePreference,
                    homeWasPolylute = home,
                    lockTaskPermitted = permitted,
                    lockHeld = lock == ActivityManager.LOCK_TASK_MODE_LOCKED,
                )
            },
            beginWindow = { events += "beginWindow"; window.begin(0L) },
            stopLockTask = {
                events += "stopLockTask"
                val hook = stopHook
                if (hook != null) hook(this) else {
                    if (lockStops) lock = ActivityManager.LOCK_TASK_MODE_NONE
                    lockStops
                }
            },
            clearPolicies = {
                events += "clearPolicies"
                flag = false
                if (policiesFailMidway) false else {
                    permitted = false
                    !policiesFailAfterRemoval
                }
            },
            clearPersistentHome = {
                events += "clearPersistentHome"
                // Android's HOME picker is in charge again: Polylute no longer resolves as HOME
                if (!homeSticky) home = false
                persistentHomePreference = false
                if (homeClearThrows) throw SecurityException("refused")
                true
            },
            clearDeviceOwner = {
                events += "clearDeviceOwner"
                clearAsked = true
                if (ownerClearThrows) throw SecurityException("refused")
                if (!ownerClearIgnored) owner = false
            },
            removeActiveAdmin = { events += "removeActiveAdmin"; adminRemoves },
            resetRequestToLauncher = { events += "resetRequest"; if (resetRequestThrows) throw IllegalStateException("cannot read") },
            homeIsPolylute = { homeStillPolylute },
            endWindow = { events += "endWindow"; window.invalidate() },
            restorePolicies = { start ->
                events += "restorePolicies"
                if (restoreWorks && owner) {
                    if (start.dedicatedFlag) flag = true
                    if (start.homeWasPolylute) home = true
                    if (start.lockTaskPermitted) permitted = true
                    persistentHomePreference = start.persistentHomePreference
                    true
                } else {
                    false
                }
            },
            relock = {
                events += "relock"
                if (relockWorks && permitted && flag) lock = ActivityManager.LOCK_TASK_MODE_LOCKED
                lock == ActivityManager.LOCK_TASK_MODE_LOCKED
            },
            readRetryDelayMs = retryDelayMs,
        )
    }

    // ── the order, on a release that works (control) ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `the phone is cleaned in order, the policies before the owner, and the result is verified by a query`() = runTest {
        val phone = Phone()
        val result = phone.coordinator().release()

        assertThat(result.outcome).isEqualTo(DapReleaseOutcome.RELEASED)
        assertThat(result.rollback).isEqualTo(DapReleaseRollback.NONE)
        assertThat(phone.events).containsExactly(
            "readOwner", "readStart", "beginWindow", "stopLockTask", "clearPolicies", "clearPersistentHome", "clearDeviceOwner",
            "readOwner", "removeActiveAdmin", "resetRequest", "endWindow",
        ).inOrder()
        assertThat(phone.events.indexOf("clearPolicies")).isLessThan(phone.events.indexOf("clearDeviceOwner"))
        assertThat(phone.events.indexOf("clearPersistentHome")).isLessThan(phone.events.indexOf("clearDeviceOwner"))
        assertThat(phone.events.indexOf("stopLockTask")).isLessThan(phone.events.indexOf("clearPolicies"))
        // the ownership read that decides "released" comes after the clear attempt, not before it
        assertThat(phone.events.lastIndexOf("readOwner")).isGreaterThan(phone.events.indexOf("clearDeviceOwner"))
        assertThat(phone.owner).isFalse()
        assertThat(phone.events).containsNoneOf("restorePolicies", "relock")
        assertThat(phone.windowOpen).isFalse()
    }

    @Test
    fun `the request goes back to Launcher only after the owner is confirmed gone`() = runTest {
        val phone = Phone()
        phone.coordinator().release()
        assertThat(phone.events.indexOf("resetRequest")).isGreaterThan(phone.events.lastIndexOf("readOwner"))
    }

    @Test
    fun `a phone that is not the owner is left alone`() = runTest {
        val phone = Phone(owner = false)
        val result = phone.coordinator().release()

        assertThat(result.outcome).isEqualTo(DapReleaseOutcome.NOT_DEVICE_OWNER)
        assertThat(phone.events).containsExactly("readOwner")
    }

    @Test
    fun `an App Mode phone, not a Device Owner at all, is left exactly as it was`() = runTest {
        val phone = Phone(owner = false, home = false, flag = false, permitted = false, lock = none)
        val result = phone.coordinator().release()

        assertThat(result.outcome).isEqualTo(DapReleaseOutcome.NOT_DEVICE_OWNER)
        assertThat(result.rollback).isEqualTo(DapReleaseRollback.NONE)
        assertThat(phone.events).containsExactly("readOwner") // nothing written, no window, no lock
        assertThat(phone.home).isFalse()
        assertThat(phone.flag).isFalse()
        assertThat(phone.permitted).isFalse()
        assertThat(phone.lock).isEqualTo(none)
        assertThat(phone.windowOpen).isFalse()
        assertThat(phone.persistentHomePreference).isTrue()
    }

    @Test
    fun `a request that cannot be reset does not undo a confirmed release or leave the window open`() = runTest {
        val phone = Phone(resetRequestThrows = true)
        val result = phone.coordinator().release()

        assertThat(result.outcome).isEqualTo(DapReleaseOutcome.RELEASED)
        assertThat(phone.windowOpen).isFalse()
    }

    @Test
    fun `a default home that is still Polylute is said, not hidden`() = runTest {
        val result = Phone(homeStillPolylute = true).coordinator().release()
        assertThat(result.outcome).isEqualTo(DapReleaseOutcome.RELEASED)
        assertThat(result.homeStillPolylute).isTrue()
        assertThat(Phone().coordinator().release().homeStillPolylute).isFalse()
    }

    @Test
    fun `a leftover admin that will not go does not undo a release, and the result says so`() = runTest {
        val result = Phone(adminRemoves = false).coordinator().release()
        assertThat(result.outcome).isEqualTo(DapReleaseOutcome.RELEASED)
        assertThat(result.adminRemoved).isFalse()
    }

    // ── R1: a failure restores what was removed ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `HOME moving to the picker does not stop a refused release from putting everything back`() = runTest {
        val phone = Phone(ownerClearIgnored = true)
        val result = phone.coordinator().release()

        assertThat(result.outcome).isEqualTo(DapReleaseOutcome.STILL_OWNER)
        assertThat(result.rollback).isEqualTo(DapReleaseRollback.RESTORED)
        // the signals the mode is inferred from are back, because the rollback put them back (it did not ask the mode first)
        assertThat(phone.home).isTrue()
        assertThat(phone.flag).isTrue()
        assertThat(phone.permitted).isTrue()
        assertThat(phone.persistentHomePreference).isTrue()
        assertThat(phone.lock).isEqualTo(locked)
        assertThat(phone.mode()).isEqualTo(EffectiveProductMode.DEDICATED_ACTIVE)
        assertThat(phone.owner).isTrue()
        assertThat(phone.windowOpen).isFalse()
        assertThat(phone.events).containsNoneOf("removeActiveAdmin", "resetRequest")
    }

    @Test
    fun `the mode reads not dedicated in the middle of the operation, which is why the rollback cannot ask it`() = runTest {
        // the premise of R34-01, shown on the same phone: after the policies are cleared, the real resolver says there is nothing to restore
        val phone = Phone()
        var modeBeforeRestore: EffectiveProductMode? = null
        val coordinator = DapReleaseOwnerCoordinator(
            ownerPresence = { OwnerPresence.PRESENT },
            readStart = { DapOwnerStart(true, true, true, true, true) },
            beginWindow = { },
            stopLockTask = { phone.lock = none; true },
            clearPolicies = { phone.flag = false; phone.permitted = false; true },
            clearPersistentHome = { phone.home = false; true },
            clearDeviceOwner = { },
            removeActiveAdmin = { true },
            resetRequestToLauncher = { },
            homeIsPolylute = { false },
            endWindow = { },
            restorePolicies = { modeBeforeRestore = phone.mode(); true },
            relock = { true },
        )
        coordinator.release()
        assertThat(modeBeforeRestore).isNotEqualTo(EffectiveProductMode.DEDICATED_ACTIVE)
    }

    @Test
    fun `a policy that fails after a partial removal is rolled back and the owner is never asked to go`() = runTest {
        listOf(Phone(policiesFailMidway = true), Phone(policiesFailAfterRemoval = true), Phone(homeClearThrows = true)).forEach { phone ->
            val result = phone.coordinator().release()

            assertThat(result.outcome).isEqualTo(DapReleaseOutcome.POLICY_NOT_CLEARED)
            assertThat(result.rollback).isEqualTo(DapReleaseRollback.RESTORED)
            assertThat(phone.events).doesNotContain("clearDeviceOwner")
            assertThat(phone.home).isTrue()
            assertThat(phone.flag).isTrue()
            assertThat(phone.permitted).isTrue()
            assertThat(phone.lock).isEqualTo(locked)
            assertThat(phone.mode()).isEqualTo(EffectiveProductMode.DEDICATED_ACTIVE)
            assertThat(phone.windowOpen).isFalse()
        }
    }

    @Test
    fun `HOME that stays Polylute through the removal is rolled back the same way, and not rewritten twice`() = runTest {
        // the control the auditor kept: the listener's own default is Polylute, so the persistent policy was never the only reason it was HOME
        val phone = Phone(homeSticky = true, ownerClearIgnored = true)
        val result = phone.coordinator().release()

        assertThat(result.outcome).isEqualTo(DapReleaseOutcome.STILL_OWNER)
        assertThat(result.rollback).isEqualTo(DapReleaseRollback.RESTORED)
        assertThat(phone.home).isTrue()
        assertThat(phone.mode()).isEqualTo(EffectiveProductMode.DEDICATED_ACTIVE)
        assertThat(phone.lock).isEqualTo(locked)
    }

    @Test
    fun `a Lock Task lift that throws is a failed lift with a rollback, not an uncaught exception with an open window`() = runTest {
        val phone = Phone()
        phone.stopHook = { p ->
            p.permitted = false
            throw IllegalStateException("activity is finishing")
        }
        val result = phone.coordinator().release()

        assertThat(result.outcome).isEqualTo(DapReleaseOutcome.LOCK_NOT_STOPPED)
        assertThat(result.rollback).isEqualTo(DapReleaseRollback.RESTORED)
        assertThat(phone.permitted).isTrue()
        assertThat(phone.windowOpen).isFalse()
    }

    @Test
    fun `a clear the platform refuses with an exception is the same as one it ignores`() = runTest {
        val phone = Phone(ownerClearThrows = true)
        val result = phone.coordinator().release()

        assertThat(result.outcome).isEqualTo(DapReleaseOutcome.STILL_OWNER)
        assertThat(result.rollback).isEqualTo(DapReleaseRollback.RESTORED)
        assertThat(phone.mode()).isEqualTo(EffectiveProductMode.DEDICATED_ACTIVE)
        assertThat(phone.lock).isEqualTo(locked)
    }

    @Test
    fun `a Lock Task that will not stop is rolled back, including an allowlist the lift had already emptied`() = runTest {
        val phone = Phone()
        phone.stopHook = { p ->
            p.permitted = false // the Device Owner escalation emptied the allowlist, and the lock still did not release
            false
        }
        val result = phone.coordinator().release()

        assertThat(result.outcome).isEqualTo(DapReleaseOutcome.LOCK_NOT_STOPPED)
        assertThat(result.rollback).isEqualTo(DapReleaseRollback.RESTORED)
        assertThat(phone.permitted).isTrue()
        assertThat(phone.events).containsNoneOf("clearPolicies", "clearPersistentHome", "clearDeviceOwner")
        assertThat(phone.events.takeLast(3)).containsExactly("restorePolicies", "endWindow", "relock").inOrder()
    }

    @Test
    fun `a restore that also fails is said as not restored, the window is still closed, and no lock is requested`() = runTest {
        val phone = Phone(ownerClearIgnored = true, restoreWorks = false)
        val result = phone.coordinator().release()

        assertThat(result.outcome).isEqualTo(DapReleaseOutcome.STILL_OWNER)
        assertThat(result.rollback).isEqualTo(DapReleaseRollback.NOT_RESTORED)
        assertThat(phone.events).doesNotContain("relock")
        assertThat(phone.windowOpen).isFalse()
        assertThat(phone.owner).isTrue()
    }

    @Test
    fun `restored is announced only after the lock is observed, not after the policy writes`() = runTest {
        val phone = Phone(ownerClearIgnored = true, relockWorks = false)
        val result = phone.coordinator().release()

        // the policies went back, but Lock Task did not come back: that is not "restored"
        assertThat(phone.events).contains("restorePolicies")
        assertThat(phone.events).contains("relock")
        assertThat(result.rollback).isEqualTo(DapReleaseRollback.NOT_RESTORED)
        // and the other way round: the order is write, close the window, then look at the lock
        val ok = Phone(ownerClearIgnored = true)
        ok.coordinator().release()
        assertThat(ok.events.takeLast(3)).containsExactly("restorePolicies", "endWindow", "relock").inOrder()
    }

    @Test
    fun `a rollback that throws is not restored, not a crash, and the window still closes`() = runTest {
        val phone = Phone(ownerClearIgnored = true)
        val result = DapReleaseOwnerCoordinator(
            ownerPresence = { OwnerPresence.PRESENT },
            readStart = { DapOwnerStart(true, true, true, true, true) },
            beginWindow = { phone.window.begin(0L) },
            stopLockTask = { true },
            clearPolicies = { true },
            clearPersistentHome = { true },
            clearDeviceOwner = { },
            removeActiveAdmin = { true },
            resetRequestToLauncher = { },
            homeIsPolylute = { false },
            endWindow = { phone.window.invalidate() },
            restorePolicies = { throw SecurityException("no longer the owner") },
            relock = { true },
        ).release()

        assertThat(result.outcome).isEqualTo(DapReleaseOutcome.STILL_OWNER)
        assertThat(result.rollback).isEqualTo(DapReleaseRollback.NOT_RESTORED)
        assertThat(phone.windowOpen).isFalse()
    }

    @Test
    fun `a device that was not contained is left exactly as it was by a refused release`() = runTest {
        // the HONOR after it left the DAP for another launcher, or an App Mode phone that is a Device Owner: owner, no HOME, no flag, no lock
        val phone = Phone(home = false, flag = false, permitted = false, lock = none, ownerClearIgnored = true)
        val result = phone.coordinator().release()

        assertThat(result.outcome).isEqualTo(DapReleaseOutcome.STILL_OWNER)
        assertThat(result.rollback).isEqualTo(DapReleaseRollback.NOT_NEEDED)
        assertThat(phone.home).isFalse()
        assertThat(phone.flag).isFalse()
        assertThat(phone.permitted).isFalse()
        assertThat(phone.lock).isEqualTo(none)
        assertThat(phone.mode()).isNotEqualTo(EffectiveProductMode.DEDICATED_ACTIVE)
        assertThat(phone.persistentHomePreference).isTrue() // the preference the release cleared is back to what it was
        assertThat(phone.events).doesNotContain("relock")
        assertThat(phone.windowOpen).isFalse()
    }

    @Test
    fun `a permanent choice of another launcher is not undone by a failed release`() = runTest {
        // Device Owner, the owner chose HONOR Launcher for good: Polylute is not HOME, but the flag and the allowlist were still set
        val phone = Phone(home = false, lock = none, ownerClearIgnored = true)
        val result = phone.coordinator().release()

        assertThat(result.outcome).isEqualTo(DapReleaseOutcome.STILL_OWNER)
        assertThat(phone.home).isFalse()
        assertThat(phone.flag).isTrue()
        assertThat(phone.permitted).isTrue()
        assertThat(result.rollback).isEqualTo(DapReleaseRollback.RESTORED)
        assertThat(phone.events).doesNotContain("relock") // it was not locked at the start
    }

    // ── R2: never "released" without proof ────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a clear the platform refused plus a failed read is unverified, not released, and nothing is re-applied`() = runTest {
        val phone = Phone(ownerClearThrows = true, ownerReadFailsAfterClear = true)
        val result = phone.coordinator().release()

        assertThat(result.outcome).isEqualTo(DapReleaseOutcome.UNVERIFIED)
        assertThat(result.rollback).isEqualTo(DapReleaseRollback.NONE)
        assertThat(phone.events).containsNoneOf("restorePolicies", "relock", "removeActiveAdmin", "resetRequest")
        assertThat(phone.windowOpen).isFalse()
        assertThat(phone.owner).isTrue() // the truth the old code could not see
    }

    @Test
    fun `a clear that looked fine plus a failed read is unverified, not released`() = runTest {
        // clearDeviceOwnerApp returned normally and, in fact, did nothing; the read that would have said so fails
        val phone = Phone(ownerClearIgnored = true, ownerReadFailsAfterClear = true)
        val result = phone.coordinator().release()

        assertThat(result.outcome).isEqualTo(DapReleaseOutcome.UNVERIFIED)
        assertThat(phone.events).containsNoneOf("restorePolicies", "relock", "removeActiveAdmin", "resetRequest")
        assertThat(phone.windowOpen).isFalse()
    }

    @Test
    fun `a clear that worked plus a failed read is also unverified, because the proof is the query and not the call`() = runTest {
        val phone = Phone(ownerReadFailsAfterClear = true)
        val result = phone.coordinator().release()

        assertThat(phone.owner).isFalse()
        assertThat(result.outcome).isEqualTo(DapReleaseOutcome.UNVERIFIED)
    }

    @Test
    fun `absence confirmed by a query is released, even when the clear call itself threw`() = runTest {
        val gone = Phone()
        assertThat(gone.coordinator().release().outcome).isEqualTo(DapReleaseOutcome.RELEASED)

        // the call threw after the role was already gone (it can): the query is what decides
        val threwButGone = DapReleaseOwnerCoordinator(
            ownerPresence = { if (gone.events.contains("x")) OwnerPresence.ABSENT else OwnerPresence.PRESENT },
            readStart = { DapOwnerStart(true, true, true, true, true) },
            beginWindow = { },
            stopLockTask = { true },
            clearPolicies = { true },
            clearPersistentHome = { true },
            clearDeviceOwner = { gone.events += "x"; throw SecurityException("already gone") },
            removeActiveAdmin = { true },
            resetRequestToLauncher = { },
            homeIsPolylute = { false },
            endWindow = { },
            restorePolicies = { true },
            relock = { true },
        )
        assertThat(threwButGone.release().outcome).isEqualTo(DapReleaseOutcome.RELEASED)
    }

    @Test
    fun `a role Android still confirms is a failure with a rollback, not an unknown`() = runTest {
        val result = Phone(ownerClearIgnored = true).coordinator().release()
        assertThat(result.outcome).isEqualTo(DapReleaseOutcome.STILL_OWNER)
        assertThat(result.rollback).isEqualTo(DapReleaseRollback.RESTORED)
    }

    @Test
    fun `a read that fails once is retried, and the retries are bounded`() = runTest {
        // one failure after the clear: the second try answers, and the release is confirmed
        val flaky = Phone()
        val flakyResult = DapReleaseOwnerCoordinator(
            ownerPresence = {
                flaky.ownerReads++
                if (flaky.ownerReads == 2) throw IllegalStateException("binder") // the first read after the clear
                if (flaky.owner) OwnerPresence.PRESENT else OwnerPresence.ABSENT
            },
            readStart = { DapOwnerStart(true, true, true, true, true) },
            beginWindow = { },
            stopLockTask = { true },
            clearPolicies = { true },
            clearPersistentHome = { true },
            clearDeviceOwner = { flaky.owner = false },
            removeActiveAdmin = { true },
            resetRequestToLauncher = { },
            homeIsPolylute = { false },
            endWindow = { },
            restorePolicies = { true },
            relock = { true },
        ).release()
        assertThat(flakyResult.outcome).isEqualTo(DapReleaseOutcome.RELEASED)

        // a read that never answers is tried exactly READ_ATTEMPTS times after the clear, then it is unknown
        val dead = Phone(ownerReadFailsAfterClear = true)
        val result = dead.coordinator().release()
        val readsAfterClear = dead.events.drop(dead.events.indexOf("clearDeviceOwner")).count { it == "readOwner" }
        assertThat(readsAfterClear).isEqualTo(DapReleaseOwnerCoordinator.READ_ATTEMPTS)
        assertThat(result.outcome).isEqualTo(DapReleaseOutcome.UNVERIFIED)
    }

    @Test
    fun `a policy failure whose ownership read then fails is unknown and applies nothing`() = runTest {
        val phone = Phone()
        // the first read (before) answers; every later one throws
        var reads = 0
        val result = DapReleaseOwnerCoordinator(
            ownerPresence = { if (reads++ == 0) OwnerPresence.PRESENT else throw IllegalStateException("binder") },
            readStart = { DapOwnerStart(true, true, true, true, true) },
            beginWindow = { phone.window.begin(0L) },
            stopLockTask = { true },
            clearPolicies = { false },
            clearPersistentHome = { true },
            clearDeviceOwner = { phone.events += "clearDeviceOwner" },
            removeActiveAdmin = { true },
            resetRequestToLauncher = { },
            homeIsPolylute = { false },
            endWindow = { phone.window.invalidate() },
            restorePolicies = { phone.events += "restorePolicies"; true },
            relock = { phone.events += "relock"; true },
        ).release()

        assertThat(result.outcome).isEqualTo(DapReleaseOutcome.UNVERIFIED)
        assertThat(phone.events).isEmpty() // no clear of the role, no restore, no relock: nothing on the strength of a guess
        assertThat(phone.windowOpen).isFalse()
    }

    @Test
    fun `an ownership or a starting state that cannot be read before anything is touched changes nothing`() = runTest {
        val blind = Phone(ownerReadFailures = Int.MAX_VALUE)
        assertThat(blind.coordinator().release().outcome).isEqualTo(DapReleaseOutcome.UNREADABLE_BEFORE)
        assertThat(blind.events).containsExactly("readOwner", "readOwner", "readOwner")

        val noStart = Phone(startUnreadable = true)
        assertThat(noStart.coordinator().release().outcome).isEqualTo(DapReleaseOutcome.UNREADABLE_BEFORE)
        assertThat(noStart.events).containsExactly("readOwner", "readStart")
        assertThat(noStart.windowOpen).isFalse()
    }

    // ── R3: a cancel during the lift leaves no orphan window ──────────────────────────────────────────────────────────────────

    /** Runs the release with its Lock Task lift held by [hold], cancels it like a destroyed activity's scope would, and says whether the cancellation went on. */
    private suspend fun TestScope.cancelledWith(phone: Phone, hold: suspend (Phone) -> Boolean, atMs: Long = 0L): Boolean {
        phone.stopHook = hold
        val running = async { phone.coordinator().release() }
        runCurrent()
        if (atMs > 0) advanceTimeBy(atMs)
        assertThat(phone.windowOpen).isTrue() // the window is open while the lift is held: this is the moment that used to be orphaned
        running.cancel()
        runCurrent()
        val propagated = try {
            running.await()
            false
        } catch (cancelled: CancellationException) {
            true
        }
        return propagated
    }

    /** What `MainActivity` does on every resume, from the window and the policy alone: the new host's decision. */
    private fun newHostResume(phone: Phone): Pair<WindowReturn, Boolean> {
        val windowReturn = DapSystemSurfaceCoordinator.reconcileOnResume(phone.window, 60_000L)
        val wouldLock = DapContainmentPolicy.shouldEnterLockTask(
            dedicatedModeEnabled = phone.flag,
            maintenanceSessionActive = phone.windowOpen,
            isDeviceOwner = phone.owner,
            isLockTaskPermitted = phone.permitted,
            lockTaskModeState = phone.lock,
        )
        return windowReturn to wouldLock
    }

    @Test
    fun `a cancel before the lift started closes the window, and the cancellation goes on`() = runTest {
        val phone = Phone()
        val propagated = cancelledWith(phone, hold = { awaitCancellation() })

        assertThat(propagated).isTrue()
        assertThat(phone.windowOpen).isFalse()
        assertThat(phone.events).contains("endWindow")
        assertThat(phone.events).doesNotContain("clearDeviceOwner")
        // nothing was lifted, so there is nothing to put back and the lock never left
        assertThat(phone.lock).isEqualTo(locked)
        assertThat(phone.mode()).isEqualTo(EffectiveProductMode.DEDICATED_ACTIVE)
    }

    @Test
    fun `a cancel after the stop was requested puts the policies back and leaves the lock to the next host`() = runTest {
        val phone = Phone()
        val propagated = cancelledWith(phone, hold = { p ->
            p.lock = none        // the activity's stopLockTask() took effect
            p.permitted = false  // and the Device Owner escalation emptied the allowlist
            awaitCancellation()
        })

        assertThat(propagated).isTrue()
        assertThat(phone.windowOpen).isFalse()
        assertThat(phone.permitted).isTrue()
        assertThat(phone.flag).isTrue()
        assertThat(phone.home).isTrue()
        assertThat(phone.mode()).isEqualTo(EffectiveProductMode.DEDICATED_ACTIVE)
        // the destroyed activity does not ask for Lock Task (it cannot); the one that replaces it does, on its resume
        assertThat(phone.events).doesNotContain("relock")
        val (windowReturn, wouldLock) = newHostResume(phone)
        assertThat(windowReturn).isEqualTo(WindowReturn.NONE)
        assertThat(wouldLock).isTrue()
    }

    @Test
    fun `a cancel during the wait for the lock to release leaves no KEEP_OPEN window for the next host`() = runTest {
        val phone = Phone()
        val propagated = cancelledWith(phone, atMs = 1_000L, hold = { p ->
            p.lock = none
            delay(3_000L) // the activity's wait for Android to say NONE
            true
        })

        assertThat(propagated).isTrue()
        // the old behaviour: the singleton window stays ACTIVE and the next resume reads KEEP_OPEN, so nothing locks until it expires
        val (windowReturn, wouldLock) = newHostResume(phone)
        assertThat(windowReturn).isEqualTo(WindowReturn.NONE)
        assertThat(windowReturn).isNotEqualTo(WindowReturn.KEEP_OPEN)
        assertThat(wouldLock).isTrue()
        assertThat(phone.windowOpen).isFalse()
    }

    @Test
    fun `a cancel while waiting on the ownership query settles by what the query then says`() = runTest {
        // the clear was refused; the first read after it fails and the release is waiting to retry when the activity goes
        val present = Phone()
        var failNext = false
        val cancelled = async {
            DapReleaseOwnerCoordinator(
                ownerPresence = {
                    if (failNext) { failNext = false; throw IllegalStateException("binder") }
                    if (present.owner) OwnerPresence.PRESENT else OwnerPresence.ABSENT
                },
                readStart = { DapOwnerStart(true, true, true, true, true) },
                beginWindow = { present.window.begin(0L) },
                stopLockTask = { true },
                clearPolicies = { present.flag = false; present.permitted = false; true },
                clearPersistentHome = { present.home = false; true },
                clearDeviceOwner = { failNext = true },
                removeActiveAdmin = { true },
                resetRequestToLauncher = { },
                homeIsPolylute = { false },
                endWindow = { present.window.invalidate() },
                restorePolicies = {
                    present.flag = true; present.permitted = true; present.home = true
                    true
                },
                relock = { true },
            ).release()
        }
        runCurrent() // the first read after the clear failed and the coordinator is in its retry delay
        assertThat(present.windowOpen).isTrue()
        cancelled.cancel()
        runCurrent()

        assertThat(try { cancelled.await(); false } catch (e: CancellationException) { true }).isTrue()
        assertThat(present.windowOpen).isFalse()
        assertThat(present.mode()).isEqualTo(EffectiveProductMode.DEDICATED_ACTIVE) // the owner was confirmed present, so it was put back
    }

    @Test
    fun `a cancel with an unknown owner closes the window and applies nothing`() = runTest {
        val phone = Phone(ownerReadFailsAfterClear = true, ownerClearIgnored = true)
        val running = async { phone.coordinator().release() }
        runCurrent() // in the first retry delay after the clear
        assertThat(phone.windowOpen).isTrue()
        running.cancel()
        runCurrent()

        assertThat(try { running.await(); false } catch (e: CancellationException) { true }).isTrue()
        assertThat(phone.windowOpen).isFalse()
        assertThat(phone.events).containsNoneOf("restorePolicies", "relock", "resetRequest")
    }

    @Test
    fun `the host recreated while the process lives inherits no window and enters Lock Task on its resume`() = runTest {
        val phone = Phone()
        val gate = CompletableDeferred<Unit>()
        phone.stopHook = { p -> p.lock = none; p.permitted = false; gate.await(); true }
        val firstHost = async { phone.coordinator().release() }
        runCurrent()
        assertThat(phone.windowOpen).isTrue()

        // the first host is destroyed: its scope is cancelled while the process lives on, and a second host is created over the same process
        firstHost.cancel()
        runCurrent()
        val (windowReturn, wouldLock) = newHostResume(phone)

        assertThat(windowReturn).isEqualTo(WindowReturn.NONE)
        assertThat(wouldLock).isTrue()
        assertThat(phone.mode()).isEqualTo(EffectiveProductMode.DEDICATED_ACTIVE)
        assertThat(phone.window.snapshot.value.isAuthenticated).isFalse()
    }

    @Test
    fun `a cancel after the release finished changes nothing and a completed release is the control`() = runTest {
        val phone = Phone()
        val done = async { phone.coordinator().release() }
        runCurrent()
        done.cancel() // too late: it has already completed
        assertThat(done.await().outcome).isEqualTo(DapReleaseOutcome.RELEASED)
        assertThat(phone.events.count { it == "endWindow" }).isEqualTo(1)
        assertThat(phone.events).containsNoneOf("restorePolicies", "relock")
        assertThat(phone.owner).isFalse()
    }

    @Test
    fun `a cancel in the middle of the lock request of a rollback does not run the rollback twice`() = runTest {
        val phone = Phone(ownerClearIgnored = true)
        val coordinator = DapReleaseOwnerCoordinator(
            ownerPresence = { if (phone.owner) OwnerPresence.PRESENT else OwnerPresence.ABSENT },
            readStart = { DapOwnerStart(true, true, true, true, true) },
            beginWindow = { phone.window.begin(0L) },
            stopLockTask = { true },
            clearPolicies = { true },
            clearPersistentHome = { true },
            clearDeviceOwner = { },
            removeActiveAdmin = { true },
            resetRequestToLauncher = { },
            homeIsPolylute = { false },
            endWindow = { phone.events += "endWindow"; phone.window.invalidate() },
            restorePolicies = { phone.events += "restorePolicies"; true },
            relock = { phone.events += "relock"; awaitCancellation() },
        )
        val running = async { coordinator.release() }
        runCurrent()
        running.cancel()
        runCurrent()

        assertThat(phone.events.count { it == "restorePolicies" }).isEqualTo(1)
        assertThat(phone.events.count { it == "endWindow" }).isEqualTo(1)
    }
}
