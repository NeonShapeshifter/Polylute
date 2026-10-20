package com.lostf1sh.pixelplayeross.dap.lifecycle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DapProductionLifecycleTest {
    @Test
    fun `package replacement in App Mode never applies appliance policy`() {
        val plan = plan(isDedicatedActive = false, isDeviceOwner = false)

        assertEquals(DapLifecyclePhase.NORMAL_OPERATION, plan.phase)
        assertFalse(plan.applyPersistentHome)
        assertFalse(plan.configureContainment)
    }

    @Test
    fun `Dedicated replacement repairs missing HOME and containment`() {
        val plan = plan(persistentHomeReady = false, containmentReady = false)

        assertEquals(DapLifecyclePhase.POST_UPDATE_RECOVERY, plan.phase)
        assertTrue(plan.applyPersistentHome)
        assertTrue(plan.configureContainment)
        assertTrue(plan.canRecover)
    }

    @Test
    fun `stable Dedicated callback is idempotent and performs no policy writes`() {
        val plans = List(20) { plan() }

        assertTrue(plans.all { !it.applyPersistentHome && !it.configureContainment })
        assertEquals(1, plans.distinct().size)
    }

    @Test
    fun `Dedicated state without Device Owner fails safe`() {
        val plan = plan(isDeviceOwner = false)

        assertEquals(DapLifecyclePhase.UPDATE_FAILED_SAFE, plan.phase)
        assertFalse(plan.canRecover)
        assertFalse(plan.applyPersistentHome)
        assertFalse(plan.configureContainment)
    }

    @Test
    fun `boot only repairs the policy that is missing`() {
        val plan = DapProductionLifecycle.plan(
            trigger = DapRecoveryTrigger.BOOT_COMPLETED,
            snapshot = snapshot(persistentHomeReady = true, containmentReady = false),
        )

        assertFalse(plan.applyPersistentHome)
        assertTrue(plan.configureContainment)
    }

    private fun plan(
        isDedicatedActive: Boolean = true,
        isDeviceOwner: Boolean = true,
        persistentHomeReady: Boolean = true,
        containmentReady: Boolean = true,
    ) = DapProductionLifecycle.plan(
        trigger = DapRecoveryTrigger.MY_PACKAGE_REPLACED,
        snapshot = snapshot(
            isDedicatedActive = isDedicatedActive,
            isDeviceOwner = isDeviceOwner,
            persistentHomeReady = persistentHomeReady,
            containmentReady = containmentReady,
        ),
    )

    private fun snapshot(
        isDedicatedActive: Boolean = true,
        isDeviceOwner: Boolean = true,
        persistentHomeReady: Boolean = true,
        containmentReady: Boolean = true,
    ) = DapRecoverySnapshot(
        isDedicatedActive = isDedicatedActive,
        isDeviceOwner = isDeviceOwner,
        persistentHomeRequired = true,
        persistentHomeReady = persistentHomeReady,
        containmentRequired = true,
        containmentPolicyReady = containmentReady,
    )
}
