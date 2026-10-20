package com.lostf1sh.pixelplayeross.dap.admin

import android.app.ActivityManager
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.dap.lifecycle.DapRecoveryTrigger
import com.lostf1sh.pixelplayeross.dap.lifecycle.DapUpdateReturnPolicy
import com.lostf1sh.pixelplayeross.dap.mode.DedicatedProvisioningEvidence
import com.lostf1sh.pixelplayeross.dap.mode.ProductLevel
import com.lostf1sh.pixelplayeross.dap.mode.ProductModeResolver
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * B34-fix2 R1 and R3: choosing another launcher through "Elegir app de inicio" ends Dedicated and stays ended; the DAP comes back by itself
 * after its own package was replaced, and only when it should. B34-fix3 R2: a Device Owner whose listener makes Polylute the home app again
 * is back in Dedicated *with* its persistent HOME (B34 table row 6), never while an exit waits and never while another launcher is home.
 *
 * [Phone] is a stateful Device Owner phone: the owner, the home app Android resolves (the persistent policy first, then the listener's own
 * default), the dedicated flag, the Lock Task allowlist and mask, the keyguard hold, the Lock Task state and the recorded HOME exit are
 * fields that the production calls change, as Android's do. The decisions are the production ones, not copies: [DapContainmentReconciler]
 * (restore, leave, settle), [ProductModeResolver], [DapHomeChangeTracker], [DapContainmentPolicy.shouldEnterLockTask], [HomePickerFlow],
 * [DapUpdateReturnPolicy] and [DapReturnToDapCoordinator]. What [Phone.Activity] adds is the order `MainActivity` calls them in, line for
 * line (each method names the activity function it stands for; `B34Fix2BoundaryTest` pins the order in the real source). That is what the
 * reviewer's HONOR run needed: a recording fake cannot show that a focus change between the clear and the pick puts the policy back.
 *
 * Nothing here touches a device; the real calls are the controller's, and the batch never runs `dpm` or `clearDeviceOwnerApp` against one.
 */
class DapHomeExitTest {

    private val locked = ActivityManager.LOCK_TASK_MODE_LOCKED
    private val none = ActivityManager.LOCK_TASK_MODE_NONE

    private enum class Home { POLYLUTE, HONOR, NONE }

    private class Phone(
        var owner: Boolean = true,
        /** The home app the listener chose in Android's own setting: what the picker changes. */
        var defaultHome: Home = Home.POLYLUTE,
        /** The Device Owner's persistent preferred HOME activity: it wins over [defaultHome]. */
        var persistentPolicy: Boolean = true,
        /**
         * What the DAP's own record of the persistent HOME holds when the test starts: the real [DapContainmentStore] over [file]. `null` is a fresh
         * install, where the key was never written (B35-fix1 R2).
         */
        startingRecord: Boolean? = true,
        var flag: Boolean = true,
        var allowlist: Boolean = true,
        var lock: Int = ActivityManager.LOCK_TASK_MODE_LOCKED,
        var homeExitChosen: Boolean = false,
        /** `disableDedicatedMode` reports failure (after clearing the flag) or throws. */
        val clearFails: Boolean = false,
        val clearThrows: Boolean = false,
        val enableFails: Boolean = false,
    ) {
        val events = mutableListOf<String>()
        val file = ContainmentFile().also { f -> startingRecord?.let { f.store.setPersistentHomeEnabled(it) } }

        /** The record: this app wrote the persistent HOME entry (it writes it on, the exit's clear writes it off). A fresh install has not written it. */
        var persistentHomePref: Boolean
            get() = file.store.isPersistentHomeEnabled()
            set(value) = file.store.setPersistentHomeEnabled(value)
        var keyguardDisabled = flag
        // the DAP lock screen setting is on, so the appliance owns the wake surface and the keyguard hold is part of what is in force
        private val requiredFeatures = DapContainmentPolicy.requiredLockTaskFeatures(dapLockScreenEnabled = true)
        var features = if (allowlist) requiredFeatures else 0

        /** Which screen the listener is looking at. */
        var foreground = "polylute"
        var pickerOpen = false
        var lockRequests = 0

        // ── what Android says ──────────────────────────────────────────────────────────────────────────────────────────

        fun resolvedHome(): Home = if (owner && persistentPolicy) Home.POLYLUTE else defaultHome

        fun state() = DapDeviceOwnerState(
            isDeviceOwner = owner,
            isAdminActive = owner,
            isLockTaskPermitted = owner && allowlist,
            isPixelPlayerHome = resolvedHome() == Home.POLYLUTE,
            isDedicatedModeEnabled = flag,
            lockTaskModeState = lock,
            lockTaskFeatures = if (owner) features else 0,
            isHomeExitChosen = homeExitChosen,
        )

        /** The mode the activity infers, with the real resolver and the same evidence `ProductModeController` builds. */
        fun mode() = state().let {
            ProductModeResolver.resolve(
                requestedMode = null,
                evidence = DedicatedProvisioningEvidence(
                    isDeviceOwner = it.isDeviceOwner,
                    isPixelPlayerHome = it.isPixelPlayerHome,
                    isDedicatedModeEnabled = it.isDedicatedModeEnabled,
                    isLockTaskPermitted = it.isLockTaskPermitted,
                    lockTaskModeState = it.lockTaskModeState,
                    homeExitChosen = it.isHomeExitChosen,
                ),
            )
        }

        // ── the controller's calls, as the platform answers them ───────────────────────────────────────────────────────

        private fun applyPersistentHome(): Boolean {
            events += "write:persistentHome"
            persistentHomePref = true
            persistentPolicy = true
            return resolvedHome() == Home.POLYLUTE
        }

        private fun configureContainment(): Boolean {
            events += "write:allowlist"
            allowlist = true
            features = requiredFeatures
            return true
        }

        private fun applyKeyguard(): Boolean {
            events += "write:keyguard"
            keyguardDisabled = DapKeyguardPolicy.shouldDisableKeyguard(
                isDeviceOwner = true,
                dedicatedModeEnabled = flag,
                dapLockScreenEnabled = true,
                isDeviceSecure = false,
            )
            return true
        }

        /** `DapDeviceOwnerController.enableDedicatedMode`, line for line. */
        private fun enableDedicatedMode(): Boolean {
            if (!owner) return false
            events += "enableDedicatedMode"
            homeExitChosen = false
            val homeApplied = applyPersistentHome()
            val configured = configureContainment()
            if (enableFails) return false
            if (homeApplied && configured) flag = true
            return homeApplied && configured
        }

        /** `DapDeviceOwnerController.disableDedicatedMode`. */
        private fun disableDedicatedMode(): Boolean {
            events += "clear:policies"
            flag = false
            applyKeyguard()
            if (clearThrows) throw SecurityException("refused")
            features = 0
            allowlist = false
            return !clearFails
        }

        private fun clearPersistentHome(): Boolean {
            events += "clear:persistentHome"
            persistentHomePref = false
            persistentPolicy = false
            return true
        }

        val reconciler = DapContainmentReconciler(
            readState = ::state,
            persistentHomeRequired = { persistentHomePref },
            containmentRequired = { flag },
            requiredLockTaskFeatures = { requiredFeatures },
            markHomeExit = { events += "mark=$it"; homeExitChosen = it },
            setDedicatedFlag = { events += "write:flag=$it"; flag = it },
            applyKeyguardDisabled = ::applyKeyguard,
            applyPersistentHome = ::applyPersistentHome,
            configureContainmentPolicy = ::configureContainment,
            clearPolicies = ::disableDedicatedMode,
            clearPersistentHome = ::clearPersistentHome,
            enableDedicatedMode = ::enableDedicatedMode,
        )

        val writes: List<String> get() = events.filter { it.startsWith("write:") || it == "enableDedicatedMode" }

        // ── the process and its activity ───────────────────────────────────────────────────────────────────────────────

        var flow = HomePickerFlow()
        var app = Activity()

        /** The process dies: its activity, its tracker and the picker flow it held go. What the device and the store hold stays. */
        fun killProcess() {
            flow = HomePickerFlow()
            app = Activity()
        }

        /** The task Lock Task held is gone with the process (an update, a reboot). */
        fun killProcessAndItsLock() {
            lock = ActivityManager.LOCK_TASK_MODE_NONE
            killProcess()
        }

        /** The listener opens Polylute (the icon, a notification, the media session, the HOME button): a start of the activity. */
        fun openPolylute() {
            if (app.created) {
                app.newIntent()
            } else {
                app.create()
            }
            app.resume()
            foreground = "polylute"
            pickerOpen = false
        }

        /** The picker takes the foreground: the activity is paused by it. */
        fun pickerShows() {
            pickerOpen = true
            foreground = "picker"
            app.pause()
        }

        fun pick(home: Home) {
            defaultHome = home
            pickerOpen = false
        }

        fun cancelPicker() {
            pickerOpen = false
        }

        /** Android's boot: the process is gone and so is Lock Task; the receiver runs; the system starts whatever HOME resolves to. */
        fun reboot() {
            killProcessAndItsLock()
            receiver(DapRecoveryTrigger.BOOT_COMPLETED)
            foreground = if (resolvedHome() == Home.POLYLUTE) {
                openPolylute()
                "polylute"
            } else {
                "honor launcher"
            }
        }

        /** `adb install -r` or a Play update: the process and its Lock Task are gone, the receiver runs, and nothing else starts anything. */
        fun replacePackage() {
            killProcessAndItsLock()
            foreground = "recents"
            receiver(DapRecoveryTrigger.MY_PACKAGE_REPLACED)
        }

        /** `DapPolicyRestoreReceiver.onReceive`: the restore if Dedicated, then the HOME start after an update if the policy says so. */
        fun receiver(trigger: DapRecoveryTrigger) {
            val result = if (mode().isDedicatedActive) {
                if (owner) reconciler.restore(trigger) else DapPolicyRestoreResult(false, false, false)
            } else {
                DapPolicyRestoreResult(isDeviceOwner = false, persistentHomeApplied = false, containmentConfigured = false)
            }
            val s = state()
            if (DapUpdateReturnPolicy.shouldStartHome(trigger, result, s.isPixelPlayerHome, s.lockTaskModeState)) {
                events += "startHome"
                openPolylute()
            }
        }

        /** `MainActivity`, in the order it calls the production pieces. */
        inner class Activity {
            private val tracker = DapHomeChangeTracker()
            var created = false
            var resumed = false

            /** `onCreate`: the picker is settled, then the activity-start restore. */
            fun create() {
                created = true
                settleHomeExit()
                restoreDeviceOwnerPolicy()
            }

            /** `onResume`: the picker is settled before any reconcile, and the lock is asked last. */
            fun resume() {
                resumed = true
                settleHomeExit()
                reconcileOwnerPolicyIfHomeChanged()
                maybeEnterDedicatedMode()
            }

            fun pause() {
                resumed = false
                flow.activityPaused()
            }

            fun newIntent() = reconcileOwnerPolicyIfHomeChanged()

            /** `onWindowFocusChanged(true)`: reconcile only, never settle. */
            fun focusRegained() = reconcileOwnerPolicyIfHomeChanged()

            /** `settleHomeExit` */
            private fun settleHomeExit() {
                if (!flow.shouldSettleNow()) return
                val settlement = reconciler.settle()
                flow.settled()
                if (settlement != HomeExitSettlement.NONE) events += "settled:$settlement"
            }

            /** `reconcileOwnerPolicyIfHomeChanged` -> `restoreDeviceOwnerPolicy` */
            private fun reconcileOwnerPolicyIfHomeChanged() {
                if (!tracker.changed(state())) return
                events += "ownerFactsChanged"
                restoreDeviceOwnerPolicy()
            }

            /** `restoreDeviceOwnerPolicy` -> `ProductModeController.restoreDedicatedPoliciesIfActive` -> `restoreOwnerPolicies` */
            private fun restoreDeviceOwnerPolicy() {
                if (!mode().isDedicatedActive) return
                if (!owner) return
                reconciler.restore(DapRecoveryTrigger.ACTIVITY_START)
            }

            /** `maybeEnterDedicatedMode` */
            fun maybeEnterDedicatedMode() {
                if (!mode().isDedicatedActive) return
                val s = state()
                if (!DapContainmentPolicy.shouldEnterLockTask(
                        dedicatedModeEnabled = s.isDedicatedModeEnabled,
                        maintenanceSessionActive = false,
                        isDeviceOwner = s.isDeviceOwner,
                        isLockTaskPermitted = s.isLockTaskPermitted,
                        lockTaskModeState = s.lockTaskModeState,
                    )
                ) return
                lockRequests++
                events += "startLockTask"
                if (s.isLockTaskPermitted) lock = ActivityManager.LOCK_TASK_MODE_LOCKED
            }

            /**
             * "Elegir app de inicio" after the confirmation: `restoreHonorLauncher` (the lift, `prepareHonorHomeRecovery`, the picker). Returns
             * whether the exit started. [pickerCanOpen] false is a device whose picker will not open.
             */
            fun chooseHomeApp(pickerCanOpen: Boolean = true): Boolean {
                check(resumed) { "the page is on screen" }
                if (!mode().isDedicatedActive) {
                    // the plain picker: nothing to lift or clear
                    return pickerCanOpen
                }
                events += "stopLockTask"
                lock = ActivityManager.LOCK_TASK_MODE_NONE
                // prepareHonorHomeRecovery: its guards, then the exit
                if (!mode().isDedicatedActive || lock != ActivityManager.LOCK_TASK_MODE_NONE) return false
                val cleared = runCatching { reconciler.leave() }.getOrDefault(false)
                if (!cleared) {
                    restoreDedicatedModeAfterTransitionFailure()
                    return false
                }
                if (pickerCanOpen) {
                    flow.launched()
                } else {
                    settleHomeExit()
                    maybeEnterDedicatedMode()
                }
                return true
            }

            /** `restoreDedicatedModeAfterTransitionFailure` */
            private fun restoreDedicatedModeAfterTransitionFailure() {
                if (!mode().isDedicatedActive) return
                enableDedicatedMode()
                maybeEnterDedicatedMode()
            }

            /** `returnToDap` ("Volver al DAP"), with the real coordinator. */
            suspend fun volverAlDap(): DapReturnOutcome = DapReturnToDapCoordinator(
                isDeviceOwner = { owner },
                reassertPolicy = {
                    // reassertDedicatedPolicy: ensureDedicatedModeReady (nothing is rewritten when it is all in place and no exit is waiting), then the
                    // persistent HOME again whatever the record says, because the listener asked and Android cannot say whether the entry is in force
                    val s = state()
                    val ready = (!s.isHomeExitChosen && DapContainmentPolicy.isRelockPolicyReady(s, true, persistentHomeEnabled = persistentHomePref) || enableDedicatedMode()) &&
                        applyPersistentHome()
                    if (ready) restoreDeviceOwnerPolicy()
                    ready
                },
                isDedicatedActive = { mode().isDedicatedActive },
                endWindow = { },
                prepareLockTaskTransition = { },
                requestLockTask = ::maybeEnterDedicatedMode,
                awaitLocked = { lock == ActivityManager.LOCK_TASK_MODE_LOCKED },
                recoverContainment = { restoreDedicatedModeAfterTransitionFailure() },
            ).returnToDap()
        }
    }

    /** Nothing of the Dedicated policy is in force: no lock, no flag, no allowlist, no keyguard hold, no persistent HOME. */
    private fun assertNothingArmed(phone: Phone) {
        assertThat(phone.lock).isEqualTo(none)
        assertThat(phone.flag).isFalse()
        assertThat(phone.allowlist).isFalse()
        assertThat(phone.keyguardDisabled).isFalse()
        assertThat(phone.persistentPolicy).isFalse()
    }

    /** As [assertNothingArmed], and the exit has been settled: nothing is waiting any more. */
    private fun assertNotContained(phone: Phone) {
        assertNothingArmed(phone)
        assertThat(phone.homeExitChosen).isFalse()
    }

    private fun assertContained(phone: Phone) {
        assertThat(phone.lock).isEqualTo(locked)
        assertThat(phone.flag).isTrue()
        assertThat(phone.allowlist).isTrue()
        assertThat(phone.persistentPolicy).isTrue()
        assertThat(phone.homeExitChosen).isFalse()
    }

    /** A running DAP: Device Owner, Polylute home, contained, the page on screen. */
    private fun dap(): Phone = Phone().also { it.app.create(); it.app.resume() }

    // ── R1: the reviewer's sequence ───────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the reviewer sequence, focus regained while Polylute is still home, ends NONE after another launcher is picked`() {
        val phone = dap()
        assertContained(phone)

        assertThat(phone.app.chooseHomeApp()).isTrue()
        // the confirm dialog goes away and the activity regains window focus while Polylute is still the home app
        phone.app.focusRegained()
        assertThat(phone.state().isPixelPlayerHome).isTrue()
        phone.pickerShows()
        phone.pick(Home.HONOR)
        // the next activity start: the icon, a notification, the media session
        phone.openPolylute()

        assertNotContained(phone)
        assertThat(phone.resolvedHome()).isEqualTo(Home.HONOR)
        assertThat(phone.lockRequests).isEqualTo(0)
        assertThat(phone.mode().isDedicatedActive).isFalse()
    }

    @Test
    fun `the same with a resume in the middle instead of a focus change`() {
        val phone = dap()
        assertThat(phone.app.chooseHomeApp()).isTrue()
        // a resume before the picker has taken the foreground: it is not the picker coming back
        phone.app.resume()
        assertThat(phone.homeExitChosen).isTrue()
        assertThat(phone.lock).isEqualTo(none)
        phone.pickerShows()
        phone.pick(Home.HONOR)
        phone.openPolylute()

        assertNotContained(phone)
        assertThat(phone.lockRequests).isEqualTo(0)
    }

    @Test
    fun `a boot restore after the choice puts nothing back`() {
        val phone = dap()
        phone.app.chooseHomeApp()
        phone.app.focusRegained()
        phone.pickerShows()
        phone.pick(Home.HONOR)

        phone.reboot()

        assertNothingArmed(phone)
        assertThat(phone.homeExitChosen).isTrue() // still waiting for Polylute's next start to settle it
        assertThat(phone.foreground).isEqualTo("honor launcher")
        // and opening Polylute afterwards from its icon still leaves it NONE with HOME reachable
        phone.openPolylute()
        assertNotContained(phone)
        assertThat(phone.lockRequests).isEqualTo(0)
    }

    @Test
    fun `a package replacement after the choice puts nothing back and starts nothing`() {
        val phone = dap()
        phone.app.chooseHomeApp()
        phone.app.focusRegained()
        phone.pickerShows()
        phone.pick(Home.HONOR)

        phone.replacePackage()

        assertNothingArmed(phone)
        assertThat(phone.events).doesNotContain("startHome")
        assertThat(phone.foreground).isEqualTo("recents")
        phone.openPolylute()
        assertNotContained(phone)
    }

    @Test
    fun `a boot and a package replacement while the picker is still open put nothing back either`() {
        val phone = dap()
        phone.app.chooseHomeApp()
        phone.pickerShows()
        phone.events.clear()

        phone.killProcessAndItsLock()
        phone.receiver(DapRecoveryTrigger.MY_PACKAGE_REPLACED)
        phone.receiver(DapRecoveryTrigger.BOOT_COMPLETED)

        assertThat(phone.writes).isEmpty()
        assertThat(phone.flag).isFalse()
        assertThat(phone.allowlist).isFalse()
        assertThat(phone.homeExitChosen).isTrue()
        assertThat(phone.events).doesNotContain("startHome")
    }

    @Test
    fun `no early event, the plain choice, ends NONE too`() {
        val phone = dap()
        phone.app.chooseHomeApp()
        phone.pickerShows()
        phone.pick(Home.HONOR)
        phone.openPolylute()
        assertNotContained(phone)
    }

    @Test
    fun `while the exit is waiting every restore writes nothing, not the flag, the allowlist, the keyguard hold or the HOME`() {
        val phone = dap()
        phone.app.chooseHomeApp()
        phone.events.clear()

        phone.app.focusRegained()
        phone.receiver(DapRecoveryTrigger.ACTIVITY_START)
        phone.receiver(DapRecoveryTrigger.BOOT_COMPLETED)
        phone.receiver(DapRecoveryTrigger.MY_PACKAGE_REPLACED)
        phone.reconciler.restore(DapRecoveryTrigger.ACTIVITY_START)

        assertThat(phone.writes).isEmpty()
        assertThat(phone.flag).isFalse()
        assertThat(phone.allowlist).isFalse()
        assertThat(phone.state().isPixelPlayerHome).isTrue() // Polylute is still the resolved home: that is what used to read as a lost policy
        assertThat(phone.mode().isDedicatedActive).isFalse()
    }

    @Test
    fun `the exit is recorded before anything is cleared, so nothing that runs in between can read a half-cleared device as a lost policy`() {
        val phone = dap()
        phone.app.chooseHomeApp()
        val markAt = phone.events.indexOf("mark=true")
        assertThat(markAt).isAtLeast(0)
        assertThat(markAt).isLessThan(phone.events.indexOf("clear:policies"))
        assertThat(markAt).isLessThan(phone.events.indexOf("clear:persistentHome"))
    }

    // ── R1: what keeps working ────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `cancel in the picker locks again, with or without a focus change before it`() {
        listOf(true, false).forEach { focusBefore ->
            val phone = dap()
            phone.app.chooseHomeApp()
            if (focusBefore) phone.app.focusRegained()
            phone.pickerShows()
            phone.cancelPicker()
            phone.openPolylute()

            assertContained(phone)
            assertThat(phone.events).contains("settled:RECONTAINED")
            assertThat(phone.keyguardDisabled).isTrue()
        }
    }

    @Test
    fun `picking Polylute again locks again`() {
        val phone = dap()
        phone.app.chooseHomeApp()
        phone.app.focusRegained()
        phone.pickerShows()
        phone.pick(Home.POLYLUTE)
        phone.openPolylute()

        assertContained(phone)
        assertThat(phone.events).contains("settled:RECONTAINED")
    }

    @Test
    fun `the persistent HOME comes back with a cancel, so HONOR cannot take HOME while the device is contained`() {
        val phone = dap()
        phone.app.chooseHomeApp()
        phone.pickerShows()
        assertThat(phone.persistentPolicy).isFalse()
        phone.cancelPicker()
        phone.openPolylute()
        assertThat(phone.persistentPolicy).isTrue()
        assertThat(phone.persistentHomePref).isTrue()
    }

    @Test
    fun `the way back from another launcher works through the picker and then Volver al DAP`() = runTest {
        val phone = dap()
        phone.app.chooseHomeApp()
        phone.app.focusRegained()
        phone.pickerShows()
        phone.pick(Home.HONOR)
        phone.openPolylute()
        assertNotContained(phone)

        // the page shows the levels only when Polylute is home: Elegir app de inicio (a plain picker now) -> Polylute
        assertThat(phone.mode().isDedicatedActive).isFalse()
        assertThat(phone.app.chooseHomeApp()).isTrue()
        phone.pickerShows()
        phone.pick(Home.POLYLUTE)
        phone.openPolylute()

        // then "Volver al DAP", which is what the listener asks for: confirmed containment, Lock Task observed
        val outcome = phone.app.volverAlDap()
        assertThat(outcome).isEqualTo(DapReturnOutcome.CONTAINED)
        assertThat(phone.lock).isEqualTo(locked)
        assertThat(phone.flag).isTrue()
        assertThat(phone.allowlist).isTrue()
        assertThat(phone.homeExitChosen).isFalse()
    }

    @Test
    fun `Volver al DAP alone brings a Device Owner with Polylute home and nothing in force back, the state the reviewer found`() = runTest {
        // the reviewer's second state: Polylute is Android's default home again, nothing is held and nothing is in force
        val phone = Phone(persistentPolicy = false, startingRecord = false, flag = false, allowlist = false, lock = none)

        val outcome = phone.app.volverAlDap()

        assertThat(outcome).isEqualTo(DapReturnOutcome.CONTAINED)
        assertContained(phone)
        assertThat(phone.persistentHomePref).isTrue()
        assertThat(phone.keyguardDisabled).isTrue()
    }

    @Test
    fun `Volver al DAP completes a Device Owner that is locked and has no persistent HOME, the half-provisioned state B34-fix2 left`() = runTest {
        // flag, allowlist and lock in force, Polylute home through the listener's default, the persistent HOME off (the exit cleared it)
        val phone = Phone(persistentPolicy = false, startingRecord = false)

        val outcome = phone.app.volverAlDap()

        assertThat(outcome).isEqualTo(DapReturnOutcome.CONTAINED)
        assertThat(phone.events).contains("enableDedicatedMode")
        assertThat(phone.persistentPolicy).isTrue()
        assertContained(phone)
    }

    @Test
    fun `Volver al DAP after Polylute was picked again through the plain picker rewrites nothing but the persistent HOME it re-asserts`() = runTest {
        // B34-fix3 R2: the resume that re-contained the device already put the persistent HOME back, so the explicit way back only confirms the
        // flag, the allowlist and the mask. B35-fix1 R2: the persistent HOME it asserts again, because the listener asked and Android cannot say
        // whether the entry is in force (the same entry, so the platform enforces nothing new)
        val phone = dap()
        phone.app.chooseHomeApp()
        phone.pickerShows()
        phone.pick(Home.HONOR)
        phone.openPolylute()
        phone.app.chooseHomeApp()
        phone.pickerShows()
        phone.pick(Home.POLYLUTE)
        phone.openPolylute()
        assertContained(phone)

        phone.events.clear()
        val outcome = phone.app.volverAlDap()

        assertThat(outcome).isEqualTo(DapReturnOutcome.CONTAINED)
        assertThat(phone.events).doesNotContain("enableDedicatedMode")
        assertThat(phone.events).doesNotContain("write:allowlist")
        assertThat(phone.events).doesNotContain("write:flag=true")
        assertThat(phone.events.filter { it == "write:persistentHome" }).hasSize(1)
        assertContained(phone)
    }

    @Test
    fun `a Device Owner that lost its persistent HOME without choosing to leave is healed`() {
        // an OEM reset of preferred activities: the flag and the allowlist are in force, HOME went back to the launcher, no exit was recorded
        val phone = Phone(persistentPolicy = false, defaultHome = Home.HONOR, lock = none)
        phone.killProcess()
        phone.openPolylute()

        assertThat(phone.events).contains("write:persistentHome")
        assertThat(phone.resolvedHome()).isEqualTo(Home.POLYLUTE)
        assertContained(phone)
    }

    @Test
    fun `a Device Owner that lost its allowlist and flag at a boot while Polylute is still home is healed, and the DAP comes back`() {
        val phone = Phone(flag = false, allowlist = false, lock = none)
        phone.reboot()
        assertContained(phone)
    }

    // ── B34-fix3 R2: Polylute picked again on a Device Owner completes Dedicated (B34 table row 6) ──────────────────────────────

    /** The state the reviewer reached on the HONOR: the exit is settled, another launcher is home, nothing of the policy is in force. */
    private fun honorHome(): Phone = Phone(
        defaultHome = Home.HONOR,
        persistentPolicy = false,
        startingRecord = false,
        flag = false,
        allowlist = false,
        lock = none,
    ).also { it.app.create(); it.app.resume() }

    @Test
    fun `row 6, from HONOR home the listener picks Polylute through the plain picker, and the resume leaves LOCKED, flag, allowlist and the persistent HOME`() {
        val phone = honorHome()
        assertThat(phone.mode().isDedicatedActive).isFalse()

        assertThat(phone.app.chooseHomeApp()).isTrue() // the plain picker: nothing to lift or clear, no exit recorded
        assertThat(phone.homeExitChosen).isFalse()
        phone.pickerShows()
        phone.pick(Home.POLYLUTE)
        phone.openPolylute()

        assertContained(phone) // LOCKED, flag, allowlist and the persistent HOME
        assertThat(phone.persistentHomePref).isTrue()
        assertThat(phone.keyguardDisabled).isTrue()
        assertThat(phone.events).contains("write:persistentHome")
        assertThat(phone.mode().level).isEqualTo(ProductLevel.DEDICATED)
    }

    @Test
    fun `row 6 end to end from Dedicated, leave to HONOR, open Polylute, pick Polylute again, ends in one complete state with no Volver al DAP`() {
        val phone = dap()
        assertThat(phone.app.chooseHomeApp()).isTrue()
        phone.app.focusRegained()
        phone.pickerShows()
        phone.pick(Home.HONOR)
        phone.openPolylute()
        assertNotContained(phone)

        assertThat(phone.app.chooseHomeApp()).isTrue()
        phone.pickerShows()
        phone.pick(Home.POLYLUTE)
        phone.openPolylute()

        assertContained(phone)
        assertThat(phone.persistentHomePref).isTrue()
    }

    @Test
    fun `the reconcile that re-contains also heals a device that is locked without its persistent HOME, the half-provisioned state`() {
        // what B34-fix2 left behind: flag, allowlist and lock in force, Polylute home by the listener's default, the persistent HOME off
        val phone = Phone(persistentPolicy = false, startingRecord = false)
        phone.killProcess()
        phone.openPolylute()

        assertThat(phone.events).contains("write:persistentHome")
        assertThat(phone.persistentPolicy).isTrue()
        assertThat(phone.persistentHomePref).isTrue()
        assertContained(phone)
    }

    /** The row-6 evidence (Polylute home, Device Owner, the persistent HOME off) with the exit still waiting for the open picker. */
    private fun exitWaitingForThePicker(): Phone = Phone(
        defaultHome = Home.POLYLUTE,
        persistentPolicy = false,
        startingRecord = false,
        flag = false,
        allowlist = false,
        lock = none,
        homeExitChosen = true,
    ).also { it.flow.launched() }

    @Test
    fun `row 6 control, while an exit is pending no trigger writes anything, the persistent HOME included`() {
        val phone = exitWaitingForThePicker()

        phone.app.focusRegained()
        phone.receiver(DapRecoveryTrigger.ACTIVITY_START)
        phone.receiver(DapRecoveryTrigger.BOOT_COMPLETED)
        phone.receiver(DapRecoveryTrigger.MY_PACKAGE_REPLACED)
        phone.reconciler.restore(DapRecoveryTrigger.ACTIVITY_START)
        phone.reconciler.restore(DapRecoveryTrigger.BOOT_COMPLETED)

        assertThat(phone.writes).isEmpty()
        assertThat(phone.persistentPolicy).isFalse()
        assertThat(phone.homeExitChosen).isTrue()
    }

    @Test
    fun `row 6 control, the exit that was pending settles with Polylute still home and the persistent HOME comes back with the rest`() {
        val phone = exitWaitingForThePicker()

        assertThat(phone.reconciler.settle()).isEqualTo(HomeExitSettlement.RECONTAINED)

        assertThat(phone.persistentPolicy).isTrue()
        assertThat(phone.persistentHomePref).isTrue()
        assertThat(phone.flag).isTrue()
        assertThat(phone.allowlist).isTrue()
        assertThat(phone.homeExitChosen).isFalse()
    }

    @Test
    fun `row 6 control, while another launcher is home no trigger writes anything, the persistent HOME included`() {
        val phone = honorHome()
        phone.events.clear()

        // the triggers as production runs them: the restore is asked only of a device that is Dedicated by evidence, and this one is not
        phone.app.focusRegained()
        phone.receiver(DapRecoveryTrigger.ACTIVITY_START)
        phone.receiver(DapRecoveryTrigger.BOOT_COMPLETED)
        phone.receiver(DapRecoveryTrigger.MY_PACKAGE_REPLACED)
        phone.killProcess()
        phone.openPolylute()
        assertThat(phone.writes).isEmpty()

        // and asked directly, the reconciler itself writes none of the policy either (the keyguard hold is released, which it always is)
        phone.reconciler.restore(DapRecoveryTrigger.ACTIVITY_START)
        phone.reconciler.restore(DapRecoveryTrigger.BOOT_COMPLETED)
        assertThat(phone.events.filter { it == "write:persistentHome" || it == "write:allowlist" || it.startsWith("write:flag") }).isEmpty()

        assertNotContained(phone)
        assertThat(phone.resolvedHome()).isEqualTo(Home.HONOR)
        assertThat(phone.lockRequests).isEqualTo(0)
    }

    @Test
    fun `row 6 control, a device that is complete is not rewritten by any trigger`() {
        val phone = dap()
        phone.events.clear()

        phone.app.focusRegained()
        phone.receiver(DapRecoveryTrigger.ACTIVITY_START)
        phone.receiver(DapRecoveryTrigger.BOOT_COMPLETED)

        assertThat(phone.events).doesNotContain("write:persistentHome")
        assertThat(phone.events).doesNotContain("write:allowlist")
        assertThat(phone.events).doesNotContain("write:flag=true")
        assertContained(phone)
    }

    @Test
    fun `row 6 control, a Device Owner that never left and lost only its persistent HOME is healed as before`() {
        val phone = Phone(persistentPolicy = false, defaultHome = Home.HONOR, lock = none)
        phone.killProcess()
        phone.openPolylute()
        assertThat(phone.events).contains("write:persistentHome")
        assertContained(phone)
    }

    // ── B35-fix1 R2: a fresh install has not written the persistent HOME ──────────────────────────────────────────────────────

    /**
     * `dpm set-device-owner` on a fresh install, the way the reviewer's HONOR got it: the preference file does not exist, [defaultHome] is the
     * launcher the listener has, and nothing of the policy is in force.
     */
    private fun freshOwner(defaultHome: Home = Home.HONOR, homeExitChosen: Boolean = false): Phone = Phone(
        defaultHome = defaultHome,
        persistentPolicy = false,
        startingRecord = null,
        flag = false,
        allowlist = false,
        lock = none,
        homeExitChosen = homeExitChosen,
    )

    @Test
    fun `a fresh install has not written the persistent HOME, and reading the record writes nothing`() {
        val phone = freshOwner()
        assertThat(phone.persistentHomePref).isFalse()
        assertThat(phone.file.values).isEmpty()
        phone.app.create()
        phone.app.resume()
        assertThat(phone.file.values).isEmpty()
    }

    @Test
    fun `a fresh Device Owner whose listener picks Polylute is LOCKED with the flag, the allowlist and the persistent HOME, with no extra step`() {
        // the reviewer's sequence: dpm set-device-owner, open the app, the picker, Polylute
        val phone = freshOwner().also { it.app.create(); it.app.resume() }
        assertThat(phone.mode().isDedicatedActive).isFalse()

        assertThat(phone.app.chooseHomeApp()).isTrue()
        phone.pickerShows()
        phone.pick(Home.POLYLUTE)
        phone.openPolylute()

        assertContained(phone) // LOCKED, flag, allowlist and the persistent HOME
        assertThat(phone.persistentHomePref).isTrue()
        assertThat(phone.keyguardDisabled).isTrue()
        assertThat(phone.events).contains("write:persistentHome")
        assertThat(phone.mode().level).isEqualTo(ProductLevel.DEDICATED)
    }

    @Test
    fun `a fresh Device Owner that already has Polylute as its home app gets the persistent HOME at its first start`() {
        val phone = freshOwner(defaultHome = Home.POLYLUTE)
        phone.app.create()
        phone.app.resume()

        assertContained(phone)
        assertThat(phone.events).contains("write:persistentHome")
        assertThat(phone.persistentHomePref).isTrue()
    }

    @Test
    fun `Volver al DAP on a fresh install that is locked without the persistent HOME writes the entry`() = runTest {
        // the state the reviewer found after the pick: flag, allowlist and lock in force, Polylute home by the listener's default, no entry
        val phone = Phone(persistentPolicy = false, startingRecord = null)
        assertThat(phone.persistentPolicy).isFalse()

        val outcome = phone.app.volverAlDap()

        assertThat(outcome).isEqualTo(DapReturnOutcome.CONTAINED)
        assertThat(phone.events).contains("write:persistentHome")
        assertThat(phone.persistentPolicy).isTrue()
        assertThat(phone.persistentHomePref).isTrue()
        assertContained(phone)
    }

    @Test
    fun `Volver al DAP writes the entry again when the record says it was written and Android lost it`() = runTest {
        // an OEM reset of preferred activities: the record is true, Polylute is still the listener's own default home, flag, allowlist and lock in force
        val phone = Phone(persistentPolicy = false, startingRecord = true)

        val outcome = phone.app.volverAlDap()

        assertThat(outcome).isEqualTo(DapReturnOutcome.CONTAINED)
        assertThat(phone.persistentPolicy).isTrue()
        assertThat(phone.events).contains("write:persistentHome")
        assertThat(phone.events).doesNotContain("enableDedicatedMode") // the flag, the allowlist and the mask were in place: not rewritten
        assertContained(phone)
    }

    @Test
    fun `fresh control, while an exit is pending no trigger writes anything and the settle puts the DAP back with its persistent HOME`() {
        val phone = freshOwner(defaultHome = Home.POLYLUTE, homeExitChosen = true)

        phone.app.focusRegained()
        phone.receiver(DapRecoveryTrigger.ACTIVITY_START)
        phone.receiver(DapRecoveryTrigger.BOOT_COMPLETED)
        phone.receiver(DapRecoveryTrigger.MY_PACKAGE_REPLACED)
        phone.reconciler.restore(DapRecoveryTrigger.ACTIVITY_START)
        phone.reconciler.restore(DapRecoveryTrigger.BOOT_COMPLETED)
        assertThat(phone.writes).isEmpty()
        assertThat(phone.persistentPolicy).isFalse()
        assertThat(phone.homeExitChosen).isTrue()

        assertThat(phone.reconciler.settle()).isEqualTo(HomeExitSettlement.RECONTAINED)
        assertThat(phone.persistentPolicy).isTrue()
        assertThat(phone.flag).isTrue()
        assertThat(phone.allowlist).isTrue()
        assertThat(phone.homeExitChosen).isFalse()
    }

    @Test
    fun `fresh control, while another launcher is home no trigger writes anything, even when the reconciler is asked directly`() {
        val phone = freshOwner(defaultHome = Home.HONOR)
        phone.app.create()
        phone.app.resume()
        phone.events.clear()

        phone.app.focusRegained()
        phone.receiver(DapRecoveryTrigger.ACTIVITY_START)
        phone.receiver(DapRecoveryTrigger.BOOT_COMPLETED)
        phone.receiver(DapRecoveryTrigger.MY_PACKAGE_REPLACED)
        phone.killProcess()
        phone.openPolylute()
        assertThat(phone.writes).isEmpty()

        // asked directly, with the guard the production triggers have (the mode must read Dedicated) taken away: still no entry on a launcher's device
        phone.reconciler.restore(DapRecoveryTrigger.ACTIVITY_START)
        phone.reconciler.restore(DapRecoveryTrigger.BOOT_COMPLETED)
        assertThat(phone.events.filter { it == "write:persistentHome" || it == "write:allowlist" || it.startsWith("write:flag") }).isEmpty()

        assertNotContained(phone)
        assertThat(phone.resolvedHome()).isEqualTo(Home.HONOR)
        assertThat(phone.file.values).isEmpty()
        assertThat(phone.lockRequests).isEqualTo(0)
    }

    @Test
    fun `an install that has the key true behaves as before the default changed`() {
        // complete: no trigger rewrites it
        val complete = dap()
        complete.events.clear()
        complete.app.focusRegained()
        complete.receiver(DapRecoveryTrigger.ACTIVITY_START)
        complete.receiver(DapRecoveryTrigger.BOOT_COMPLETED)
        assertThat(complete.events.filter { it == "write:persistentHome" || it == "write:allowlist" || it.startsWith("write:flag") }).isEmpty()
        assertContained(complete)

        // lost its entry without choosing to leave: healed, as before
        val lost = Phone(persistentPolicy = false, defaultHome = Home.HONOR, startingRecord = true, lock = none)
        lost.killProcess()
        lost.openPolylute()
        assertThat(lost.events).contains("write:persistentHome")
        assertContained(lost)
    }

    // ── R1: failures of the exit itself ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a clear that fails leaves no exit recorded and the usual recovery puts the DAP back`() {
        val phone = Phone(clearFails = true).also { it.app.create(); it.app.resume() }
        assertThat(phone.app.chooseHomeApp()).isFalse()
        assertThat(phone.homeExitChosen).isFalse()
        assertThat(phone.events).contains("mark=true")
        assertThat(phone.events.last { it.startsWith("mark=") }).isEqualTo("mark=false")
        assertThat(phone.lock).isEqualTo(locked)
        assertThat(phone.flag).isTrue()
    }

    @Test
    fun `a clear that throws leaves no exit recorded either`() {
        val phone = Phone(clearThrows = true).also { it.app.create(); it.app.resume() }
        assertThat(phone.app.chooseHomeApp()).isFalse()
        assertThat(phone.homeExitChosen).isFalse()
        assertThat(phone.lock).isEqualTo(locked)
    }

    @Test
    fun `a picker that will not open is settled at once as a cancel, and the DAP locks again`() {
        val phone = dap()
        assertThat(phone.app.chooseHomeApp(pickerCanOpen = false)).isTrue()
        assertContained(phone)
        assertThat(phone.events).contains("settled:RECONTAINED")
    }

    @Test
    fun `a picker that opens but whose return the DAP cannot confirm is said, not hidden`() {
        val phone = Phone(enableFails = true).also { it.app.create(); it.app.resume() }
        phone.app.chooseHomeApp()
        phone.pickerShows()
        phone.cancelPicker()
        phone.openPolylute()
        // nothing stays recorded: the usual reconciliation keeps healing and "Volver al DAP" is there
        assertThat(phone.homeExitChosen).isFalse()
        assertThat(phone.events).contains("settled:NOT_RECONTAINED")
    }

    @Test
    fun `the process dying while the picker is open leaves the exit recorded and the next start settles it, cancel or pick`() {
        val cancelled = dap()
        cancelled.app.chooseHomeApp()
        cancelled.pickerShows()
        cancelled.killProcess()
        cancelled.cancelPicker()
        cancelled.openPolylute()
        assertContained(cancelled)

        val picked = dap()
        picked.app.chooseHomeApp()
        picked.pickerShows()
        picked.killProcess()
        picked.pick(Home.HONOR)
        picked.openPolylute()
        assertNotContained(picked)
    }

    @Test
    fun `an exit nobody is waiting for is not settled twice`() {
        val phone = dap()
        phone.app.chooseHomeApp()
        phone.pickerShows()
        phone.pick(Home.HONOR)
        phone.openPolylute()
        assertThat(phone.reconciler.settle()).isEqualTo(HomeExitSettlement.NONE)
        assertThat(dap().reconciler.settle()).isEqualTo(HomeExitSettlement.NONE)
    }

    @Test
    fun `settling without a Device Owner is a stand and writes nothing`() {
        val phone = Phone(owner = false, flag = false, allowlist = false, lock = none, homeExitChosen = true, persistentPolicy = false)
        assertThat(phone.reconciler.settle()).isEqualTo(HomeExitSettlement.LEFT)
        assertThat(phone.writes).isEmpty()
        assertThat(phone.homeExitChosen).isFalse()
    }

    // ── the picker flow ───────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a resume between the launch and the pause is not the picker coming back, and the one after the pause is`() {
        val flow = HomePickerFlow()
        assertThat(flow.shouldSettleNow()).isTrue() // nothing in flight: a fresh process, or no picker
        flow.launched()
        assertThat(flow.shouldSettleNow()).isFalse()
        flow.activityPaused()
        assertThat(flow.shouldSettleNow()).isTrue()
        flow.settled()
        flow.activityPaused() // an ordinary pause later changes nothing
        assertThat(flow.shouldSettleNow()).isTrue()
    }

    @Test
    fun `a pause with no picker launched never arms the flow`() {
        val flow = HomePickerFlow()
        flow.activityPaused()
        flow.launched()
        assertThat(flow.shouldSettleNow()).isFalse()
    }

    // ── R3: the DAP after an update ───────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `after a package replacement a Dedicated Device Owner is brought back by itself and locked, with no Home press`() {
        val phone = dap()
        phone.replacePackage()

        assertThat(phone.events).contains("startHome")
        assertThat(phone.foreground).isEqualTo("polylute")
        assertContained(phone)
        assertThat(phone.lockRequests).isEqualTo(1) // the one after the update: the first start found the DAP already locked
    }

    @Test
    fun `after a package replacement the policy that was lost is restored before the home is started`() {
        val phone = Phone(flag = false, allowlist = false, lock = none)
        phone.replacePackage()
        assertThat(phone.events.indexOf("write:allowlist")).isLessThan(phone.events.indexOf("startHome"))
        assertContained(phone)
    }

    @Test
    fun `a package replacement on a phone that is not a Device Owner starts nothing`() {
        val phone = Phone(owner = false, flag = false, allowlist = false, persistentPolicy = false, startingRecord = false, lock = none)
        phone.replacePackage()
        assertThat(phone.events).doesNotContain("startHome")
        assertThat(phone.foreground).isEqualTo("recents")
        assertThat(phone.lock).isEqualTo(none)
    }

    @Test
    fun `a package replacement for a device whose listener chose another launcher never relocks it`() {
        val phone = dap()
        phone.app.chooseHomeApp()
        phone.pickerShows()
        phone.pick(Home.HONOR)
        phone.openPolylute()
        assertNotContained(phone)

        phone.replacePackage()

        assertThat(phone.events).doesNotContain("startHome")
        assertThat(phone.lock).isEqualTo(none)
        assertThat(phone.foreground).isEqualTo("recents")
        assertThat(phone.flag).isFalse()
    }

    @Test
    fun `a package replacement while the picker is open starts nothing, and the start that follows settles it`() {
        val phone = dap()
        phone.app.chooseHomeApp()
        phone.pickerShows()

        phone.replacePackage()
        assertThat(phone.events).doesNotContain("startHome")
        assertThat(phone.lock).isEqualTo(none)

        phone.cancelPicker()
        phone.openPolylute()
        assertContained(phone) // cancelled: it locks again, as the dialog promised
    }

    @Test
    fun `a boot starts nothing from the receiver, the system starts the home app itself`() {
        val phone = dap()
        phone.reboot()
        // the receiver restored and the system's own HOME start opened Polylute: one start, and it was not the receiver's
        assertThat(phone.events).doesNotContain("startHome")
        assertContained(phone)
    }

    @Test
    fun `the update policy starts the home only for a replaced package, a restored owner, Polylute as home and nothing held`() {
        val restored = DapPolicyRestoreResult(isDeviceOwner = true, persistentHomeApplied = true, containmentConfigured = true)
        fun start(
            trigger: DapRecoveryTrigger = DapRecoveryTrigger.MY_PACKAGE_REPLACED,
            result: DapPolicyRestoreResult = restored,
            home: Boolean = true,
            lock: Int = none,
        ) = DapUpdateReturnPolicy.shouldStartHome(trigger, result, home, lock)

        assertThat(start()).isTrue()
        assertThat(start(trigger = DapRecoveryTrigger.BOOT_COMPLETED)).isFalse()
        assertThat(start(trigger = DapRecoveryTrigger.ACTIVITY_START)).isFalse()
        assertThat(start(result = DapPolicyRestoreResult(false, false, false))).isFalse()
        assertThat(start(result = restored.copy(containmentConfigured = false))).isFalse()
        assertThat(start(home = false)).isFalse()
        assertThat(start(lock = locked)).isFalse()
    }

    // ── R2, on the same phone: what Android confirms ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `the level the page shows is Launcher until Android reads LOCKED, on the phone that was found`() {
        val phone = Phone(persistentPolicy = false, startingRecord = false, flag = false, allowlist = false, lock = none)
        assertThat(phone.mode().isDedicatedActive).isTrue() // Device Owner + home: the evidence heals a lost policy
        assertThat(phone.mode().level).isEqualTo(ProductLevel.LAUNCHER)
        assertThat(phone.mode().isLockHeld).isFalse()

        val contained = dap()
        assertThat(contained.mode().level).isEqualTo(ProductLevel.DEDICATED)
    }
}
