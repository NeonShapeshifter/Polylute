package com.lostf1sh.pixelplayeross.dap.lifecycle

/** Supported events from which the appliance can reconstruct its state. */
enum class DapRecoveryTrigger {
    ACTIVITY_START,
    BOOT_COMPLETED,
    MY_PACKAGE_REPLACED,
}

enum class DapLifecyclePhase {
    NORMAL_OPERATION,
    AUTHENTICATED_MAINTENANCE,
    UPDATE_PREPARATION,
    EXTERNAL_REPLACEMENT,
    POST_UPDATE_RECOVERY,
    RECOVERED,
    UPDATE_FAILED_SAFE,
}

data class DapRecoverySnapshot(
    val isDedicatedActive: Boolean,
    val isDeviceOwner: Boolean,
    val persistentHomeRequired: Boolean,
    val persistentHomeReady: Boolean,
    val containmentRequired: Boolean,
    val containmentPolicyReady: Boolean,
)

data class DapRecoveryPlan(
    val phase: DapLifecyclePhase,
    val applyPersistentHome: Boolean,
    val configureContainment: Boolean,
    val canRecover: Boolean,
)

/**
 * Pure update/boot recovery policy. Android state is always re-read before this is evaluated;
 * volatile process state and version strings are deliberately absent.
 */
object DapProductionLifecycle {
    fun plan(
        trigger: DapRecoveryTrigger,
        snapshot: DapRecoverySnapshot,
    ): DapRecoveryPlan {
        if (!snapshot.isDedicatedActive) {
            return DapRecoveryPlan(
                phase = DapLifecyclePhase.NORMAL_OPERATION,
                applyPersistentHome = false,
                configureContainment = false,
                canRecover = true,
            )
        }
        if (!snapshot.isDeviceOwner) {
            return DapRecoveryPlan(
                phase = DapLifecyclePhase.UPDATE_FAILED_SAFE,
                applyPersistentHome = false,
                configureContainment = false,
                canRecover = false,
            )
        }

        val repairHome = snapshot.persistentHomeRequired && !snapshot.persistentHomeReady
        val repairContainment =
            snapshot.containmentRequired && !snapshot.containmentPolicyReady
        val isPostUpdate = trigger == DapRecoveryTrigger.MY_PACKAGE_REPLACED
        return DapRecoveryPlan(
            phase = if (repairHome || repairContainment || isPostUpdate) {
                DapLifecyclePhase.POST_UPDATE_RECOVERY
            } else {
                DapLifecyclePhase.RECOVERED
            },
            applyPersistentHome = repairHome,
            configureContainment = repairContainment,
            canRecover = true,
        )
    }
}
