package com.lostf1sh.pixelplayeross.dap.lifecycle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DapProvisioningPolicyTest {
    @Test
    fun `normal unrequested install remains App Mode`() {
        assertEquals(
            DapProvisioningStatus.APP_MODE,
            DapProvisioningPolicy.status(snapshot(dedicatedRequested = false)),
        )
    }

    @Test
    fun `request without Device Owner never claims Dedicated Active`() {
        assertEquals(
            DapProvisioningStatus.ELIGIBLE_FOR_SUPPORTED_PROVISIONING,
            DapProvisioningPolicy.status(snapshot()),
        )
    }

    @Test
    fun `missing recovery prerequisite makes setup required`() {
        assertEquals(
            DapProvisioningStatus.SETUP_REQUIRED,
            DapProvisioningPolicy.status(snapshot(recoveryAvailable = false)),
        )
    }

    @Test
    fun `Device Owner with incomplete policy is explicitly partial`() {
        assertEquals(
            DapProvisioningStatus.PARTIALLY_PROVISIONED,
            DapProvisioningPolicy.status(
                snapshot(isDeviceOwner = true, persistentHomeReady = true),
            ),
        )
    }

    @Test
    fun `only complete authoritative owner policy is Dedicated Active`() {
        assertEquals(
            DapProvisioningStatus.DEDICATED_ACTIVE,
            DapProvisioningPolicy.status(
                snapshot(
                    isDeviceOwner = true,
                    persistentHomeReady = true,
                    containmentReady = true,
                ),
            ),
        )
    }

    private fun snapshot(
        dedicatedRequested: Boolean = true,
        isDeviceOwner: Boolean = false,
        recoveryAvailable: Boolean = true,
        persistentHomeReady: Boolean = false,
        containmentReady: Boolean = false,
    ) = DapProvisioningSnapshot(
        dedicatedRequested = dedicatedRequested,
        isDeviceOwner = isDeviceOwner,
        adminComponentAvailable = true,
        homeComponentAvailable = true,
        lockTaskSupported = true,
        recoveryAvailable = recoveryAvailable,
        persistentHomeReady = persistentHomeReady,
        containmentReady = containmentReady,
    )
}
