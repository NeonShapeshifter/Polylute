package com.lostf1sh.pixelplayeross.dap.mode

import android.app.ActivityManager
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.dap.admin.DapKeyguardPolicy
import com.lostf1sh.pixelplayeross.data.volume.ScreenOffOverridePolicy
import com.lostf1sh.pixelplayeross.presentation.lockscreen.DapLockScreenPolicy
import org.junit.jupiter.api.Test

/**
 * B34: every place that branches on "dedicated", decided per level (Launcher, Pinned, Dedicated). The default answer was: the lock surface
 * works at all three when its setting is on, only Dedicated touches the keyguard policy, and a screen-off dialog follows the appliance
 * rule as it did. Each row below is one of those decisions, pinned against the real policy objects.
 */
class B34ModeBranchesTest {

    private val none = ActivityManager.LOCK_TASK_MODE_NONE

    private fun launcher() = ProductModeResolver.resolve(null, DedicatedProvisioningEvidence(false, true, false, false, none))
    private fun pinned() = ProductModeResolver.resolve(null, DedicatedProvisioningEvidence(false, true, false, false, ActivityManager.LOCK_TASK_MODE_PINNED))
    private fun dedicated() = ProductModeResolver.resolve(null, DedicatedProvisioningEvidence(true, true, true, true, ActivityManager.LOCK_TASK_MODE_LOCKED))
    private fun app() = ProductModeResolver.resolve(null, DedicatedProvisioningEvidence(false, false, false, false, none))
    private fun honorRestored() = ProductModeResolver.resolve(ProductModeRequest.DEDICATED, DedicatedProvisioningEvidence(true, false, false, false, none))

    @Test
    fun `the lock surface works at all three levels when its setting is on, and not in App Mode`() {
        listOf(launcher(), pinned(), dedicated()).forEach { state ->
            assertThat(state.allowsLockSurface).isTrue()
            // the setting on, the keyguard not showing, woke from screen off, something to control: it shows
            assertThat(
                DapLockScreenPolicy.shouldShow(enabled = true, dedicated = state.allowsLockSurface, keyguardLocked = false, wokeFromScreenOff = true, hasSong = true),
            ).isTrue()
            // with the setting off it never does
            assertThat(
                DapLockScreenPolicy.shouldShow(enabled = false, dedicated = state.allowsLockSurface, keyguardLocked = false, wokeFromScreenOff = true, hasSong = true),
            ).isFalse()
            // and Android's own lock screen stays in charge wherever it is showing
            assertThat(
                DapLockScreenPolicy.shouldShow(enabled = true, dedicated = state.allowsLockSurface, keyguardLocked = true, wokeFromScreenOff = true, hasSong = true),
            ).isFalse()
        }
        assertThat(app().allowsLockSurface).isFalse()
        assertThat(honorRestored().allowsLockSurface).isFalse()
        assertThat(DapLockScreenPolicy.shouldArm(enabled = true, dedicated = app().allowsLockSurface)).isFalse()
    }

    @Test
    fun `only Dedicated touches the keyguard policy`() {
        // the policy needs Device Owner: Launcher and Pinned are not one, so it never disables the keyguard there
        listOf(launcher(), pinned()).forEach { state ->
            assertThat(state.isDeviceOwner).isFalse()
            assertThat(
                DapKeyguardPolicy.shouldDisableKeyguard(
                    isDeviceOwner = state.isDeviceOwner,
                    dedicatedModeEnabled = state.isDedicatedActive,
                    dapLockScreenEnabled = true,
                    isDeviceSecure = false,
                ),
            ).isFalse()
        }
        assertThat(
            DapKeyguardPolicy.shouldDisableKeyguard(isDeviceOwner = true, dedicatedModeEnabled = true, dapLockScreenEnabled = true, isDeviceSecure = false),
        ).isTrue()
    }

    @Test
    fun `a screen-off override dialog follows the appliance rule, which is Dedicated only`() {
        fun delivery(state: ProductModeState, hostResumed: Boolean) = ScreenOffOverridePolicy.delivery(
            protectionActive = true,
            overrideActive = false,
            dedicated = state.isDedicatedActive,
            hostResumed = hostResumed,
        )
        assertThat(delivery(dedicated(), hostResumed = true)).isEqualTo(ScreenOffOverridePolicy.Delivery.OVER_LOCK_SCREEN)
        assertThat(delivery(dedicated(), hostResumed = false)).isEqualTo(ScreenOffOverridePolicy.Delivery.WAKE_THEN_SHOW)
        // Launcher and Pinned: nothing brings the app to the front on a wake, so the notification and the grace stand
        listOf(launcher(), pinned(), app()).forEach { state ->
            assertThat(delivery(state, hostResumed = false)).isEqualTo(ScreenOffOverridePolicy.Delivery.ON_UNLOCK)
        }
    }

    @Test
    fun `the Android shortcuts are offered while Dedicated is active or the device is a Device Owner`() {
        assertThat(dedicated().offersAndroidShortcuts).isTrue()
        // the HONOR after it chose another launcher: still owned, not dedicated, and Volver needs the way out to stay
        assertThat(honorRestored().offersAndroidShortcuts).isTrue()
        assertThat(launcher().offersAndroidShortcuts).isFalse()
        assertThat(pinned().offersAndroidShortcuts).isFalse()
        assertThat(app().offersAndroidShortcuts).isFalse()
    }

    @Test
    fun `Lock Task is entered only by Dedicated`() {
        // the containment rule is keyed on the dedicated flag, Device Owner and the allowlist: none of which Launcher or Pinned has
        listOf(launcher(), pinned()).forEach { state ->
            assertThat(state.isDedicatedActive).isFalse()
        }
        assertThat(dedicated().isDedicatedActive).isTrue()
    }

    @Test
    fun `a Device Owner stays Dedicated whatever level was asked for`() {
        listOf(ProductModeRequest.APP, ProductModeRequest.PINNED, null).forEach { asked ->
            val state = ProductModeResolver.resolve(asked, DedicatedProvisioningEvidence(true, true, true, true, ActivityManager.LOCK_TASK_MODE_LOCKED))
            assertThat(state.level).isEqualTo(ProductLevel.DEDICATED)
            assertThat(state.requestedLevel).isEqualTo(ProductLevel.DEDICATED)
        }
    }
}
