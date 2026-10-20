package com.lostf1sh.pixelplayeross.presentation.settings

import android.app.ActivityManager
import android.content.Intent
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.dap.admin.DapReleaseOutcome
import com.lostf1sh.pixelplayeross.dap.admin.DapReleaseResult
import com.lostf1sh.pixelplayeross.dap.admin.DapReleaseRollback
import com.lostf1sh.pixelplayeross.dap.admin.DapReturnOutcome
import com.lostf1sh.pixelplayeross.dap.admin.ModeLevelHost
import com.lostf1sh.pixelplayeross.dap.admin.SystemSurfaceHost
import com.lostf1sh.pixelplayeross.dap.admin.SystemSurfaceOutcome
import com.lostf1sh.pixelplayeross.dap.mode.DedicatedProvisioningEvidence
import com.lostf1sh.pixelplayeross.dap.mode.ProductLevel
import com.lostf1sh.pixelplayeross.dap.mode.ProductModeRequest
import com.lostf1sh.pixelplayeross.dap.mode.ProductModeResolver
import com.lostf1sh.pixelplayeross.dap.mode.ProductModeState
import org.junit.jupiter.api.Test

/**
 * B34: what each tap on "Inicio y modo DAP" asks of the platform. The seams are recording fakes, so the order and the answers are what is
 * tested; the real calls are the activity's, and none of them is made against a device.
 */
class HomeModeActionsTest {

    private val none = ActivityManager.LOCK_TASK_MODE_NONE

    private class Surfaces : SystemSurfaceHost {
        var outcome = SystemSurfaceOutcome.OPENED
        var returnOutcome = DapReturnOutcome.CONTAINED
        val events = mutableListOf<String>()

        /** What the selector the page handed the host answered (B34-fix2 R1: the host settles the exit itself when it says false). */
        var selectorAnswers = mutableListOf<Boolean>()
        override fun openSystemSurface(intent: Intent, onResult: (SystemSurfaceOutcome) -> Unit) { events += "open"; onResult(outcome) }
        override fun restoreHonorLauncher(openSelector: () -> Boolean, onResult: (SystemSurfaceOutcome) -> Unit) {
            events += "liftAndClear"
            if (outcome == SystemSurfaceOutcome.OPENED) selectorAnswers += openSelector()
            onResult(outcome)
        }
        override fun returnToDap(onResult: (DapReturnOutcome) -> Unit) { events += "return"; onResult(returnOutcome) }
    }

    private class Mode : ModeLevelHost {
        var pinAsks = true
        var releaseResult = DapReleaseResult(DapReleaseOutcome.RELEASED)
        val events = mutableListOf<String>()
        override fun requestAppPinning(): Boolean { events += "pin"; return pinAsks }
        override fun releaseAppPinning(): Boolean { events += "unpin"; return true }
        override fun releaseDeviceOwner(onResult: (DapReleaseResult) -> Unit) { events += "releaseOwner"; onResult(releaseResult) }
    }

    private class Rig(var state: ProductModeState) {
        val surfaces = Surfaces()
        val mode = Mode()
        val toasts = mutableListOf<Int>()
        val requested = mutableListOf<ProductLevel>()
        val opened = mutableListOf<String>()
        var pickerAvailable = true
        var refreshes = 0
        var copied: String? = null
        var securityOpens = 0

        // one list of everything the platform was asked, in order
        val timeline = mutableListOf<String>()

        fun actions(withHost: Boolean = true, withMode: Boolean = true) = HomeModeActionsImpl(
            state = { state },
            requestLevel = { requested += it },
            refresh = { refreshes++ },
            host = surfaces.takeIf { withHost },
            modeHost = mode.takeIf { withMode },
            openHomePicker = { timeline += "picker"; opened += "picker"; pickerAvailable },
            openSecuritySettings = { securityOpens++; true },
            dpmCommand = "adb shell dpm set-device-owner a/b",
            copy = { copied = it },
            notify = { toasts += it },
        )
    }

    private fun mode(
        requested: ProductModeRequest? = null,
        owner: Boolean = false,
        home: Boolean = true,
        flag: Boolean = false,
        permitted: Boolean = false,
        lock: Int = none,
    ) = ProductModeResolver.resolve(requested, DedicatedProvisioningEvidence(owner, home, flag, permitted, lock))

    @Test
    fun `choosing the home app at Launcher level opens Android's picker and touches nothing else`() {
        val rig = Rig(mode())
        rig.actions().onChooseHomeApp()
        assertThat(rig.opened).containsExactly("picker")
        assertThat(rig.surfaces.events).isEmpty()
        assertThat(rig.mode.events).isEmpty()
        assertThat(rig.toasts).isEmpty()
    }

    @Test
    fun `choosing the home app when Polylute is not home is the same plain picker`() {
        val rig = Rig(mode(home = false))
        rig.actions().onChooseHomeApp()
        assertThat(rig.opened).containsExactly("picker")
        assertThat(rig.surfaces.events).isEmpty()
    }

    @Test
    fun `a device with no home picker says so`() {
        val rig = Rig(mode()).apply { pickerAvailable = false }
        rig.actions().onChooseHomeApp()
        assertThat(rig.toasts).containsExactly(R.string.maintenance_not_available)
    }

    @Test
    fun `choosing the home app while Pinned lets go of the pin first`() {
        val rig = Rig(mode(lock = ActivityManager.LOCK_TASK_MODE_PINNED))
        rig.actions().onChooseHomeApp()
        assertThat(rig.mode.events).containsExactly("unpin")
        assertThat(rig.opened).containsExactly("picker")
    }

    @Test
    fun `choosing the home app while Dedicated lifts Lock Task and clears the HOME policy first, then shows the picker`() {
        val rig = Rig(mode(owner = true, flag = true, permitted = true, lock = ActivityManager.LOCK_TASK_MODE_LOCKED))
        rig.actions().onChooseHomeApp()
        assertThat(rig.surfaces.events).containsExactly("liftAndClear")
        assertThat(rig.opened).containsExactly("picker")
        assertThat(rig.toasts).isEmpty()

        // a lift that fails shows no picker and says why
        val failing = Rig(mode(owner = true, flag = true, permitted = true, lock = ActivityManager.LOCK_TASK_MODE_LOCKED))
        failing.surfaces.outcome = SystemSurfaceOutcome.NOT_LIFTED
        failing.actions().onChooseHomeApp()
        assertThat(failing.opened).isEmpty()
        assertThat(failing.toasts).containsExactly(R.string.maintenance_lock_task_exit_failed)

        failing.toasts.clear()
        failing.surfaces.outcome = SystemSurfaceOutcome.NOT_STARTED
        failing.actions().onChooseHomeApp()
        assertThat(failing.toasts).containsExactly(R.string.maintenance_not_available)
    }

    @Test
    fun `the picker the host is handed answers whether it opened, so an exit nothing opened for can be settled as a cancel`() {
        val opens = Rig(mode(owner = true, flag = true, permitted = true, lock = ActivityManager.LOCK_TASK_MODE_LOCKED))
        opens.actions().onChooseHomeApp()
        assertThat(opens.surfaces.selectorAnswers).containsExactly(true)

        val noPicker = Rig(mode(owner = true, flag = true, permitted = true, lock = ActivityManager.LOCK_TASK_MODE_LOCKED))
            .apply { pickerAvailable = false }
        noPicker.actions().onChooseHomeApp()
        assertThat(noPicker.surfaces.selectorAnswers).containsExactly(false)
        assertThat(noPicker.toasts).containsExactly(R.string.maintenance_not_available)
    }

    @Test
    fun `a Device Owner that is not Dedicated any more has no policy to clear, so the plain picker opens`() {
        val rig = Rig(mode(owner = true, home = false))
        rig.actions().onChooseHomeApp()
        assertThat(rig.surfaces.events).isEmpty()
        assertThat(rig.opened).containsExactly("picker")
    }

    @Test
    fun `selecting a level records the request and reads again`() {
        val rig = Rig(mode())
        rig.actions().onSelectLevel(ProductLevel.PINNED)
        rig.actions().onSelectLevel(ProductLevel.DEDICATED)
        assertThat(rig.requested).containsExactly(ProductLevel.PINNED, ProductLevel.DEDICATED).inOrder()
        assertThat(rig.refreshes).isEqualTo(2)
        assertThat(rig.mode.events).isEmpty()
    }

    @Test
    fun `leaving Pinned for another level releases the pin`() {
        val rig = Rig(mode(requested = ProductModeRequest.PINNED, lock = ActivityManager.LOCK_TASK_MODE_PINNED))
        rig.actions().onSelectLevel(ProductLevel.LAUNCHER)
        assertThat(rig.mode.events).containsExactly("unpin")
        assertThat(rig.requested).containsExactly(ProductLevel.LAUNCHER)
    }

    @Test
    fun `Fijar asks Android to pin and says to confirm, and a refusal is said`() {
        val rig = Rig(mode(requested = ProductModeRequest.PINNED))
        rig.actions().onPinNow()
        assertThat(rig.mode.events).containsExactly("pin")
        assertThat(rig.toasts).containsExactly(R.string.home_mode_pin_asked)

        rig.toasts.clear()
        rig.mode.pinAsks = false
        rig.actions().onPinNow()
        assertThat(rig.toasts).containsExactly(R.string.maintenance_not_available)
        // with no activity to ask nothing is claimed
        rig.toasts.clear()
        rig.actions(withMode = false).onPinNow()
        assertThat(rig.toasts).containsExactly(R.string.maintenance_not_available)
    }

    @Test
    fun `Soltar ahora releases the pin and the security shortcut opens the settings`() {
        val rig = Rig(mode(lock = ActivityManager.LOCK_TASK_MODE_PINNED))
        rig.actions().onUnpin()
        rig.actions().onOpenSecuritySettings()
        assertThat(rig.mode.events).containsExactly("unpin")
        assertThat(rig.securityOpens).isEqualTo(1)
    }

    @Test
    fun `the command is copied as it was given and nothing is run`() {
        val rig = Rig(mode())
        rig.actions().onCopyDpmCommand()
        assertThat(rig.copied).isEqualTo("adb shell dpm set-device-owner a/b")
        assertThat(rig.toasts).containsExactly(R.string.home_mode_guide_copied)
        assertThat(rig.surfaces.events).isEmpty()
        assertThat(rig.mode.events).isEmpty()
    }

    @Test
    fun `Volver al DAP says each ending as what it is`() {
        val rig = Rig(mode(owner = true, flag = true, permitted = true))
        val expected = mapOf(
            DapReturnOutcome.CONTAINED to listOf(R.string.system_return_done),
            DapReturnOutcome.POLICY_NOT_APPLIED to listOf(R.string.maintenance_lock_task_entry_failed),
            DapReturnOutcome.NOT_LOCKED to listOf(R.string.system_return_not_locked),
            DapReturnOutcome.NOTHING_TO_CONTAIN to emptyList(),
        )
        expected.forEach { (outcome, toasts) ->
            rig.toasts.clear()
            rig.surfaces.returnOutcome = outcome
            rig.actions().onReturnToDap()
            assertThat(rig.toasts).isEqualTo(toasts)
        }
        assertThat(rig.surfaces.events).hasSize(4)
    }

    @Test
    fun `releasing the owner says each ending as what it is, and a release with no activity does nothing`() {
        val rig = Rig(mode(owner = true, flag = true, permitted = true))
        val expected = mapOf(
            DapReleaseResult(DapReleaseOutcome.RELEASED) to R.string.home_mode_release_done,
            DapReleaseResult(DapReleaseOutcome.RELEASED, homeStillPolylute = true) to R.string.home_mode_release_done_home_still,
            DapReleaseResult(DapReleaseOutcome.NOT_DEVICE_OWNER) to R.string.maintenance_not_available,
            DapReleaseResult(DapReleaseOutcome.UNREADABLE_BEFORE) to R.string.home_mode_release_unreadable,
            // B34-fix1 R2: a read that kept failing is neither "Liberado" nor "Sigue siendo propietario"
            DapReleaseResult(DapReleaseOutcome.UNVERIFIED) to R.string.home_mode_release_unverified,
        )
        expected.forEach { (result, toast) ->
            rig.toasts.clear()
            rig.mode.releaseResult = result
            rig.actions().onReleaseDeviceOwner()
            assertThat(rig.toasts).containsExactly(toast)
        }
        rig.toasts.clear()
        rig.actions(withMode = false).onReleaseDeviceOwner()
        assertThat(rig.toasts).containsExactly(R.string.maintenance_not_available)
    }

    @Test
    fun `a failed release says whether the containment is confirmed back, was not needed, or could not be confirmed`() {
        val rig = Rig(mode(owner = true, flag = true, permitted = true))
        // outcome x rollback -> the text. "put back and checked" is said only for RESTORED; NOT_RESTORED names the way back
        val expected = mapOf(
            (DapReleaseOutcome.LOCK_NOT_STOPPED to DapReleaseRollback.RESTORED) to R.string.home_mode_release_lock_not_stopped,
            (DapReleaseOutcome.LOCK_NOT_STOPPED to DapReleaseRollback.NOT_NEEDED) to R.string.home_mode_release_lock_not_stopped_plain,
            (DapReleaseOutcome.LOCK_NOT_STOPPED to DapReleaseRollback.NOT_RESTORED) to R.string.home_mode_release_lock_not_stopped_unrestored,
            (DapReleaseOutcome.POLICY_NOT_CLEARED to DapReleaseRollback.RESTORED) to R.string.home_mode_release_policy_failed,
            (DapReleaseOutcome.POLICY_NOT_CLEARED to DapReleaseRollback.NOT_NEEDED) to R.string.home_mode_release_policy_failed_plain,
            (DapReleaseOutcome.POLICY_NOT_CLEARED to DapReleaseRollback.NOT_RESTORED) to R.string.home_mode_release_policy_failed_unrestored,
            (DapReleaseOutcome.STILL_OWNER to DapReleaseRollback.RESTORED) to R.string.home_mode_release_still_owner,
            (DapReleaseOutcome.STILL_OWNER to DapReleaseRollback.NOT_NEEDED) to R.string.home_mode_release_still_owner_plain,
            (DapReleaseOutcome.STILL_OWNER to DapReleaseRollback.NOT_RESTORED) to R.string.home_mode_release_still_owner_unrestored,
        )
        expected.forEach { (ending, toast) ->
            rig.toasts.clear()
            rig.mode.releaseResult = DapReleaseResult(ending.first, rollback = ending.second)
            rig.actions().onReleaseDeviceOwner()
            assertThat(rig.toasts).containsExactly(toast)
        }
        // every one of the nine, and the three kinds of ending, are different texts: nothing is said twice for different truths
        assertThat(expected.values.toSet()).hasSize(9)
    }

    @Test
    fun `a text that says released is reachable only from a confirmed release`() {
        val released = setOf(R.string.home_mode_release_done, R.string.home_mode_release_done_home_still)
        DapReleaseOutcome.entries.forEach { outcome ->
            DapReleaseRollback.entries.forEach { rollback ->
                val text = HomeModeActionsImpl.releaseMessage(DapReleaseResult(outcome, rollback = rollback))
                if (outcome != DapReleaseOutcome.RELEASED) assertThat(released).doesNotContain(text)
            }
        }
    }
}
