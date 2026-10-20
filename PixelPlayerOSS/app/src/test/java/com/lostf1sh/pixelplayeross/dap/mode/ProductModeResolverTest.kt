package com.lostf1sh.pixelplayeross.dap.mode

import android.app.ActivityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProductModeResolverTest {
    @Test
    fun `fresh normal install defaults to App Mode`() {
        val state = resolve(requested = null)

        assertEquals(ProductModeRequest.APP, state.requestedMode)
        assertEquals(EffectiveProductMode.APP, state.effectiveMode)
        assertFalse(state.isDedicatedActive)
        // Containment is off, but the protected Maintenance entry exists: a device with no Dedicated
        // provisioning is exactly the device whose owner may need recovery, so the entry cannot be
        // gated on the mode it exists to repair. The PIN is the boundary.
        assertTrue(state.isMaintenanceAvailable)
    }

    @Test
    fun `explicit App request remains App when unprovisioned`() {
        assertEquals(
            EffectiveProductMode.APP,
            resolve(requested = ProductModeRequest.APP).effectiveMode,
        )
    }

    @Test
    fun `Dedicated request without Device Owner is setup required not active`() {
        val state = resolve(requested = ProductModeRequest.DEDICATED)

        assertEquals(EffectiveProductMode.DEDICATED_SETUP_REQUIRED, state.effectiveMode)
        assertFalse(state.isDedicatedActive)
        // Half-applied provisioning is the case Maintenance most needs to be reachable from.
        assertTrue(state.isMaintenanceAvailable)
    }

    @Test
    fun `Device Owner without dedicated evidence does not falsely activate containment`() {
        val state = resolve(
            requested = ProductModeRequest.DEDICATED,
            isDeviceOwner = true,
        )

        assertEquals(EffectiveProductMode.DEDICATED_SETUP_REQUIRED, state.effectiveMode)
        assertFalse(state.hasDedicatedProvisioningEvidence)
    }

    @Test
    fun `existing Device Owner HOME migrates stale App preference fail safe`() {
        val state = resolve(
            requested = ProductModeRequest.APP,
            isDeviceOwner = true,
            isPixelPlayerHome = true,
        )

        assertEquals(ProductModeRequest.DEDICATED, state.requestedMode)
        assertEquals(EffectiveProductMode.DEDICATED_ACTIVE, state.effectiveMode)
        assertTrue(state.isMaintenanceAvailable)
    }

    @Test
    fun `existing owner containment evidence remains Dedicated when HOME is transiently absent`() {
        val state = resolve(
            requested = null,
            isDeviceOwner = true,
            isDedicatedModeEnabled = true,
            isLockTaskPermitted = true,
        )

        assertEquals(EffectiveProductMode.DEDICATED_ACTIVE, state.effectiveMode)
        assertTrue(state.hasDedicatedProvisioningEvidence)
    }

    @Test
    fun `non-owner stale containment flag cannot activate Dedicated Mode`() {
        val state = resolve(
            requested = null,
            isDedicatedModeEnabled = true,
            isLockTaskPermitted = true,
        )

        assertEquals(EffectiveProductMode.APP, state.effectiveMode)
        assertFalse(state.isDedicatedActive)
    }

    @Test
    fun `repeated resolution is deterministic and does not create lifecycle churn`() {
        val evidence = DedicatedProvisioningEvidence(
            isDeviceOwner = true,
            isPixelPlayerHome = true,
            isDedicatedModeEnabled = true,
            isLockTaskPermitted = true,
        )
        val states = List(20) { ProductModeResolver.resolve(null, evidence) }

        assertEquals(1, states.distinct().size)
        assertTrue(states.first().isDedicatedActive)
    }

    // ── B34: the levels, from Android evidence ──────────────────────────────────────────────────────

    @Test
    fun `not home and nothing held is App Mode with no level`() {
        val state = resolve(requested = null)
        assertEquals(EffectiveProductMode.APP, state.effectiveMode)
        assertEquals(null, state.level)
        assertFalse(state.allowsLockSurface)
    }

    @Test
    fun `home with nothing held is Launcher`() {
        val state = resolve(requested = null, isPixelPlayerHome = true)
        assertEquals(EffectiveProductMode.LAUNCHER, state.effectiveMode)
        assertEquals(ProductLevel.LAUNCHER, state.level)
        assertTrue(state.allowsLockSurface)
    }

    @Test
    fun `a Pinned request is not Pinned until Android says pinned`() {
        val asked = resolve(requested = ProductModeRequest.PINNED, isPixelPlayerHome = true)
        assertEquals(ProductLevel.PINNED, asked.requestedLevel)
        assertEquals(ProductLevel.LAUNCHER, asked.level)

        val pinned = resolve(
            requested = ProductModeRequest.PINNED,
            isPixelPlayerHome = true,
            lockTaskModeState = ActivityManager.LOCK_TASK_MODE_PINNED,
        )
        assertEquals(EffectiveProductMode.PINNED, pinned.effectiveMode)
        assertEquals(ProductLevel.PINNED, pinned.level)
    }

    @Test
    fun `a pin the listener did not ask for is still Pinned, and a pin without home is nothing`() {
        assertEquals(
            EffectiveProductMode.PINNED,
            resolve(requested = null, isPixelPlayerHome = true, lockTaskModeState = ActivityManager.LOCK_TASK_MODE_PINNED).effectiveMode,
        )
        assertEquals(
            EffectiveProductMode.APP,
            resolve(requested = null, lockTaskModeState = ActivityManager.LOCK_TASK_MODE_PINNED).effectiveMode,
        )
    }

    @Test
    fun `a Dedicated request without Device Owner shows Launcher as the actual level`() {
        val state = resolve(requested = ProductModeRequest.DEDICATED, isPixelPlayerHome = true)
        assertEquals(EffectiveProductMode.DEDICATED_SETUP_REQUIRED, state.effectiveMode)
        assertEquals(ProductLevel.DEDICATED, state.requestedLevel)
        assertEquals(ProductLevel.LAUNCHER, state.level)
        assertFalse(state.isDedicatedActive)
    }

    @Test
    fun `Device Owner with home is Dedicated by evidence, and the level is Dedicated only when Android reads LOCKED`() {
        val unlocked = resolve(requested = null, isDeviceOwner = true, isPixelPlayerHome = true)
        // the evidence still says the device is provisioned (it is what heals a lost policy); what Android confirms is Launcher
        assertEquals(EffectiveProductMode.DEDICATED_ACTIVE, unlocked.effectiveMode)
        assertTrue(unlocked.isDedicatedActive)
        assertEquals(ProductLevel.LAUNCHER, unlocked.level)
        assertEquals(ProductLevel.DEDICATED, unlocked.requestedLevel)
        assertFalse(unlocked.isLockHeld)
        val locked = resolve(
            requested = null,
            isDeviceOwner = true,
            isPixelPlayerHome = true,
            lockTaskModeState = ActivityManager.LOCK_TASK_MODE_LOCKED,
        )
        assertTrue(locked.isLockHeld)
        assertEquals(ProductLevel.DEDICATED, locked.level)
    }

    // ── B34-fix2 R2: the level is what Android confirms ─────────────────────────────────────────────────────────────

    @Test
    fun `Dedicated is never the level while nothing is locked, whatever the evidence is`() {
        listOf(
            resolve(requested = null, isDeviceOwner = true, isPixelPlayerHome = true),
            resolve(requested = null, isDeviceOwner = true, isPixelPlayerHome = true, isDedicatedModeEnabled = true, isLockTaskPermitted = true),
            resolve(requested = ProductModeRequest.DEDICATED, isDeviceOwner = true, isPixelPlayerHome = true, isLockTaskPermitted = true),
        ).forEach { state ->
            assertTrue(state.isDedicatedActive)
            assertEquals(ProductLevel.LAUNCHER, state.level)
        }
    }

    @Test
    fun `a Device Owner that is not home has no level until Android holds the lock`() {
        val notHeld = resolve(requested = null, isDeviceOwner = true, isDedicatedModeEnabled = true, isLockTaskPermitted = true)
        assertTrue(notHeld.isDedicatedActive)
        assertEquals(null, notHeld.level)
        val held = resolve(
            requested = null,
            isDeviceOwner = true,
            isDedicatedModeEnabled = true,
            isLockTaskPermitted = true,
            lockTaskModeState = ActivityManager.LOCK_TASK_MODE_LOCKED,
        )
        assertEquals(ProductLevel.DEDICATED, held.level)
    }

    @Test
    fun `the lock surface and the Back guard do not flicker with Lock Task, because the level does not drive them`() {
        val unlocked = resolve(requested = null, isDeviceOwner = true, isPixelPlayerHome = true)
        val locked = resolve(
            requested = null,
            isDeviceOwner = true,
            isPixelPlayerHome = true,
            lockTaskModeState = ActivityManager.LOCK_TASK_MODE_LOCKED,
        )
        assertTrue(unlocked.allowsLockSurface)
        assertTrue(locked.allowsLockSurface)
        // and the Device Owner that is not home keeps what it had: the evidence says provisioned
        assertTrue(resolve(requested = null, isDeviceOwner = true, isDedicatedModeEnabled = true, isLockTaskPermitted = true).allowsLockSurface)
    }

    // ── B34-fix2 R1: the recorded exit outranks the inference ───────────────────────────────────────────────────────

    @Test
    fun `a recorded exit stops Device Owner plus home, the flag and the allowlist from proving Dedicated`() {
        fun exiting(home: Boolean, flag: Boolean, permitted: Boolean) = ProductModeResolver.resolve(
            ProductModeRequest.DEDICATED,
            DedicatedProvisioningEvidence(true, home, flag, permitted, ActivityManager.LOCK_TASK_MODE_NONE, homeExitChosen = true),
        )
        listOf(
            exiting(home = true, flag = false, permitted = false), // the window the reviewer hit: Polylute is still home until another is picked
            exiting(home = true, flag = true, permitted = true),
            exiting(home = false, flag = true, permitted = true),
        ).forEach { state ->
            assertFalse(state.isDedicatedActive)
            assertFalse(state.hasDedicatedProvisioningEvidence)
        }
        // what the page shows meanwhile: Launcher while Polylute is home, nothing otherwise
        assertEquals(ProductLevel.LAUNCHER, exiting(home = true, flag = false, permitted = false).level)
        assertEquals(null, exiting(home = false, flag = false, permitted = false).level)
    }

    @Test
    fun `without a recorded exit nothing changed for a Device Owner that lost a policy`() {
        val state = ProductModeResolver.resolve(
            null,
            DedicatedProvisioningEvidence(true, true, false, false, ActivityManager.LOCK_TASK_MODE_NONE, homeExitChosen = false),
        )
        assertTrue(state.isDedicatedActive)
    }

    @Test
    fun `a recorded exit lets the Dedicated request go once Polylute is not home, like any request`() {
        val evidence = DedicatedProvisioningEvidence(true, false, false, false, ActivityManager.LOCK_TASK_MODE_NONE, homeExitChosen = true)
        assertEquals(ProductModeRequest.APP, ProductModeResolver.requestAfterLosingHome(ProductModeRequest.DEDICATED, evidence))
    }

    @Test
    fun `the HONOR restored state, Device Owner but not home, is not Dedicated and has no level`() {
        val state = resolve(requested = ProductModeRequest.DEDICATED, isDeviceOwner = true)
        assertEquals(EffectiveProductMode.DEDICATED_SETUP_REQUIRED, state.effectiveMode)
        assertEquals(null, state.level)
        assertTrue(state.isDeviceOwner)
        assertTrue(state.offersAndroidShortcuts, "the Android shortcuts stay: the device is still a Device Owner")
    }

    @Test
    fun `the Android shortcuts follow the rule per level`() {
        assertFalse(resolve(requested = null).offersAndroidShortcuts)
        assertFalse(resolve(requested = null, isPixelPlayerHome = true).offersAndroidShortcuts)
        assertFalse(
            resolve(requested = null, isPixelPlayerHome = true, lockTaskModeState = ActivityManager.LOCK_TASK_MODE_PINNED).offersAndroidShortcuts,
        )
        assertTrue(resolve(requested = null, isDeviceOwner = true, isPixelPlayerHome = true).offersAndroidShortcuts)
    }

    @Test
    fun `losing home ends a Pinned or Dedicated request but not a Device Owner's`() {
        fun after(requested: ProductModeRequest?, home: Boolean, owner: Boolean = false, flag: Boolean = false) =
            ProductModeResolver.requestAfterLosingHome(
                requested,
                DedicatedProvisioningEvidence(owner, home, flag, flag),
            )
        assertEquals(ProductModeRequest.APP, after(ProductModeRequest.PINNED, home = false))
        assertEquals(ProductModeRequest.APP, after(ProductModeRequest.DEDICATED, home = false))
        assertEquals(null, after(ProductModeRequest.PINNED, home = true))
        assertEquals(null, after(ProductModeRequest.APP, home = false))
        assertEquals(null, after(null, home = false))
        // a Device Owner whose policy still proves Dedicated keeps its request: the policy re-asserts HOME
        assertEquals(null, after(ProductModeRequest.DEDICATED, home = false, owner = true, flag = true))
    }

    @Test
    fun `a pin is released only when Polylute is no longer home`() {
        fun release(home: Boolean, state: Int) = ProductModeResolver.shouldReleasePin(
            DedicatedProvisioningEvidence(false, home, false, false, state),
        )
        assertTrue(release(home = false, state = ActivityManager.LOCK_TASK_MODE_PINNED))
        assertFalse(release(home = true, state = ActivityManager.LOCK_TASK_MODE_PINNED))
        assertFalse(release(home = false, state = ActivityManager.LOCK_TASK_MODE_NONE))
    }

    private fun resolve(
        requested: ProductModeRequest?,
        isDeviceOwner: Boolean = false,
        isPixelPlayerHome: Boolean = false,
        isDedicatedModeEnabled: Boolean = false,
        isLockTaskPermitted: Boolean = false,
        lockTaskModeState: Int = ActivityManager.LOCK_TASK_MODE_NONE,
    ) = ProductModeResolver.resolve(
        requestedMode = requested,
        evidence = DedicatedProvisioningEvidence(
            isDeviceOwner = isDeviceOwner,
            isPixelPlayerHome = isPixelPlayerHome,
            isDedicatedModeEnabled = isDedicatedModeEnabled,
            isLockTaskPermitted = isLockTaskPermitted,
            lockTaskModeState = lockTaskModeState,
        ),
    )
}
