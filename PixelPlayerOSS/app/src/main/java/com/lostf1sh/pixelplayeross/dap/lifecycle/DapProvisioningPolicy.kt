package com.lostf1sh.pixelplayeross.dap.lifecycle

enum class DapProvisioningStatus {
    APP_MODE,
    SETUP_REQUIRED,
    ELIGIBLE_FOR_SUPPORTED_PROVISIONING,
    PARTIALLY_PROVISIONED,
    DEDICATED_ACTIVE,
}

data class DapProvisioningSnapshot(
    val dedicatedRequested: Boolean,
    val isDeviceOwner: Boolean,
    val adminComponentAvailable: Boolean,
    val homeComponentAvailable: Boolean,
    val lockTaskSupported: Boolean,
    val recoveryAvailable: Boolean,
    val persistentHomeReady: Boolean,
    val containmentReady: Boolean,
)

/** A preference can request setup, but only authoritative Android evidence can make it active. */
object DapProvisioningPolicy {
    fun status(snapshot: DapProvisioningSnapshot): DapProvisioningStatus {
        if (!snapshot.dedicatedRequested && !snapshot.isDeviceOwner) {
            return DapProvisioningStatus.APP_MODE
        }
        if (!snapshot.isDeviceOwner) {
            return if (
                snapshot.adminComponentAvailable &&
                snapshot.homeComponentAvailable &&
                snapshot.lockTaskSupported &&
                snapshot.recoveryAvailable
            ) {
                DapProvisioningStatus.ELIGIBLE_FOR_SUPPORTED_PROVISIONING
            } else {
                DapProvisioningStatus.SETUP_REQUIRED
            }
        }
        if (snapshot.persistentHomeReady && snapshot.containmentReady) {
            return DapProvisioningStatus.DEDICATED_ACTIVE
        }
        return DapProvisioningStatus.PARTIALLY_PROVISIONED
    }
}
